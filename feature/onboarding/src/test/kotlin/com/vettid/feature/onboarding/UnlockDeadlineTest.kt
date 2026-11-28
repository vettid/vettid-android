package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
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
 * (#19722) The unlock screen judges the owner check's deadline again as time passes: a check that comes due while the screen
 * is shown (or while the app was away, as after a manual lock some time before the deadline) asks for the password too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockDeadlineTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)
    /**
     * Long before the real clock: a code path that read the real clock in place of [UnlockViewModel.clock] finds every time
     * here passed, so it fails at once rather than waiting forever in virtual time for a time that never comes.
     */
    private val start = Instant.parse("2000-01-01T00:00:00Z")

    /** Wall-clock time that passed with no virtual time (deep sleep: the clock moves on, a delay does not). */
    private var slept = 0L

    /** The view model on a virtual wall clock that moves with the test's time. */
    private fun TestScope.unlock(): UnlockViewModel =
        UnlockViewModel(vault, vault, vault, FakeReleaseNotes()).also { vm ->
            vm.clock = { start.plusMillis(testScheduler.currentTime + slept) }
            vault.clock = vm.clock
        }

    private fun okUntil(deadline: Instant, idleLockAt: Instant? = null) = OwnerCheckView(
        OwnerCheckState.OK, deadline, 86_400, 0, hold = true, holdOffUntil = null, idleLockAt = idleLockAt,
    )

    @Test
    fun aDeadlinePassingWhileTheScreenIsShownAsksForThePassword() = runTest {
        // The view kept across the member's lock: OK, due in ten minutes.
        vault.ownerCheck.value = okUntil(start.plusSeconds(600))
        val vm = unlock()
        vm.setVisible(true)
        runCurrent()
        assertFalse(vm.uiState.value.checkDue)
        advanceTimeBy(599_000)
        runCurrent()
        assertFalse("not yet", vm.uiState.value.checkDue)
        advanceTimeBy(JUDGE_STEP_MS)
        runCurrent()
        assertTrue("the password with the PIN", vm.uiState.value.checkDue)
        vm.setPin("40281795")
        assertFalse("the PIN alone no longer unlocks", vm.uiState.value.submitAllowed)
        vm.setVisible(false)
    }

    /** A deadline passed in deep sleep: judged within one step once the phone wakes, not when the long delay ends. */
    @Test
    fun aDeadlinePassedInDeepSleepIsJudgedWithinAStep() = runTest {
        vault.ownerCheck.value = okUntil(start.plusSeconds(3_600))
        val vm = unlock()
        vm.setVisible(true)
        runCurrent()
        assertFalse(vm.uiState.value.checkDue)
        slept = 3_600_000
        advanceTimeBy(JUDGE_STEP_MS)
        runCurrent()
        assertTrue(vm.uiState.value.checkDue)
        vm.setVisible(false)
    }

    /** Away from the screen nothing runs; coming back judges again at once. */
    @Test
    fun aReturnToTheScreenJudgesAgain() = runTest {
        vault.ownerCheck.value = okUntil(start.plusSeconds(600))
        val vm = unlock()
        vm.setVisible(true)
        runCurrent()
        vm.setVisible(false)
        runCurrent()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertFalse("not judged while away", vm.uiState.value.checkDue)
        vm.setVisible(true)
        runCurrent()
        assertTrue(vm.uiState.value.checkDue)
        vm.setVisible(false)
    }

    /** The idle lock's time passing says why the vault locked itself, as the deadline does. */
    @Test
    fun anIdleLockTimePassingIsJudgedToo() = runTest {
        // The deadline still ahead: only the idle lock's time can make the check due here.
        vault.ownerCheck.value = okUntil(start.plusSeconds(86_400), idleLockAt = start.plusSeconds(120))
        val vm = unlock()
        vm.setVisible(true)
        runCurrent()
        assertNull(vm.uiState.value.idleLocked)
        assertFalse(vm.uiState.value.checkDue)
        advanceTimeBy(120_000 + JUDGE_STEP_MS)
        runCurrent()
        assertTrue(vm.uiState.value.idleLocked != null)
        assertTrue("the password with the PIN", vm.uiState.value.checkDue)
        vm.setVisible(false)
    }

    /** A tap just past the deadline, before the screen judged again: the password is asked for, and nothing is sent. */
    @Test
    fun aTapPastTheDeadlineAsksForThePasswordFirst() = runTest {
        vault.ownerCheck.value = okUntil(start.plusSeconds(600))
        val vm = unlock() // not shown: nothing judges again by itself
        runCurrent()
        vm.setPin("40281795")
        assertTrue(vm.uiState.value.submitAllowed)
        advanceTimeBy(600_000)
        vm.submit()
        runCurrent()
        assertTrue(vm.uiState.value.checkDue)
        assertFalse("nothing sent", vault.calls.any { it.startsWith("unlock") })
        vm.setPassword("correct horse")
        vm.submit()
        runCurrent()
        assertTrue("now the unlock with the check", vault.calls.any { it.startsWith("unlockWithCheck") })
    }

    /** The same at the tap with only the idle lock's time passed (the deadline still ahead): and the screen says why. */
    @Test
    fun aTapPastTheIdleLockTimeAsksForThePasswordFirst() = runTest {
        vault.ownerCheck.value = okUntil(start.plusSeconds(86_400), idleLockAt = start.plusSeconds(120))
        val vm = unlock()
        runCurrent()
        vm.setPin("40281795")
        assertTrue(vm.uiState.value.submitAllowed)
        advanceTimeBy(120_000)
        vm.submit()
        runCurrent()
        assertTrue(vm.uiState.value.checkDue)
        assertTrue("why it locked", vm.uiState.value.idleLocked != null)
        assertFalse("nothing sent", vault.calls.any { it.startsWith("unlock") })
    }

    /** A lock by the checks that came after the screen last judged: the tap asks for the password first. */
    @Test
    fun aTapAfterALockByTheChecksAsksForThePasswordFirst() = runTest {
        val vm = unlock()
        runCurrent()
        vm.setPin("40281795")
        vault.lockedByOwnerCheck.value = true // not judged by the screen yet: no runCurrent before the tap
        vm.submit()
        assertTrue(vm.uiState.value.checkDue)
        assertTrue(vm.uiState.value.lockedByOwnerCheck)
        runCurrent()
        assertFalse("nothing sent", vault.calls.any { it.startsWith("unlock") })
    }

    /** (ANDROID-PLAN 0.1.95) A lock a read found, cause unknown: locked, but not "ten wrong checks", and no password asked. */
    @Test
    fun aLockOfUnknownCauseAsksForThePinOnly() = runTest {
        vault.lockedByOwnerCheck.value = true
        vault.lockCauseUnknown.value = true
        val vm = unlock()
        runCurrent()
        assertFalse(vm.uiState.value.lockedByOwnerCheck)
        assertFalse(vm.uiState.value.checkDue)
    }

    /** A second tap with no password after the ask: still nothing sent. */
    @Test
    fun aSecondTapWithoutThePasswordSendsNothing() = runTest {
        vault.ownerCheck.value = okUntil(start.plusSeconds(600))
        val vm = unlock()
        runCurrent()
        vm.setPin("40281795")
        advanceTimeBy(600_000)
        vm.submit()
        runCurrent()
        vm.submit()
        runCurrent()
        assertFalse(vm.uiState.value.submitAllowed)
        assertFalse("nothing sent", vault.calls.any { it.startsWith("unlock") })
    }

    @Test
    fun theNextJudgementIsTheEarlierTimeStillAhead() {
        val now = start
        assertNull(nextJudgement(null, now))
        assertNull(nextJudgement(okUntil(now.minusSeconds(1)), now))
        assertEquals(now.plusSeconds(5), nextJudgement(okUntil(now.plusSeconds(5), idleLockAt = now.plusSeconds(9)), now))
        assertEquals(now.plusSeconds(9), nextJudgement(okUntil(now.minusSeconds(5), idleLockAt = now.plusSeconds(9)), now))
        assertNull("a deadline now is not ahead", nextJudgement(okUntil(now), now))
    }
}
