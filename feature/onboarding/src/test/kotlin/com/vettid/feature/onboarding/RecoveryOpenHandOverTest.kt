package com.vettid.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.RecoveryOpenOutcome
import com.vettid.core.data.vault.RecoveryStage
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The release-R recovery's last screen as the recovery flow shows it ([RecoverContent] on a real [RecoverViewModel] and
 * [RecoveryOpenViewModel] over one [FakeVault]): `ok`, then Continue, opens the app.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryOpenHandOverTest {
    @get:Rule
    val rule = createComposeRule()

    private val vault = FakeVault(AppPhase.Setup(SetupStage.RECOVERING)).apply {
        phoneSecret = true
        recoveryStageValue = RecoveryStage.PIN
        recoveryOpenOutcomes.add(RecoveryOpenOutcome("ok", 1))
    }

    /** The recovery flow at its PIN, the open answered `ok`: the `ok` text with Continue. */
    private fun showOk() {
        val recover = RecoverViewModel(vault, vault, vault, FakeReleaseNotes())
        val open = RecoveryOpenViewModel(vault, vault)
        rule.setContent {
            val state by recover.uiState.collectAsStateWithLifecycle()
            RecoverContent(state, recover, {}, {}, {}, recoveryOpen = open)
        }
        rule.onNodeWithTag("open_pin").performTextInput("975310")
        rule.onNodeWithTag("open_password").performTextInput("correct horse battery")
        rule.onNodeWithTag("open_continue").performClick()
        rule.onNodeWithTag("open_send").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("open_answer_ok").assertExists()
    }

    /**
     * When the open returns without the app open (the phone left at FINISHING, which the onboarding ignores under the
     * recovery screen), the screen hands over to the end of setup (MoveScreens: `onOpened` = the recovery flow's
     * `finish`), and the app opens.
     */
    @Test
    fun continueAfterOkFinishesSetupFromTheRecoveryScreen() {
        vault.recoveryOpenLeavesFinishing = true
        showOk()
        assertFalse("finishSetup" in vault.calls)
        rule.onNodeWithTag("open_app").performClick()
        rule.waitForIdle()
        assertTrue("finishRecoveryOpen" in vault.calls)
        assertTrue("the screen's hand-over", "finishSetup" in vault.calls)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    /** A lock asked for between `ok` and Continue: the phone stays locked and the screen says so. */
    @Test
    fun continueWithALockPendingSaysSoAndNeverOpens() {
        showOk()
        vault.lockPending = true
        rule.onNodeWithTag("open_app").performClick()
        rule.waitForIdle()
        assertEquals(AppPhase.Locked, vault.phase.value)
        rule.onNodeWithTag("open_app_failure")
            .assertTextEquals(RuntimeEnvironment.getApplication().getString(R.string.unlock_lock_unconfirmed_body))
    }
}
