package com.vettid.core.wallet

import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainServers
import com.vettid.core.chain.Network
import com.vettid.core.chain.SaveResult

/**
 * The chain server setting as the Chain server screen uses it (ANDROID-PLAN §4 "The chain source"), in front of [ChainServers] so
 * that the screen's ViewModel is tested without HTTP.
 */
interface ChainServerSettings {
    suspend fun server(network: Network): ChainServer

    /** Tests [text] as "Use my own server" for [network] and saves it only if the check passes. */
    suspend fun save(network: Network, text: String): SaveResult

    /** "Reset to default"; false if the setting could not be written. */
    suspend fun reset(network: Network): Boolean
}

/** [ChainServerSettings] over the real [ChainServers]. */
class ServersChainSettings(private val servers: ChainServers) : ChainServerSettings {
    override suspend fun server(network: Network): ChainServer = servers.server(network)

    override suspend fun save(network: Network, text: String): SaveResult = servers.save(network, text)

    override suspend fun reset(network: Network): Boolean = servers.reset(network)
}
