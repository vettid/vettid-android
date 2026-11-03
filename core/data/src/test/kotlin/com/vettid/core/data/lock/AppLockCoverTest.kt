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
    fun aDateSetBackAndRestoredIsRememberedAndStillLocksOnReturn() = runTest {
        val l = unlocked()
        l.setTimeout(AppLockTimeout.FIVE_MINUTES)
        val min = 60_000L
        val covered = l.coverFlow(backgroundScope)
        clock = 12 * 60 * min
        l.onBackground() // 12:00
        runCurrent()
        clock += min // 12:01
        wallSkew = -61 * min // the date reads 11:00
        l.onClockChanged() // TIME_CHANGED
        runCurrent()
        assertTrue("cover up", covered.value)
        clock += min // 12:02
        wallSkew = 0 // restored
        l.onClockChanged()
        runCurrent()
        assertTrue("still up", covered.value)
        assertTrue(l.wouldShowLock())
        clock += min // 12:03, under the 5 minute timeout
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
        assertTrue(covered.value)
        // A new background starts clean.
        assertTrue(l.completeUnlock(l.cipherToUnlock()!!))
        l.onBackground()
        assertFalse(l.wouldShowLock())
    }

    @Test
    fun aPromptInProgressDoesNotCover() = runTest {
        val l = unlocked()
        l.onBackground()
        clock = timeoutMs * 2
        l.promptStarted()
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
    fun aDateSetBackWithNoBroadcastIsSeenAtTheDeadline() = runTest {
        val l = unlocked()
        val covered = l.coverFlow(backgroundScope)
        runCurrent()
        l.onBackground()
        runCurrent()
        clock = 1_000
        wallSkew = -3_600_000 // set back, and TIME_CHANGED late or never: no onClockChanged()
        advanceTimeBy(timeoutMs - 1)
        runCurrent()
        assertFalse("nothing looked yet", covered.value)
        advanceTimeBy(2_000) // the timer's look at the monotonic deadline
        runCurrent()
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
        l.promptStarted()
        clock = timeoutMs
        advanceTimeBy(timeoutMs + 500)
        runCurrent()
        assertFalse(covered.value)
        l.promptEnded(PromptOutcome.DISMISSED, inForeground = false)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(covered.value)
    }

    @Test
    fun aLongPromptHoldsTheCoverBackAndItsEndBringsItAtOnce() = runTest {
        val l = unlocked()
        val covered = l.coverFlow(backgroundScope)
        runCurrent()
        l.onBackground()
        runCurrent()
        l.promptStarted()
        clock = timeoutMs
        advanceTimeBy(timeoutMs + 200_000) // looks at 1, 3, 7, 15, 31, 63, 123, 183 s after the timer: all false
        runCurrent()
        assertFalse("held back for the whole wait", covered.value)
        l.promptEnded(PromptOutcome.DISMISSED, inForeground = false)
        runCurrent()
        assertTrue("the end of the prompt decides at once, no waiting for the retry", covered.value)
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
