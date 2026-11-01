// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.notify

import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which feed items notify, on which channel (ANDROID-PLAN 0.1.23, Notification modes 7 and 11). */
class NotificationMappingTest {
    private fun item(kind: String, priority: String = "normal") = FeedItem("i", 1, kind, "2026-10-09T12:00:00Z", "active", priority)

    private val expected = mapOf(
        Channels.MESSAGES to listOf("message.received", "message.attachment_failed", "message.attachment_refused"),
        Channels.REQUESTS to listOf(
            "connection.request", "connection.authenticate.requested", "grant.request", "share.pending", "critical-secret.use.request",
            "device.pair.pending", "device.session.pending", "approval.pending", "action.request", "intro.request", "location.request",
        ),
        Channels.MISSED_CALLS to listOf("call.missed"),
        Channels.SECURITY to listOf(
            "credential.alarm", "owner_check.locked", "owner_check.failed", "owner_check.hold_changed", "credential.password_failed",
            "credential.reset", "device.replaced", "device.transferred", "device.signer_changed",
            // ANDROID-PLAN 0.1.52 (owner decision of 2026-10-19): the urgent recovery notifications.
            "recovery.change_required", "recovery.counter_unavailable",
            // VAULT-MESSAGING 0.50.0 (owner decision of 2026-10-20): a `rollback` closure's urgent item.
            "recovery.generation_closed",
        ),
        Channels.ACTIVITY to listOf(
            "connection.added", "connection.request.peer_declined", "connection.asks_paused", "connection.stale", "connection.removed",
            "grant.shared", "grant.revoked", "share.rate_limited", "item.revealed", "device.paired", "device.unlinked", "credential.rotated",
            "leash.rate_limited", "leash.item.read", "leash.agent.suspended", "leash.referrals_limited", "location.shared", "wallet.signed",
            // ANDROID-PLAN 0.1.52 §4 "Event titles": the release R kinds, on Vault activity as every other kind.
            "device.secret_renewed", "recovery.completed", "recovery.attempt",
            "identity.rotation_failed", "identity.rotate_conflict", "leash.item.run", "leash.item.proxy",
            // ANDROID-PLAN 0.1.102 "Votes": "The channel is Vault activity; no new channel."
            "vote.open", "vote.closing", "vote.result", "vote.problem",
            // ANDROID-PLAN 0.1.74 "Notifications": a spending limit's items, on Vault activity.
            "wallet.cap_refused", "wallet.caps_lowered", "wallet.caps_raised",
        ),
    )

    @Test
    fun everyKindOfTheSpecHasItsChannel() {
        val mapped = expected.values.flatten().toSet()
        assertEquals("every §10.9 kind but guide and the History-only ones is mapped", FeedKinds.KNOWN - "guide" - FeedKinds.HISTORY_ONLY, mapped)
        expected.forEach { (channel, kinds) -> kinds.forEach { assertEquals(it, channel, NotificationMapping.channel(item(it))) } }
    }

    /** ANDROID-PLAN 0.1.52 §4 "Event titles": one recovery raises one notification; a PIN change is History only. */
    @Test
    fun theHistoryOnlyKindsNeverNotify() {
        for (k in listOf("credential.recovered", "recovery.device_paired", "vault.pin_changed")) {
            assertNull(k, NotificationMapping.channel(item(k)))
            assertNull(k, NotificationMapping.channel(item(k, priority = "urgent")))
        }
        assertEquals(Channels.ACTIVITY, NotificationMapping.channel(item("recovery.completed")))
    }

    /** Owner decision of 2026-10-19: a failed recovery attempt alarms on Security; a success or the member's own does not. */
    @Test
    fun aFailedRecoveryAttemptNotifiesOnSecurityAlarms() {
        fun attempt(ref: String?, priority: String) = item("recovery.attempt", priority).copy(ref = ref)
        assertEquals(Channels.SECURITY, NotificationMapping.channel(attempt("2/3/not_success/counter", "urgent")))
        assertEquals(Channels.SECURITY, NotificationMapping.channel(attempt("garbled", "urgent")))
        assertEquals(Channels.SECURITY, NotificationMapping.channel(attempt(null, "urgent")))
        assertEquals(Channels.ACTIVITY, NotificationMapping.channel(attempt("2/4/success/counter", "urgent")))
        assertEquals(Channels.ACTIVITY, NotificationMapping.channel(attempt("2/3/not_success/counter", "normal")))
        assertEquals(Channels.ACTIVITY, NotificationMapping.channel(attempt(null, "normal")))
        for (ref in listOf("password", "3")) {
            assertEquals(Channels.SECURITY, NotificationMapping.channel(item("recovery.change_required", "high").copy(ref = ref)))
        }
        assertEquals(Channels.SECURITY, NotificationMapping.channel(item("recovery.counter_unavailable", "urgent")))
        assertEquals(Channels.ACTIVITY, NotificationMapping.channel(item("identity.rotation_failed", "high")))
        // VAULT-MESSAGING 0.50.0 (owner decision of 2026-10-20): a host served an older recovery record.
        assertEquals(Channels.SECURITY, NotificationMapping.channel(item("recovery.generation_closed", "urgent").copy(ref = "4/rollback")))

    }

    @Test
    fun lowPriorityAndGuidesNeverNotifyAndUnknownKindsAreActivity() {
        assertNull(NotificationMapping.channel(item("guide")))
        assertNull(NotificationMapping.channel(item("message.received", priority = "low")))
        assertEquals(Channels.ACTIVITY, NotificationMapping.channel(item("vault.something_new")))
        assertNull(NotificationMapping.channel(item("vault.something_new", priority = "low")))
        assertEquals(Channels.SECURITY, NotificationMapping.channel(item("credential.alarm", priority = "urgent")))
    }
}
