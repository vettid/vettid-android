package com.vettid.core.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.net.Uri
import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * VettID's notification sounds (owner decision 2026-10-23): each channel's sound and audio usage, the ids that
 * changed once (`<id>_v2`) with the old ones deleted at start, and the silent Activity and Connection channels.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelSoundsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val nm = app.getSystemService(NotificationManager::class.java)

    private fun uri(res: Int) = Uri.parse("android.resource://${app.packageName}/$res")

    private fun channel(id: String): NotificationChannel = requireNotNull(nm.getNotificationChannel(id)) { "no channel $id" }

    @Test
    fun eachChannelHasItsSoundAndUsage() {
        Channels.ensure(app)
        val expected = mapOf(
            Channels.MESSAGES to (R.raw.vettid_message to AudioAttributes.USAGE_NOTIFICATION),
            Channels.REQUESTS to (R.raw.vettid_urgent to AudioAttributes.USAGE_NOTIFICATION),
            Channels.SECURITY to (R.raw.vettid_urgent to AudioAttributes.USAGE_NOTIFICATION),
            Channels.OWNER_CHECK to (R.raw.vettid_urgent to AudioAttributes.USAGE_NOTIFICATION),
            Channels.CALLS to (R.raw.vettid_ringtone to AudioAttributes.USAGE_NOTIFICATION_RINGTONE),
            Channels.MISSED_CALLS to (R.raw.vettid_message to AudioAttributes.USAGE_NOTIFICATION),
        )
        expected.forEach { (id, sound) ->
            val c = channel(id)
            assertEquals(id, uri(sound.first), c.sound)
            assertEquals(id, sound.second, c.audioAttributes.usage)
            assertEquals(id, AudioAttributes.CONTENT_TYPE_SONIFICATION, c.audioAttributes.contentType)
        }
    }

    /** Owner decision 2026-10-23: a missed call has its own channel with the message sound, not the ringtone. */
    @Test
    fun aMissedCallGoesToItsOwnChannel() {
        Channels.ensure(app)
        assertEquals("missed_calls_v2", Channels.MISSED_CALLS)
        val item = FeedItem("i", 1, "call.missed", "2026-10-23T12:00:00Z", "active", "normal")
        assertEquals(Channels.MISSED_CALLS, NotificationMapping.channel(item))
        val c = channel(Channels.MISSED_CALLS)
        assertEquals(uri(R.raw.vettid_message), c.sound)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, c.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, c.lockscreenVisibility)
        assertEquals(app.getString(R.string.notify_channel_missed_calls), c.name)
    }

    /** Spec review A2: ringing in vibrate mode buzzes. */
    @Test
    fun callsVibrates() {
        Channels.ensure(app)
        assertTrue(channel(Channels.CALLS).shouldVibrate())
    }

    @Test
    fun vaultUpdatesTakesTheGeneralSound() {
        val c = NotificationChannel(Channels.VAULT_UPDATES, "Vault updates", NotificationManager.IMPORTANCE_DEFAULT)
        Channels.applySound(app, c)
        assertEquals(uri(R.raw.vettid_general), c.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, c.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, c.audioAttributes.contentType)
    }

    @Test
    fun theSoundsAreVettIdsRawResources() {
        val names = Channels.SOUNDS.values.map { app.resources.getResourceEntryName(it) }.toSet()
        assertEquals(setOf("vettid_general", "vettid_message", "vettid_urgent", "vettid_ringtone"), names)
        Channels.SOUNDS.values.forEach { app.resources.openRawResource(it).use { s -> assertTrue(s.read() >= 0) } }
    }

    @Test
    fun activityAndConnectionStaySilentWithTheirIds() {
        Channels.ensure(app)
        assertEquals("activity", Channels.ACTIVITY)
        assertEquals("connection", Channels.CONNECTION)
        listOf(Channels.ACTIVITY, Channels.CONNECTION).forEach { assertNull(it, channel(it).sound) }
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel(Channels.ACTIVITY).importance)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel(Channels.CONNECTION).importance)
        assertFalse(channel(Channels.CONNECTION).canShowBadge())
    }

    @Test
    fun theOldIdsAreDeletedAtStart() {
        val old = listOf("messages", "requests", "calls", "security", "owner_check", "vault_updates")
        assertEquals(old, Channels.RETIRED)
        nm.createNotificationChannels(
            (old + Channels.ACTIVITY).map { NotificationChannel(it, it, NotificationManager.IMPORTANCE_HIGH) },
        )
        Channels.ensure(app)
        val left = nm.notificationChannels.map { it.id }.toSet()
        old.forEach { assertFalse("$it is deleted", it in left) }
        assertEquals(Channels.ids.toSet(), left)
    }

    @Test
    fun theNewIdsAreTheOldOnesWithV2() {
        val changed = listOf(
            Channels.MESSAGES, Channels.REQUESTS, Channels.CALLS, Channels.SECURITY, Channels.OWNER_CHECK, Channels.VAULT_UPDATES,
        )
        assertEquals(Channels.RETIRED.map { it + "_v2" }, changed)
        assertEquals(changed.toSet() + Channels.MISSED_CALLS, Channels.SOUNDS.keys)
    }

    @Test
    fun namesImportanceAndVisibilityAreKept() {
        Channels.ensure(app)
        val loud = listOf(
            Channels.MESSAGES, Channels.REQUESTS, Channels.CALLS, Channels.MISSED_CALLS, Channels.SECURITY, Channels.OWNER_CHECK,
        )
        loud.forEach {
            val c = channel(it)
            assertEquals(it, NotificationManager.IMPORTANCE_HIGH, c.importance)
            assertEquals(it, Notification.VISIBILITY_PRIVATE, c.lockscreenVisibility)
        }
        assertEquals(app.getString(R.string.notify_channel_messages), channel(Channels.MESSAGES).name)
        assertEquals(app.getString(R.string.notify_channel_calls_body), channel(Channels.CALLS).description)
    }
}
