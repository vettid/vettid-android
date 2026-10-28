package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.OwnerCheckOutcome
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** (ANDROID-PLAN 0.1.93) "Unlock and check in": ask only for what is used, and stay on the unlock screen when the check fails. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockCheckInTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    private fun view(state: OwnerCheckState, deadline: Instant) =
        OwnerCheckView(state, deadline, 86_400, 0, hold = true, holdOffUntil = null)

    private suspend fun TestScope.dueVm(): UnlockViewModel {
        vault.ownerCheck.value = view(OwnerCheckState.HELD, Instant.now().minusSeconds(60))
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        return vm
    }

    private fun UnlockViewModel.checkIn(pin: String, password: String) {
        setPin(pin)
        setPassword(password)
        submit()
    }

    /** (ANDROID-PLAN 0.1.93) No saved owner-check state (a new install, a transfer, a recovery): the PIN only. */
    @Test
    fun withoutSavedStateOnlyThePin() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.checkDue)
        vm.setPin("40281795")
        assertTrue(vm.uiState.value.submitAllowed)
    }

    /** The PIN is right and the password wrong: the same screen says so, with the tries left, and asks only for the password. */
    @Test
    fun aWrongPasswordAfterTheUnlockStaysHereAndAsksOnlyForThePassword() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.checkRetry)
        assertEquals(UnlockMessage.CheckPasswordWrong(7), s.message)
        assertEquals("", s.pin)
        assertEquals("", s.password)
        assertEquals(AppPhase.Locked, vault.phase.value) // no Daily check screen: the app has not opened
        assertNull(vault.unlockCheckOutcome.value) // nothing parked for the Daily check screen
        assertFalse(s.submitAllowed) // the password first
        // The next try sends the kept PIN with the new password; a pass opens the app.
        vm.setPassword("correct horse")
        assertTrue(vm.uiState.value.submitAllowed)
        vm.submit()
        advanceUntilIdle()
        assertEquals("40281795" to "correct horse", vault.lastHeldCheck)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        assertFalse(vm.uiState.value.checkRetry)
    }

    @Test
    fun anotherWrongPasswordCountsDownTheTries() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(6))
        vm.setPassword("still no")
        vm.submit()
        advanceUntilIdle()
        assertEquals(UnlockMessage.CheckPasswordWrong(6), vm.uiState.value.message)
        assertTrue(vm.uiState.value.checkRetry)
    }

    /** The tenth failure locks the vault: the locked screen with the owner-check lock texts, not a retry. */
    @Test
    fun theTenthWrongPasswordShowsTheLockedScreen() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(0))
        // No vault.locking event: the tenth failure alone marks the lock.
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.checkRetry)
        assertTrue(s.lockedByOwnerCheck)
        assertTrue(s.checkDue)
    }

    /** A failure that locks the vault while the retry shows ends the retry. */
    @Test
    fun aLockWhileRetryingEndsTheRetry() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(1))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.checkRetry)
        vault.lockedByOwnerCheck.value = true
        advanceUntilIdle()
        assertFalse(vm.uiState.value.checkRetry)
    }

    /** A backoff used no try: the vault is open, the countdown runs, and "Check in" is off until it ends. */
    @Test
    fun aBackoffShowsTheCountdownAndKeepsCheckInOff() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.Backoff(30))
        vm.checkIn("40281795", "pw")
        advanceTimeBy(2_000)
        var s = vm.uiState.value
        assertTrue(s.checkRetry)
        assertNull(s.message)
        assertTrue(s.checkWaitSeconds in 1..30)
        vm.setPassword("pw")
        assertFalse(vm.uiState.value.submitAllowed)
        advanceTimeBy(31_000)
        s = vm.uiState.value
        assertEquals(0L, s.checkWaitSeconds)
        assertTrue(s.submitAllowed)
    }

    /** A wrong PIN is only the PIN error: no check is sent. */
    @Test
    fun aWrongPinSendsNoCheck() = runTest {
        val vm = dueVm()
        vault.unlockResults.add(UnlockAttempt.BadPin(0))
        vm.checkIn("40281795", "pw")
        advanceUntilIdle()
        assertEquals(UnlockMessage.BadPin, vm.uiState.value.message)
        assertFalse(vm.uiState.value.checkRetry)
        assertFalse("check" in vault.calls)
        assertNull(vault.unlockCheckOutcome.value)
    }

    /** The accepted PIN is forgotten when the app leaves the foreground: both are asked again. */
    @Test
    fun leavingTheForegroundForgetsThePin() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        vm.appLeft()
        val s = vm.uiState.value
        assertFalse(s.checkRetry)
        assertTrue(s.checkDue)
        assertEquals("", s.pin)
        vm.setPassword("pw")
        vm.submit() // no PIN: nothing is sent
        advanceUntilIdle()
        assertNull(vault.lastHeldCheck)
    }

    /** The app leaves the foreground while the unlock and its check run: the PIN is not kept, the spent try shows. */
    @Test
    fun leavingWhileTheUnlockRunsKeepsNoPin() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        vm.appLeft() // ON_STOP before the answer arrives
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.checkRetry)
        assertEquals(UnlockMessage.CheckPasswordWrong(7), s.message)
        vm.setPassword("pw")
        vm.submit()
        advanceUntilIdle()
        assertNull(vault.lastHeldCheck) // no PIN was kept
        vm.appEntered()
    }

    /** ON_STOP during "Check in": the wrong password's tries left still show, on the ordinary screen. */
    @Test
    fun leavingDuringCheckInStillShowsTheSpentTry() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(6))
        vm.setPassword("again")
        vm.submit()
        vm.appLeft()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.checkRetry)
        assertEquals(UnlockMessage.CheckPasswordWrong(6), s.message)
    }

    /** Typing keeps the tries-left line; it is not replaced by the "enter your password" text. */
    @Test
    fun typingKeepsTheFailureLine() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        vm.setPassword("c")
        assertEquals(UnlockMessage.CheckPasswordWrong(7), vm.uiState.value.message)
    }

    /** Any other end of the wait (another lock, a wipe, another unlock) ends the retry. */
    @Test
    fun theWaitingOpenEndingEndsTheRetry() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.checkRetry)
        vault.checkHeld.value = false
        advanceUntilIdle()
        assertFalse(vm.uiState.value.checkRetry)
    }

    /** The idle lock notice ends the retry too. */
    @Test
    fun theIdleLockEndsTheRetry() = runTest {
        val vm = dueVm()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        vault.idleLocked.value = com.vettid.core.data.vault.IdleLocked(604_800)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.checkRetry)
    }

    /** An update refused with a check that failed on the password still shows the failure. */
    @Test
    fun anUpdateRefusedStillShowsTheFailedCheck() = runTest {
        val vm = dueVm()
        vault.unlockResults.add(UnlockAttempt.UpdateRefused("x"))
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.checkRetry)
        assertEquals(UnlockMessage.CheckPasswordWrong(7), vm.uiState.value.message)
    }
}
