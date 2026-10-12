package com.vettid.feature.onboarding

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * The setup code, the transfer code and the typed recovery code are typed on a keyboard that does not suggest,
 * correct or learn them (A6 audit S2): the password class of text, autocorrect off, flagged "do not learn". What the
 * focused field tells the input method is read the way the platform reads it.
 */
@RunWith(RobolectricTestRunner::class)
class CodeKeyboardsTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var view: View

    /** An actions object that does nothing (the screens only render here). */
    private inline fun <reified T> none(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        if (m.returnType == Boolean::class.javaPrimitiveType) false else null
    } as T

    private fun assertNeverLearned(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
        val info = EditorInfo()
        rule.runOnUiThread { checkNotNull(view.onCreateInputConnection(info)) { "no field is taking input" } }
        assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertFalse("autocorrect", info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0)
        assertTrue("do not learn", info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    @Test
    fun theSetupCode() {
        rule.setContent {
            view = LocalView.current
            val state = OnboardingUiState(step = OnboardingStep.SETUP_TYPE, codeInput = "K7QM")
            SetupTypeScreen(state, none<OnboardingActions>(), onBack = null)
        }
        assertNeverLearned("setup_code")
    }

    @Test
    fun theTransferCode() {
        rule.setContent {
            view = LocalView.current
            TransferInContent(TransferInUiState(step = TransferInStep.PASTE), none<TransferInActions>(), onLeave = {})
        }
        assertNeverLearned("transfer_code")
    }

    @Test
    fun theTypedRecoveryCode() {
        rule.setContent {
            view = LocalView.current
            RecoverContent(RecoverUiState(step = RecoverStep.TYPE, typed = "ABCD"), none<RecoverActions>(), {}, {}, {})
        }
        assertNeverLearned("recover_typed")
    }
}
