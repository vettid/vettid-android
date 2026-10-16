package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.RecoveryOpenOutcome
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The recovery open of release R (VAULT-MESSAGING 0.33.0 §11.14.6, ANDROID-PLAN 0.1.40). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryOpenViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Setup(SetupStage.RECOVERING)).apply { phoneSecret = true }

    private fun RecoveryOpenViewModel.enterAndSend(pin: String = "975310", password: String = "correct horse battery") {
        setPin(pin)
        setPassword(password)
        review()
        send()
    }

    @Test
    fun okShowsTheAttemptsUsedBeforeTheAppOpens() = runTest {
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("ok", 2))
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.enterAndSend()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(OpenStep.DONE, s.step)
        assertEquals(2, s.outcome?.used)
        assertEquals("", s.pin)
        assertEquals("", s.password)
        // The app opens only when the member continues.
        assertFalse("finishRecoveryOpen" in vault.calls)
        vm.openApp()
        advanceUntilIdle()
        assertTrue("finishRecoveryOpen" in vault.calls)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    /** After ok the vault removed the recovery: a failure then, or a restart, finishes with the PIN, never a new open. */
    @Test
    fun anOkWhoseRestFailedIsFinishedWithThePin() = runTest {
        vault.fail["recoveryOpen"] = VaultFailure(FailureKind.NO_RESPONSE)
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vault.recoveryOpenedValue = true // the vault answered ok; the handshake did not complete
        vm.enterAndSend()
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertEquals(FailureKind.NO_RESPONSE, vm.uiState.value.failure)
        // The phone's own unlock with a wrong PIN: said as that, still finishing.
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("bad_pin", 1, opened = true))
        vm.setPin("111111")
        vm.send()
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertTrue(vm.uiState.value.outcome!!.opened)
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("ok", 1))
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertEquals(OpenStep.DONE, vm.uiState.value.step)
        // And after a restart: straight to the PIN alone.
        val again = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, again.uiState.value.step)
    }

    /** Finishing an ok recovery the vault no longer accepts: erase VettID from this phone, after a confirmation only. */
    @Test
    fun aVaultThatNoLongerHasThisPhoneOffersTheErase() = runTest {
        vault.recoveryOpenedValue = true
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals(OpenStep.FINISH, vm.uiState.value.step)
        assertFalse(vm.uiState.value.canErase)
        // A temporary failure: no erase.
        vault.fail["recoveryOpen"] = VaultFailure(FailureKind.NO_RESPONSE)
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canErase)
        // Its own key refused at both epochs: the vault no longer has this phone.
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("device_secret", 1, opened = true))
        vm.setPin("975310")
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canErase)
        vm.erase()
        assertFalse("eraseThisPhone" in vault.calls)
        vm.askErase()
        assertTrue(vm.uiState.value.confirmErase)
        vm.erase()
        advanceUntilIdle()
        assertTrue("eraseThisPhone" in vault.calls)
        assertEquals(AppPhase.SignedOut, vault.phase.value)
        // An answer only a vault without this phone gives also offers it.
        val again = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vault.fail["recoveryOpen"] = VaultFailure(FailureKind.OTHER, "unreadable_result")
        again.setPin("975310")
        again.send()
        advanceUntilIdle()
        assertTrue(again.uiState.value.canErase)
    }

    /** "The last attempt was right but could not be completed": only after an open that got no answer. */
    @Test
    fun rightButIncompleteOnlyAfterAnUnansweredOpen() = runTest {
        val at = Instant.parse("2026-10-20T12:00:00Z")
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("attempt_lost", 3, at))
        val vm = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        vm.enterAndSend()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.rightButIncomplete)
        // An attempt_lost before never makes the next answer "right but incomplete".
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("wait", 3, at))
        vm.again()
        vm.review()
        vm.send()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.rightButIncomplete)
        // The data layer reports that the open before got no answer (kept across a restart).
        vault.recoveryOpenOutcomes.add(RecoveryOpenOutcome("wait", 4, at, afterUnanswered = true))
        vm.again()
        vm.review()
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.rightButIncomplete)
    }

    /** The warnings before sending also on the first send after a restart: the attempts used as last reported. */
    @Test
    fun warningsUseTheAttemptsReportedBeforeARestart() = runTest {
        vault.recoveryAttemptsUsed = 1
        val second = RecoveryOpenViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals(OpenWarning.SECOND, second.uiState.value.warning)
        vault.recoveryAttemptsUsed = 8
        assertEquals(OpenWarning.FEW_LEFT, RecoveryOpenViewModel(vault, vault).uiState.value.warning)
    }
}
