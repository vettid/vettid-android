package com.vettid.core.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * `src/test/resources/vectors/wallet-inspect-vault-214b88f.json`: PSBTs and the answers of vettid-vault's own `btc.Inspect` for them
 * (see the file's `generator` member and `regenerate-w1-vectors.go.txt`). Test data only: the public BIP39 phrase "abandon ... about".
 */
object Vectors {
    class Case(val name: String, val why: String, val source: String, val wallet: String, val psbt: String, val build: JsonObject?, val expect: JsonObject) {
        val ok: Boolean get() = expect.getValue("ok").jsonPrimitive.boolean
        val summaryJson: String get() = expect.getValue("summary_json").jsonPrimitive.content
        val reason: String? get() = expect["reason"]?.jsonPrimitive?.content
        val code: String? get() = expect["code"]?.jsonPrimitive?.content
    }

    private val root: JsonObject by lazy {
        val text = Vectors::class.java.getResourceAsStream("/vectors/wallet-inspect-vault-214b88f.json")!!.readBytes().decodeToString()
        Json.parseToJsonElement(text).jsonObject
    }

    val cases: List<Case> by lazy {
        root.getValue("cases").jsonArray.map {
            val o = it.jsonObject
            fun s(k: String) = o.getValue(k).jsonPrimitive.content
            Case(s("name"), s("why"), s("source"), s("wallet"), s("psbt"), o["build"]?.jsonObject, o.getValue("expect").jsonObject)
        }
    }

    val wallets: Map<String, Wallet> by lazy {
        root.getValue("wallets").jsonObject.mapValues { (_, w) ->
            val o = w.jsonObject
            val network = Network.fromVaultName(o.getValue("network").jsonPrimitive.content)!!
            Wallet(
                network,
                o.getValue("accounts").jsonArray.map { a ->
                    val ao = a.jsonObject
                    val d = ao.getValue("descriptors").jsonObject
                    Account.fromDescriptors(d.getValue("receive").jsonPrimitive.content, d.getValue("change").jsonPrimitive.content, network)
                },
            )
        }
    }

    /** The receive and change descriptors the vault gave for an account of a vector wallet. */
    fun descriptorsOf(wallet: String, type: String): Pair<String, String> {
        val a = root.getValue("wallets").jsonObject.getValue(wallet).jsonObject.getValue("accounts").jsonArray
            .map { it.jsonObject }.first { it.getValue("type").jsonPrimitive.content == type }
        val d = a.getValue("descriptors").jsonObject
        return d.getValue("receive").jsonPrimitive.content to d.getValue("change").jsonPrimitive.content
    }

    fun coins(build: JsonObject): List<BuildCoin> = build.getValue("coins").jsonArray.map {
        val c = it.jsonObject
        BuildCoin(
            AccountType.fromVaultName(c.getValue("account").jsonPrimitive.content)!!,
            c.getValue("chain").jsonPrimitive.int == 1,
            c.getValue("index").jsonPrimitive.int,
            c.getValue("prev_tx_hex").jsonPrimitive.content.hexToBytes(),
            c.getValue("vout").jsonPrimitive.int,
        )
    }

    fun outputs(build: JsonObject): List<BuildOutput> = build.getValue("payees").jsonArray.map {
        val p = it.jsonObject
        val amount = p.getValue("amount").jsonPrimitive.long
        if (p["change"]?.jsonPrimitive?.boolean == true) {
            BuildOutput.Own(
                AccountType.fromVaultName(p.getValue("account").jsonPrimitive.content)!!,
                (p["chain"]?.jsonPrimitive?.int ?: 0) == 1,
                p["index"]?.jsonPrimitive?.int ?: 0,
                amount,
            )
        } else {
            BuildOutput.Pay(p.getValue("address").jsonPrimitive.content, amount)
        }
    }
}
