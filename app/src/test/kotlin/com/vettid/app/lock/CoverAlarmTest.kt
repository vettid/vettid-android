package com.vettid.app.lock

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The cover's alarm: an inexact, non-waking ELAPSED_REALTIME alarm with an immutable, explicit, empty broadcast; the receiver asks the notifier. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoverAlarmTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Test
    fun setIsInexactElapsedRealtimeImmutableExplicitAndCarriesNothing() {
        AndroidCoverAlarm(context).set(1_234_567L)
        val alarm = manager.nextScheduledAlarm!!
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarm.type)
        assertEquals(1_234_567L, alarm.triggerAtMs)
        assertEquals("not exact, not while idle", 0L, alarm.interval)
        val s = shadowOf(alarm.operation)
        assertTrue(s.isBroadcastIntent)
        assertTrue("immutable", s.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = s.savedIntent
        assertEquals(CoverAlarmReceiver::class.java.name, intent.component!!.className)
        assertEquals(context.packageName, intent.component!!.packageName)
        assertEquals(CoverAlarmReceiver.ACTION, intent.action)
        assertNull(intent.extras)
    }

    @Test
    fun aLongWaitUsesAWindowOfTenMinutesAndAShortOneDoesNot() {
        val now = 1_000_000L
        val a = AndroidCoverAlarm(context) { now }
        a.set(now + 15 * 60_000L)
        val long = manager.nextScheduledAlarm!!
        assertEquals(AlarmManager.ELAPSED_REALTIME, long.type)
        assertEquals(now + 15 * 60_000L, long.triggerAtMs)
        assertEquals("a window of 10 minutes", 10 * 60_000L, long.windowLengthMs)
        a.set(now + 15 * 60_000L - 1)
        val short = manager.nextScheduledAlarm!!
        assertEquals(now + 15 * 60_000L - 1, short.triggerAtMs)
        assertTrue("no window", short.windowLengthMs <= 0L)
        // The window alarm is immutable and explicit as well.
        a.set(now + 60 * 60_000L)
        val s = shadowOf(manager.nextScheduledAlarm!!.operation)
        assertTrue(s.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(CoverAlarmReceiver::class.java.name, s.savedIntent.component!!.className)
    }

    @Test
    fun theWaitIsMeasuredOnTheMonotonicClockAndTheTypeDoesNotWake() {
        // The wait is deadline - elapsed: 14 min 59 s is a plain set, 15 min a window; the type is never a *_WAKEUP.
        val elapsed = 5_000_000L
        val a = AndroidCoverAlarm(context) { elapsed }
        a.set(elapsed + 15 * 60_000L)
        val alarm = manager.nextScheduledAlarm!!
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarm.type)
        assertEquals(elapsed + 15 * 60_000L, alarm.triggerAtMs)
        assertEquals(10 * 60_000L, alarm.windowLengthMs)
        a.set(elapsed + 5 * 60_000L)
        val short = manager.nextScheduledAlarm!!
        assertEquals(AlarmManager.ELAPSED_REALTIME, short.type)
        assertEquals(elapsed + 5 * 60_000L, short.triggerAtMs)
    }

    @Test
    fun theClockChangedReceiverReactsOnlyToTimeChanged() {
        var n = 0
        val r = ClockChangedReceiver { n++ }
        r.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        assertEquals(1, n)
        r.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        r.onReceive(context, Intent("something.else"))
        assertEquals(1, n)
    }

    @Test
    fun cancelAndSetAgainLeaveOneAlarm() {
        val a = AndroidCoverAlarm(context)
        a.set(1_000L)
        a.set(2_000L)
        assertEquals(1, manager.scheduledAlarms.size)
        assertEquals(2_000L, manager.nextScheduledAlarm!!.triggerAtMs)
        a.cancel()
        assertNull(manager.nextScheduledAlarm)
    }

    @Test
    fun theReceiverAsksTheNotifierOnlyForItsOwnAction() {
        var asked = 0
        CoverAlarmReceiver.handle(Intent(CoverAlarmReceiver.ACTION)) { asked++ }
        assertEquals(1, asked)
        CoverAlarmReceiver.handle(Intent("something.else")) { asked++ }
        assertEquals(1, asked)
        assertNotNull(CoverAlarmReceiver.ACTION)
        assertFalse(CoverAlarmReceiver.ACTION.isEmpty())
    }
}
