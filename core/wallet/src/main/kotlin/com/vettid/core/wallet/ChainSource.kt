package com.vettid.core.wallet

import com.vettid.core.chain.ChainAccess
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainServers
import com.vettid.core.chain.ChainSummary
import com.vettid.core.chain.ChainTarget
import com.vettid.core.chain.ChainUtxo
import com.vettid.core.chain.Network

/** What a look-up on a network can use (ANDROID-PLAN §4 "The chain source"), as the wallet manager needs it. */
sealed interface ChainOpen {
    /**
     * The server answers for the network: [summary] reads one address's totals; [host] is for "Using mempool.space"; [spend] is what a
     * send needs of it (W2; null: a source that only looks up).
     */
    class Ready(val host: String, val spend: SpendAccess? = null, val summary: suspend (address: String) -> ChainSummary) : ChainOpen {
        override fun toString(): String = "Ready"
    }

    /** No server to ask: [server] says why (not set, not offered, could not be read). */
    data class NoServer(val server: ChainServer) : ChainOpen

    /** The server's block 0 is not the network's any more. */
    data class WrongNetwork(val found: Network?) : ChainOpen

    /** The server did not answer the check. */
    data class Unchecked(val error: Exception) : ChainOpen
}

/** What a send asks of the chain server (Esplora): the coins, their previous transactions, the fee rates, and the broadcast. */
interface SpendAccess {
    /** The unspent outputs of one address, confirmed or not. */
    suspend fun unspent(address: String): List<ChainUtxo>

    /** The full previous transaction, checked to be the transaction [txid] names. */
    suspend fun rawTransaction(txid: String): ByteArray

    /** `GET /tx/<txid>`: whether the server knows the transaction (confirmed or not). Throws [com.vettid.core.chain.ChainError]. */
    suspend fun knows(txid: String): Boolean

    /** Fee rates in sat/vB by confirmation target in blocks. */
    suspend fun feeEstimates(): Map<Int, Double>

    /** `POST /tx`: the txid the server answers. Throws [com.vettid.core.chain.ChainError]. */
    suspend fun broadcast(tx: ByteArray): String
}

/** The chain side of the wallet manager, so that its tests need no server. */
fun interface ChainSource {
    suspend fun open(network: Network): ChainOpen
}

/** [ChainSource] over the member's server setting ([ChainServers.access]: block 0 checked once per session). */
class ServersChainSource(private val servers: ChainServers) : ChainSource {
    override suspend fun open(network: Network): ChainOpen = when (val a = servers.access(network)) {
        is ChainAccess.Ready -> {
            val host = when (val s = a.server) {
                is ChainServer.Default -> s.url.host
                is ChainServer.Own -> s.url.host
                else -> ""
            }
            val client = a.client
            val spend = object : SpendAccess {
                override suspend fun unspent(address: String) = client.unspent(ChainTarget.Address(address))

                override suspend fun rawTransaction(txid: String) = client.rawTransaction(txid)

                override suspend fun knows(txid: String) = client.knowsTransaction(txid)

                override suspend fun feeEstimates() = client.feeEstimates()

                override suspend fun broadcast(tx: ByteArray) = client.broadcast(tx)
            }
            ChainOpen.Ready(host, spend) { address -> client.summary(ChainTarget.Address(address)) }
        }
        is ChainAccess.NoServer -> ChainOpen.NoServer(a.server)
        is ChainAccess.WrongNetwork -> ChainOpen.WrongNetwork(a.found)
        is ChainAccess.Unchecked -> ChainOpen.Unchecked(a.error)
    }
}
