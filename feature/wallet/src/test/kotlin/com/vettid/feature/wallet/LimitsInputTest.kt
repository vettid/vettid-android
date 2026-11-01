package com.vettid.feature.wallet

import androidx.compose.ui.text.AnnotatedString
import com.vettid.core.wallet.WalletCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The creation step's rules (ANDROID-PLAN §4 "Spending limits"): No limit by default, 1 to 2.1e15 sats, per payment <= per period. */
class LimitsInputTest {
    private fun parse(tx: String, period: String, btc: Boolean = false) = LimitsInput.parse(LimitsForm(true, tx, period, btc))

    @Test
    fun noLimitIsTheDefaultAndSetsNothing() {
        val form = LimitsForm()
        assertFalse(form.limit)
        val p = LimitsInput.parse(form)
        assertTrue(p.ok)
        assertNull(p.caps)
        // Typed amounts are ignored while "No limit" is chosen.
        assertNull(LimitsInput.parse(LimitsForm(false, "500", "100")).caps)
    }

    @Test
    fun eitherFieldAloneOrBothAreFine() {
        assertEquals(WalletCaps(500, null), parse("500", "").caps)
        assertEquals(WalletCaps(null, 2_000_000), parse("", "2000000").caps)
        assertEquals(WalletCaps(500, 500), parse("500", "500").caps)
        assertEquals(WalletCaps(1, CAPS_MAX), parse("1", "2100000000000000").caps)
    }

    @Test
    fun theRangeIsOneToTwoPointOneQuadrillion() {
        for (bad in listOf("0", "00", "2100000000000001", "9999999999999999")) {
            assertEquals(bad, LimitProblem.OUT_OF_RANGE, parse("", bad).perPeriodProblem)
            assertFalse(parse("", bad).ok)
        }
        assertEquals(LimitProblem.OUT_OF_RANGE, parse("0", "").perTxProblem)
        assertTrue(parse("2100000000000000", "").ok)
    }

    @Test
    fun perPaymentAbovePerPeriodIsRefused() {
        val p = parse("2000001", "2000000")
        assertFalse(p.ok)
        assertEquals(LimitProblem.ABOVE_PERIOD, p.perTxProblem)
        assertNull(p.caps)
    }

    @Test
    fun aLimitChosenWithBothFieldsEmptyIsNotAChoice() {
        val p = parse("", "")
        assertFalse(p.ok)
        assertEquals(LimitProblem.EMPTY, p.perTxProblem)
    }

    @Test
    fun bitcoinIsReadAsSatsWithAtMostEightDecimals() {
        assertEquals(WalletCaps(500_000, 2_000_000), parse("0.005", "0.02", btc = true).caps)
        assertEquals(WalletCaps(1, null), parse("0.00000001", "", btc = true).caps)
        assertEquals(LimitProblem.OUT_OF_RANGE, parse("0.000000001", "", btc = true).perTxProblem)
        assertEquals(LimitProblem.OUT_OF_RANGE, parse("0", "", btc = true).perTxProblem)
        assertEquals(LimitProblem.OUT_OF_RANGE, parse("21000000.00000001", "", btc = true).perTxProblem)
        assertEquals(WalletCaps(null, CAPS_MAX), parse("", "21000000", btc = true).caps)
    }

    @Test
    fun theFieldsTakeWholeNumbersOfSatsOnly() {
        assertEquals("1500", LimitsInput.filter("1,500", btc = false))
        assertEquals("12", LimitsInput.filter("1.2", btc = false))
        assertEquals("1.59", LimitsInput.filter("1.5.9x", btc = true))
        assertEquals(16, LimitsInput.filter("1".repeat(30), btc = false).length)
    }

    @Test
    fun convertingTheUnitKeepsAValidAmount() {
        assertEquals("0.005", LimitsInput.convert("500000", toBtc = true))
        assertEquals("500000", LimitsInput.convert("0.005", toBtc = false))
        assertEquals("", LimitsInput.convert("", toBtc = true))
        assertEquals("", LimitsInput.convert("0", toBtc = true))
        assertEquals("", LimitsInput.convert("0.000000001", toBtc = false))
    }

    @Test
    fun theThousandsSeparatorIsDrawnWithoutChangingWhatIsTyped() {
        val t = ThousandsTransformation.filter(AnnotatedString("2000000"))
        val sep = java.text.DecimalFormatSymbols.getInstance().groupingSeparator
        assertEquals("2${sep}000${sep}000", t.text.text)
        assertEquals(0, t.offsetMapping.originalToTransformed(0))
        assertEquals(1, t.offsetMapping.originalToTransformed(1))
        assertEquals(3, t.offsetMapping.originalToTransformed(2))
        assertEquals(9, t.offsetMapping.originalToTransformed(7))
        for (o in 0..7) assertEquals(o, t.offsetMapping.transformedToOriginal(t.offsetMapping.originalToTransformed(o)))
        assertEquals("999", ThousandsTransformation.filter(AnnotatedString("999")).text.text)
        assertEquals("", ThousandsTransformation.filter(AnnotatedString("")).text.text)
    }

    private companion object {
        const val CAPS_MAX = 2_100_000_000_000_000L
    }
}
