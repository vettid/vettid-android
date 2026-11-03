// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.presence

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** The words of the presence line, as the plan gives them (ANDROID-PLAN §4 "Presence"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PresenceTextTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val now = Instant.parse("2026-11-03T15:00:00Z")
    private val zone = ZoneId.of("UTC")

    private fun line(r: PresenceReading) = PresenceText.line(res, r, now, zone)

    @Test
    fun theStatesWithTheirAgeOrWithout() {
        assertEquals("Available · last seen 10 min ago", line(PresenceReading.Known(PresenceState.AVAILABLE, now.minusSeconds(600))))
        assertEquals("Busy · last seen today", line(PresenceReading.Known(PresenceState.BUSY, now.minusSeconds(7200))))
        assertEquals("Away · last seen just now", line(PresenceReading.Known(PresenceState.AWAY, now.minusSeconds(60))))
        // Nothing after the state without last_active, or one that cannot be told.
        assertEquals("Away", line(PresenceReading.Known(PresenceState.AWAY, null)))
        assertEquals("Busy", line(PresenceReading.Known(PresenceState.BUSY, now.plusSeconds(60))))
    }

    @Test
    @Config(sdk = [36], qualifiers = "en-rGB")
    fun anEarlierDayIsItsDateInTheDevicesLocale() {
        assertEquals("Available · last seen 2 Nov", line(PresenceReading.Known(PresenceState.AVAILABLE, Instant.parse("2026-11-02T23:00:00Z"))))
        assertEquals("Busy · last seen 9 Mar", line(PresenceReading.Known(PresenceState.BUSY, Instant.parse("2026-03-09T10:00:00Z"))))
        assertEquals("Away · last seen 31 Dec 2025", line(PresenceReading.Known(PresenceState.AWAY, Instant.parse("2025-12-31T10:00:00Z"))))
    }

    @Test
    @Config(sdk = [36], qualifiers = "en-rUS")
    fun theOrderFollowsTheLocale() {
        assertEquals("Available · last seen Nov 2", line(PresenceReading.Known(PresenceState.AVAILABLE, Instant.parse("2026-11-02T23:00:00Z"))))
    }

    @Test
    fun checkingAndUnknown() {
        assertEquals("Checking…", line(PresenceReading.Checking))
        assertEquals("Status unknown", line(PresenceReading.Unknown))
    }
}
