package com.vettid.core.notify

import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.data.feed.FeedManager
import com.vettid.core.vault.FeedItem

/**
 * Which feed items notify, and on which channel (ANDROID-PLAN 0.1.23, Notification modes 7). Feed items are the one
 * source of notifications, so read state stays shared: a notification's tag is its item's `item_id`. Items of
 * priority `low` and `guide` items never notify; a kind this app does not know notifies on Vault activity from
 * priority `normal` up.
 */
object NotificationMapping {
    private val MESSAGES = setOf("message.received")
    private val REQUESTS = setOf(
        "connection.request", "connection.authenticate.requested", "grant.request", "share.pending",
        "critical-secret.use.request", "device.pair.pending", "device.session.pending", "approval.pending",
        "action.request", "intro.request", "location.request",
    )
    private val CALLS = setOf("call.missed")
    private val SECURITY = setOf(
        "credential.alarm", "owner_check.locked", "owner_check.failed", "owner_check.hold_changed",
        "credential.password_failed", "credential.reset", "device.replaced", "device.transferred",
    )

    /** The channel [item] notifies on; null when it does not notify. */
    fun channel(item: FeedItem): String? = when {
        item.priority == FeedManager.PRIORITY_LOW || item.kind == "guide" -> null
        item.kind in MESSAGES -> Channels.MESSAGES
        item.kind in REQUESTS -> Channels.REQUESTS
        item.kind in CALLS -> Channels.CALLS
        item.kind in SECURITY -> Channels.SECURITY
        // Connections, grants, shares, items, devices, the credential renewed, agents, location, wallet; and
        // a newer vault's kinds (priority normal and above, never low: above).
        else -> Channels.ACTIVITY
    }

    /** Kinds whose screens come later (§6): the notification says to open a newer VettID. */
    fun needsNewerApp(item: FeedItem): Boolean = item.kind in REQUESTS && FeedKinds.notAvailable(item.kind)

    /** Security and Daily check say "VettID needs your attention" on the lock screen; the others "New activity". */
    fun urgentChannel(channel: String): Boolean = channel == Channels.SECURITY || channel == Channels.OWNER_CHECK
}
