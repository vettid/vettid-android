package com.vettid.core.notify

import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.data.feed.FeedManager
import com.vettid.core.data.vault.RecoveryRefs
import com.vettid.core.vault.FeedItem

/**
 * Which feed items notify, and on which channel (ANDROID-PLAN 0.1.23, Notification modes 7). Feed items are the one
 * source of notifications, so read state stays shared: a notification's tag is its item's `item_id`. Items of
 * priority `low` and `guide` items never notify; a kind this app does not know notifies on Vault activity from
 * priority `normal` up.
 */
object NotificationMapping {
    /** (VAULT-MESSAGING 0.32.0 §10.5.1) A photo that failed or was refused is about a message too. */
    private val MESSAGES = setOf("message.received", "message.attachment_failed", "message.attachment_refused")
    private val REQUESTS = setOf(
        "connection.request", "connection.authenticate.requested", "grant.request", "share.pending",
        "critical-secret.use.request", "device.pair.pending", "device.session.pending", "approval.pending",
        "action.request", "intro.request", "location.request",
    )
    private val CALLS = setOf("call.missed")
    private val SECURITY = setOf(
        "credential.alarm", "owner_check.locked", "owner_check.failed", "owner_check.hold_changed",
        "credential.password_failed", "credential.reset", "device.replaced", "device.transferred",
        "device.signer_changed",
    )

    /** The channel [item] notifies on; null when it does not notify. */
    fun channel(item: FeedItem): String? = when {
        item.priority == FeedManager.PRIORITY_LOW || item.kind == "guide" -> null
        // ANDROID-PLAN 0.1.52 §4 "Event titles": one notification per recovery; a PIN change is History only.
        FeedKinds.historyOnly(item.kind) -> null
        item.kind in MESSAGES -> Channels.MESSAGES
        item.kind in REQUESTS -> Channels.REQUESTS
        item.kind in CALLS -> Channels.CALLS
        item.kind in SECURITY || urgentRecovery(item) -> Channels.SECURITY
        // Connections, grants, shares, items, devices, the credential renewed, agents, location, wallet; and
        // a newer vault's kinds (priority normal and above, never low: above).
        else -> Channels.ACTIVITY
    }

    /**
     * The urgent recovery notifications (ANDROID-PLAN 0.1.52 §4 "Event titles", owner decision of 2026-10-19): a failed
     * `recovery.attempt` (urgent and `not_success`, or urgent with a `ref` that does not read), `recovery.counter_unavailable`
     * and `recovery.change_required`. A successful or not urgent attempt stays on Vault activity.
     */
    private fun urgentRecovery(item: FeedItem): Boolean = when (item.kind) {
        "recovery.counter_unavailable", "recovery.change_required" -> true
        "recovery.attempt" -> item.priority == FeedManager.PRIORITY_URGENT && RecoveryRefs.attempt(item.ref)?.success != true
        // VAULT-MESSAGING 0.50.0 (owner decision of 2026-10-20): a host served an older recovery record.
        RecoveryRefs.KIND_CLOSED -> RecoveryRefs.rollback(item.kind, item.ref)
        else -> false
    }

    /** Kinds whose screens come later (§6): the notification says to open a newer VettID. */
    fun needsNewerApp(item: FeedItem): Boolean = item.kind in REQUESTS && FeedKinds.notAvailable(item.kind)

    /** Security and Daily check say "VettID needs your attention" on the lock screen; the others "New activity". */
    fun urgentChannel(channel: String): Boolean = channel == Channels.SECURITY || channel == Channels.OWNER_CHECK
}
