package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckOutcome
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.VaultProbe
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
 * (ANDROID-PLAN 0.1.95-0.1.97, VAULT-MESSAGING 0.73.0-0.73.2) "Probably locked, checking": a failed or unanswered check that
 * leaves the estimate of tries left at 0 says the vault has probably locked, offers no check and re-reads the vault's state
 * (no try used); a check with no answer above 0 re-reads once and says it may have counted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbablyLockedTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked)

    private fun held(failures: Int = 9) = OwnerCheckView(
        OwnerCheckState.HELD, Instant.now().minusSeconds(60), 86_400, failures, hold = true, holdOffUntil = null,
    )

    /** The gated screen with the PIN and password typed and one check sent, answered with [answer]. */
    private fun TestScope.sendCheck(answer: OwnerCheckOutcome, failures: Int = 9): OwnerCheckViewModel {
        vault.ownerCheck.value = held(failures)
        val vm = OwnerCheckViewModel(vault, vault)
        vm.start(OwnerCheckMode.GATED)
        runCurrent()
        vault.checkResults.add(answer)
        vm.setPin("40281795")
        vm.setPassword("nope")
        vm.submit()
        runCurrent()
        return vm
    }

    private val estimatedZero = OwnerCheckOutcome.BadPassword(0, estimated = true)

    private fun OwnerCheckViewModel.screen() = uiState.value

    @Test
    fun anEstimateOfZeroSaysProbablyLockedWithNoFieldsAndNoCheck() = runTest {
        val vm = sendCheck(estimatedZero)
        val s = vm.screen()
        assertEquals(LockWatch.CHECKING, s.watch)
        assertEquals("", s.pin)
        assertFalse(s.pinKept)
        assertFalse(s.locked) // never shown as locked: the app does not know
        assertFalse(vault.lockedByOwnerCheck.value)
        assertEquals(null, s.message)
        vm.setPin("40281795")
        vm.setPassword("pw")
        assertFalse(vm.screen().submitAllowed)
        // The lock action stays: it is not tied to the state.
        vm.lockVault()
        runCurrent()
        assertTrue("lock" in vault.calls)
        assertEquals(1, vault.calls.count { it == "check" }) // nothing was sent but the first check
    }

    @Test
    fun anUnansweredCheckWithTheEstimateAtZeroIsTheSame() = runTest {
        val vm = sendCheck(OwnerCheckOutcome.Failed(FailureKind.NO_RESPONSE, null, estimatedLeft = 0))
        assertEquals(LockWatch.CHECKING, vm.screen().watch)
        assertEquals("", vm.screen().pin)
    }

    @Test
    fun aFreshCountOrAnEstimateAboveZeroIsNotProbablyLocked() = runTest {
        val vm = sendCheck(OwnerCheckOutcome.BadPassword(3, estimated = true))
        assertEquals(LockWatch.NONE, vm.screen().watch)
        assertEquals(0, vault.calls.count { it == "probeVault" })
    }

    /** At once, 5 s, 15 s, 1 min and 3 min; one at a time; no check sent; then "isn't answering". */
    @Test
    fun theReadsRunAtOnceThenAt5s15s1minAnd3minOneAtATime() = runTest {
        val times = mutableListOf<Long>()
        vault.onProbe = { times += testScheduler.currentTime }
        vault.probeTakes = 100
        val start = testScheduler.currentTime
        val vm = sendCheck(estimatedZero)
        advanceTimeBy(190_000)
        runCurrent()
        // Each read takes 100 ms, and the next is counted from the end of the one before: never two at once.
        assertEquals(listOf(0L, 5_100L, 15_200L, 60_300L, 180_400L), times.map { it - start })
        assertEquals(1, vault.maxConcurrentProbes)
        assertEquals(LockWatch.SILENT, vm.screen().watch)
        assertEquals(1, vault.calls.count { it == "check" })
    }

    @Test
    fun anApiLockedShowsTheLockText() = runTest {
        val vm = sendCheck(estimatedZero)
        vault.probeResults.add(VaultProbe.LOCKED)
        advanceTimeBy(6_000)
        runCurrent()
        val s = vm.screen()
        assertTrue(s.locked)
        assertFalse(s.lockCauseUnknown) // at an estimate of 0 the app's own count names the cause
        assertEquals(LockWatch.NONE, s.watch)
        assertTrue(vault.lockedByOwnerCheck.value)
        // No more reads once locked.
        val reads = vault.calls.count { it == "probeVault" }
        advanceTimeBy(200_000)
        assertEquals(reads, vault.calls.count { it == "probeVault" })
    }

    @Test
    fun aVaultStatusAnswerShowsTheCheckWithTheRealCount() = runTest {
        val vm = sendCheck(estimatedZero)
        vault.ownerCheck.value = held(failures = 6)
        vault.probeResults.add(VaultProbe.OPEN) // the read at once (during sendCheck) found nothing; the one at 5 s answers
        advanceTimeBy(6_000)
        runCurrent()
        val s = vm.screen()
        assertEquals(LockWatch.NONE, s.watch)
        assertFalse(s.locked)
        assertEquals(4, s.view?.checksLeft)
        vm.setPin("40281795")
        vm.setPassword("pw")
        assertTrue(vm.screen().submitAllowed) // the ordinary check, PIN and password
    }

    /** The member API's `unlocked` is not an answer: the checking text stays (the manager returns NO_ANSWER for it). */
    @Test
    fun anApiUnlockedKeepsChecking() = runTest {
        val vm = sendCheck(estimatedZero)
        repeat(3) { vault.probeResults.add(VaultProbe.NO_ANSWER) }
        advanceTimeBy(20_000)
        assertEquals(LockWatch.CHECKING, vm.screen().watch)
    }

    @Test
    fun noAnswersEndInIsntAnsweringAndCheckAgainReadsAgain() = runTest {
        val vm = sendCheck(estimatedZero)
        advanceTimeBy(190_000)
        runCurrent()
        assertEquals(LockWatch.SILENT, vm.screen().watch)
        val before = vault.calls.count { it == "probeVault" }
        assertEquals(5, before)
        vm.checkAgain()
        runCurrent()
        assertEquals(LockWatch.CHECKING, vm.screen().watch)
        assertEquals(before + 1, vault.calls.count { it == "probeVault" }) // at once
        vault.probeResults.add(VaultProbe.NO_ANSWER) // (the first of the new run was already taken)
        advanceTimeBy(190_000)
        runCurrent()
        assertEquals(before + 5, vault.calls.count { it == "probeVault" })
        assertEquals(LockWatch.SILENT, vm.screen().watch)
        vm.lockVault() // still offered
        runCurrent()
        assertTrue("lock" in vault.calls)
    }

    @Test
    fun aReconnectOrAForegroundReturnReadsAtOnce() = runTest {
        val vm = sendCheck(estimatedZero)
        advanceTimeBy(1_000)
        val base = vault.calls.count { it == "probeVault" }
        vault.reconnectRelay()
        runCurrent()
        assertEquals(base + 1, vault.calls.count { it == "probeVault" })
        vault.setForeground(false)
        runCurrent()
        vault.setForeground(true)
        runCurrent()
        assertEquals(base + 2, vault.calls.count { it == "probeVault" })
        assertEquals(LockWatch.CHECKING, vm.screen().watch)
        advanceUntilIdle()
    }

    @Test
    fun anUnansweredCheckAboveZeroSaysAtLeastNAndKeepsThePin() = runTest {
        vault.ownerCheck.value = held(failures = 5)
        val vm = OwnerCheckViewModel(vault, vault)
        vm.start(OwnerCheckMode.GATED)
        runCurrent()
        vault.checkResults.add(OwnerCheckOutcome.Failed(FailureKind.NO_RESPONSE, null, estimatedLeft = 4))
        vm.setPin("40281795")
        vm.setPassword("nope")
        vm.submit()
        runCurrent()
        assertEquals(1, vault.calls.count { it == "probeVault" }) // one re-read, no more
        assertEquals(OwnerCheckMessage.MaybeCounted(4), vm.screen().message)
        assertEquals(LockWatch.NONE, vm.screen().watch)
        advanceTimeBy(200_000)
        assertEquals(1, vault.calls.count { it == "probeVault" })
    }

    @Test
    fun anUnansweredCheckAboveZeroThatFindsTheVaultLockedHasNoKnownCause() = runTest {
        vault.probeResults.add(VaultProbe.LOCKED)
        val vm = sendCheck(OwnerCheckOutcome.Failed(FailureKind.NETWORK, null, estimatedLeft = 4))
        val s = vm.screen()
        assertTrue(s.locked)
        assertTrue(s.lockCauseUnknown) // the locked screen, without the "ten wrong checks" text
        assertEquals(null, s.message)
    }

    @Test
    fun anUnansweredCheckAboveZeroThatGetsAStatusAnswerShowsTheUsualCount() = runTest {
        vault.probeResults.add(VaultProbe.OPEN)
        val vm = sendCheck(OwnerCheckOutcome.Failed(FailureKind.NETWORK, null, estimatedLeft = 4))
        // The vault answered: the failure stays on screen (the check did not go through), beside the real count.
        assertTrue(vm.screen().message is OwnerCheckMessage.Failed)
        assertEquals(LockWatch.NONE, vm.screen().watch)
        assertFalse(vm.screen().locked)
    }

    /** The member's lock, leaving the screen and starting it afresh each end the reads in progress. */
    @Test
    fun cancelAndLockVaultStopTheReads() = runTest {
        val vm = sendCheck(estimatedZero)
        vm.cancel()
        advanceTimeBy(200_000)
        assertEquals(1, vault.calls.count { it == "probeVault" })
        val second = sendCheck(estimatedZero)
        val reads = vault.calls.count { it == "probeVault" }
        second.lockVault()
        advanceTimeBy(200_000)
        assertEquals(reads, vault.calls.count { it == "probeVault" })
        assertFalse(vault.lockedByOwnerCheck.value)
    }

    /** The unlock's own check went unanswered, or left the estimate at 0: the same follow-up as on the screen. */
    @Test
    fun theUnlockPathFollowsUpAnUnansweredCheck() = runTest {
        vault.ownerCheck.value = held(failures = 5)
        vault.unlockCheckOutcome.value = OwnerCheckOutcome.Failed(FailureKind.NETWORK, null, estimatedLeft = 4)
        val vm = OwnerCheckViewModel(vault, vault)
        vm.start(OwnerCheckMode.GATED)
        runCurrent()
        assertEquals(1, vault.calls.count { it == "probeVault" })
        assertEquals(OwnerCheckMessage.MaybeCounted(4), vm.screen().message)
    }

    @Test
    fun theUnlockPathWithTheEstimateAtZeroIsProbablyLocked() = runTest {
        vault.ownerCheck.value = held()
        vault.unlockCheckOutcome.value = OwnerCheckOutcome.BadPassword(0, estimated = true)
        val vm = OwnerCheckViewModel(vault, vault)
        vm.start(OwnerCheckMode.GATED)
        runCurrent()
        assertEquals(LockWatch.CHECKING, vm.screen().watch)
        assertEquals("", vm.screen().pin)
    }

    /** With `failures == 0` the screen would show nothing: the failure stays, so the member sees the check did not go through. */
    @Test
    fun anOpenRereadKeepsTheFailureMessage() = runTest {
        vault.probeResults.add(VaultProbe.OPEN)
        val vm = sendCheck(OwnerCheckOutcome.Failed(FailureKind.NETWORK, null, estimatedLeft = 4), failures = 0)
        assertTrue(vm.screen().message is OwnerCheckMessage.Failed)
    }

    /** Trigger reads do not pile up: while one runs, at most one more waits. */
    @Test
    fun triggerReadsAreBounded() = runTest {
        vault.probeTakes = 1_000
        val vm = sendCheck(estimatedZero)
        repeat(6) { vault.reconnectRelay() }
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        // The read at once, and one trigger read behind it; five triggers were skipped.
        assertEquals(2, vault.calls.count { it == "probeVault" })
        assertEquals(1, vault.maxConcurrentProbes)
        assertEquals(LockWatch.CHECKING, vm.screen().watch)
    }

    /** §3.6.5: nothing entered is kept past the check's answer, so the password is gone when the re-read starts. */
    @Test
    fun thePasswordIsClearedBeforeTheReread() = runTest {
        vault.ownerCheck.value = held(failures = 5)
        val vm = OwnerCheckViewModel(vault, vault)
        vm.start(OwnerCheckMode.GATED)
        runCurrent()
        var seen: String? = null
        vault.onProbe = { seen = vm.screen().password }
        vault.checkResults.add(OwnerCheckOutcome.Failed(FailureKind.NO_RESPONSE, null, estimatedLeft = 4))
        vm.setPin("40281795")
        vm.setPassword("nope")
        vm.submit()
        runCurrent()
        assertEquals("", seen)
    }

    /** Lock vault is offered in every mode while the watch runs, and Cancel stays beside it where the check can be left. */
    @Test
    fun lockVaultAndCancelStayOfferedWhileTheWatchRuns() {
        for (watch in listOf(LockWatch.CHECKING, LockWatch.SILENT)) {
            for (mode in OwnerCheckMode.entries) {
                assertTrue(lockOffered(mode, watch))
                assertEquals(mode != OwnerCheckMode.GATED, cancelBesideLock(mode, watch))
            }
        }
        // Without the watch: gated offers the lock only, the others their Cancel only.
        assertTrue(lockOffered(OwnerCheckMode.GATED, LockWatch.NONE))
        assertFalse(lockOffered(OwnerCheckMode.VOLUNTARY, LockWatch.NONE))
        assertFalse(cancelBesideLock(OwnerCheckMode.HOLD_OFF, LockWatch.NONE))
    }
}
