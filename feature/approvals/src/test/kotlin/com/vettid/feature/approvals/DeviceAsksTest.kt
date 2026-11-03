// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.items.FieldLabel
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.DataView
import com.vettid.core.data.social.HeldRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** ANDROID-PLAN 0.1.116 §4 AA5, decisions 6, 10, 15 and 18: what a device request shows and allows. */
class DeviceAsksTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")

    private val items = listOf(
        ItemSummary(
            "i1", 1, "Bank PIN", "other", Sensitivity.SECRET,
            fieldLabels = listOf(FieldLabel("f1", "PIN", "password"), FieldLabel("f2", "Hint", "text")),
        ),
    )

    private fun held(h: HeldRequest?, fromFeed: Boolean = false) =
        Approval.DeviceRequest("approval.pending", "h1", "Bot", "agent", "agent.request", now, null, deviceId = "A", held = h, fromFeed = fromFeed)

    @Test
    fun theChoicesAreFixedAndTheRequestedLengthIsAddedAndPreselected() {
        assertEquals(listOf(900, 3_600, 14_400, 28_800, 86_400), DeviceAsks.choices(null))
        assertEquals(DeviceAsks.CHOICES, DeviceAsks.choices(3_600))
        assertEquals(listOf(900, 3_600, 5_400, 14_400, 28_800, 86_400), DeviceAsks.choices(5_400))
        assertEquals(listOf(300, 900, 3_600, 14_400, 28_800, 86_400), DeviceAsks.choices(300))
        assertEquals(5_400, DeviceAsks.preselected(5_400))
        assertEquals(900, DeviceAsks.preselected(900))
        // Unknown length (a feed item) or one the vault would not accept: 1 hour.
        assertEquals(3_600, DeviceAsks.preselected(null))
        assertEquals(3_600, DeviceAsks.preselected(10))
        assertEquals(3_600, DeviceAsks.preselected(100_000))
        assertEquals(DeviceAsks.CHOICES, DeviceAsks.choices(100_000))
    }

    @Test
    fun anItemUseShowsItsItemFieldAndDecodedData() {
        val s = DeviceAsks.show(held(HeldRequest.ItemUse("i1", "f1", "hi".toByteArray())), items) as HeldShow.Use
        assertEquals("Bank PIN", s.item)
        assertEquals("PIN", s.field)
        assertEquals(DataView.Text("hi"), s.data)
        assertTrue(s.approvable)
        val bin = DeviceAsks.show(held(HeldRequest.ItemUse("i1", "f1", byteArrayOf(0, -1))), items) as HeldShow.Use
        assertEquals(DataView.Hex("00 ff"), bin.data)
    }

    @Test
    fun anItemOrFieldThePhoneCannotNameIsDenyOnly() {
        for (h in listOf(
            HeldRequest.ItemUse("gone", "f1", byteArrayOf(1)),
            HeldRequest.ItemUse("i1", "nope", byteArrayOf(1)),
            HeldRequest.ItemGet("gone", null),
            HeldRequest.ItemGet("i1", listOf("f1", "nope")),
        )) {
            val s = DeviceAsks.show(held(h), items)
            assertTrue(h.toString(), s is HeldShow.NoItem)
            assertFalse(s.approvable)
        }
        assertTrue((DeviceAsks.show(held(HeldRequest.ItemUse("gone", "f1", byteArrayOf(1))), items) as HeldShow.NoItem).use)
        assertFalse((DeviceAsks.show(held(HeldRequest.ItemGet("gone", null)), items) as HeldShow.NoItem).use)
        // No cache at all (not loaded): nothing is approved unseen.
        assertFalse(DeviceAsks.show(held(HeldRequest.ItemGet("i1", null)), emptyList()).approvable)
    }

    @Test
    fun anItemGetNamesItsFieldsOrAllOfThem() {
        assertEquals(HeldShow.Get("Bank PIN", listOf("PIN", "Hint")), DeviceAsks.show(held(HeldRequest.ItemGet("i1", listOf("f1", "f2"))), items))
        assertEquals(HeldShow.Get("Bank PIN", null), DeviceAsks.show(held(HeldRequest.ItemGet("i1", null)), items))
    }

    @Test
    fun theRestIsDenyOnlyOrTheCatalog() {
        assertSame(HeldShow.Catalog, DeviceAsks.show(held(HeldRequest.Catalog), items))
        assertTrue(DeviceAsks.show(held(HeldRequest.Catalog), items).approvable)
        assertEquals(HeldShow.Later("Bank PIN"), DeviceAsks.show(held(HeldRequest.Later("i1")), items))
        assertEquals(HeldShow.Later(null), DeviceAsks.show(held(HeldRequest.Later("gone")), items))
        assertSame(HeldShow.Desktop, DeviceAsks.show(held(HeldRequest.DesktopStepUp), items))
        assertSame(HeldShow.Other, DeviceAsks.show(held(HeldRequest.Unshown), items))
        assertSame(HeldShow.Other, DeviceAsks.show(held(null), items))
        // Known only from the feed item: the body is gone.
        assertSame(HeldShow.Gone, DeviceAsks.show(held(null, fromFeed = true), items))
        listOf(HeldShow.Later(null), HeldShow.Desktop, HeldShow.Other, HeldShow.Gone).forEach { assertFalse(it.approvable) }
    }

    @Test
    fun aFeedOnlyRequestTakesItsNameAndKindFromTheDeviceList() {
        val ask = Approval.DeviceRequest("device.session.pending", "s1", null, null, null, now, null, deviceId = "A", fromFeed = true)
        val agent = DeviceInfo("A", "agent", "Build bot", null, null, null, null)
        val named = DeviceAsks.named(ask, listOf(agent)) as Approval.DeviceRequest
        assertEquals("Build bot", named.deviceName)
        assertEquals("agent", named.role)
        assertTrue(DeviceAsks.listed(ask, listOf(agent)))
        assertEquals("desktop", (DeviceAsks.named(ask, listOf(agent.copy(kind = "desktop"))) as Approval.DeviceRequest).role)
        // Not in the list, or this phone's own entry: no name, not listed.
        assertSame(ask, DeviceAsks.named(ask, emptyList()))
        assertFalse(DeviceAsks.listed(ask, emptyList()))
        assertFalse(DeviceAsks.listed(ask, listOf(agent.copy(thisPhone = true))))
        // An ask with its event keeps what the event said.
        val withEvent = ask.copy(fromFeed = false, deviceName = "Event name")
        assertSame(withEvent, DeviceAsks.named(withEvent, listOf(agent)))
        assertNull((DeviceAsks.named(ask, listOf(agent.copy(name = ""))) as Approval.DeviceRequest).deviceName)
    }
}
