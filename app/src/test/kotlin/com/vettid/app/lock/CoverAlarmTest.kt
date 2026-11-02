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

/** The cover's alarm: an inexact RTC alarm with an immutable, explicit, empty broadcast; the receiver asks the notifier. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoverAlarmTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Test
    fun setIsInexactRtcImmutableExplicitAndCarriesNothing() {
        AndroidCoverAlarm(context).set(1_234_567L)
        val alarm = manager.nextScheduledAlarm!!
        assertEquals(AlarmManager.RTC, alarm.type)
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
