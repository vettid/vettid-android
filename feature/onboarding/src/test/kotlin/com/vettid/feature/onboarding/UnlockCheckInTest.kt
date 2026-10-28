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

/**
 * (ANDROID-PLAN 0.1.93) "Unlock and check in": the unlock asks only for what it uses, and a check that fails on the password
 * shows on the held screen, which asks for the password only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockCheckInTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)

    private suspend fun TestScope.dueUnlock(): UnlockViewModel {
        vault.ownerCheck.value = OwnerCheckView(
            OwnerCheckState.HELD, Instant.now().minusSeconds(60), 86_400, 0, hold = true, holdOffUntil = null,
        )
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        return vm
    }

    private fun UnlockViewModel.checkIn(pin: String, password: String) {
        setPin(pin)
        setPassword(password)
        submit()
    }

    private fun heldScreen(): OwnerCheckViewModel = OwnerCheckViewModel(vault, vault).also { it.start(OwnerCheckMode.GATED) }

    @Test
    fun withoutSavedStateOnlyThePin() = runTest {
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.checkDue)
        vm.setPin("40281795")
        assertTrue(vm.uiState.value.submitAllowed)
    }

    @Test
    fun aWrongPinSendsNoCheck() = runTest {
        val vm = dueUnlock()
        vault.unlockResults.add(UnlockAttempt.BadPin(0))
        vm.checkIn("40281795", "pw")
        advanceUntilIdle()
        assertEquals(UnlockMessage.BadPin, vm.uiState.value.message)
        assertFalse("check" in vault.calls)
        assertNull(vault.unlockCheckOutcome.value)
        assertNull(vault.takeUnlockPin())
    }

    /** The held screen says the try, keeps the PIN, asks for the password only, and a pass sends the kept PIN. */
    @Test
    fun aWrongPasswordShowsOnTheHeldScreenWhichAsksForThePasswordOnly() = runTest {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value) // the vault's real state: open, held; the app shows the held screen
        val held = heldScreen()
        val s = held.uiState.value
        assertTrue(s.pinKept)
        assertEquals(7, s.unlockedWrong)
        assertNull(s.message)
        assertEquals("40281795", s.pin)
        assertFalse(s.submitAllowed) // the password first
        held.setPassword("correct horse")
        assertEquals(7, held.uiState.value.unlockedWrong) // typing keeps the line
        held.submit()
        advanceUntilIdle()
        assertEquals("40281795", vault.lastPin)
        assertTrue(held.uiState.value.passed)
        assertEquals("", held.uiState.value.pin)
    }

    @Test
    fun anotherWrongPasswordCountsDownAndKeepsThePin() = runTest {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        val held = heldScreen()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(6))
        held.setPassword("still no")
        held.submit()
        advanceUntilIdle()
        val s = held.uiState.value
        assertEquals(6, s.unlockedWrong)
        assertTrue(s.pinKept)
        assertEquals("40281795", s.pin)
    }

    @Test
    fun aBackoffSaysTheVaultIsOpenAndKeepsCheckInOff() = runTest {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.Backoff(30))
        vm.checkIn("40281795", "pw")
        advanceUntilIdle()
        val held = heldScreen()
        advanceTimeBy(100)
        val s = held.uiState.value
        assertTrue(s.pinKept)
        assertTrue(s.unlockedOpen)
        assertNull(s.unlockedWrong)
        assertEquals(30, s.waitSeconds)
        held.setPassword("pw")
        assertFalse(held.uiState.value.submitAllowed)
    }

    /** ON_STOP on the held screen: the PIN is forgotten, and the same screen asks for both. */
    @Test
    fun leavingTheHeldScreenForgetsThePin() = runTest {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        val held = heldScreen()
        vault.setForeground(false) // the process went to the background
        advanceUntilIdle()
        val s = held.uiState.value
        assertFalse(s.pinKept)
        assertEquals("", s.pin)
        held.setPassword("pw")
        assertFalse(held.uiState.value.submitAllowed) // the PIN again
        vault.setForeground(true)
    }

    /** ON_STOP while the unlock and its check run: no PIN is kept, and the spent try shows as the ordinary failure. */
    @Test
    fun leavingWhileTheUnlockRunsKeepsNoPin() = runTest {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        vault.setForeground(false)
        advanceUntilIdle()
        val s = heldScreen().uiState.value
        assertFalse(s.pinKept)
        assertEquals(OwnerCheckMessage.BadPassword(7), s.message)
        assertNull(s.unlockedWrong)
        vault.setForeground(true)
    }

    /** The tenth failure locks the vault: no retry, the lock flag is set without the `vault.locking` notice. */
    @Test
    fun theTenthWrongPasswordLocks() = runTest {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(0))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.lockedByOwnerCheck)
        val held = heldScreen()
        advanceUntilIdle()
        // The held screen shows the lock text and offers no check.
        assertFalse(held.uiState.value.pinKept)
        assertTrue(held.uiState.value.locked)
        held.setPin("975310")
        held.setPassword("pw")
        assertFalse(held.uiState.value.submitAllowed)
    }

    /** A refused update with a check that failed on the password still keeps the PIN for the held screen. */
    @Test
    fun anUpdateRefusedStillKeepsThePin() = runTest {
        val vm = dueUnlock()
        vault.unlockResults.add(UnlockAttempt.UpdateRefused("x"))
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        assertEquals(7, heldScreen().uiState.value.unlockedWrong)
    }
}
