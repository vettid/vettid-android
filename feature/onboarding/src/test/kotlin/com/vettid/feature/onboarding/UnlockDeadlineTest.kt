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
    private val start = Instant.parse("2026-11-28T12:00:00Z")

    /** The view model on a virtual wall clock that moves with the test's time. */
    private fun TestScope.unlock(): UnlockViewModel =
        UnlockViewModel(vault, vault, vault, FakeReleaseNotes()).also { vm ->
            vm.clock = { start.plusMillis(testScheduler.currentTime) }
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
        vault.ownerCheck.value = okUntil(start.minusSeconds(60), idleLockAt = start.plusSeconds(120))
        val vm = unlock()
        vm.setVisible(true)
        runCurrent()
        assertNull(vm.uiState.value.idleLocked)
        advanceTimeBy(120_000 + JUDGE_STEP_MS)
        runCurrent()
        assertTrue(vm.uiState.value.idleLocked != null)
        vm.setVisible(false)
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
