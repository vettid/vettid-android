package com.vettid.core.data.lock

import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the cover is up (owner decision 2026-11-02): the lock is locked or unknown, or it is on and the app has been in the
 * background for the timeout or longer. It decides whether notifications show names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockCoverTest {
    private var clock = 0L // the monotonic clock
    private var wallSkew = 0L // the date set by hand
    private val prefs = InMemoryPreferencesRepository()
    private val keys = SoftKeys()
    private val timeoutMs = AppLockTimeout.DEFAULT.seconds * 1000L

    private fun lock() = AppLock(prefs, keys, MemFile(), now = { clock + wallSkew }, elapsed = { clock })

    private suspend fun unlocked(): AppLock = lock().also {
        it.start()
        it.completeEnable(it.cipherToEnable())
    }

    @Test
    fun onlyLockedAndPendingCoverByState() {
        assertTrue(AppLockState.LOCKED.coversApp())
        assertTrue(AppLockState.PENDING.coversApp())
        assertFalse(AppLockState.UNLOCKED.coversApp())
        assertFalse(AppLockState.DISABLED.coversApp())
    }

    @Test
    fun aLockThatIsOffNeverCovers() = runTest {
        val l = lock()
        assertTrue("not read yet", l.wouldShowLock())
        l.start()
        assertEquals(AppLockState.DISABLED, l.state.value)
        l.onBackground()
        clock = timeoutMs * 10
        assertFalse(l.wouldShowLock())
    }

    @Test
    fun inTheBackgroundNamesShowUntilTheTimeoutAndNotFromIt() = runTest {
        val l = unlocked()
        assertFalse("in front, unlocked", l.wouldShowLock())
        l.onBackground()
        clock = timeoutMs - 1
        assertFalse("before the timeout", l.wouldShowLock())
        clock = timeoutMs
        assertTrue("at the timeout", l.wouldShowLock())
        clock = timeoutMs * 3
        assertTrue("past it", l.wouldShowLock())
        // The lock itself engages only on return, as before.
        assertEquals(AppLockState.UNLOCKED, l.state.value)
    }

    @Test
    fun comingBackAndUnlockingRestoresNormalBehaviour() = runTest {
        val l = unlocked()
        l.onBackground()
        clock = timeoutMs
        assertTrue(l.wouldShowLock())
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
        assertTrue(l.wouldShowLock())
        assertTrue(l.completeUnlock(l.cipherToUnlock()!!))
        assertFalse(l.wouldShowLock())
        // Back before the timeout: never locked, never covered.
        l.onBackground()
        clock += timeoutMs - 1
        assertFalse(l.wouldShowLock())
        l.onForeground()
        assertFalse(l.wouldShowLock())
    }

    @Test
    fun aDateSetBackCoversAtOnceAndOneSetForwardDoesNot() = runTest {
        val l = unlocked()
        l.onBackground()
        clock = 1_000
        wallSkew = 10 * timeoutMs // forward: the monotonic time decides
        assertFalse(l.wouldShowLock())
        wallSkew = -3_600_000
        assertTrue("set back", l.wouldShowLock())
        assertEquals(AppLockState.UNLOCKED, l.state.value)
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun aPromptInProgressDoesNotCover() = runTest {
        val l = unlocked()
        l.onBackground()
        clock = timeoutMs * 2
        l.authenticating = true
        assertFalse(l.wouldShowLock())
    }

    @Test
    fun theFlowStartsCoveredAndTurnsTrueWhenTheTimeoutElapsesInTheBackground() = runTest {
        val l = unlocked()
        val covered = l.coverFlow(backgroundScope)
        assertTrue("covered until the state is read", covered.value)
        runCurrent()
        assertFalse(covered.value)
        l.onBackground()
        runCurrent()
        assertFalse(covered.value)
        advanceTimeBy(timeoutMs - 1)
        runCurrent()
        assertFalse("not yet", covered.value)
        clock = timeoutMs
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("the timer fired", covered.value)
    }

    @Test
    fun aTimerThatFiresEarlyLooksAgainInsteadOfBeingLost() = runTest {
        val l = unlocked()
        val covered = l.coverFlow(backgroundScope)
        runCurrent()
        l.onBackground() // at clock 0
        runCurrent()
        // The monotonic timer elapses but the wall clock was set back: not covered yet.
        advanceTimeBy(timeoutMs + 500)
        runCurrent()
        assertFalse(covered.value)
        clock = timeoutMs
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue("it looked again", covered.value)
    }

    @Test
    fun theFlowTurnsTrueAtOnceWhenTheClockIsSetBackAndTheSystemSaysSo() = runTest {
        val l = unlocked()
        val covered = l.coverFlow(backgroundScope)
        runCurrent()
        l.onBackground()
        runCurrent()
        assertFalse(covered.value)
        clock = 1_000
        wallSkew = -3_600_000
        l.onClockChanged() // ACTION_TIME_CHANGED
        runCurrent() // no virtual time passes: not the timer
        assertTrue(covered.value)
    }

    @Test
    fun theTimerRunsOnTheMonotonicClockNotTheWallClock() = runTest {
        val l = unlocked()
        val covered = l.coverFlow(backgroundScope)
        runCurrent()
        l.onBackground()
        runCurrent()
        wallSkew = 100 * timeoutMs // a date set forward does not bring the timer forward
        advanceTimeBy(timeoutMs - 1)
        runCurrent()
        assertFalse(covered.value)
        clock = timeoutMs - 1
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse("1 ms short on the monotonic clock: it looked again and found no cover", covered.value)
        clock = timeoutMs
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(covered.value)
    }

    @Test
    fun aPromptInProgressAtTheTimerIsLookedAtAgain() = runTest {
        val l = unlocked()
        val covered = l.coverFlow(backgroundScope)
        runCurrent()
        l.onBackground()
        runCurrent()
        l.authenticating = true
        clock = timeoutMs
        advanceTimeBy(timeoutMs + 500)
        runCurrent()
        assertFalse(covered.value)
        l.authenticating = false
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(covered.value)
    }

    @Test
    fun theRetryBacksOffAndNeverWaitsMoreThanSixtySeconds() = runTest {
        val l = unlocked()
        val covered = l.coverFlow(backgroundScope)
        runCurrent()
        l.onBackground()
        runCurrent()
        l.authenticating = true
        clock = timeoutMs
        advanceTimeBy(timeoutMs + 200_000) // looks at 1, 3, 7, 15, 31, 63, 123, 183 s after the timer: all false
        runCurrent()
        l.authenticating = false
        advanceTimeBy(1_500)
        runCurrent()
        assertFalse("backed off: not looking every second", covered.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue("the cap: a look within 60 s", covered.value)
    }

    @Test
    fun theCoverHasNoGapWhenTheAppComesBackAfterTheTimeout() = runTest {
        val l = unlocked()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val covered = l.coverFlow(scope)
        val seen = mutableListOf<Boolean>()
        val job = scope.launch { covered.collect { seen += it } }
        l.onBackground()
        clock = timeoutMs
        advanceTimeBy(timeoutMs + 500)
        runCurrent()
        assertTrue(covered.value)
        seen.clear()
        l.onForeground()
        runCurrent()
        assertEquals(AppLockState.LOCKED, l.state.value)
        assertFalse("never uncovered on the way: $seen", seen.contains(false))
        job.cancel()
        scope.cancel()
    }

    @Test
    fun comingBackBeforeTheTimeoutCancelsTheTimer() = runTest {
        val l = unlocked()
        val covered = l.coverFlow(backgroundScope)
        runCurrent()
        l.onBackground()
        runCurrent()
        advanceTimeBy(timeoutMs / 2)
        clock = timeoutMs / 2
        l.onForeground()
        runCurrent()
        advanceTimeBy(timeoutMs * 2)
        runCurrent()
        assertFalse(covered.value)
    }

    @Test
    fun theFlowFollowsTheLockedStateAndTheLockBeingTurnedOff() = runTest {
        val l = unlocked()
        val covered = l.coverFlow(backgroundScope)
        runCurrent()
        l.lockNow()
        runCurrent()
        assertTrue(covered.value)
        assertTrue(l.completeUnlock(l.cipherToUnlock()!!))
        runCurrent()
        assertFalse(covered.value)
        l.onBackground()
        runCurrent()
        l.disable()
        advanceTimeBy(timeoutMs * 2)
        runCurrent()
        assertFalse("off: never covered", covered.value)
    }
}
