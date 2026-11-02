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
    private var clock = 0L
    private val prefs = InMemoryPreferencesRepository()
    private val keys = SoftKeys()
    private val timeoutMs = AppLockTimeout.DEFAULT.seconds * 1000L

    private fun lock() = AppLock(prefs, keys, MemFile()) { clock }

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
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(covered.value)
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
