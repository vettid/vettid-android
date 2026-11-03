package com.vettid.feature.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the daily owner check waits for on the wallet's forms (VAULT-MESSAGING §3.6.5): typed input only, not a screen shown. */
class HoldsInputTest {
    @Test
    fun `an import form with nothing typed holds nothing, with words typed it does`() {
        assertFalse(ImportUiState().holdsInput)
        assertTrue(ImportUiState(typed = "abandon").holdsInput)
        assertTrue(ImportUiState(name = "Savings").holdsInput)
        assertFalse(ImportUiState(step = ImportStep.DONE, name = "Savings").holdsInput)
    }

    @Test
    fun `an import limits step holds a typed limit only`() {
        assertFalse(ImportUiState(step = ImportStep.LIMITS).holdsInput)
        assertTrue(ImportUiState(step = ImportStep.LIMITS, limits = LimitsForm(perTx = "1000")).holdsInput)
    }

    @Test
    fun `a new wallet holds a typed name or word, not the phrase on show`() {
        assertFalse(BackupUiState(step = BackupStep.FORM).holdsInput)
        assertTrue(BackupUiState(step = BackupStep.FORM, name = "Savings").holdsInput)
        assertFalse(BackupUiState(step = BackupStep.PHRASE, name = "Savings").holdsInput)
        assertFalse(BackupUiState(step = BackupStep.CHECK).holdsInput)
        assertTrue(BackupUiState(step = BackupStep.CHECK, typed = "abandon").holdsInput)
    }

    @Test
    fun `spending limits hold a typed limit in the form only`() {
        assertFalse(SpendingLimitsUiState(step = LimitsStep.OVERVIEW, form = CapsEditForm(perTx = "5")).holdsInput)
        assertFalse(SpendingLimitsUiState(step = LimitsStep.LOWER).holdsInput)
        assertTrue(SpendingLimitsUiState(step = LimitsStep.LOWER, form = CapsEditForm(perTx = "5")).holdsInput)
        assertTrue(SpendingLimitsUiState(step = LimitsStep.RAISE, form = CapsEditForm(removeTx = true)).holdsInput)
    }
}
