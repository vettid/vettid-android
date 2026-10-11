package com.vettid.feature.onboarding

import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vettid.core.altchan.RecoveryCodes
import com.vettid.core.ui.theme.VettIdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The typed recovery code's screen (ANDROID-PLAN 0.1.33; VAULT-MESSAGING 0.25.0 §11.11.10) on a phone: the field
 * groups what is typed, Continue needs all 12 groups, refusals are shown, "Scan instead" leads back, and the window
 * is secure only while this screen is shown. The characters are §16's TEST ONLY vector.
 */
@RunWith(AndroidJUnit4::class)
class RecoverTypeScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<LockedHostActivity>()

    private val typed = "7ZK3M9QX2HTDCDEFGHJKMNPQRSTVWXYZ0123456789ABV3A1"

    /** The view model's field handling without a vault: what the screen is given back for what is typed. */
    private inner class Typing : RecoverActions {
        var state by mutableStateOf(RecoverUiState(step = RecoverStep.TYPE, accountHost = "account.vettid.org"))
        var submitted = 0

        override fun scan() {
            state = state.copy(step = RecoverStep.SCAN)
        }

        override fun back(): Boolean = false
        override fun scanned(text: String) = Unit
        override fun type() {
            state = state.copy(step = RecoverStep.TYPE)
        }

        override fun setTyped(v: String) {
            val input = RecoveryCodes.typedInput(v)
            val problem = if (input.refused == RecoveryCodes.Refused.LETTER_U) TypedProblem.LETTER_U else null
            state = state.copy(typed = input.text, typedProblem = problem, typedGroup = input.refusedGroup)
        }

        override fun submitTyped() {
            submitted++
        }

        override fun retryPreflight() = Unit
        override fun setApproveOffer(approve: Boolean) = Unit
        override fun setPin(v: String) = Unit
        override fun submitPin() = Unit
        override fun setPassword(v: String) = Unit
        override fun submitPassword() = Unit
        override fun finish() = Unit
    }

    private fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun editable() =
        rule.onNodeWithTag("recover_typed").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    @Test
    fun theFieldGroupsWhatIsTypedAndContinueNeedsAllTwelveGroups() {
        val t = Typing()
        rule.setContent { VettIdTheme { RecoverContent(t.state, t, {}, {}, {}, camera = {}) } }
        rule.onNodeWithText("Type your recovery code").assertExists()
        rule.onNodeWithText("0 of 12 groups").assertExists()
        rule.onNodeWithTag("primary_button").assertIsNotEnabled()
        rule.onNodeWithTag("recover_typed").performTextInput("7zk3m9qx2h")
        rule.waitForIdle()
        assertEquals("7ZK3M9QX2H", t.state.typed)
        assertEquals("7ZK3 M9QX 2H", editable())
        rule.onNodeWithText("2 of 12 groups").assertExists()
        // A U is refused in the field and its group named.
        rule.onNodeWithTag("recover_typed").performTextInput("u")
        rule.onNodeWithText("The code has no letter U. Check whether it is a V. Look at group 3.").assertExists()
        assertEquals("7ZK3M9QX2H", t.state.typed)
        rule.onNodeWithTag("recover_typed").performTextInput(typed.drop(10))
        rule.waitForIdle()
        assertEquals("7ZK3 M9QX 2HTD CDEF\nGHJK MNPQ RSTV WXYZ\n0123 4567 89AB V3A1", editable())
        rule.onNodeWithText("12 of 12 groups").assertExists()
        rule.onNodeWithTag("primary_button").assertIsEnabled().performClick()
        assertEquals(1, t.submitted)
    }

    @Test
    fun theWindowIsSecureOnlyWhileTheTypedEntryIsShown() {
        val t = Typing()
        t.state = t.state.copy(step = RecoverStep.INTRO)
        rule.setContent { VettIdTheme { RecoverContent(t.state, t, {}, {}, {}, camera = {}) } }
        rule.waitForIdle()
        assertFalse(secure())
        rule.onNodeWithText("Type the code instead").performClick()
        rule.waitForIdle()
        assertTrue(secure())
        rule.onNodeWithText("Scan instead").performClick()
        rule.waitForIdle()
        assertEquals(RecoverStep.SCAN, t.state.step)
        assertFalse("cleared on leaving", secure())
        rule.onNodeWithText("Type the code instead").performClick()
        rule.waitForIdle()
        assertTrue(secure())
    }
}
