package com.vettid.core.wallet

import com.vettid.core.chain.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Where the wallet flags are kept: the app's encrypted settings on this phone ([load] is null before the first save). */
interface WalletFlagsFile {
    fun load(): ByteArray?

    fun save(bytes: ByteArray)
}

/**
 * What the app keeps of its wallets on this phone (ANDROID-PLAN §4 "Local state"), in its encrypted settings, never sent to the
 * vault: the **backed-up** flag per `wallet_id` (the vault has none) and, for a legacy `testnet` wallet only, which test chain the
 * member said it is on (question 16), whether the chain server's privacy note was shown for a wallet, and the **pending spending
 * limits** (W1d): the limits chosen at creation, kept per `wallet_id` until `wallet.caps.set` has been answered (not a secret; the
 * vault learns them only from that request).
 * Read from the file every time, nothing kept in memory. A write is refused when a wipe began
 * after the operation started ([generation] moved), so that a write in flight cannot bring the file back after it was erased.
 */
@Suppress("TooManyFunctions")
class WalletFlags(private val file: WalletFlagsFile, private val generation: () -> Long = { 0 }) {
    private val lock = Mutex()

    private data class Flags(
        val backedUp: Set<String>,
        val testnet: Map<String, Network>,
        val noteShown: Set<String> = emptySet(),
        val pendingCaps: Map<String, WalletCaps> = emptyMap(),
        /**
         * After [reset]: the wallets the vault held then, asked "Set a spending limit?" again before Receive (unless the vault already
         * caps one, decided at Receive from the vault's own view). A wallet made or answered later is not in it.
         */
        val askCaps: Set<String> = emptySet(),
        /** The reset came before the wallets were listed: the first list read after it fills [askCaps] (less [answeredCaps]). */
        val askNext: Boolean = false,
        /** Answered or made while [askNext] was waiting: left out of [askCaps] when the list fills it. */
        val answeredCaps: Set<String> = emptySet(),
    )

    /** Whether [walletId] was backed up (the phrase checked). False when the file cannot be read: "Not backed up" fails safe. */
    suspend fun backedUp(walletId: String): Boolean = lock.withLock { read()?.backedUp?.contains(walletId) == true }

    /** Records [walletId] as backed up; false if it could not be written. */
    suspend fun setBackedUp(walletId: String): Boolean = change { it.copy(backedUp = it.backedUp + walletId) }

    /**
     * The test chain the member named for a legacy `testnet` wallet: [Network.TESTNET] (testnet3) or [Network.TESTNET4]; null if not
     * asked yet.
     */
    suspend fun testnetLabel(walletId: String): Network? = lock.withLock { read()?.testnet?.get(walletId) }

    suspend fun setTestnetLabel(walletId: String, network: Network): Boolean {
        require(network == Network.TESTNET || network == Network.TESTNET4) { "a test chain of `testnet`" }
        return change { it.copy(testnet = it.testnet + (walletId to network)) }
    }

    /** Whether the chain server's privacy note was shown for [walletId] (before its first look-up). False when the file cannot be read. */
    suspend fun privacyNoteShown(walletId: String): Boolean = lock.withLock { read()?.noteShown?.contains(walletId) == true }

    /** Records the privacy note as shown for [walletId]; false if it could not be written. */
    suspend fun setPrivacyNoteShown(walletId: String): Boolean = change { it.copy(noteShown = it.noteShown + walletId) }

    /** The limits chosen for [walletId] that `wallet.caps.set` has not carried yet; null when none (or the file cannot be read). */
    suspend fun pendingCaps(walletId: String): WalletCaps? = lock.withLock { read()?.pendingCaps?.get(walletId) }

    /**
     * Every wallet's pending limits, or null when the file cannot be read: then **nobody knows** which wallets have a limit still to
     * be set, and the caller must not offer Receive for any wallet until the file reads again (fail secure).
     */
    suspend fun allPendingCaps(): Map<String, WalletCaps>? = lock.withLock { read()?.pendingCaps }

    /** Records the limits chosen for [walletId], to be set; false if it could not be written. */
    suspend fun setPendingCaps(walletId: String, caps: WalletCaps): Boolean =
        change { it.copy(pendingCaps = it.pendingCaps + (walletId to caps)) }

    /** The limits of [walletId] were set (or are not wanted): forgotten; false if it could not be written. */
    suspend fun clearPendingCaps(walletId: String): Boolean = change { it.copy(pendingCaps = it.pendingCaps - walletId) }

    /** Waits for a write in progress (a wipe's hook, after the generation moved), so that none that started before the wipe is kept. */
    suspend fun fence() = lock.withLock { }

    /** Forgets what is kept of a deleted wallet. */
    suspend fun forget(walletId: String): Boolean = change {
        Flags(
            it.backedUp - walletId, it.testnet - walletId, it.noteShown - walletId, it.pendingCaps - walletId,
            it.askCaps - walletId, it.askNext, it.answeredCaps - walletId,
        )
    }

    /**
     * Whether [walletId]'s limit is to be asked before Receive (it was one of the wallets at a [reset] and was not answered since;
     * whether the vault already caps it is for the caller); null when the file cannot be read.
     */
    suspend fun asksCaps(walletId: String): Boolean? = lock.withLock { read()?.let { walletId in it.askCaps } }

    /**
     * The first wallet list read after a [reset] that came before the wallets were listed: those wallets (less the ones answered or
     * made meanwhile) are the ones to ask, and from then on only those. Nothing when no reset waits; false if it could not be written.
     */
    suspend fun listed(ids: Set<String>): Boolean = change {
        if (!it.askNext) it else it.copy(askCaps = ids - it.answeredCaps, askNext = false, answeredCaps = emptySet())
    }

    /** [walletId]'s limit was answered (pending now, or "No limit"): not asked again; false if it could not be written. */
    suspend fun clearAskCaps(walletId: String): Boolean = change {
        it.copy(askCaps = it.askCaps - walletId, answeredCaps = if (it.askNext) it.answeredCaps + walletId else it.answeredCaps)
    }

    /**
     * Writes a **fresh** file over one that cannot be read: nothing of the old one is kept (backed up, test chains, privacy notes,
     * pending limits except [pending], the ones chosen in this session and not yet sent, which are carried over), and the limit of
     * each wallet in [listed] (null: the wallets are not listed yet; the first list read fills it, [listed]) is asked again before
     * Receive. The one sanctioned overwrite
     * (the member confirmed "Reset wallet settings on this phone"); false when the file reads fine (nothing is reset then), when
     * a wipe began meanwhile or when it could not be written.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun reset(pending: Map<String, WalletCaps> = emptyMap(), listed: Set<String>? = null): Boolean {
        val started = generation()
        return lock.withLock {
            if (readResult() is Read.Loaded) return@withLock false
            if (generation() != started) return@withLock false
            try {
                val fresh = Flags(emptySet(), emptyMap(), pendingCaps = pending, askCaps = listed.orEmpty(), askNext = listed == null)
                withContext(Dispatchers.IO) { file.save(encode(fresh)) }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun change(f: (Flags) -> Flags): Boolean {
        val started = generation()
        return lock.withLock {
            // A file that cannot be read (cannot be decrypted, I/O error, or loads but is not a file this version parses: another
            // `version` after a downgrade, a bad `pending_caps` entry) is never written over: it may hold pending limits that
            // would be lost with it, which would open Receive, so the change fails. Fail secure: until it reads again, no flag
            // can be written.
            val now = (readResult() as? Read.Loaded)?.flags ?: return@withLock false
            val next = f(now)
            if (next == now) return@withLock true
            if (generation() != started) return@withLock false
            try {
                withContext(Dispatchers.IO) { file.save(encode(next)) }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** What reading the file found. */
    private sealed interface Read {
        class Loaded(val flags: Flags) : Read

        /** The file could not be read: it could not be loaded (decryption, I/O) or it loaded but this version cannot parse it. */
        data object Unreadable : Read
    }

    /** The flags, or null when the file cannot be loaded or does not parse. */
    private suspend fun read(): Flags? = (readResult() as? Read.Loaded)?.flags

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun readResult(): Read = withContext(Dispatchers.IO) {
        val bytes = try {
            file.load() ?: return@withContext Read.Loaded(Flags(emptySet(), emptyMap()))
        } catch (_: Exception) {
            return@withContext Read.Unreadable
        }
        try {
            decode(bytes)?.let { Read.Loaded(it) } ?: Read.Unreadable
        } catch (_: IllegalArgumentException) {
            Read.Unreadable
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun decode(bytes: ByteArray): Flags? {
        val o = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: return null
        if ((o["version"] as? JsonPrimitive)?.intOrNull != VERSION) return null
        val backedUp = (o["backed_up"] as? JsonArray ?: return null).map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null
        }.toSet()
        val testnet = (o["testnet"] as? JsonObject ?: return null).entries.associate { (id, v) ->
            val name = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val n = Network.fromVaultName(name)?.takeIf { it == Network.TESTNET || it == Network.TESTNET4 } ?: return null
            id to n
        }
        // `note_shown` came after version 1: a file without it reads as an empty set (no version bump).
        val noteShown = when (val n = o["note_shown"]) {
            null -> emptySet()
            is JsonArray -> n.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }.toSet()
            else -> return null
        }
        // `pending_caps` came with W1d: a file without it reads as none.
        val pending = when (val c = o["pending_caps"]) {
            null -> emptyMap()
            is JsonObject -> c.entries.associate { (id, v) -> id to (capsOf(v) ?: return null) }
            else -> return null
        }
        // `ask_caps`, `ask_next` and `answered_caps` came with the reset: a file without them reads as not asking.
        fun ids(key: String): Set<String>? = when (val a = o[key]) {
            null -> emptySet()
            is JsonArray -> a.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }.toSet()
            else -> null
        }
        val ask = ids("ask_caps") ?: return null
        val answered = ids("answered_caps") ?: return null
        val askNext = when (val a = o["ask_next"]) {
            null -> false
            is JsonPrimitive -> a.booleanOrNull ?: return null
            else -> return null
        }
        return Flags(backedUp, testnet, noteShown, pending, ask, askNext, answered)
    }

    /** A pending entry `{per_tx_sats?, per_period_sats?}`; null if it is not limits the vault would take (the file is then unreadable). */
    private fun capsOf(e: JsonElement): WalletCaps? {
        val o = e as? JsonObject ?: return null
        fun member(k: String): Long? = when (val v = o[k]) {
            null, JsonNull -> null
            is JsonPrimitive -> v.takeUnless { it.isString }?.longOrNull ?: throw IllegalArgumentException(k)
            else -> throw IllegalArgumentException(k)
        }
        return WalletCaps(member("per_tx_sats"), member("per_period_sats")).takeIf { CapsRules.problem(it) == null && !it.none }
    }

    private fun encode(f: Flags): ByteArray = buildJsonObject {
        put("version", VERSION)
        put("backed_up", JsonArray(f.backedUp.sorted().map(::JsonPrimitive)))
        put("testnet", buildJsonObject { f.testnet.entries.sortedBy { it.key }.forEach { put(it.key, it.value.vaultName) } })
        put("note_shown", JsonArray(f.noteShown.sorted().map(::JsonPrimitive)))
        if (f.askCaps.isNotEmpty()) put("ask_caps", JsonArray(f.askCaps.sorted().map(::JsonPrimitive)))
        if (f.askNext) put("ask_next", true)
        if (f.answeredCaps.isNotEmpty()) put("answered_caps", JsonArray(f.answeredCaps.sorted().map(::JsonPrimitive)))
        if (f.pendingCaps.isNotEmpty()) {
            put(
                "pending_caps",
                buildJsonObject {
                    f.pendingCaps.entries.sortedBy { it.key }.forEach { (id, c) ->
                        put(
                            id,
                            buildJsonObject {
                                c.perTxSats?.let { put("per_tx_sats", it) }
                                c.perPeriodSats?.let { put("per_period_sats", it) }
                            },
                        )
                    }
                },
            )
        }
    }.toString().encodeToByteArray()

    private companion object {
        const val VERSION = 1
    }
}
