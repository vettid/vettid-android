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
        Channels.MESSAGES to listOf("message.received"),
        Channels.REQUESTS to listOf(
            "connection.request", "connection.authenticate.requested", "grant.request", "share.pending", "critical-secret.use.request",
            "device.pair.pending", "device.session.pending", "approval.pending", "action.request", "intro.request", "location.request",
        ),
        Channels.CALLS to listOf("call.missed"),
        Channels.SECURITY to listOf(
            "credential.alarm", "owner_check.locked", "owner_check.failed", "owner_check.hold_changed", "credential.password_failed",
            "credential.reset", "device.replaced", "device.transferred",
        ),
        Channels.ACTIVITY to listOf(
            "connection.added", "connection.request.peer_declined", "connection.asks_paused", "connection.stale", "connection.removed",
            "grant.shared", "grant.revoked", "share.rate_limited", "item.revealed", "device.paired", "device.unlinked", "credential.rotated",
            "leash.rate_limited", "leash.item.read", "leash.agent.suspended", "leash.referrals_limited", "location.shared", "wallet.signed",
        ),
    )

    @Test
    fun everyKindOfTheSpecHasItsChannel() {
        val mapped = expected.values.flatten().toSet()
        assertEquals("every §10.9 kind but guide is mapped", FeedKinds.KNOWN - "guide", mapped)
        expected.forEach { (channel, kinds) -> kinds.forEach { assertEquals(it, channel, NotificationMapping.channel(item(it))) } }
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
