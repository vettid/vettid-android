package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckOutcome
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * ANDROID-PLAN 0.1.124 §4 "The owner check's progress": while the vault verifies, the screen is checking (no entries, no
 * Check); a pass shows the tick before the screen closes, a pass that needs presence recorded keeps today's screen, any other
 * answer goes back to the form with today's messages, and an answer that comes after the member's Lock vault is dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerCheckProgressTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked)

    private val held = OwnerCheckView(OwnerCheckState.HELD, Instant.now().minusSeconds(60), 86_400, 2, hold = true, holdOffUntil = null)

    /** The screen for [mode] with both entries typed and a check sent; the vault holds it until [gate] completes. */
    private fun TestScope.sendHeld(mode: OwnerCheckMode, gate: CompletableDeferred<Unit>): OwnerCheckViewModel {
        vault.ownerCheck.value = held
        vault.checkGate = gate
        val vm = OwnerCheckViewModel(vault, vault)
        vm.start(mode)
        runCurrent()
        vm.setPin("40281795")
        vm.setPassword("correct horse")
        vm.submit()
        runCurrent()
        return vm
    }

    @Test
    fun aCheckWithTheVaultIsCheckingAndCannotBeSentAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = sendHeld(OwnerCheckMode.VOLUNTARY, gate)
        val s = vm.uiState.value
        assertTrue(s.checking)
        assertFalse(s.submitAllowed)
        assertFalse(s.passedTick)
        vm.submit() // ignored: one check at a time
        runCurrent()
        assertEquals(1, vault.calls.count { it == "check" })
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.checking)
    }

    @Test
    fun aPassShowsTheTickFirstAndClosesOnlyWhenItWasShown() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = sendHeld(OwnerCheckMode.VOLUNTARY, gate)
        gate.complete(Unit)
        runCurrent()
        val s = vm.uiState.value
        assertTrue(s.passedTick)
        assertFalse("the screen stays for the tick", s.passed)
        assertFalse(s.checking)
        assertEquals("", s.pin)
        assertEquals("", s.password)
        vm.tickShown()
        assertTrue(vm.uiState.value.passed)
    }

    @Test
    fun theGatedCheckAndTheHoldOffShowTheTickToo() = runTest {
        for (mode in listOf(OwnerCheckMode.GATED, OwnerCheckMode.HOLD_OFF)) {
            val gate = CompletableDeferred<Unit>()
            val vm = sendHeld(mode, gate)
            gate.complete(Unit)
            runCurrent()
            assertTrue("$mode", vm.uiState.value.passedTick)
            assertFalse("$mode", vm.uiState.value.passed)
        }
    }

    @Test
    fun aPassThatNeedsPresenceRecordedKeepsTodaysScreenWithNoTick() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = sendHeld(OwnerCheckMode.GATED, gate)
        vault.presenceUnrecorded.value = true
        gate.complete(Unit)
        runCurrent()
        val s = vm.uiState.value
        assertTrue(s.passedUnrecorded)
        assertFalse(s.passedTick)
        vm.tickShown() // nothing: there is no tick
        assertFalse(vm.uiState.value.passed)
    }

    @Test
    fun everyOtherAnswerGoesBackToTheFormWithTodaysMessage() = runTest {
        val answers = listOf(
            OwnerCheckOutcome.BadPin(7) to OwnerCheckMessage.BadPin(7),
            OwnerCheckOutcome.BadPassword(7) to OwnerCheckMessage.BadPassword(7),
            OwnerCheckOutcome.Backoff(0) to OwnerCheckMessage.Backoff,
            OwnerCheckOutcome.Failed(FailureKind.NETWORK, null) to OwnerCheckMessage.Failed(FailureKind.NETWORK, null),
        )
        for ((answer, message) in answers) {
            val gate = CompletableDeferred<Unit>()
            vault.checkResults.add(answer)
            val vm = sendHeld(OwnerCheckMode.GATED, gate)
            gate.complete(Unit)
            runCurrent()
            val s = vm.uiState.value
            assertFalse("$answer", s.checking)
            assertFalse("$answer", s.passedTick)
            assertFalse("$answer", s.passed)
            assertEquals("$answer", message, s.message)
        }
    }

    @Test
    fun lockVaultWhileTheGatedCheckIsOutDropsItsAnswer() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = sendHeld(OwnerCheckMode.GATED, gate)
        vm.lockVault()
        runCurrent()
        assertTrue(vault.calls.contains("lock"))
        assertFalse(vm.uiState.value.checking)
        gate.complete(Unit) // the vault passed the check after all: its answer comes after the member's lock
        runCurrent()
        val s = vm.uiState.value
        assertFalse(s.passedTick)
        assertFalse(s.passed)
        assertNull(s.message)
    }

    @Test
    fun lockVaultWhileTheGatedCheckIsOutDropsAFailureToo() = runTest {
        val gate = CompletableDeferred<Unit>()
        vault.checkResults.add(OwnerCheckOutcome.Backoff(120))
        val vm = sendHeld(OwnerCheckMode.GATED, gate)
        vm.lockVault()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        val s = vm.uiState.value
        assertNull(s.message)
        assertEquals("no backoff runs for a dropped answer", 0L, s.waitSeconds)
    }

    @Test
    fun lockVaultDuringTheReReadAfterAnUnansweredCheckDropsItsAnswer() = runTest {
        val gate = CompletableDeferred<Unit>()
        vault.checkResults.add(OwnerCheckOutcome.Failed(FailureKind.NETWORK, null, estimatedLeft = 5))
        vault.probeTakes = 1_000
        val vm = sendHeld(OwnerCheckMode.GATED, gate)
        gate.complete(Unit)
        runCurrent()
        assertTrue("the re-read runs", vault.calls.contains("probeVault"))
        vm.lockVault()
        advanceTimeBy(2_000)
        runCurrent()
        assertNull("the answer after the re-read is dropped", vm.uiState.value.message)
    }

    @Test
    fun lockVaultBeforeAnUnansweredChecksAnswerMakesNoReRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        vault.checkResults.add(OwnerCheckOutcome.Failed(FailureKind.NETWORK, null, estimatedLeft = 5))
        val vm = sendHeld(OwnerCheckMode.GATED, gate)
        vm.lockVault()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertFalse("no re-read after the member's own lock", vault.calls.contains("probeVault"))
    }

    @Test
    fun aFailedLockHoldsASecondCheckUntilTheDroppedOneReturns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = sendHeld(OwnerCheckMode.GATED, gate)
        vault.fail["lock"] = com.vettid.core.data.vault.VaultFailure(FailureKind.NETWORK)
        vm.lockVault()
        runCurrent()
        vm.setPin("40281795")
        vm.setPassword("correct horse")
        assertTrue(vm.uiState.value.awaitingDropped)
        assertFalse("no second check alongside the first", vm.uiState.value.submitAllowed)
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.awaitingDropped)
        assertTrue(vm.uiState.value.submitAllowed)
    }

    @Test
    fun theEntriesGoWhenTheCheckIsSent() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = sendHeld(OwnerCheckMode.VOLUNTARY, gate)
        assertEquals("", vm.uiState.value.pin)
        assertEquals("", vm.uiState.value.password)
        assertEquals("the check carried what was typed", "40281795", vault.lastPin)
        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun theTicksHapticIsGivenOnceAndItsHoldIsNotRestarted() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = sendHeld(OwnerCheckMode.VOLUNTARY, gate)
        assertFalse("no tick yet", vm.tick.takeHaptic())
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.tick.takeHaptic())
        assertFalse("a rotation gives no second haptic", vm.tick.takeHaptic())
        Thread.sleep(50)
        assertTrue("the hold counts from the tick's start", vm.tick.remainingMs(1_000) < 1_000)
    }

    @Test
    fun theScreenLeavingDropsALateAnswer() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = sendHeld(OwnerCheckMode.GATED, gate)
        vm.cancel()
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.passedTick)
    }

    @Test
    fun aCheckThatBecomesTheGatedOneKeepsItsAnswer() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = sendHeld(OwnerCheckMode.VOLUNTARY, gate)
        vm.start(OwnerCheckMode.GATED) // the deadline passed while the voluntary check was out
        runCurrent()
        assertTrue("still checking: no entries, no second check", vm.uiState.value.checking)
        assertFalse(vm.uiState.value.submitAllowed)
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.uiState.value.passedTick)
    }

    @Test
    fun lockVaultDuringTheTickDoesNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = sendHeld(OwnerCheckMode.GATED, gate)
        gate.complete(Unit)
        runCurrent()
        vm.lockVault()
        runCurrent()
        assertFalse(vault.calls.contains("lock"))
        assertTrue(vm.uiState.value.passedTick)
    }

    @Test
    fun theTickIsHeldOneSecondOrThreeWithAScreenReader() {
        assertEquals(1_000L, passedTickMillis(screenReaderOn = false))
        assertEquals(3_000L, passedTickMillis(screenReaderOn = true))
    }
}
