package com.vettid.feature.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two forms' rules (ANDROID-PLAN §4 "Spending limits"): lower-only, raise or remove, and what each comes to. */
class CapsEditTest {
    private fun plan(now: Pair<Long?, Long?>, form: CapsEditForm, raise: Boolean) = CapsEdit.plan(now.first, now.second, form, raise)

    private val both = 500_000L to 2_000_000L

    @Test
    fun anEmptyFormChangesNothing() {
        for (raise in listOf(false, true)) {
            val p = plan(both, CapsEditForm(), raise)
            assertEquals(CapsAction.NONE, p.action)
            assertEquals(CapFormProblem.NOTHING, p.problem)
            assertFalse(p.ok)
        }
    }

    @Test
    fun lowerTakesALowerNumberOrALimitWhereThereWasNone() {
        assertEquals(CapsAction.SET, plan(both, CapsEditForm(perTx = "100"), false).action)
        val setWhereNone = plan(null to null, CapsEditForm(perPeriod = "5000"), false)
        assertEquals(CapsAction.SET, setWhereNone.action)
        assertEquals(5_000L, setWhereNone.setPerPeriod)
        assertNull(setWhereNone.setPerTx)
        // Only the members that lower are sent.
        val p = plan(both, CapsEditForm(perTx = "100", perPeriod = "2000000"), false)
        assertEquals(100L, p.setPerTx)
        assertNull(p.setPerPeriod)
    }

    @Test
    fun lowerRefusesAHigherNumberAndTheRemoval() {
        val p = plan(both, CapsEditForm(perTx = "600000"), false)
        assertEquals(CapFieldProblem.NOT_LOWER, p.txProblem)
        assertFalse(p.ok)
        // The remove switch is not part of this form.
        assertEquals(CapsAction.NONE, plan(both, CapsEditForm(removeTx = true), false).action)
    }

    @Test
    fun raiseTakesAHigherNumberOrARemoval() {
        val up = plan(both, CapsEditForm(perTx = "600000"), true)
        assertEquals(CapsAction.RAISE, up.action)
        assertEquals(600_000L, up.newPerTx)
        assertEquals(2_000_000L, up.newPerPeriod)
        val gone = plan(both, CapsEditForm(removePeriod = true), true)
        assertEquals(CapsAction.RAISE, gone.action)
        assertNull(gone.newPerPeriod)
        assertEquals(500_000L, gone.newPerTx)
        assertTrue(gone.ok)
    }

    @Test
    fun raiseThatOnlyLowersIsASetAndOneThatDoesBothIsARaise() {
        assertEquals(CapsAction.SET, plan(both, CapsEditForm(perTx = "100"), true).action)
        val mixed = plan(both, CapsEditForm(perTx = "600000", perPeriod = "1000000"), true)
        assertEquals(CapsAction.RAISE, mixed.action)
        assertEquals(600_000L, mixed.newPerTx)
        assertEquals(1_000_000L, mixed.newPerPeriod)
    }

    @Test
    fun thePairMustStayInOrderAndInRange() {
        assertEquals(CapFormProblem.ABOVE_PERIOD, plan(both, CapsEditForm(perTx = "3000000"), true).problem)
        assertEquals(CapFormProblem.ABOVE_PERIOD, plan(both, CapsEditForm(perPeriod = "100"), false).problem)
        assertEquals(CapFieldProblem.OUT_OF_RANGE, plan(both, CapsEditForm(perTx = "0"), true).txProblem)
        assertEquals(CapFieldProblem.OUT_OF_RANGE, plan(both, CapsEditForm(perPeriod = "9999999999999999"), true).periodProblem)
        // Removing the period while a per-payment limit stays is fine.
        assertTrue(plan(both, CapsEditForm(removePeriod = true), true).ok)
        assertEquals("CapsEditForm", CapsEditForm().toString())
        assertEquals("CapsPlan", plan(both, CapsEditForm(), true).toString())
    }

    @Test
    fun bitcoinIsReadAsSats() {
        val p = plan(both, CapsEditForm(perTx = "0.001", btc = true), false)
        assertEquals(100_000L, p.setPerTx)
    }
}
