package com.vettid.feature.onboarding

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * "Cancel the recovery and unlock" in progress: the screen has no primary button while a recovery is pending, so the
 * cancel (and the owner check that follows it with a phone secret) shows its progress in the recovery card.
 */
@RunWith(RobolectricTestRunner::class)
class UnlockCancellingTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = Proxy.newProxyInstance(UnlockActions::class.java.classLoader, arrayOf(UnlockActions::class.java)) { _, _, _ ->
        null
    } as UnlockActions

    private val release = ReleaseView(3, "aa".repeat(48), "active", null, "")
    private val pending = UnlockUiState(
        loading = false, preflight = PreflightInfo(release, 3, false, false, null), recoveryPending = true, phoneSecret = true,
        pin = "123456",
    )

    @Test
    fun theCancelInProgressShowsItsProgress() {
        rule.mainClock.autoAdvance = false // the indicator's animation never ends
        rule.setContent { UnlockContent(pending.copy(busy = true), actions) }
        rule.onNodeWithTag("unlock_recovery_cancelling").assertExists()
        rule.onNodeWithText("Canceling the recovery…").assertExists()
        rule.onNodeWithText("Cancel the recovery and unlock").assertIsNotEnabled()
    }

    /** A cancel refused for its PIN: the keypad shows under the recovery card, with its wait. */
    @Test
    fun aCancelRefusedForItsPinShowsTheKeypadWithItsWait() {
        rule.setContent {
            UnlockContent(pending.copy(phoneSecret = false, pin = "", cancelPinAsked = true, waitSeconds = 30), actions)
        }
        rule.onNodeWithTag("recovery_pending").assertExists()
        rule.onNodeWithTag("unlock_pin").assertExists()
        rule.onNodeWithText("Too many wrong PINs. Try again in", substring = true).assertExists()
        rule.onNodeWithText("Cancel the recovery and unlock").assertIsNotEnabled()
    }

    /** The owner check due without a phone secret: the recovery card asks for the password the cancel sends with it. */
    @Test
    fun aDueCheckAsksForThePasswordInTheRecoveryCard() {
        rule.setContent { UnlockContent(pending.copy(phoneSecret = false, checkDue = true, password = ""), actions) }
        rule.onNodeWithTag("unlock_cancel_password").assertExists()
        rule.onNodeWithText("Cancel the recovery and unlock").assertIsNotEnabled()
    }

    /** A due check with a refused cancel: exactly one password field (the card's), and the keypad's action is Done. */
    @Test
    fun aDueCheckWithARefusedCancelHasOnePasswordField() {
        rule.setContent {
            UnlockContent(pending.copy(phoneSecret = false, checkDue = true, cancelPinAsked = true, pin = "", password = ""), actions)
        }
        rule.onNodeWithTag("unlock_password").assertDoesNotExist()
        rule.onNodeWithTag("unlock_cancel_password").assertExists()
        rule.onNodeWithTag("unlock_pin").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
    }

    /** A failed cancel shows its notice beside the keypad. */
    @Test
    fun aFailedCancelShowsItsNotice() {
        rule.setContent {
            UnlockContent(
                pending.copy(
                    phoneSecret = false, cancelPinAsked = true, pin = "", message = UnlockMessage.Failed(FailureKind.NO_RESPONSE, null),
                ),
                actions,
            )
        }
        rule.onNodeWithTag("unlock_failed").assertExists()
    }

    /** The cancel button is enabled only when the cancel would be sent. */
    @Test
    fun theCancelIsEnabledOnlyWhenItWouldBeSent() {
        val s = pending.copy(phoneSecret = false)
        assertTrue(s.cancelAllowed)
        assertFalse("no PIN", s.copy(pin = "").cancelAllowed)
        assertFalse("waiting", s.copy(waitSeconds = 5).cancelAllowed)
        assertFalse("the due check's password", s.copy(checkDue = true).cancelAllowed)
        assertTrue(s.copy(checkDue = true, password = "the password").cancelAllowed)
    }

    /** The keypad's Done does not send anything while a recovery is pending: only the cancel button cancels it. */
    @Test
    fun theKeypadsDoneSendsNothingWhileARecoveryIsPending() {
        val called = mutableListOf<String>()
        val recording = Proxy.newProxyInstance(UnlockActions::class.java.classLoader, arrayOf(UnlockActions::class.java)) { _, m, _ ->
            called += m.name
            null
        } as UnlockActions
        rule.setContent { UnlockContent(pending.copy(phoneSecret = false, pin = "11223344", cancelPinAsked = true), recording) }
        rule.onNodeWithTag("unlock_pin").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
        rule.onNodeWithTag("unlock_pin").performImeAction()
        assertFalse("submitted: $called", called.any { it == "submit" || it == "cancelRecoveryAndUnlock" })
    }

    /** Before any refusal, a pending recovery shows no keypad (the PIN typed for the unlock goes with the cancel). */
    @Test
    fun noKeypadBeforeACancelIsRefused() {
        rule.setContent { UnlockContent(pending.copy(phoneSecret = false), actions) }
        rule.onNodeWithTag("unlock_pin").assertDoesNotExist()
    }

    @Test
    fun nothingInProgressBeforeTheCancel() {
        rule.setContent { UnlockContent(pending.copy(password = "password one"), actions) }
        rule.onNodeWithTag("recovery_pending").assertExists()
        rule.onNodeWithTag("unlock_recovery_cancelling").assertDoesNotExist()
    }
}
