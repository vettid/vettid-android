package com.vettid.core.chain

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

    const val RAW_HEX = "0200000001" + "ab" + "00"
}
