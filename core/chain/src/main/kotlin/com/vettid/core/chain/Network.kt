package com.vettid.core.chain

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.BlockHash

/**
 * The chains of VAULT-MESSAGING §10.18 "Networks", by the names the vault uses (`wallet.get` `network`). testnet, testnet4 and
 * signet share encodings (addresses, `tpub`, coin type 1) and differ only by genesis block, which is what bitcoin-kmp's chain hash
 * is; the app knows which one a wallet is on from the name. `REGTEST` exists in development builds of the vault only.
 */
enum class Network(val vaultName: String, internal val chainHash: BlockHash, val coinType: Long, internal val testKeys: Boolean) {
    MAINNET("mainnet", Block.LivenetGenesisBlock.hash, 0, false),
    TESTNET("testnet", Block.Testnet3GenesisBlock.hash, 1, true),
    TESTNET4("testnet4", Block.Testnet4GenesisBlock.hash, 1, true),
    SIGNET("signet", Block.SignetGenesisBlock.hash, 1, true),
    REGTEST("regtest", Block.RegtestGenesisBlock.hash, 1, true),
    ;

    companion object {
        fun fromVaultName(name: String): Network? = entries.firstOrNull { it.vaultName == name }
    }
}
