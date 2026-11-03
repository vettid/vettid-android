// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.feed

import com.vettid.core.data.R
import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANDROID-PLAN 0.1.116 "Notifications and the feed": the labels and targets of the device and agent items (AA5). */
class FeedDeviceItemsTest {
    private fun item(kind: String, device: String? = "d1", ref: String? = "r1") =
        FeedItem("i", 1, kind, "2026-11-02T12:00:00Z", "active", "normal", deviceId = device, ref = ref)

    private val names = FeedNames(devices = mapOf("d1" to "Build bot", "d2" to "‮evil\u0007"), pairingName = "New bot")

    private fun iso(s: String) = FeedText(literal = "⁨$s⁩")

    @Test
    fun theLabelsNameTheDevice() {
        val named = mapOf(
            "device.session.pending" to R.string.data_feed_device_session_named,
            "approval.pending" to R.string.data_feed_device_approval_named,
            "device.paired" to R.string.data_feed_device_paired_named,
            "device.unlinked" to R.string.data_feed_device_unlinked_named,
            "leash.rate_limited" to R.string.data_feed_leash_rate_limited_named,
            "leash.referrals_limited" to R.string.data_feed_leash_referrals_named,
            "leash.agent.suspended" to R.string.data_feed_leash_suspended_named,
        )
        named.forEach { (kind, res) -> assertEquals(kind, FeedText(res, listOf(iso("Build bot"))), FeedKinds.title(item(kind), names)) }
        // The pairing's item carries no name: the held pairing's name while the app has it.
        assertEquals(FeedText(R.string.data_feed_device_pair_pending_named, listOf(iso("New bot"))), FeedKinds.title(item("device.pair.pending", null), names))
        assertEquals(FeedText(R.string.data_feed_device_pair_pending), FeedKinds.title(item("device.pair.pending", null), FeedNames()))
    }

    @Test
    fun withoutAKnownNameTheLabelsSayADeviceOrAnAgent() {
        val any = mapOf(
            "device.session.pending" to R.string.data_feed_device_session,
            "approval.pending" to R.string.data_feed_device_approval,
            "device.paired" to R.string.data_feed_device_paired,
            "leash.agent.suspended" to R.string.data_feed_leash_suspended,
            "leash.rate_limited" to R.string.data_feed_leash_rate_limited,
            "leash.referrals_limited" to R.string.data_feed_leash_referrals,
        )
        any.forEach { (kind, res) ->
            assertEquals(kind, FeedText(res), FeedKinds.title(item(kind, device = "unknown"), names))
            assertEquals(kind, FeedText(res), FeedKinds.title(item(kind, device = null), names))
        }
    }

    @Test
    fun aDeviceNameIsCleanedAndIsolated() {
        val t = FeedKinds.title(item("device.session.pending", device = "d2"), names)
        val s = t.args.single() as FeedText
        assertFalse(s.literal!!.contains('‮') || s.literal!!.contains('\u0007'))
        assertTrue(s.literal!!.startsWith("⁨") && s.literal!!.endsWith("⁩"))
    }

    @Test
    fun theTargetsAreTheRequestTheAgentsPageAndTheComparison() {
        assertEquals(FeedTarget.ApprovalEntry("device:device.session.pending:s1"), FeedKinds.target(item("device.session.pending", ref = "s1")))
        assertEquals(FeedTarget.ApprovalEntry("device:approval.pending:h1"), FeedKinds.target(item("approval.pending", ref = "h1")))
        assertEquals(FeedTarget.Pairing, FeedKinds.target(item("device.pair.pending", null, "p1")))
        for (kind in listOf("leash.agent.suspended", "leash.referrals_limited", "leash.rate_limited")) {
            assertEquals(FeedTarget.Device("d1"), FeedKinds.target(item(kind)))
            assertEquals(FeedTarget.DeviceList, FeedKinds.target(item(kind, device = null)))
            assertFalse("$kind is no longer 'not available'", FeedKinds.notAvailable(kind))
        }
        // The others stay as they were until AA6.
        assertTrue(FeedKinds.notAvailable("leash.item.read"))
        assertEquals(FeedTarget.Sheet, FeedKinds.target(item("leash.item.read")))
    }

    @Test
    fun theDeviceKindsAreAsksButThePairingIsNot() {
        assertTrue(FeedKinds.isAsk("device.session.pending"))
        assertTrue(FeedKinds.isAsk("approval.pending"))
        assertFalse(FeedKinds.isAsk("leash.agent.suspended"))
    }

    @Test
    fun namesComeFromTheDeviceListAndTheHeldPairing() {
        val list = listOf(
            com.vettid.core.data.devices.DeviceInfo("a", "agent", "Bot", null, null, null, null),
            com.vettid.core.data.devices.DeviceInfo("b", "desktop", "", null, null, null, null),
        )
        assertEquals(mapOf("a" to "Bot"), FeedNames.deviceNames(list))
        val pending = com.vettid.core.data.devices.PendingPairing("p", "Held bot", "123456", java.time.Instant.EPOCH)
        val st = com.vettid.core.data.devices.PairingState(com.vettid.core.data.devices.PairingStage.Compare(pending))
        assertEquals("Held bot", FeedNames.pairingName(st))
        assertEquals(null, FeedNames.pairingName(com.vettid.core.data.devices.PairingState()))
    }
}
