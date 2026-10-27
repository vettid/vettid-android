package com.vettid.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.vettid.core.testing.FakeVault
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The lost-ok finish pane: "Use a new recovery code" beside the PIN whenever the rule allows it, with no answer yet. */
@RunWith(RobolectricTestRunner::class)
class RecoveryOpenFinishNewCodeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun pane(allowed: Boolean, onNewCode: (() -> Unit)?) {
        val vault = FakeVault()
        val vm = RecoveryOpenViewModel(vault, vault)
        val state = RecoveryOpenUiState(step = OpenStep.FINISH, lostOk = true, newCodeAllowed = allowed)
        rule.setContent { RecoveryOpenPane(state, vm, onNewCode = onNewCode) }
    }

    @Test
    fun theButtonShowsBesideThePinBeforeAnyAnswer() {
        var taken = 0
        pane(allowed = true, onNewCode = { taken++ })
        rule.onNodeWithTag("open_finish_pin").assertIsDisplayed()
        rule.onNodeWithTag("open_new_code_after_lost_ok").performClick()
        assertEquals(1, taken)
    }

    @Test
    fun theButtonIsHiddenWhenTheRuleDoesNotAllowANewCode() {
        pane(allowed = false, onNewCode = {})
        rule.onNodeWithTag("open_finish_pin").assertIsDisplayed()
        rule.onNodeWithTag("open_new_code_after_lost_ok").assertDoesNotExist()
    }

    @Test
    fun theButtonIsHiddenWithoutAHandler() {
        pane(allowed = true, onNewCode = null)
        rule.onNodeWithTag("open_new_code_after_lost_ok").assertDoesNotExist()
    }
}
