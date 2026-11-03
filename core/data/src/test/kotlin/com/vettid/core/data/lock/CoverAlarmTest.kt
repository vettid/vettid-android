package com.vettid.core.data.lock

import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The alarm at the cover's deadline (owner decision 2026-11-02): set at background, gone on return, off or lock. */
class CoverAlarmTest {
    private class FakeAlarm : CoverAlarm {
        var at: Long? = null
        val sets = mutableListOf<Long>()
        var cancels = 0

        override fun set(atElapsedMs: Long) {
            at = atElapsedMs
            sets += atElapsedMs
        }

        override fun cancel() {
            at = null
            cancels++
        }
    }

    private var clock = 1_000_000L // the monotonic clock
    private var wallSkew = 0L
    private val alarm = FakeAlarm()
    private val minute = 60_000L

    private fun lock() =
        AppLock(InMemoryPreferencesRepository(), SoftKeys(), MemFile(), alarm, now = { clock + wallSkew }, elapsed = { clock })

    private suspend fun unlocked(timeout: AppLockTimeout = AppLockTimeout.ONE_MINUTE): AppLock = lock().also {
        it.start()
        it.completeEnable(it.cipherToEnable())
        it.setTimeout(timeout)
    }

    @Test
    fun armedAtBackgroundForTheDeadline() = runTest {
        val l = unlocked()
        assertEquals(null, alarm.at)
        l.onBackground()
        assertEquals(clock + minute, alarm.at)
    }

    @Test
    fun theDeadlineIsMonotonicAndNoDateMovesIt() = runTest {
        val l = unlocked()
        wallSkew = 5_000_000L // the wall clock is far from the monotonic one
        l.onBackground()
        val deadline = clock + minute
        assertEquals(deadline, alarm.at)
        wallSkew = -5_000_000L // set back, set forward: the alarm is not touched
        l.setTimeout(AppLockTimeout.ONE_MINUTE)
        assertEquals(deadline, alarm.at)
    }

    @Test
    fun cancelledOnReturnAndOnLockingAndOnDisable() = runTest {
        val l = unlocked()
        l.onBackground()
        assertTrue(alarm.at != null)
        clock += minute / 2
        l.onForeground()
        assertEquals(null, alarm.at)
        l.onBackground()
        assertTrue(alarm.at != null)
        l.lockNow()
        assertEquals("locked: the cover is up already", null, alarm.at)
        val l2 = unlocked()
        l2.onBackground()
        assertTrue(alarm.at != null)
        l2.disable()
        assertEquals(null, alarm.at)
    }

    @Test
    fun reArmedWhenTheTimeoutChanges() = runTest {
        val l = unlocked()
        l.onBackground()
        val first = clock
        l.setTimeout(AppLockTimeout.FIVE_MINUTES)
        assertEquals(first + 5 * minute, alarm.at)
        l.setTimeout(AppLockTimeout.IMMEDIATELY)
        assertEquals("immediately: no alarm", null, alarm.at)
    }

    @Test
    fun notArmedForImmediatelyOrWhenTheLockIsOffOrAPromptIsShowing() = runTest {
        val immediate = unlocked(AppLockTimeout.IMMEDIATELY)
        immediate.onBackground()
        assertTrue(alarm.sets.isEmpty())
        val off = lock()
        off.start()
        off.onBackground()
        assertTrue(alarm.sets.isEmpty())
        val prompt = unlocked()
        prompt.authenticating = true
        prompt.onBackground()
        prompt.onBackground(changingConfigurations = true)
        assertTrue(alarm.sets.isEmpty())
    }

    @Test
    fun anAlarmThatFailsBreaksNothing() = runTest {
        val failing = object : CoverAlarm {
            override fun set(atElapsedMs: Long) = throw SecurityException("no")

            override fun cancel() = throw SecurityException("no")
        }
        val l = AppLock(InMemoryPreferencesRepository(), SoftKeys(), MemFile(), failing, now = { clock }, elapsed = { clock })
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.onBackground()
        l.onForeground()
        assertEquals(AppLockState.UNLOCKED, l.state.value)
    }
}
