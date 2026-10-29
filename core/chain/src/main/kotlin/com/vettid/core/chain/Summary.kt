package com.vettid.core.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The `summary` of VAULT-MESSAGING §10.18, as `wallet.psbt.inspect` returns it (and as [PsbtInspector] computes it from the same
 * PSBT). Amounts are satoshis; a txid is the 64-hex form Bitcoin shows; `type` is `p2wpkh` or `p2tr`; `path` is `m/84'/c'/0'/{0,1}/i`.
 */
data class InspectSummary(
    val txid: String,
    val inputs: List<SummaryInput>,
    val outputs: List<SummaryOutput>,
    val totalInSats: Long,
    val sendingSats: Long,
    val changeSats: Long,
    val feeSats: Long,
    val vsize: Long,
) {
    /**
     * The summary in the vault's own serialization (vettid-vault `features/wallet/wallet.go`, `summaryJSON`: this member order, no
     * spaces), so that two summaries compare byte for byte.
     */
    fun toJson(): String = buildString {
        append("{\"txid\":").append(q(txid)).append(",\"inputs\":[")
        inputs.forEachIndexed { i, x ->
            if (i > 0) append(',')
            append("{\"txid\":").append(q(x.txid)).append(",\"vout\":").append(x.vout).append(",\"amount_sats\":").append(x.amountSats)
            append(",\"type\":").append(q(x.type)).append(",\"path\":").append(q(x.path)).append('}')
        }
        append("],\"outputs\":[")
        outputs.forEachIndexed { i, x ->
            if (i > 0) append(',')
            append("{\"address\":").append(q(x.address)).append(",\"amount_sats\":").append(x.amountSats).append(",\"change\":").append(x.change)
            if (x.change) append(",\"type\":").append(q(x.type!!)).append(",\"path\":").append(q(x.path!!))
            append('}')
        }
        append("],\"total_in_sats\":").append(totalInSats).append(",\"sending_sats\":").append(sendingSats)
        append(",\"change_sats\":").append(changeSats).append(",\"fee_sats\":").append(feeSats).append(",\"vsize\":").append(vsize).append('}')
    }

    companion object {
        private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** Reads the vault's answer; any member missing or of the wrong type is an [IllegalArgumentException]. */
        fun fromJson(text: String): InspectSummary = try {
            val o = Json.parseToJsonElement(text).jsonObject
            fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.also { require(it.isString) }.content
            fun JsonObject.n(k: String) = getValue(k).jsonPrimitive.long
            fun JsonObject.a(k: String): JsonArray = getValue(k).jsonArray
            InspectSummary(
                txid = o.s("txid"),
                inputs = o.a("inputs").map {
                    val x = it.jsonObject
                    SummaryInput(x.s("txid"), x.n("vout"), x.n("amount_sats"), x.s("type"), x.s("path"))
                },
                outputs = o.a("outputs").map {
                    val x = it.jsonObject
                    val change = x.getValue("change").jsonPrimitive.boolean
                    SummaryOutput(x.s("address"), x.n("amount_sats"), change, if (change) x.s("type") else null, if (change) x.s("path") else null)
                },
                totalInSats = o.n("total_in_sats"),
                sendingSats = o.n("sending_sats"),
                changeSats = o.n("change_sats"),
                feeSats = o.n("fee_sats"),
                vsize = o.n("vsize"),
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("not a wallet.psbt.inspect summary", e)
        }
    }
}

data class SummaryInput(val txid: String, val vout: Long, val amountSats: Long, val type: String, val path: String)

/** [type] and [path] are present for change outputs only, as the vault gives them. */
data class SummaryOutput(val address: String, val amountSats: Long, val change: Boolean, val type: String?, val path: String?)

/** The vault's reasons for `invalid_psbt` (VAULT-MESSAGING §10.18 table), plus the one the app adds for a PSBT member it cannot send. */
object PsbtReasons {
    const val SIZE = "size"
    const val MALFORMED = "malformed"
    const val SHAPE = "shape"
    const val VERSION = "version"
    const val DUPLICATE_INPUT = "duplicate_input"
    const val PREVIOUS_TX = "previous_tx"
    const val WITNESS_UTXO = "witness_utxo"
    const val SIGHASH = "sighash"
    const val INPUT_SCRIPT = "input_script"
    const val AMOUNT = "amount"
    const val FOREIGN_INPUT = "foreign_input"
    const val NO_ACCOUNT = "no_account"
    const val DERIVATION_PATH = "derivation_path"
    const val DERIVATION_KEY = "derivation_key"
    const val DERIVATION_SCRIPT = "derivation_script"
    const val INTERNAL_KEY = "internal_key"
    const val OUTPUT_AMOUNT = "output_amount"
    const val OUTPUT_SCRIPT = "output_script"
    const val FEE = "fee"
    const val FEE_RATE = "fee_rate"
}

sealed interface InspectResult {
    data class Ok(val summary: InspectSummary) : InspectResult

    /** The PSBT would be refused with `invalid_psbt` and this [reason] (one of [PsbtReasons]). */
    data class Refused(val reason: String) : InspectResult
}
