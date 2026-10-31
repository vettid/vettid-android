package com.vettid.core.chain

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Protocol
import fr.acinq.bitcoin.Transaction

/**
 * Answers in the form mempool.space's Esplora gives them (`/api/address/…`, `/txs`, `/utxo`, `/tx/…/hex`, `/fee-estimates`,
 * `POST /tx`), with this test's own address and txids.
 */
internal object EsploraFixtures {
    const val ADDRESS = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    const val OTHER_ADDRESS = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"

    /** A distinct txid per [n]. */
    fun txid(n: Int): String = "%064x".format(n.toLong() + 0x1000)

    const val SUMMARY = """{"address":"$ADDRESS",
        "chain_stats":{"funded_txo_count":3,"funded_txo_sum":150000,"spent_txo_count":1,"spent_txo_sum":50000,"tx_count":3},
        "mempool_stats":{"funded_txo_count":1,"funded_txo_sum":2500,"spent_txo_count":1,"spent_txo_sum":10000,"tx_count":2}}"""

    fun tx(n: Int, confirmed: Boolean, height: Long = 200_000L + n): String {
        val status = if (confirmed) {
            """{"confirmed":true,"block_height":$height,"block_hash":"${txid(900_000 + n)}","block_time":1730000000}"""
        } else {
            """{"confirmed":false}"""
        }
        return """{"txid":"${txid(n)}","version":2,"locktime":0,
            "vin":[{"txid":"${txid(100_000 + n)}","vout":1,"prevout":{"scriptpubkey":"0014751e76e8199196d454941c45d1b3a323f1433bd6",
              "scriptpubkey_asm":"OP_0 OP_PUSHBYTES_20 751e","scriptpubkey_type":"v0_p2wpkh","scriptpubkey_address":"$OTHER_ADDRESS",
              "value":60000},"scriptsig":"","scriptsig_asm":"","witness":["30","02"],"is_coinbase":false,"sequence":4294967293}],
            "vout":[{"scriptpubkey":"0014751e76e8199196d454941c45d1b3a323f1433bd6","scriptpubkey_asm":"OP_0",
              "scriptpubkey_type":"v0_p2wpkh",
              "scriptpubkey_address":"$ADDRESS","value":50000},
              {"scriptpubkey":"6a0474657374","scriptpubkey_asm":"OP_RETURN","scriptpubkey_type":"op_return","value":0}],
            "size":222,"weight":561,"sigops":1,"fee":$n,"status":$status}"""
    }

    fun page(vararg txs: String): String = txs.joinToString(",", "[", "]")

    val COINBASE_TX = """{"txid":"${txid(7)}","version":2,"locktime":0,
        "vin":[{"txid":"${"0".repeat(64)}","vout":4294967295,"prevout":null,"scriptsig":"03a0bb0d","witness":[],"is_coinbase":true,
          "sequence":4294967295}],
        "vout":[{"scriptpubkey":"0014751e76e8199196d454941c45d1b3a323f1433bd6","scriptpubkey_address":"$ADDRESS","value":5000000000}],
        "fee":0,"status":{"confirmed":true,"block_height":1,"block_hash":"${txid(8)}","block_time":1296688602}}"""

    val UTXO = """[{"txid":"${txid(1)}","vout":0,"status":{"confirmed":true,"block_height":200001,"block_hash":"${txid(9)}",
        "block_time":1730000000},"value":50000},
        {"txid":"${txid(2)}","vout":3,"status":{"confirmed":false},"value":2500}]"""

    const val FEES = """{"1":87.882,"2":87.882,"3":87.882,"6":68.285,"144":1.027,"1008":1.0}"""

    /** A real transaction: the genesis block's coinbase, and its well-known txid. */
    val RAW_TX: ByteArray = Transaction.write(Block.LivenetGenesisBlock.tx[0], Protocol.PROTOCOL_VERSION)
    val RAW_HEX: String = RAW_TX.toHex()
    const val RAW_TXID = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"

    /** An address's totals with [confirmedTxs] confirmed transactions (and 1,000 sats confirmed). */
    fun summary(confirmedTxs: Int, confirmedSats: Long = 1_000, unconfirmedSats: Long = 0): String {
        val funded = maxOf(confirmedSats, 0)
        val spent = funded - confirmedSats
        val mFunded = maxOf(unconfirmedSats, 0)
        val mSpent = mFunded - unconfirmedSats
        return """{"chain_stats":{"funded_txo_count":1,"funded_txo_sum":$funded,"spent_txo_count":0,"spent_txo_sum":$spent,
            "tx_count":$confirmedTxs},"mempool_stats":{"funded_txo_count":0,"funded_txo_sum":$mFunded,"spent_txo_count":0,
            "spent_txo_sum":$mSpent,"tx_count":0}}"""
    }
}
