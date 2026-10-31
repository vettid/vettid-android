package com.vettid.core.wallet

import com.vettid.core.chain.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Where the wallet flags are kept: the app's encrypted settings on this phone ([load] is null before the first save). */
interface WalletFlagsFile {
    fun load(): ByteArray?

    fun save(bytes: ByteArray)
}

/**
 * What the app keeps of its wallets on this phone (ANDROID-PLAN §4 "Local state"), in its encrypted settings, never sent to the
 * vault: the **backed-up** flag per `wallet_id` (the vault has none) and, for a legacy `testnet` wallet only, which test chain the
 * member said it is on (question 16). Read from the file every time, nothing kept in memory. A write is refused when a wipe began
 * after the operation started ([generation] moved), so that a write in flight cannot bring the file back after it was erased.
 */
class WalletFlags(private val file: WalletFlagsFile, private val generation: () -> Long = { 0 }) {
    private val lock = Mutex()

    private data class Flags(val backedUp: Set<String>, val testnet: Map<String, Network>)

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

    /** Forgets what is kept of a deleted wallet. */
    suspend fun forget(walletId: String): Boolean = change { Flags(it.backedUp - walletId, it.testnet - walletId) }

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
        return Flags(backedUp, testnet)
    }

    private fun encode(f: Flags): ByteArray = buildJsonObject {
        put("version", VERSION)
        put("backed_up", JsonArray(f.backedUp.sorted().map(::JsonPrimitive)))
        put("testnet", buildJsonObject { f.testnet.entries.sortedBy { it.key }.forEach { put(it.key, it.value.vaultName) } })
    }.toString().encodeToByteArray()

    private companion object {
        const val VERSION = 1
    }
}
