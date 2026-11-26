package com.vettid.core.data.social

import com.vettid.core.testing.FakeSocial
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** ANDROID-PLAN 0.1.145: a desktop's block or removal is approved only while the list still names its connection as shown. */
class DesktopTargetsTest {
    private val now = Instant.parse("2026-11-26T12:00:00Z")

    private fun req(shown: String?, remove: Boolean = false) = Approval.DeviceRequest(
        "approval.pending", "h1", "Laptop", "desktop", if (remove) "connection.remove" else "block.add", now, now.plusSeconds(300),
        deviceId = "desk1", held = HeldRequest.DesktopConnection(remove, "c1", null), heldConnection = shown,
    )

    private val sam = FakeSocial.connection("c1", "Sam", last = "Lee")

    @Test
    fun namedAsShownIsResolved() {
        for (remove in listOf(false, true)) {
            assertTrue(DesktopTargets.resolved(req("Sam Lee", remove), listOf(sam)))
            assertTrue("stale", DesktopTargets.resolved(req("Sam Lee", remove), listOf(sam.copy(state = ConnectionState.STALE))))
        }
    }

    @Test
    fun goneRenamedOrNoLongerActiveIsRefused() {
        for (remove in listOf(false, true)) {
            assertFalse("never shown named", DesktopTargets.resolved(req(null, remove), listOf(sam)))
            assertFalse("gone from the list", DesktopTargets.resolved(req("Sam Lee", remove), emptyList()))
            assertFalse("renamed", DesktopTargets.resolved(req("Sam Lee", remove), listOf(sam.copy(lastName = "Roe"))))
            assertFalse("now blocked", DesktopTargets.resolved(req("Sam Lee", remove), listOf(sam.copy(state = ConnectionState.BLOCKED))))
            assertFalse("now pending", DesktopTargets.resolved(req("Sam Lee", remove), listOf(sam.copy(state = ConnectionState.PENDING))))
            val nameless = sam.copy(firstName = null, lastName = null)
            assertFalse("its names gone", DesktopTargets.resolved(req("Sam Lee", remove), listOf(nameless)))
        }
    }

    @Test
    fun anythingElseIsNotJudgedHere() {
        val other = req(null).copy(held = HeldRequest.Catalog)
        assertTrue(DesktopTargets.resolved(other, emptyList()))
    }
}
