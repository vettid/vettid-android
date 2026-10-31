package com.vettid.core.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** Where the chain server setting is kept: the app's encrypted settings on this phone ([load] is null before the first save). */
interface ChainSettingsFile {
    fun load(): ByteArray?

    fun save(bytes: ByteArray)
}

/**
 * The servers a network starts with (ANDROID-PLAN §4 "The chain source", question 17): mempool.space's Esplora for signet and
 * testnet4; none for mainnet, which is not offered yet (its server comes with the production approval); none for testnet3, which the
 * app does not connect to; regtest only in a development build that names one ([regtest], devStack's build config).
 */
class ChainDefaults(val regtest: ChainServerUrl? = null) {
    fun of(network: Network): ChainServerUrl? = when (network) {
        Network.SIGNET -> SIGNET
        Network.TESTNET4 -> TESTNET4
        Network.REGTEST -> regtest
        Network.MAINNET, Network.TESTNET -> null
    }

    /**
     * Whether the app offers a server setting for [network] in this build: signet and testnet4 (staging's wallet networks), regtest in
     * devStack. Not mainnet yet (it comes with the production approval), not testnet3.
     */
    fun offered(network: Network): Boolean = when (network) {
        Network.SIGNET, Network.TESTNET4 -> true
        Network.REGTEST -> regtest != null
        Network.MAINNET, Network.TESTNET -> false
    }

    companion object {
        val SIGNET: ChainServerUrl = ChainServerUrl.builtIn("https://mempool.space/signet/api")
        val TESTNET4: ChainServerUrl = ChainServerUrl.builtIn("https://mempool.space/testnet4/api")

        /** The defaults of a build whose regtest server is [regtest] (empty in every build but devStack). */
        fun forBuild(regtest: String?): ChainDefaults = ChainDefaults(ChainServerUrl.loopbackOrNull(regtest))
    }
}

/** A network's server as the setting stands. */
sealed interface ChainServer {
    /** The network's default ("Reset to default" gives it back). */
    data class Default(val url: ChainServerUrl) : ChainServer

    /** The member's own server, checked when it was saved. */
    data class Own(val url: ChainServerUrl) : ChainServer

    /**
     * No server: a network with no default, or one whose setting was lost (unreadable) and has not been saved or reset since.
     * Nothing is looked up.
     */
    data object NotSet : ChainServer

    /** The app does not connect to this network in this build (mainnet for now, testnet3; regtest outside devStack). */
    data object NotOffered : ChainServer

    /** The setting could not be read: no server is used (not even a default) until it is saved or reset. */
    data object Unreadable : ChainServer
}

/** The result of [ChainServers.save]. Nothing is saved unless it is [Saved]. */
sealed interface SaveResult {
    data class Saved(val url: ChainServerUrl) : SaveResult

    data class BadUrl(val problem: UrlProblem) : SaveResult

    data object NotOffered : SaveResult

    /** The server gave no usable answer to the check. */
    data class Failed(val error: ChainError) : SaveResult

    /** The server is on another network ([found], or one the app does not know). */
    data class WrongNetwork(val found: Network?) : SaveResult

    /** The setting could not be written. */
    data object NotWritten : SaveResult
}

/** What a lookup on a network can use: a checked client, or why there is none. */
sealed interface ChainAccess {
    /**
     * [server]'s client; its block 0 was checked this session. Use it for the lookup at hand and drop it: a client kept past
     * [ChainServers.forgetChecks] (a new session) would skip the next check.
     */
    data class Ready(val client: EsploraClient, val server: ChainServer) : ChainAccess

    /** No server to ask ([server] is [ChainServer.NotSet], [ChainServer.NotOffered] or [ChainServer.Unreadable]). */
    data class NoServer(val server: ChainServer) : ChainAccess

    /** The server's block 0 is no longer the network's ([found], or unknown): treated as no server. */
    data class WrongNetwork(val found: Network?) : ChainAccess

    /** Block 0 could not be read: nothing is looked up. */
    data class Unchecked(val error: ChainError) : ChainAccess
}

/**
 * The chain server per network (not per wallet), kept in the app's encrypted settings on this phone only and never sent to the
 * vault (a new phone starts from the defaults). Saving tests the server first: it must answer, and its block 0 must be the network's
 * genesis ([checkGenesis]). The setting is read from the file every time (nothing of it is kept in memory, so a wipe leaves
 * nothing behind); [access] checks block 0 again once per session before the first lookup on a network.
 */
class ChainServers(
    private val file: ChainSettingsFile,
    val defaults: ChainDefaults,
    private val clientFor: (ChainServerUrl) -> EsploraClient,
    /** Moves when a wipe begins: a save or reset that started before it writes nothing (the file stays erased). */
    private val generation: () -> Long = { 0 },
) {
    constructor(file: ChainSettingsFile, defaults: ChainDefaults, http: OkHttpClient, generation: () -> Long = { 0 }) :
        this(file, defaults, { EsploraClient(it, http) }, generation)

    /** A network's entry in the file: the member's server, or none at all (the setting was lost and not yet saved or reset). */
    private sealed interface Entry {
        data class Own(val url: ChainServerUrl) : Entry

        data object None : Entry
    }

    private val lock = Mutex()

    /** (network, server) pairs whose block 0 was checked this session; [forgetChecks] clears them. */
    private val checked = HashSet<Pair<Network, String>>()

    suspend fun server(network: Network): ChainServer = lock.withLock { current(network, read()) }

    /**
     * The client for a lookup on [network]: the setting's server, after its block 0 was checked once this session (a server that
     * changed after it was saved is not used).
     */
    suspend fun access(network: Network): ChainAccess {
        val server = server(network)
        val url = when (server) {
            is ChainServer.Default -> server.url
            is ChainServer.Own -> server.url
            else -> return ChainAccess.NoServer(server)
        }
        val client = clientFor(url)
        val key = network to url.text
        if (lock.withLock { key in checked }) return ChainAccess.Ready(client, server)
        return when (val check = client.checkGenesis(network)) {
            GenesisCheck.Matches -> {
                lock.withLock { checked += key }
                ChainAccess.Ready(client, server)
            }
            is GenesisCheck.WrongNetwork -> ChainAccess.WrongNetwork(check.found)
            is GenesisCheck.Failed -> ChainAccess.Unchecked(check.error)
        }
    }

    /** Waits for a write in progress (a wipe's hook, after the generation moved): none that started before it is kept after. */
    suspend fun fence() = lock.withLock { }

    /** A new session: every server's block 0 is checked again before its next lookup. */
    suspend fun forgetChecks() = lock.withLock { checked.clear() }

    /** Checks [text] as "Use my own server" for [network] and saves it only if the check passes. */
    suspend fun save(network: Network, text: String): SaveResult {
        val started = generation()
        if (!defaults.offered(network)) return SaveResult.NotOffered
        val url = when (val p = ChainServerUrl.parse(text)) {
            is UrlCheck.Refused -> return SaveResult.BadUrl(p.problem)
            is UrlCheck.Ok -> p.url
        }
        when (val check = clientFor(url).checkGenesis(network)) {
            is GenesisCheck.Failed -> return SaveResult.Failed(check.error)
            is GenesisCheck.WrongNetwork -> return SaveResult.WrongNetwork(check.found)
            GenesisCheck.Matches -> Unit
        }
        return lock.withLock {
            if (write(network, Entry.Own(url), started)) {
                checked += network to url.text
                SaveResult.Saved(url)
            } else {
                SaveResult.NotWritten
            }
        }
    }

    /**
     * "Reset to default": forgets the member's server for [network]. False if the setting could not be written. With an unreadable
     * setting, every other network is written as having no server, until it is saved or reset itself.
     */
    suspend fun reset(network: Network): Boolean {
        val started = generation()
        return lock.withLock { write(network, null, started) }
    }

    private fun current(network: Network, entries: Map<Network, Entry>?): ChainServer {
        if (!defaults.offered(network)) return ChainServer.NotOffered
        if (entries == null) return ChainServer.Unreadable
        return when (val e = entries[network]) {
            is Entry.Own -> ChainServer.Own(e.url)
            Entry.None -> ChainServer.NotSet
            null -> defaults.of(network)?.let { ChainServer.Default(it) } ?: ChainServer.NotSet
        }
    }

    /**
     * Writes [network]'s entry as [entry] (null: removed, the default). An unreadable setting is replaced by one in which every other
     * offered network has no server, so that none of them falls back to a default the member may not have chosen. Nothing is written
     * when nothing changes.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun write(network: Network, entry: Entry?, started: Long): Boolean {
        val entries = read()
        val base = entries ?: Network.entries.filter { it != network && defaults.offered(it) }.associateWith { Entry.None }
        val next = base.toMutableMap()
        if (entry == null) next.remove(network) else next[network] = entry
        if (entries != null && next == entries) return true
        if (generation() != started) return false
        return try {
            withContext(Dispatchers.IO) { file.save(encode(next)) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** The file's entries, or null when it cannot be read or is not this format. */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun read(): Map<Network, Entry>? = withContext(Dispatchers.IO) {
        val bytes = try {
            file.load() ?: return@withContext emptyMap()
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
    private fun decode(bytes: ByteArray): Map<Network, Entry>? {
        val o = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: return null
        if ((o["version"] as? JsonPrimitive)?.intOrNull != VERSION) return null
        val servers = o["servers"] as? JsonObject ?: return null
        return servers.entries.associate { (name, v) ->
            val network = Network.fromVaultName(name) ?: return null
            val entry = if (v is JsonNull) {
                Entry.None
            } else {
                val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                Entry.Own((ChainServerUrl.parse(text) as? UrlCheck.Ok)?.url ?: return null)
            }
            network to entry
        }
    }

    private fun encode(entries: Map<Network, Entry>): ByteArray = buildJsonObject {
        put("version", VERSION)
        put(
            "servers",
            buildJsonObject {
                for ((network, entry) in entries.entries.sortedBy { it.key.vaultName }) {
                    when (entry) {
                        is Entry.Own -> put(network.vaultName, entry.url.text)
                        Entry.None -> put(network.vaultName, JsonNull)
                    }
                }
            },
        )
    }.toString().encodeToByteArray()

    private companion object {
        const val VERSION = 1
    }
}
