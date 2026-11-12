package com.vettid.feature.onboarding

import androidx.test.core.app.ApplicationProvider
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.RecoveryOpenOutcome
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * VAULT-MESSAGING 0.88.17 §11.4, §11.14.6: `state_unreadable` shows the owner's texts, the unlock's and the recovery
 * open's; "Try again" is offered as for other refusals, never automatic, and nothing is sent after the refusal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StateUnreadableShownTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun theUnlockShowsItsTextAndSendsNothingMore() = runTest {
        val vault = FakeVault(AppPhase.Locked)
        vault.unlockResults.add(UnlockAttempt.Failed(FailureKind.STATE_UNREADABLE, "state_unreadable"))
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(UnlockMessage.Failed(FailureKind.STATE_UNREADABLE, "state_unreadable"), s.message)
        assertFalse(s.busy)
        assertEquals("", s.pin)
        assertEquals("one unlock, no retry of its own", 1, vault.calls.count { it == "unlock" })
        assertFalse("not a phone the vault does not know", s.notRecognised)
    }

    @Test
    fun theRecoveryOpenShowsItsOwnTextAndAllowsALaterTry() {
        val t = openAnswerText(RecoveryOpenOutcome("state_unreadable"), false)
        assertEquals(R.string.onboarding_open_state_unreadable, t.res)
        assertEquals(
            "Your vault can't be opened right now. Nothing in it has been changed. Wait for a VettID update before you " +
                "try again: each try uses one of your recovery attempts.",
            ctx.getString(t.res),
        )
        assertTrue("not an ended recovery", openCanTryAgain("state_unreadable"))
    }
}
