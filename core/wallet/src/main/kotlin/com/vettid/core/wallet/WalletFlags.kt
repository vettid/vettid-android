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
class WalletFlags(private val file: WalletFlagsFile, private val generation: () -> Long = { 0 }) {
    private val lock = Mutex()

    private data class Flags(
        val backedUp: Set<String>,
        val testnet: Map<String, Network>,
        val noteShown: Set<String> = emptySet(),
        val pendingCaps: Map<String, WalletCaps> = emptyMap(),
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

    /** Every wallet's pending limits. An unreadable file reads as none (the limits are lost with it; nothing else can be done). */
    suspend fun allPendingCaps(): Map<String, WalletCaps> = lock.withLock { read()?.pendingCaps.orEmpty() }

    /** Records the limits chosen for [walletId], to be set; false if it could not be written. */
    suspend fun setPendingCaps(walletId: String, caps: WalletCaps): Boolean =
        change { it.copy(pendingCaps = it.pendingCaps + (walletId to caps)) }

    /** The limits of [walletId] were set (or are not wanted): forgotten; false if it could not be written. */
    suspend fun clearPendingCaps(walletId: String): Boolean = change { it.copy(pendingCaps = it.pendingCaps - walletId) }

    /** Waits for a write in progress (a wipe's hook, after the generation moved), so that none that started before the wipe is kept. */
    suspend fun fence() = lock.withLock { }

    /** Forgets what is kept of a deleted wallet. */
    suspend fun forget(walletId: String): Boolean = change {
        Flags(it.backedUp - walletId, it.testnet - walletId, it.noteShown - walletId, it.pendingCaps - walletId)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun change(f: (Flags) -> Flags): Boolean {
        val started = generation()
        return lock.withLock {
            // An unreadable file is replaced: losing a flag only shows "Not backed up" or asks the test chain again.
            val now = read() ?: Flags(emptySet(), emptyMap())
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

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun read(): Flags? = withContext(Dispatchers.IO) {
        val bytes = try {
            file.load() ?: return@withContext Flags(emptySet(), emptyMap())
        } catch (_: Exception) {
            return@withContext null
        }
        try {
            decode(bytes)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Suppress("ReturnCount")
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
        return Flags(backedUp, testnet, noteShown, pending)
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
