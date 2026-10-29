package com.vettid.core.chain

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.BlockHash

/**
 * The chains of VAULT-MESSAGING §10.18 "Networks". testnet3 and testnet4 share encodings (addresses, `tpub`, coin type 1) and
 * differ only by genesis block, which is what bitcoin-kmp's chain hash is; the app knows which one a wallet is on.
 */
enum class Network(val chainHash: BlockHash, val coinType: Long, val testKeys: Boolean) {
    MAINNET(Block.LivenetGenesisBlock.hash, 0, false),
    TESTNET(Block.Testnet3GenesisBlock.hash, 1, true),
    TESTNET4(Block.Testnet4GenesisBlock.hash, 1, true),
    SIGNET(Block.SignetGenesisBlock.hash, 1, true),
}
