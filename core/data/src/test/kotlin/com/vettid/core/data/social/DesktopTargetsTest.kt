// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.testing.FakeSocial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** ANDROID-PLAN 0.1.145: a desktop's block or removal is approved only while the list still names its connection as shown. */
class DesktopTargetsTest {
    private val now = Instant.parse("2026-11-26T12:00:00Z")

    private fun req(shown: String?, remove: Boolean = false) = Approval.DeviceRequest(
        "approval.pending", "h1", "Laptop", "desktop", if (remove) "connection.remove" else "block.add", now, now.plusSeconds(300),
        deviceId = "desk1", held = HeldRequest.DesktopConnection(remove, "c1", null), heldConnection = shown,
    )

    private val utc = ZoneId.of("UTC")
    private val sam = FakeSocial.connection("c1", "Sam", last = "Lee")

    @Test
    fun namedAsShownIsResolved() {
        for (remove in listOf(false, true)) {
            assertTrue(DesktopTargets.resolved(req("Sam Lee", remove), listOf(sam), utc))
            assertTrue("stale", DesktopTargets.resolved(req("Sam Lee", remove), listOf(sam.copy(state = ConnectionState.STALE)), utc))
        }
    }

    @Test
    fun goneRenamedOrNoLongerActiveIsRefused() {
        for (remove in listOf(false, true)) {
            assertFalse("never shown named", DesktopTargets.resolved(req(null, remove), listOf(sam), utc))
            assertFalse("gone from the list", DesktopTargets.resolved(req("Sam Lee", remove), emptyList(), utc))
            assertFalse("renamed", DesktopTargets.resolved(req("Sam Lee", remove), listOf(sam.copy(lastName = "Roe")), utc))
            assertFalse("now blocked", DesktopTargets.resolved(req("Sam Lee", remove), listOf(sam.copy(state = ConnectionState.BLOCKED)), utc))
            assertFalse("now pending", DesktopTargets.resolved(req("Sam Lee", remove), listOf(sam.copy(state = ConnectionState.PENDING)), utc))
            val nameless = sam.copy(firstName = null, lastName = null)
            assertFalse("its names gone", DesktopTargets.resolved(req("Sam Lee", remove), listOf(nameless), utc))
        }
    }

    @Test
    fun anythingElseIsNotJudgedHere() {
        val other = req(null).copy(held = HeldRequest.Catalog)
        assertTrue(DesktopTargets.resolved(other, emptyList(), utc))
    }

    // --- 0.1.146: two connections with the same name ---

    private val block = HeldRequest.DesktopConnection(false, "c1", null)
    private val nov3 = LocalDate.of(2026, 11, 3)

    private fun on(id: String, first: String?, last: String?, day: Int?, state: ConnectionState = ConnectionState.ACTIVE, service: Boolean = false) =
        FakeSocial.connection(id, first ?: "x", state = state, last = last ?: "").copy(
            firstName = first, lastName = last, service = service, name = if (service) "${first.orEmpty()}" else first.orEmpty(),
            createdAt = day?.let { Instant.parse("2026-11-%02dT10:00:00Z".format(it)) },
        )

    @Test
    fun aUniqueNameShowsNoDate() {
        assertEquals(DesktopTargets.Shown("Sam Lee", null), DesktopTargets.shown(block, listOf(on("c1", "Sam", "Lee", 3), on("c2", "Ada", "Ng", 3)), utc))
    }

    @Test
    fun aNamesakeConnectedAnotherDayShowsEachItsDate() {
        val namesakes = listOf(
            "active" to on("c2", "Sam", "Lee", 5),
            "stale" to on("c2", "Sam", "Lee", 5, ConnectionState.STALE),
            "a service of the same shown name" to on("c2", "Sam Lee", null, 5, service = true),
            "spaces" to on("c2", " Sam ", " Lee", 5),
            "case" to on("c2", "SAM", "lee", 5),
        )
        for ((why, other) in namesakes) {
            assertEquals(why, DesktopTargets.Shown("Sam Lee", nov3), DesktopTargets.shown(block, listOf(on("c1", "Sam", "Lee", 3), other), utc))
            assertEquals(why, nov3.plusDays(2), DesktopTargets.shown(block.copy(connectionId = "c2"), listOf(on("c1", "Sam", "Lee", 3), other), utc)?.connectedOn)
        }
        // Composed and decomposed, and a split between first and last name that shows the same.
        val composed = on("c1", "Zoë", "Ann Lee", 3)
        val decomposed = on("c2", "Zoe\u0308 Ann", "Lee", 5)
        assertEquals(nov3, DesktopTargets.shown(block, listOf(composed, decomposed), utc)?.connectedOn)
    }

    @Test
    fun namesakesTheDateCannotTellApartAreDenyOnly() {
        assertNull("the same day", DesktopTargets.shown(block, listOf(on("c1", "Sam", "Lee", 3), on("c2", "Sam", "Lee", 3)), utc))
        assertNull("its own date unreadable", DesktopTargets.shown(block, listOf(on("c1", "Sam", "Lee", null), on("c2", "Sam", "Lee", 5)), utc))
        assertNull("the namesake's unreadable", DesktopTargets.shown(block, listOf(on("c1", "Sam", "Lee", 3), on("c2", "Sam", "Lee", null)), utc))
        // The same instant can be two days apart in another zone, and one in this one.
        val late = on("c2", "Sam", "Lee", 3).copy(createdAt = Instant.parse("2026-11-03T23:30:00Z"))
        assertNull(DesktopTargets.shown(block, listOf(on("c1", "Sam", "Lee", 3), late), utc))
        assertEquals(nov3, DesktopTargets.shown(block, listOf(on("c1", "Sam", "Lee", 3), late), ZoneId.of("Asia/Tokyo"))?.connectedOn)
        // A connection without its name.
        assertNull(DesktopTargets.shown(block, listOf(on("c1", null, null, 3)), utc))
    }

    @Test
    fun onlyActiveAndStaleNamesakesCount() {
        for (state in listOf(ConnectionState.PENDING, ConnectionState.BLOCKED, ConnectionState.OTHER)) {
            assertEquals("$state", DesktopTargets.Shown("Sam Lee", null), DesktopTargets.shown(block, listOf(on("c1", "Sam", "Lee", 3), on("c2", "Sam", "Lee", 3, state)), utc))
        }
    }

    @Test
    fun aNamesakeThatComesOrGoesAfterTheRowWasShownIsRefused() {
        val unique = req("Sam Lee")
        val dated = req("Sam Lee").copy(heldConnectedOn = nov3)
        val alone = listOf(on("c1", "Sam", "Lee", 3))
        val two = alone + on("c2", "Sam", "Lee", 5)
        assertTrue(DesktopTargets.resolved(unique, alone, utc))
        assertTrue(DesktopTargets.resolved(dated, two, utc))
        assertFalse("a namesake since", DesktopTargets.resolved(unique, two, utc))
        assertFalse("no namesake any more", DesktopTargets.resolved(dated, alone, utc))
    }

    @Test
    fun theKeyCleansCollapsesComposesAndFolds() {
        assertEquals(DesktopTargets.key("Straße Lee"), DesktopTargets.key("STRASSE  lee"))
        assertEquals(DesktopTargets.key("Zoë"), DesktopTargets.key("Zoe\u0308"))
        assertFalse(DesktopTargets.key("Sam Lee") == DesktopTargets.key("Sam Le"))
    }
}
