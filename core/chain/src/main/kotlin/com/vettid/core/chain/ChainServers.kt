package com.vettid.core.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 * testnet4; none for mainnet (chosen with the production approval); none for testnet3, which the app does not connect to; regtest
 * only in a development build that names one ([regtest], devStack's build config).
 */
class ChainDefaults(val regtest: ChainServerUrl? = null) {
    fun of(network: Network): ChainServerUrl? = when (network) {
        Network.SIGNET -> SIGNET
        Network.TESTNET4 -> TESTNET4
        Network.REGTEST -> regtest
        Network.MAINNET, Network.TESTNET -> null
    }

    /** Whether the app offers a server setting for [network] in this build. */
    fun offered(network: Network): Boolean = when (network) {
        Network.SIGNET, Network.TESTNET4, Network.MAINNET -> true
        Network.REGTEST -> regtest != null
        Network.TESTNET -> false
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

    /** No server: mainnet before its default is chosen. Nothing is looked up. */
    data object NotSet : ChainServer

    /** The app does not connect to this network in this build (testnet3; regtest outside devStack). */
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

/**
 * The chain server per network (not per wallet), kept in the app's encrypted settings on this phone only and never sent to the
 * vault (a new phone starts from the defaults). Saving tests the server first: it must answer, and its block 0 must be the network's
 * genesis ([checkGenesis]).
 */
class ChainServers(
    private val file: ChainSettingsFile,
    val defaults: ChainDefaults,
    private val clientFor: (ChainServerUrl) -> EsploraClient,
) {
    constructor(file: ChainSettingsFile, defaults: ChainDefaults, http: OkHttpClient) : this(file, defaults, { EsploraClient(it, http) })

    private val lock = Mutex()

    /** The member's own servers by network, or null when the file could not be read. Loaded once. */
    private var own: Map<Network, ChainServerUrl>? = null
    private var loaded = false

    suspend fun server(network: Network): ChainServer = lock.withLock { current(network) }

    /** A client for [network]'s server, or null when there is none to use (see [ChainServer]). */
    suspend fun client(network: Network): EsploraClient? = when (val s = server(network)) {
        is ChainServer.Default -> clientFor(s.url)
        is ChainServer.Own -> clientFor(s.url)
        else -> null
    }

    /** Checks [text] as "Use my own server" for [network] and saves it only if the check passes. */
    suspend fun save(network: Network, text: String): SaveResult {
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
        return lock.withLock { if (write(network, url)) SaveResult.Saved(url) else SaveResult.NotWritten }
    }

    /** "Reset to default": forgets the member's server for [network]. False if the setting could not be written. */
    suspend fun reset(network: Network): Boolean = lock.withLock { write(network, null) }

    private suspend fun current(network: Network): ChainServer {
        if (!defaults.offered(network)) return ChainServer.NotOffered
        val servers = loadedServers() ?: return ChainServer.Unreadable
        servers[network]?.let { return ChainServer.Own(it) }
        return defaults.of(network)?.let { ChainServer.Default(it) } ?: ChainServer.NotSet
    }

    private suspend fun loadedServers(): Map<Network, ChainServerUrl>? {
        if (!loaded) {
            own = withContext(Dispatchers.IO) { read() }
            loaded = true
        }
        return own
    }

    /** Writes the servers with [network]'s set to [url] (or removed); an unreadable file is replaced from empty. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun write(network: Network, url: ChainServerUrl?): Boolean {
        val next = (loadedServers() ?: emptyMap()).toMutableMap()
        if (url == null) next.remove(network) else next[network] = url
        return try {
            withContext(Dispatchers.IO) { file.save(encode(next)) }
            own = next
            true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun read(): Map<Network, ChainServerUrl>? {
        val bytes = try {
            file.load() ?: return emptyMap()
        } catch (_: Exception) {
            return null
        }
        return try {
            val o = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: return null
            if ((o["version"] as? JsonPrimitive)?.intOrNull != VERSION) return null
            val servers = o["servers"] as? JsonObject ?: return null
            servers.entries.associate { (name, v) ->
                val network = Network.fromVaultName(name) ?: return null
                val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val url = (ChainServerUrl.parse(text) as? UrlCheck.Ok)?.url ?: return null
                network to url
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encode(servers: Map<Network, ChainServerUrl>): ByteArray = buildJsonObject {
        put("version", VERSION)
        put("servers", buildJsonObject { servers.entries.sortedBy { it.key.vaultName }.forEach { put(it.key.vaultName, it.value.text) } })
    }.toString().encodeToByteArray()

    private companion object {
        const val VERSION = 1
    }
}
