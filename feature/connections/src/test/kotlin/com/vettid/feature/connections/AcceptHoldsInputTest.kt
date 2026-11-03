package com.vettid.feature.connections

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An invitation that came with a link is the member's to confirm, not input; one typed or pasted is (VAULT-MESSAGING §3.6.5). */
class AcceptHoldsInputTest {
    @Test
    fun `a value from a link is not input until the member edits it`() {
        assertFalse(AcceptUiState(input = "vettid://invite/abc", fromLink = true).holdsInput)
        assertTrue(AcceptUiState(input = "vettid://invite/abc", fromLink = true, typed = true).holdsInput)
    }

    @Test
    fun `a pasted value is input in the input step only`() {
        assertTrue(AcceptUiState(input = "abc", typed = true).holdsInput)
        assertFalse(AcceptUiState(input = "", typed = true).holdsInput)
        assertFalse(AcceptUiState(step = AcceptStep.ACCEPTING, input = "abc", typed = true).holdsInput)
    }
}
