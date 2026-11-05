package com.vettid.core.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Posts [LocalNotification]s (ANDROID-PLAN 0.1.23, Notification modes 8): private on the lock screen with a public
 * version ("New activity in VettID", or "VettID needs your attention" for Security and Daily check); a batch's
 * update alerts once. A tap opens the app ([NotifyIntents]): the item (read, then its target) or Settings →
 * Notifications. Without the notification permission nothing is posted. [cancelLeftovers] takes away what a process
 * that died with the vault open left showing.
 */
class AndroidPoster(private val context: Context) : Poster {
    private val nm = NotificationManagerCompat.from(context)

    private fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            nm.areNotificationsEnabled()

    @android.annotation.SuppressLint("MissingPermission") // checked in allowed()
    override fun post(n: LocalNotification) {
        if (!allowed()) return
        Channels.ensure(context)
        val public = NotificationCompat.Builder(context, n.channel)
            .setSmallIcon(R.drawable.ic_stat_vettid)
            .setContentTitle(n.publicTitle)
            .build()
        val b = NotificationCompat.Builder(context, n.channel)
            .setSmallIcon(R.drawable.ic_stat_vettid)
            .setContentTitle(n.title)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(n.alertOnce)
            .setAutoCancel(true)
            .setCategory(category(n.channel))
            .setContentIntent(NotifyIntents.open(context, n))
            .setDeleteIntent(NotifyIntents.dismissed(context, n.tag))
        // A re-post replaces the notification, so a deadline set before is cleared explicitly unless this one has its own.
        b.setTimeoutAfter(n.closeAt?.let { maxOf(1L, it.toEpochMilli() - System.currentTimeMillis()) } ?: 0L)
        n.openUntil?.let { b.setWhen(it.toEpochMilli()).setShowWhen(true) }
        if (n.silent) b.setSilent(true)
        n.text?.let { b.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        if (n.channel == Channels.SECURITY) b.setPriority(NotificationCompat.PRIORITY_MAX)
        nm.notify(n.tag, NOTIFICATION_ID, b.build())
    }

    override fun isPosted(tag: String): Boolean =
        nm.activeNotifications.any { it.tag == tag && it.id == NOTIFICATION_ID }

    override fun cancel(tag: String) {
        nm.cancel(tag, NOTIFICATION_ID)
    }

    override fun cancelLeftovers(keep: Set<String>) {
        val active = try {
            nm.activeNotifications
        } catch (_: SecurityException) {
            return
        }
        // Only this poster's tagged notifications; never an ongoing one (the service's), nor a state notice.
        active.filter { n ->
            val tag = n.tag
            tag != null && n.id == NOTIFICATION_ID && tag !in keep && n.notification.flags and Notification.FLAG_ONGOING_EVENT == 0
        }.forEach { nm.cancel(it.tag, NOTIFICATION_ID) }
    }

    private fun category(channel: String): String = when (channel) {
        Channels.MESSAGES -> NotificationCompat.CATEGORY_MESSAGE
        Channels.MISSED_CALLS -> NotificationCompat.CATEGORY_MISSED_CALL
        Channels.SECURITY, Channels.OWNER_CHECK -> NotificationCompat.CATEGORY_ALARM
        Channels.REQUESTS -> NotificationCompat.CATEGORY_REMINDER
        else -> NotificationCompat.CATEGORY_STATUS
    }

    companion object {
        /** One id for every tagged notification (the tag tells them apart). */
        const val NOTIFICATION_ID = 2001
    }
}

/**
 * The intents a notification's tap sends to the app's launcher activity, which hands them to the shell: an item
 * ([ACTION_OPEN_ITEM] with [EXTRA_ITEM_ID]; the shell marks it read and opens its target) or Settings → Notifications
 * ([ACTION_OPEN_SETTINGS]).
 */
object NotifyIntents {
    const val ACTION_OPEN_ITEM = "com.vettid.app.action.OPEN_NOTIFICATION"
    const val ACTION_OPEN_SETTINGS = "com.vettid.app.action.OPEN_NOTIFICATION_SETTINGS"
    const val EXTRA_ITEM_ID = "com.vettid.app.extra.ITEM_ID"

    /** The stalled-location notice's tap (A6 N21): the connection's page, where the share card is. */
    const val ACTION_OPEN_CONNECTION = "com.vettid.app.action.OPEN_CONNECTION"
    const val EXTRA_CONNECTION_ID = "com.vettid.app.extra.CONNECTION_ID"

    /** The member dismissed the notification [EXTRA_TAG] (swipe or clear all); a not-exported broadcast to this app only. */
    const val ACTION_DISMISSED = "com.vettid.app.action.NOTIFICATION_DISMISSED"
    const val EXTRA_TAG = "com.vettid.app.extra.NOTIFICATION_TAG"

    /**
     * The delete intent of the notification [tag]: a broadcast to this app's package only, immutable, carrying nothing but
     * the tag. The data URI tells the tags' PendingIntents apart (extras do not).
     */
    fun dismissed(context: Context, tag: String): PendingIntent = PendingIntent.getBroadcast(
        context, tag.hashCode(),
        Intent(ACTION_DISMISSED).setPackage(context.packageName).setData(android.net.Uri.fromParts("vettid", "dismissed", tag))
            .putExtra(EXTRA_TAG, tag),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun open(context: Context, n: LocalNotification): PendingIntent {
        val i = launch(context)
        when {
            n.itemId != null -> i.setAction(ACTION_OPEN_ITEM).putExtra(EXTRA_ITEM_ID, n.itemId)
            n.settings -> i.setAction(ACTION_OPEN_SETTINGS)
            else -> i.setAction(Intent.ACTION_MAIN)
        }
        return PendingIntent.getActivity(context, n.tag.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * The tap on a connection's notice: an explicit intent to the app's own launcher activity, immutable, carrying the
     * connection id (the data URI tells one connection's PendingIntent from another's). Only the notification's own content
     * intent has it; the lock-screen version has none.
     */
    fun connection(context: Context, connectionId: String): PendingIntent = PendingIntent.getActivity(
        context, connectionId.hashCode(),
        launch(context).setAction(ACTION_OPEN_CONNECTION).putExtra(EXTRA_CONNECTION_ID, connectionId)
            .setData(android.net.Uri.fromParts("vettid", "connection", connectionId)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun settings(context: Context): PendingIntent = PendingIntent.getActivity(
        context, ACTION_OPEN_SETTINGS.hashCode(), launch(context).setAction(ACTION_OPEN_SETTINGS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun launch(context: Context): Intent =
        (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
