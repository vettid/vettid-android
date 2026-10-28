package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
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

    /** A rotation starts the screen again on the same view model: the kept PIN and the tries-left line stay. */
    @Test
    fun aRotationKeepsThePinAndTheFailureLine() = runTest {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(9))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        val held = heldScreen()
        held.start(OwnerCheckMode.GATED) // the recreated screen's LaunchedEffect
        val s = held.uiState.value
        assertTrue(s.pinKept)
        assertEquals("40281795", s.pin)
        assertEquals(9, s.unlockedWrong)
        // Leaving for good starts the next one afresh.
        held.cancel()
        held.start(OwnerCheckMode.GATED)
        assertFalse(held.uiState.value.pinKept)
        assertNull(held.uiState.value.unlockedWrong)
    }

    /** A new unlock outcome wins over the retained state of a screen that was never cancelled. */
    @Test
    fun aFreshOutcomeWinsOverRetainedState() = runTest {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(9))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        val held = heldScreen()
        assertEquals(9, held.uiState.value.unlockedWrong)
        // Another unlock, its check failing again; the screen was not cancelled in between.
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(8))
        vm.checkIn("51392846", "nope")
        advanceUntilIdle()
        held.start(OwnerCheckMode.GATED)
        assertEquals(8, held.uiState.value.unlockedWrong)
        assertEquals("51392846", held.uiState.value.pin)
    }

    /** Out and back while the unlock request runs: the app was in front at the answer, but no PIN is kept. */
    @Test
    fun anOutAndBackDuringTheUnlockKeepsNoPin() = runTest {
        val vm = dueUnlock()
        vault.duringUnlock = {
            vault.setForeground(false)
            vault.setForeground(true)
        }
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(7))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        val s = heldScreen().uiState.value
        assertFalse(s.pinKept)
        assertEquals("", s.pin)
        assertEquals(OwnerCheckMessage.BadPassword(7), s.message)
    }

    /** Submit, out and back, then the answer: the kept PIN is dropped and the screen asks for both. */
    @Test
    fun anOutAndBackBetweenSubmitAndAnswerDropsThePin() = runTest {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(9))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        val held = heldScreen()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(8))
        held.setPassword("again")
        held.submit()
        vault.setForeground(false)
        vault.setForeground(true)
        advanceUntilIdle()
        val s = held.uiState.value
        assertFalse(s.pinKept)
        assertEquals("", s.pin)
        assertEquals(OwnerCheckMessage.BadPassword(8), s.message)
    }

    /** The held screen's retry answered with no tries left drops the kept PIN at once, without waiting for `vault.locking`. */
    @Test
    fun aRetryWithNoTriesLeftDropsTheKeptPinAtOnce() =
        retryDropsThePin(OwnerCheckOutcome.BadPassword(0), OwnerCheckMessage.BadPassword(0))

    /** The same with no count from the vault: no tries are assumed left. */
    @Test
    fun aRetryWithAnUnknownCountDropsTheKeptPin() =
        retryDropsThePin(OwnerCheckOutcome.BadPassword(null), OwnerCheckMessage.BadPassword(null))

    /** A failure that sending again cannot fix (here the vault refused the session) drops the kept PIN. */
    @Test
    fun aRetryRefusedDropsTheKeptPin() = retryDropsThePin(
        OwnerCheckOutcome.Failed(FailureKind.UNAUTHORIZED, "unauthorized"),
        OwnerCheckMessage.Failed(FailureKind.UNAUTHORIZED, "unauthorized", null),
    )

    /** A lost vault session (no session, forgotten, not paired: [FailureKind.OTHER]) drops the kept PIN. */
    @Test
    fun aRetryWithTheSessionGoneDropsTheKeptPin() = retryDropsThePin(
        OwnerCheckOutcome.Failed(FailureKind.OTHER, null),
        OwnerCheckMessage.Failed(FailureKind.OTHER, null, null),
    )

    /** A backoff on the retry used no check: the PIN stays, with the vault-is-open line and the wait. */
    @Test
    fun aBackoffOnARetryKeepsThePin() = runTest {
        val held = heldAfterWrongPassword()
        vault.checkResults.add(OwnerCheckOutcome.Backoff(30))
        held.setPassword("again")
        held.submit()
        advanceTimeBy(100)
        val s = held.uiState.value
        assertTrue(s.pinKept)
        assertEquals("40281795", s.pin)
        assertTrue(s.unlockedOpen)
        assertEquals(30, s.waitSeconds)
    }

    /** After the PIN was dropped, the next check sends the PIN the member typed, not the one kept before. */
    @Test
    fun afterADropTheNextCheckSendsTheTypedPin() = runTest {
        val held = heldAfterWrongPassword()
        vault.checkResults.add(OwnerCheckOutcome.Failed(FailureKind.OTHER, null))
        held.setPassword("again")
        held.submit()
        advanceUntilIdle()
        vault.checkResults.add(OwnerCheckOutcome.Passed)
        held.setPin("55512345")
        held.setPassword("pw")
        held.submit()
        advanceUntilIdle()
        assertEquals("55512345", vault.lastPin)
        assertTrue(held.uiState.value.passed)
    }

    /** A lock that fails leaves the ordinary check: the kept PIN, its line and the hidden PIN field all go. */
    @Test
    fun aFailedLockLeavesTheOrdinaryCheck() = runTest {
        val held = heldAfterWrongPassword()
        vault.fail["lock"] = FakeVault.failure(FailureKind.NETWORK)
        held.lockVault()
        advanceUntilIdle()
        val s = held.uiState.value
        assertFalse(s.locking)
        assertFalse(s.pinKept)
        assertEquals("", s.pin)
        assertNull(s.unlockedWrong)
        assertFalse(s.unlockedOpen)
        assertEquals(OwnerCheckMessage.Failed(FailureKind.NETWORK, null, null), s.message) // never silent
        held.setPin("40281795")
        held.setPassword("pw")
        assertTrue(held.uiState.value.submitAllowed)
    }

    /** A failure on the way to the vault keeps the PIN, so the password-only retry can be sent again. */
    @Test
    fun aRetryWithNoAnswerKeepsThePin() = runTest {
        val held = heldAfterWrongPassword()
        vault.checkResults.add(OwnerCheckOutcome.Failed(FailureKind.NO_RESPONSE, null))
        held.setPassword("again")
        held.submit()
        advanceUntilIdle()
        val s = held.uiState.value
        assertTrue(s.pinKept)
        assertEquals("40281795", s.pin)
        assertEquals("", s.password)
    }

    /** The vault found locked during a backoff: the lock text only, not "Your vault is unlocked…" beside it. */
    @Test
    fun aLockDuringABackoffLeavesOnlyTheLockText() = runTest {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.Backoff(30))
        vm.checkIn("40281795", "pw")
        advanceUntilIdle()
        val held = heldScreen()
        advanceTimeBy(100)
        assertTrue(held.uiState.value.unlockedOpen)
        vault.lockedByOwnerCheck.value = true
        advanceTimeBy(100)
        val s = held.uiState.value
        assertTrue(s.locked)
        assertFalse(s.unlockedOpen)
        assertNull(s.unlockedWrong)
        assertFalse(s.pinKept)
        assertEquals("", s.pin)
        assertEquals(0L, s.waitSeconds)
        assertNull(s.message)
    }

    private suspend fun TestScope.heldAfterWrongPassword(): OwnerCheckViewModel {
        val vm = dueUnlock()
        vault.checkResults.add(OwnerCheckOutcome.BadPassword(1))
        vm.checkIn("40281795", "nope")
        advanceUntilIdle()
        return heldScreen().also { assertTrue(it.uiState.value.pinKept) }
    }

    private fun retryDropsThePin(answer: OwnerCheckOutcome, shown: OwnerCheckMessage) = runTest {
        val held = heldAfterWrongPassword()
        vault.checkResults.add(answer)
        held.setPassword("still no")
        held.submit()
        advanceUntilIdle()
        val s = held.uiState.value
        assertFalse(s.pinKept)
        assertEquals("", s.pin)
        assertEquals("", s.password)
        assertEquals(shown, s.message)
        assertNull(s.unlockedWrong)
        assertFalse(vault.lockedByOwnerCheck.value)
        held.setPassword("pw")
        assertFalse(held.uiState.value.submitAllowed) // the PIN must be typed again
    }
}
