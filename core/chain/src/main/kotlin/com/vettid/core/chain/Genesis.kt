package com.vettid.core.chain

/**
 * The genesis block hashes the app knows (ANDROID-PLAN §4 "The chain source"), as Esplora shows them (`GET /block-height/0`, display
 * order). A server is used for a network only if its block 0 is that network's: a testnet3 server is refused for testnet4, a
 * mainnet server for a test network. `GenesisTest` checks each against bitcoin-kmp's own. Regtest has none of its own here (a
 * development chain): a regtest server is accepted if it answers with a block 0 that is none of these.
 */
internal object Genesis {
    private val HASHES: Map<Network, String> = mapOf(
        Network.MAINNET to "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
        Network.TESTNET to "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943",
        Network.TESTNET4 to "00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043",
        Network.SIGNET to "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6",
    )

    /** The network's genesis hash; null for regtest. */
    fun of(network: Network): String? = HASHES[network]

    /** The known network whose genesis [hash] is, or null. */
    fun networkOf(hash: String): Network? = HASHES.entries.firstOrNull { it.value == hash }?.key
}

/** What a server's block 0 says about it, for one network. */
sealed interface GenesisCheck {
    /** Block 0 is the network's genesis (or, for regtest, the server answered with none of the known ones). */
    data object Matches : GenesisCheck

    /** Block 0 is another network's ([found]), or one the app does not know (null). */
    data class WrongNetwork(val found: Network?) : GenesisCheck

    /** No usable answer: nothing is known about the server. */
    data class Failed(val error: ChainError) : GenesisCheck
}

/** Reads the server's block 0 and compares it with [network]'s genesis. */
suspend fun EsploraClient.checkGenesis(network: Network): GenesisCheck {
    val hash = try {
        blockHash(0)
    } catch (e: ChainError) {
        return GenesisCheck.Failed(e)
    }
    val found = Genesis.networkOf(hash)
    val expected = Genesis.of(network)
    return when {
        expected == null && found == null -> GenesisCheck.Matches
        hash == expected -> GenesisCheck.Matches
        else -> GenesisCheck.WrongNetwork(found)
    }
}
