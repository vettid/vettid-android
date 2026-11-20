package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * "Cancel the recovery and unlock" when the vault does not end the recovery: it applies the cancel only after a correct PIN
 * (a wrong PIN, a wait, a failure leave the recovery pending), so the keypad asks for the PIN again and the cancel can go
 * again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockCancelRetryTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    /**
     * A wrong PIN on "Cancel the recovery and unlock": the recovery stays pending (the vault applies the cancel only after a
     * correct PIN), the keypad asks for a new PIN with its wait, and the cancel goes again with it once the wait is over.
     */
    @Test
    fun aWrongPinOnTheCancelAsksForANewPin() = runTest {
        vault.unlockResults += UnlockAttempt.RecoveryPending
        vault.unlockResults += UnlockAttempt.BadPin(30)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        vm.cancelRecoveryAndUnlock()
        runCurrent()
        val s = vm.uiState.value
        assertTrue(s.recoveryPending)
        assertTrue("the keypad asks again", s.cancelPinAsked)
        assertEquals("", s.pin)
        assertEquals(30L, s.waitSeconds)
        assertFalse("not within the wait, nor without a PIN", s.cancelAllowed)
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(0L, vm.uiState.value.waitSeconds)
        assertFalse("not without a PIN", vm.uiState.value.cancelAllowed)
        vm.setPin("11223344")
        assertTrue(vm.uiState.value.cancelAllowed)
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        assertTrue(vault.lastCancelRecovery)
        assertEquals("11223344", vault.lastPin)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        assertFalse(vm.uiState.value.recoveryPending)
        assertFalse(vm.uiState.value.cancelPinAsked)
    }

    /** A cancel that fails (no answer): the recovery stays pending, the keypad asks for the PIN, and the cancel can go again. */
    @Test
    fun aFailedCancelAsksForThePinAgain() = runTest {
        vault.unlockResults += UnlockAttempt.RecoveryPending
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NO_RESPONSE, null)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.recoveryPending)
        assertTrue(vm.uiState.value.cancelPinAsked)
        assertFalse("no PIN: not a button that does nothing", vm.uiState.value.cancelAllowed)
        vm.setPin("40281795")
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    /** A cancel retried with a new PIN that the vault answers `backoff`: the keypad stays, with the vault's new wait. */
    @Test
    fun aBackoffOnTheCancelKeepsTheKeypadWithItsWait() = runTest {
        vault.unlockResults += UnlockAttempt.RecoveryPending
        vault.unlockResults += UnlockAttempt.BadPin(5)
        vault.unlockResults += UnlockAttempt.Backoff(60)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        vm.cancelRecoveryAndUnlock()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        vm.setPin("11223344")
        vm.cancelRecoveryAndUnlock()
        runCurrent()
        val s = vm.uiState.value
        assertTrue(s.recoveryPending)
        assertTrue(s.cancelPinAsked)
        assertEquals(60L, s.waitSeconds)
        assertFalse(s.cancelAllowed)
        assertTrue(vault.lastCancelRecovery)
    }

    /** With a phone secret, a wrong PIN on the cancel asks for the PIN and the password again, both before the cancel goes. */
    @Test
    fun aWrongPinOnTheCancelWithAPhoneSecretAsksForBoth() = runTest {
        vault.phoneSecret = true
        vault.unlockResults += UnlockAttempt.RecoveryPending
        vault.unlockResults += UnlockAttempt.BadPin(1)
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        vm.setPassword("the password")
        vm.cancelRecoveryAndUnlock()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(vm.uiState.value.cancelPinAsked)
        vm.setPin("11223344")
        assertFalse("the password again", vm.uiState.value.cancelAllowed)
        vm.setPassword("the password")
        assertTrue(vm.uiState.value.cancelAllowed)
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        assertEquals("11223344", vault.lastPin)
        assertTrue(vault.lastCancelRecovery)
        assertFalse(vm.uiState.value.recoveryPending)
    }

    /**
     * A cancel answered "new PIN" (an unresolved PIN change, §11.14.9 step 5): the keypad asks for the new PIN under the
     * new-PIN notice, and the cancel goes with it.
     */
    @Test
    fun aNewPinAskedDuringTheCancelGoesWithTheCancel() = runTest {
        vault.pinChangeUnresolved.value = true
        vault.unlockResults += UnlockAttempt.RecoveryPending
        vault.unlockResults += UnlockAttempt.NewPinNeeded
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.recoveryPending)
        assertTrue(s.cancelPinAsked)
        assertTrue(s.newPinStep)
        assertEquals("", s.pin)
        vm.setPin("11223344")
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        assertEquals("11223344", vault.lastPin)
        assertTrue(vault.lastCancelRecovery)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    /** The owner check due, no phone secret: the cancel goes with the owner check and the password typed in the recovery card. */
    @Test
    fun aDueCheckCancelGoesWithThePassword() = runTest {
        vault.ownerCheck.value =
            OwnerCheckView(OwnerCheckState.HELD, Instant.now().minusSeconds(60), 86_400, 0, hold = true, holdOffUntil = null)
        vault.unlockResults += UnlockAttempt.RecoveryPending
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.checkDue)
        vm.setPin("40281795")
        vm.setPassword("the password")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.recoveryPending)
        assertFalse("the password again", vm.uiState.value.cancelAllowed)
        vm.setPassword("the password")
        assertTrue(vm.uiState.value.cancelAllowed)
        vm.cancelRecoveryAndUnlock()
        advanceUntilIdle()
        assertTrue(vault.lastCancelRecovery)
        assertEquals("the password", vault.lastPassword)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }
}
