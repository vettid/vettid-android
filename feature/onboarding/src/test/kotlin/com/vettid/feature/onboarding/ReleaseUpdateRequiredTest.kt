package com.vettid.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.ReleaseUpdateOffer
import com.vettid.core.data.vault.ReleaseView
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.data.vault.UpdateProgress
import com.vettid.core.data.vault.UpdateStep
import com.vettid.core.testing.FakeReleaseUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rule 3 (the release gate): the update screen as the gate shows it has no way back, and the update's own failed step has
 * no "Not now" while the update is required (the open vault shows nothing else); "Unlock without updating" stays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class ReleaseUpdateRequiredTest {
    @get:Rule
    val rule = createComposeRule()

    private fun rel(n: Long) = ReleaseView(n, "%02d".format(n).repeat(48), "active", null, "https://vettid.org/r/$n")

    private val offer = ReleaseUpdateOffer(rel(4), rel(5))

    private var finished = 0

    private val flowActions = object : ReleaseUpdateFlowActions {
        override fun setPin(v: String) = Unit
        override fun retry() = Unit
        override fun askAbandon() = Unit
        override fun dismissAbandon() = Unit
        override fun confirmAbandon() = Unit
        override fun finish() {
            finished++
        }
    }

    private val actions = object : ReleaseUpdateActions {
        override fun setPin(v: String) = Unit
        override fun approve() = Unit
    }

    private fun failed(vaultOpen: Boolean) =
        UpdateProgress(UpdateStep.FAILED, rel(4), rel(5), failure = FailureKind.NETWORK, vaultOpen = vaultOpen)

    /** No "Not now" while required: "Continue" leads back to the gate's screen (the update's outcome is left). */
    @Test
    fun aFailedUpdateHasNoNotNowWhileRequired() {
        var state by mutableStateOf(ReleaseUpdateFlowUiState(progress = failed(vaultOpen = true), required = true))
        rule.setContent { ReleaseUpdateFlowContent(state, flowActions) }
        rule.onNodeWithTag("release_update_failed").assertExists()
        rule.onNodeWithText("Not now").assertDoesNotExist()
        rule.onNodeWithText("Continue").performClick()
        rule.waitForIdle()
        assertEquals(1, finished)
        state = state.copy(required = false)
        rule.waitForIdle()
        rule.onNodeWithText("Not now").assertExists()
        rule.onNodeWithText("Continue").assertDoesNotExist()
    }

    /**
     * The update failed for want of a usable user_guid, the vault still open (nothing was locked): the recover-or-erase
     * words, the PIN field and "Try again" disabled (no PIN can open the vault on this phone), "Continue" still there.
     */
    @Test
    fun aPhoneWithoutAUserGuidGetsNoTryAgain() {
        val p = UpdateProgress(
            UpdateStep.FAILED, rel(4), rel(5), failure = FailureKind.NOT_SUPPORTED, failureCode = UnlockAttempt.CODE_NO_USER_GUID,
            vaultOpen = true,
        )
        val state = ReleaseUpdateFlowUiState(progress = p, pin = "246813", required = true)
        rule.setContent { ReleaseUpdateFlowContent(state, flowActions) }
        rule.onNodeWithText("This phone no longer holds your vault", substring = true).assertExists()
        rule.onNodeWithTag("release_update_pin").assertIsNotEnabled()
        rule.onNodeWithText("Try again").assertIsNotEnabled()
        rule.onNodeWithText("Continue").assertIsEnabled()
        // Another failure of the same step keeps its PIN and "Try again".
        assertTrue(state.copy(progress = p.copy(failure = FailureKind.NETWORK, failureCode = null)).retryAllowed)
    }

    @Test
    fun unlockingWithoutUpdatingStaysWhileRequired() {
        val state = ReleaseUpdateFlowUiState(progress = failed(vaultOpen = false), required = true)
        rule.setContent { ReleaseUpdateFlowContent(state, flowActions) }
        rule.onNodeWithText("Unlock without updating").assertExists()
    }

    @Test
    fun theGatesUpdateScreenHasNoWayBack() {
        rule.setContent { ReleaseUpdateContent(ReleaseUpdateUiState(loading = false, offer = offer), actions, onBack = null) }
        rule.onNodeWithTag("release_offer").assertExists()
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun theFlowFollowsWhetherTheUpdateIsRequired() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val updates = FakeReleaseUpdates(offer)
            updates.required.value = true
            val vm = ReleaseUpdateFlowViewModel(updates)
            testScheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.required)
            updates.required.value = false
            testScheduler.advanceUntilIdle()
            assertFalse(vm.uiState.value.required)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** A phone with no usable user_guid: the unlock screen's recover-or-erase text, not "does not support this yet". */
    @Test
    fun aPhoneWithoutAUserGuidIsToldItCannotOpenTheVault() {
        val p = UpdateProgress(
            UpdateStep.FAILED, rel(4), rel(5), failure = FailureKind.NOT_SUPPORTED, failureCode = UnlockAttempt.CODE_NO_USER_GUID,
            vaultOpen = false,
        )
        rule.setContent { ReleaseUpdateFlowContent(ReleaseUpdateFlowUiState(progress = p, required = true), flowActions) }
        rule.onNodeWithText("This phone no longer holds your vault", substring = true).assertExists()
        rule.onNodeWithText("does not support this yet", substring = true).assertDoesNotExist()
    }

    /** Another NOT_SUPPORTED (no previous release to go back to) is not a phone without a user_guid. */
    @Test
    fun anotherNotSupportedIsNotAPhoneWithoutAUserGuid() {
        val p = UpdateProgress(
            UpdateStep.FAILED, rel(4), rel(5), failure = FailureKind.NOT_SUPPORTED, failureCode = "no_previous_release",
            vaultOpen = false,
        )
        rule.setContent { ReleaseUpdateFlowContent(ReleaseUpdateFlowUiState(progress = p, required = true), flowActions) }
        rule.onNodeWithText("This phone no longer holds your vault", substring = true).assertDoesNotExist()
    }

    /** The code with another kind is not the unlock guard's refusal: no recover-or-erase text. */
    @Test
    fun theCodeAloneIsNotAPhoneWithoutAUserGuid() {
        val p = UpdateProgress(
            UpdateStep.FAILED, rel(4), rel(5), failure = FailureKind.OTHER, failureCode = UnlockAttempt.CODE_NO_USER_GUID,
            vaultOpen = false,
        )
        rule.setContent { ReleaseUpdateFlowContent(ReleaseUpdateFlowUiState(progress = p, required = true), flowActions) }
        rule.onNodeWithText("This phone no longer holds your vault", substring = true).assertDoesNotExist()
    }
}
