package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    @Test
    fun unlocksWithThePin() = runTest {
        val vm = UnlockViewModel(vault, vault)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pinAllowed)
        vm.setPin("4028 1795")
        assertEquals("40281795", vm.uiState.value.pin)
        vm.submit()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        assertEquals("", vm.uiState.value.pin)
        assertNull(vault.lastApproved)
    }

    @Test
    fun softwareUpdatedMustBeAcknowledgedBeforeThePin() = runTest {
        vault.preflightInfo = PreflightInfo(FakeVault.release(4), 3, softwareUpdated = true, rollback = false, offer = null)
        val vm = UnlockViewModel(vault, vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse("unlock" in vault.calls)
        vm.acknowledgeUpdate()
        vm.submit()
        advanceUntilIdle()
        assertTrue("unlock" in vault.calls)
    }

    @Test
    fun rollbackNeverSendsThePin() = runTest {
        vault.preflightInfo = PreflightInfo(FakeVault.release(2), 3, softwareUpdated = false, rollback = true, offer = null)
        val vm = UnlockViewModel(vault, vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.pinAllowed)
        assertFalse("unlock" in vault.calls)
    }

    @Test
    fun approvedOfferTravelsWithTheUnlock() = runTest {
        val offer = FakeVault.release(5)
        vault.preflightInfo = PreflightInfo(FakeVault.release(4, "deprecated"), 4, false, false, offer)
        val vm = UnlockViewModel(vault, vault)
        advanceUntilIdle()
        vm.setApproveOffer(true)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertEquals(offer, vault.lastApproved)
    }

    @Test
    fun badPinStartsTheBackoffCountdown() = runTest {
        vault.unlockResults += UnlockAttempt.BadPin(retryAfterSeconds = 30)
        val vm = UnlockViewModel(vault, vault)
        advanceUntilIdle()
        vm.setPin("11112222")
        vm.submit()
        advanceTimeBy(1)
        assertEquals(UnlockMessage.BadPin, vm.uiState.value.message)
        assertEquals(30L, vm.uiState.value.waitSeconds)
        assertFalse(vm.uiState.value.pinAllowed)
        advanceTimeBy(10_500)
        assertEquals(20L, vm.uiState.value.waitSeconds)
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.waitSeconds)
        assertTrue(vm.uiState.value.pinAllowed)
    }

    @Test
    fun recoveryPendingThenCancelAndUnlock() = runTest {
        vault.unlockResults += UnlockAttempt.RecoveryPending
        val vm = UnlockViewModel(vault, vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.recoveryPending)
        assertFalse(vault.lastCancelRecovery)
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        assertTrue(vault.lastCancelRecovery)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    @Test
    fun stateRollbackAndFailures() = runTest {
        vault.unlockResults += UnlockAttempt.StateRollback
        val vm = UnlockViewModel(vault, vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.stateRollback)

        vault.fail["preflight"] = FakeVault.failure(FailureKind.RELEASE_ENDED)
        val ended = UnlockViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals(FailureKind.RELEASE_ENDED, ended.uiState.value.preflightError)
        assertFalse(ended.uiState.value.pinAllowed)
    }
}
