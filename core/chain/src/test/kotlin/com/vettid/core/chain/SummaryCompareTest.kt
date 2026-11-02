package com.vettid.core.chain

import org.junit.Assert.assertEquals
import org.junit.Test

/** The vault's summary against what was built: each kind of difference is seen (ANDROID-PLAN §4 "Sending", step 4). */
class SummaryCompareTest {
    private val payee = "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl"
    private val change = SummaryOutput("tb1pchange", 9_000, true, "p2tr", "m/86'/1'/0'/1/0")
    private val built = InspectSummary(
        txid = "aa".repeat(32),
        inputs = listOf(SummaryInput("bb".repeat(32), 0, 50_000, "p2wpkh", "m/84'/1'/0'/0/0")),
        outputs = listOf(SummaryOutput(payee, 40_000, false, null, null), change),
        totalInSats = 50_000, sendingSats = 40_000, changeSats = 9_000, feeSats = 1_000, vsize = 153,
    )

    private fun diff(vault: InspectSummary, address: String = payee, amount: Long = 40_000) =
        SummaryCompare.differences(address, amount, built, vault)

    @Test
    fun theSameSummaryIsNoDifference() {
        assertEquals(emptySet<SummaryDifference>(), diff(built))
        // The vault's own vsize is not money.
        assertEquals(emptySet<SummaryDifference>(), diff(built.copy(vsize = 160)))
    }

    @Test
    fun aBech32AddressInCapitalsIsTheSameAddress() {
        assertEquals(emptySet<SummaryDifference>(), diff(built, address = payee.uppercase()))
    }

    @Test
    fun aDifferentPayeeAddressIsSeen() {
        val other = built.copy(outputs = listOf(SummaryOutput("tb1qother", 40_000, false, null, null), change))
        assertEquals(setOf(SummaryDifference.PAYEE_ADDRESS), diff(other))
        // A string that differs in one character, or in case outside bech32.
        val oneOff = built.copy(outputs = listOf(SummaryOutput(payee.dropLast(1) + "m", 40_000, false, null, null), change))
        assertEquals(setOf(SummaryDifference.PAYEE_ADDRESS), diff(oneOff))
    }

    @Test
    fun aDifferentAmountIsSeen() {
        val more = built.copy(
            outputs = listOf(SummaryOutput(payee, 40_001, false, null, null), change), sendingSats = 40_001,
        )
        assertEquals(true, SummaryDifference.PAYEE_AMOUNT in diff(more))
        // The member entered another amount than the PSBT carries.
        assertEquals(setOf(SummaryDifference.PAYEE_AMOUNT), diff(built, amount = 39_999))
    }

    @Test
    fun aDifferentChangeIsSeen() {
        val moved = built.copy(outputs = listOf(built.outputs[0], change.copy(address = "tb1pelsewhere")))
        assertEquals(setOf(SummaryDifference.CHANGE), diff(moved))
        val less = built.copy(outputs = listOf(built.outputs[0], change.copy(amountSats = 8_000)), changeSats = 8_000)
        assertEquals(true, SummaryDifference.CHANGE in diff(less))
        val noPath = built.copy(outputs = listOf(built.outputs[0], change.copy(path = "m/86'/1'/0'/1/7")))
        assertEquals(setOf(SummaryDifference.CHANGE), diff(noPath))
    }

    @Test
    fun aDifferentFeeIsSeen() {
        assertEquals(setOf(SummaryDifference.FEE), diff(built.copy(feeSats = 1_001)))
        assertEquals(setOf(SummaryDifference.FEE), diff(built.copy(totalInSats = 50_001)))
    }

    @Test
    fun anExtraOutputOrADifferentCoinOrTxidIsSeen() {
        val extra = built.copy(outputs = built.outputs + SummaryOutput("tb1qthief", 330, false, null, null))
        assertEquals(true, SummaryDifference.OUTPUTS in diff(extra))
        assertEquals(true, SummaryDifference.PAYEE_ADDRESS in diff(extra))
        assertEquals(setOf(SummaryDifference.INPUTS), diff(built.copy(inputs = listOf(built.inputs[0].copy(amountSats = 50_001)))))
        assertEquals(setOf(SummaryDifference.TXID), diff(built.copy(txid = "cc".repeat(32))))
    }
}
