package com.vettid.core.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** What a process that died with the vault open left showing is cancelled; the vault's state notices and others stay. */
@RunWith(RobolectricTestRunner::class)
class PosterLeftoversTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val nm = app.getSystemService(NotificationManager::class.java)

    private fun note(channel: String, title: String, ongoing: Boolean = false): Notification =
        NotificationCompat.Builder(app, channel).setSmallIcon(R.drawable.ic_stat_vettid).setContentTitle(title).setOngoing(ongoing).build()

    @Before
    fun channels() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Channels.ensure(app)
    }

    @Test
    fun itemNotificationsGoAndTheRestStays() {
        nm.notify("f1", AndroidPoster.NOTIFICATION_ID, note(Channels.MESSAGES, "New message from Sam Lee"))
        nm.notify("f2", AndroidPoster.NOTIFICATION_ID, note(Channels.REQUESTS, "Request"))
        nm.notify(FeedNotifier.TAG_LOCKED, AndroidPoster.NOTIFICATION_ID, note(Channels.SECURITY, "Your vault is locked"))
        nm.notify(FeedNotifier.TAG_CHECK, AndroidPoster.NOTIFICATION_ID, note(Channels.OWNER_CHECK, "Daily check due"))
        nm.notify(VaultConnectionService.NOTIFICATION_ID, note(Channels.CONNECTION, "VettID is connecting", ongoing = true))
        nm.notify("svc", AndroidPoster.NOTIFICATION_ID, note(Channels.CONNECTION, "ongoing with our id", ongoing = true))
        nm.notify(1001, note(Channels.VAULT_UPDATES, "Vault update available"))

        AndroidPoster(app).cancelLeftovers(setOf(FeedNotifier.TAG_LOCKED, FeedNotifier.TAG_CHECK, FeedNotifier.TAG_ALARM))

        val left = nm.activeNotifications.map { it.tag to it.id }.toSet()
        assertEquals(
            setOf(
                FeedNotifier.TAG_LOCKED to AndroidPoster.NOTIFICATION_ID,
                FeedNotifier.TAG_CHECK to AndroidPoster.NOTIFICATION_ID,
                "svc" to AndroidPoster.NOTIFICATION_ID,
                null to VaultConnectionService.NOTIFICATION_ID,
                null to 1001,
            ),
            left,
        )
    }
}
