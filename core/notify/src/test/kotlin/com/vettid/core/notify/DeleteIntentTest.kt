package com.vettid.core.notify

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** A notification's delete intent: a dismissal reaches the app, and only this app, with nothing but the tag. */
@RunWith(RobolectricTestRunner::class)
class DeleteIntentTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val nm = app.getSystemService(NotificationManager::class.java)

    @Before
    fun permission() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun theDeleteIntentIsAnImmutableBroadcastToThisAppWithOnlyTheTag() {
        AndroidPoster(app).post(
            LocalNotification("f1", Channels.MESSAGES, "New message from Sam Lee", publicTitle = "New activity in VettID", itemId = "f1"),
        )
        val pi = nm.activeNotifications.single { it.tag == "f1" }.notification.deleteIntent
        assertNotNull(pi)
        val s = shadowOf(pi)
        assertTrue(s.isBroadcastIntent)
        assertTrue("immutable", s.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = s.savedIntent
        assertEquals(NotifyIntents.ACTION_DISMISSED, intent.action)
        assertEquals("explicit to this app", app.packageName, intent.`package`)
        assertEquals("f1", intent.getStringExtra(NotifyIntents.EXTRA_TAG))
        assertEquals(setOf(NotifyIntents.EXTRA_TAG), intent.extras!!.keySet())
    }

    @Test
    fun eachTagHasItsOwnPendingIntent() {
        val a = shadowOf(NotifyIntents.dismissed(app, "f1")).savedIntent
        val b = shadowOf(NotifyIntents.dismissed(app, "f2")).savedIntent
        assertNotEquals(a.data, b.data)
    }
}
