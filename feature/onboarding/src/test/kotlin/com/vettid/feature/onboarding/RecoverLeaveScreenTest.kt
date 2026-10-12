package com.vettid.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * A6 audit E1 on screen: the PIN and password steps of a registered recovery offer "Start over", its confirmation
 * says what it does and what it does not (the recovery itself is not cancelled), and a recovery the vault no longer
 * takes has a screen of its own with the same way out.
 */
@RunWith(RobolectricTestRunner::class)
class RecoverLeaveScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val asked = mutableListOf<String>()

    /** Records which action a screen asked for. */
    private val actions = Proxy.newProxyInstance(RecoverActions::class.java.classLoader, arrayOf(RecoverActions::class.java)) { _, m, _ ->
        asked += m.name
        if (m.returnType == Boolean::class.javaPrimitiveType) false else null
    } as RecoverActions

    private fun show(state: RecoverUiState) = rule.setContent { RecoverContent(state, actions, {}, {}, {}) }

    private val release = ReleaseView(3, "aa".repeat(48), "active", null, "")
    private val pin = RecoverUiState(step = RecoverStep.PIN, preflight = PreflightInfo(release, 0, false, false, null))

    @Test
    fun thePinStepOffersStartOver() {
        show(pin)
        rule.onNodeWithText("Start over").assertIsDisplayed().performClick()
        assertEquals(listOf("askStartOver"), asked)
    }

    @Test
    fun thePinStepOffersStartOverAlsoWhenTheReleaseCheckBlocksThePin() {
        show(pin.copy(preflight = null, preflightError = FailureKind.RELEASE_ENDED))
        rule.onNodeWithTag("recover_pin").assertDoesNotExist()
        rule.onNodeWithText("Start over").assertIsDisplayed()
    }

    @Test
    fun thePasswordStepOffersStartOver() {
        show(RecoverUiState(step = RecoverStep.PASSWORD))
        rule.onNodeWithText("Start over").assertIsDisplayed().performClick()
        assertEquals(listOf("askStartOver"), asked)
    }

    @Test
    fun theConfirmationSaysWhatStartOverDoesAndDoesNot() {
        show(pin.copy(confirmStartOver = true))
        rule.onNodeWithText("Start over on this phone?").assertIsDisplayed()
        rule.onNodeWithText("This phone forgets the recovery it registered", substring = true).assertIsDisplayed()
        rule.onNodeWithText("The recovery itself is not cancelled by this", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Your vault is not changed.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("dismissStartOver"), asked)
        rule.onNodeWithTag("confirm_button").performClick()
        assertEquals(listOf("dismissStartOver", "confirmStartOver"), asked)
    }

    @Test
    fun anEndedRecoveryHasItsOwnScreenWithStartOverAndAnotherTry() {
        show(RecoverUiState(step = RecoverStep.ENDED))
        rule.onNodeWithText("This recovery has ended").assertIsDisplayed()
        rule.onNodeWithTag("recover_ended_body").assertIsDisplayed()
        rule.onNodeWithText("Your vault did not recognise this phone.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("recover_pin").assertDoesNotExist()
        rule.onNodeWithTag("primary_button").performClick()
        rule.onNodeWithText("Try the PIN again").performClick()
        assertEquals(listOf("askStartOver", "tryPinAgain"), asked)
    }
}
