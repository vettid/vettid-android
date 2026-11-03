package com.vettid.feature.settings

import com.vettid.core.data.vault.AccountInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The names the change-name form starts with are not input; a changed one is (VAULT-MESSAGING §3.6.5). */
class ChangeNameHoldsInputTest {
    private val account = AccountInfo("s***@example.org", firstName = "Sam", lastName = "Rivera")

    @Test
    fun `the starting names are not input`() {
        assertFalse(ChangeNameUiState(account = account, first = "Sam", last = "Rivera").holdsInput)
        assertFalse(ChangeNameUiState().holdsInput)
    }

    @Test
    fun `a changed name is input, on the names step only`() {
        assertTrue(ChangeNameUiState(account = account, first = "Samuel", last = "Rivera").holdsInput)
        assertFalse(ChangeNameUiState(step = ChangeNameStep.CONFIRM, account = account, first = "Samuel", last = "Rivera").holdsInput)
    }
}
