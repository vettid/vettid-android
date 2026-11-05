package com.vettid.core.vault

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** The sending side of "Location" (VAULT-MESSAGING §10.16): the shapes and bounds of `location.share.start` and `location.update`. */
class LocationApiTest {
    private fun start(
        mode: String,
        precision: String = "approximate",
        duration: Long? = null,
        interval: Long? = null,
        request: String? = null,
    ): JsonObject = locationStartBody("c1", mode, precision, duration, interval, false, request)

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("expected a refusal")
        } catch (e: LocationMessageException) {
            assertTrue(e.message!!.startsWith("location: "))
        }
    }

    @Test
    fun aContinuousShareCarriesBothAndAOnceShareNeither() {
        val c = start("continuous", "exact", 3_600, 60, "r1")
        assertEquals(setOf("connection_id", "mode", "precision", "duration_seconds", "interval_seconds", "history", "request_id"), c.keys)
        assertEquals(3_600L, (c["duration_seconds"] as JsonPrimitive).longOrNull)
        val once = start("once")
        assertEquals(setOf("connection_id", "mode", "precision", "history"), once.keys)
        assertEquals("once", (once["mode"] as JsonPrimitive).content)
    }

    @Test
    fun theVaultsBoundsAreKept() {
        // 5 minutes to 7 days, 10 seconds to 1 hour, both ends in.
        start("continuous", duration = 300, interval = 10)
        start("continuous", duration = 604_800, interval = 3_600)
        refused { start("continuous", duration = 299, interval = 60) }
        refused { start("continuous", duration = 604_801, interval = 60) }
        refused { start("continuous", duration = 3_600, interval = 9) }
        refused { start("continuous", duration = 3_600, interval = 3_601) }
        // A continuous share has both; a once share has neither.
        refused { start("continuous", duration = 3_600) }
        refused { start("continuous", interval = 60) }
        refused { start("once", duration = 900) }
        refused { start("once", interval = 60) }
        refused { start("sometimes") }
        refused { start("once", precision = "street") }
    }

    private fun sample(
        lat: Double = 52.5200,
        lon: Double = 13.4050,
        accuracy: Double? = 12.5,
        altitude: Double? = 34.0,
        speed: Double? = 1.5,
        heading: Double? = 90.0,
    ) = LocationSample(lat, lon, accuracy, altitude, speed, heading, Instant.parse("2026-11-03T12:00:00Z"))

    @Test
    fun aPositionIsTheNumbersAndTheFixTimeOnly() {
        val b = sample().body()
        assertEquals(setOf("lat", "lon", "accuracy_m", "altitude_m", "speed_mps", "heading_deg", "at"), b.keys)
        assertEquals(52.52, (b["lat"] as JsonPrimitive).doubleOrNull!!, 0.0)
        assertEquals("2026-11-03T12:00:00.000Z", (b["at"] as JsonPrimitive).content)
    }

    @Test
    fun aMemberOutOfRangeIsLeftOutNeverClamped() {
        val b = sample(accuracy = -1.0, altitude = 100_001.0, speed = -0.1, heading = 360.0).body()
        assertEquals(setOf("lat", "lon", "at"), b.keys)
        assertFalse(sample(accuracy = null, altitude = null, speed = null, heading = null).body().containsKey("accuracy_m"))
        // Heading 0 is a heading.
        assertEquals(0.0, (sample(heading = 0.0).body()["heading_deg"] as JsonPrimitive).doubleOrNull!!, 0.0)
    }

    @Test
    fun aPositionOutsideTheGlobeIsRefusedWithoutItsValue() {
        refused { sample(lat = 90.0001) }
        refused { sample(lon = -180.5) }
        refused { sample(lat = Double.NaN) }
        val e = runCatching { sample(lat = 123.456) }.exceptionOrNull()!!
        assertFalse(e.message!!.contains("123"))
    }

    @Test
    fun aSamplePrintsItsTypeNameOnly() {
        assertEquals("LocationSample", sample().toString())
        assertNull(runCatching { "${sample()}".contains("52.52") }.getOrNull()?.takeIf { it })
    }

    // --- receiving (L1b) ---

    @Test
    fun aRequestsNoteIsAtMost256BytesOfUtf8() {
        val ok = locationRequestBody("c1", "x".repeat(256))
        assertEquals(setOf("connection_id", "note"), ok.keys)
        // 256 bytes exactly: 128 two-byte characters; one more byte is refused.
        locationRequestBody("c1", "\u00e9".repeat(128))
        refused { locationRequestBody("c1", "x".repeat(257)) }
        refused { locationRequestBody("c1", "\u00e9".repeat(128) + "x") }
    }

    @Test
    fun aBlankNoteIsLeftOut() {
        assertEquals(setOf("connection_id"), locationRequestBody("c1", null).keys)
        assertEquals(setOf("connection_id"), locationRequestBody("c1", "  ").keys)
    }
}
