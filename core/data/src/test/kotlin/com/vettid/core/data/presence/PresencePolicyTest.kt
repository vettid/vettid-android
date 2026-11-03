// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** The policy's rules and `last_active` as the line says it (ANDROID-PLAN §4 "Presence"; VAULT-MESSAGING §10.17). */
class PresencePolicyTest {
    private val all = PresencePolicy(1)
    private val none = PresencePolicy(1, share = PresenceShare.NONE)

    @Test
    fun exceptInvertsTheShare() {
        assertTrue(all.sharesWith("a"))
        assertFalse(all.copy(except = listOf("a")).sharesWith("a"))
        assertTrue(all.copy(except = listOf("a")).sharesWith("b"))
        assertFalse(none.sharesWith("a"))
        assertTrue(none.copy(except = listOf("a")).sharesWith("a"))
    }

    @Test
    fun theSwitchPutsTheConnectionInOrOutOfExceptWhicheverWayTheShareGoes() {
        assertEquals(listOf("a"), all.withSharedWith("a", false).except)
        assertEquals(emptyList<String>(), all.withSharedWith("a", false).withSharedWith("a", true).except)
        assertEquals(listOf("a"), none.withSharedWith("a", true).except)
        assertEquals(emptyList<String>(), none.withSharedWith("a", true).withSharedWith("a", false).except)
        // Already so: the same policy.
        assertEquals(all, all.withSharedWith("a", true))
        assertEquals(none, none.withSharedWith("a", false))
    }

    @Test
    fun changingWhoCanSeeStartsTheExceptionsAgain() {
        val p = all.copy(except = listOf("a", "b"))
        assertEquals(emptyList<String>(), p.withShare(PresenceShare.NONE).except)
        assertEquals(p, p.withShare(PresenceShare.ALL))
    }

    @Test
    fun exceptionsAreDeduplicatedAndSorted() {
        assertEquals(listOf("a", "b", "c"), all.withExcept(listOf("c", "a", "b", "a")).except)
    }

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-03T15:00:00Z")

    private fun age(minutesAgo: Long?) = PresenceAge.of(minutesAgo?.let { now.minusSeconds(it * 60) }, now, zone)

    @Test
    fun lastActiveIsShownInMinutesUpToTheHourThenAsToday() {
        assertEquals(PresenceAge.Minutes(10), age(10))
        assertEquals(PresenceAge.Minutes(55), age(55))
        assertEquals(PresenceAge.Today, age(60))
        assertEquals(PresenceAge.Today, age(180))
        // The vault rounds down to 5 minutes: under the first step "0 min ago" is not said.
        assertEquals(PresenceAge.JustNow, age(0))
        assertEquals(PresenceAge.JustNow, age(4))
        assertEquals(PresenceAge.Minutes(5), age(5))
    }

    @Test
    fun nothingIsShownWithoutLastActiveOrWhenItIsNotToday() {
        assertNull(age(null))
        // Past an hour but on an earlier day: the plan has no words for it, so none are said.
        assertNull(PresenceAge.of(Instant.parse("2026-11-02T23:00:00Z"), now, zone))
        // In the future: not shown.
        assertNull(PresenceAge.of(now.plusSeconds(600), now, zone))
    }

    @Test
    fun theWireBodiesAreReadDefensively() {
        fun obj(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
        assertEquals(
            PresencePolicy(3, PresenceState.AWAY, PresenceShare.NONE, listOf("a", "b")),
            PresenceWire.policy(obj("""{"version":3,"state":"away","share":"none","except":["b","a","b"]}""")),
        )
        // Unknown state or share: the vault's defaults; no version: no policy.
        assertEquals(PresencePolicy(3), PresenceWire.policy(obj("""{"version":3,"state":"x","share":"y"}""")))
        assertNull(PresenceWire.policy(obj("""{"state":"busy"}""")))
        val at = Instant.parse("2026-11-03T12:00:00Z")
        // A last_active a little ahead (skew) is now, far ahead is dropped; invisible is never an answer.
        val ahead = PresenceWire.result(obj("""{"connection_id":"c","ping_id":"p","state":"busy","last_active":"2026-11-03T12:03:00Z"}"""), at)
        assertEquals(at, ahead?.lastActive)
        val far = PresenceWire.result(obj("""{"connection_id":"c","ping_id":"p","state":"busy","last_active":"2026-11-03T13:00:00Z"}"""), at)
        assertNull(far?.lastActive)
        assertNull(PresenceWire.result(obj("""{"connection_id":"c","ping_id":"p","state":"invisible"}"""), at))
    }
}
