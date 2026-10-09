package com.vettid.core.notify

import android.content.res.Resources
import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.prefs.NotificationPreviews
import com.vettid.core.vault.FeedItem

/**
 * A notification as the app builds it: [tag] (an item's `item_id`, or a fixed tag for the vault's state), the
 * [channel], the texts, and the lock screen's [publicTitle]. [names]: it names a connection, a device or an item, so
 * it is cancelled when the vault locks or is held (ANDROID-PLAN 0.1.23, Notification modes 8). [itemId]: a tap opens
 * that item; [settings]: a tap opens Settings → Notifications.
 */
data class LocalNotification(
    val tag: String,
    val channel: String,
    val title: String,
    val text: String? = null,
    val publicTitle: String,
    val itemId: String? = null,
    val names: Boolean = false,
    val alertOnce: Boolean = false,
    val settings: Boolean = false,
)

/** Posts and cancels [LocalNotification]s ([AndroidPoster] on a phone; a list in tests). */
interface Poster {
    fun post(n: LocalNotification)

    fun cancel(tag: String)
}

/**
 * What a feed item's notification says, by the member's choice of **Show in notifications** (ANDROID-PLAN 0.1.23,
 * Notification modes 8): [NotificationPreviews.NAMES] the connection's "First Last" (or the device) and the kind,
 * never message text, item names or values, tags or amounts; [NotificationPreviews.NAMES_AND_TEXT] adds the message
 * text (decrypted on the phone) and item names, never values; [NotificationPreviews.NOTHING] "New activity in VettID".
 * The member's email never appears. Built in memory; nothing of it is stored by the app.
 */
object NotificationContent {
    fun of(
        item: FeedItem,
        channel: String,
        previews: NotificationPreviews,
        names: FeedNames,
        res: Resources,
        messageText: String? = null,
    ): LocalNotification {
        val urgent = NotificationMapping.urgentChannel(channel)
        val public = res.getString(if (urgent) R.string.notify_public_attention else R.string.notify_public_activity)
        val newer = res.getString(R.string.notify_newer_app).takeIf { NotificationMapping.needsNewerApp(item) }
        return when (previews) {
            NotificationPreviews.NOTHING -> LocalNotification(
                item.itemId, channel, res.getString(R.string.notify_public_activity), publicTitle = public, itemId = item.itemId,
                alertOnce = true,
            )
            NotificationPreviews.NAMES -> LocalNotification(
                item.itemId, channel, FeedKinds.title(item, names, details = false).resolve(res), newer,
                public, item.itemId, names = true, alertOnce = true,
            )
            NotificationPreviews.NAMES_AND_TEXT -> LocalNotification(
                item.itemId, channel, FeedKinds.title(item, names).resolve(res),
                messageText?.takeIf { item.kind == "message.received" } ?: newer
                    ?: FeedKinds.supporting(item, names)?.resolve(res),
                public, item.itemId, names = true, alertOnce = true,
            )
        }
    }
}
