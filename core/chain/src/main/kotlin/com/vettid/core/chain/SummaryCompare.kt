package com.vettid.core.chain

/** What differed between the vault's summary and what the app built ([SummaryCompare]). Kinds only: no value reaches a log. */
enum class SummaryDifference {
    /** The transaction's id. */
    TXID,

    /** The coins spent: an outpoint or an amount. */
    INPUTS,

    /** A different number of outputs, or an output of another kind (payee or change) than built. */
    OUTPUTS,

    /** No output to the payee's address as the member entered it, or more than one. */
    PAYEE_ADDRESS,

    /** The payee's amount. */
    PAYEE_AMOUNT,

    /** The change: its amount, address or place in the wallet. */
    CHANGE,

    /** The fee, or the total the inputs carry. */
    FEE,
}

/**
 * The compare of ANDROID-PLAN §4 "Sending", step 4: the vault's `wallet.psbt.inspect` summary against what the app built and what the
 * member entered. The vault re-encodes an output script as an address of the wallet's network, so the payee's address string is
 * compared as the member entered it (a bech32 address in capitals is the same string in lower case: bech32 is case-insensitive and the
 * vault writes lower case). Any difference stops the send before anything is signed. The vault's `vsize` is not compared: it is not
 * money, and the fee in sats is.
 */
object SummaryCompare {
    fun differences(
        enteredAddress: String,
        enteredAmountSats: Long,
        built: InspectSummary,
        vault: InspectSummary,
    ): Set<SummaryDifference> {
        val out = linkedSetOf<SummaryDifference>()
        if (vault.txid != built.txid) out += SummaryDifference.TXID
        if (vault.inputs.map { Triple(it.txid, it.vout, it.amountSats) } != built.inputs.map { Triple(it.txid, it.vout, it.amountSats) }) {
            out += SummaryDifference.INPUTS
        }
        val payees = vault.outputs.filter { !it.change }
        val payee = payees.singleOrNull { sameAddress(enteredAddress, it.address) }
        if (payee == null || payees.size != 1) out += SummaryDifference.PAYEE_ADDRESS
        if (payee != null && payee.amountSats != enteredAmountSats) out += SummaryDifference.PAYEE_AMOUNT
        if (vault.sendingSats != enteredAmountSats) out += SummaryDifference.PAYEE_AMOUNT
        val vaultChange = vault.outputs.filter { it.change }
        val builtChange = built.outputs.filter { it.change }
        if (vault.changeSats != built.changeSats || vaultChange != builtChange) out += SummaryDifference.CHANGE
        if (vault.feeSats != built.feeSats || vault.totalInSats != built.totalInSats) out += SummaryDifference.FEE
        if (vault.outputs.size != built.outputs.size || vault.outputs.map { it.change } != built.outputs.map { it.change }) {
            out += SummaryDifference.OUTPUTS
        }
        return out
    }

    private fun sameAddress(entered: String, vault: String): Boolean =
        entered == vault || (isBech32(entered) && entered.lowercase() == vault)

    private fun isBech32(a: String): Boolean {
        val l = a.lowercase()
        return (l.startsWith("bc1") || l.startsWith("tb1") || l.startsWith("bcrt1")) && (a == l || a == a.uppercase())
    }
}
