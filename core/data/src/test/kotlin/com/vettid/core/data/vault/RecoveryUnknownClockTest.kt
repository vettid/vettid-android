package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A day of an unavailable recovery counter, across every read, reset by any read that finds it available. */
class RecoveryUnknownClockTest {
    private val hour = 3_600_000L

    @Test
    fun aDayAtEveryRead() {
        var since: Long? = null
        since = RecoveryUnknownClock.next(since, true, 0)
        assertEquals(0L, since)
        since = RecoveryUnknownClock.next(since, true, 23 * hour)
        assertFalse(RecoveryUnknownClock.urgent(since, 23 * hour))
        since = RecoveryUnknownClock.next(since, true, 24 * hour)
        assertTrue(RecoveryUnknownClock.urgent(since, 24 * hour))
    }

    /** Two short outages days apart are not one long one: the read in between stops the clock. */
    @Test
    fun anAvailableReadInBetweenStopsIt() {
        var since = RecoveryUnknownClock.next(null, true, 0)
        since = RecoveryUnknownClock.next(since, false, hour)
        assertNull(since)
        since = RecoveryUnknownClock.next(since, true, 48 * hour)
        assertFalse(RecoveryUnknownClock.urgent(since, 48 * hour))
    }

    /**
     * STAGING-TIME-SCALE S8 (ANDROID-PLAN 0.1.49): at 24 the day is an hour, divided when the clock starts and kept with
     * it; a clock started before the scale keeps its 24 hours, one started at 24 keeps its hour.
     */
    @Test
    fun theDayIsDividedWhenTheClockStarts() {
        assertEquals(hour, RecoveryUnknownClock.urgentMs(24))
        assertEquals(RecoveryUnknownClock.URGENT_MS, RecoveryUnknownClock.urgentMs(1))

        var c = RecoveryUnknownClock.advance(RecoveryUnknownClock.State(), true, 0, 24)
        assertEquals(RecoveryUnknownClock.State(0L, hour), c)
        c = RecoveryUnknownClock.advance(c, true, hour - 1, 24)
        assertFalse(c.urgent(hour - 1))
        // Read at real time now (the scale changed): the running clock keeps its hour.
        c = RecoveryUnknownClock.advance(c, true, hour, 1)
        assertEquals(hour, c.period)
        assertTrue(c.urgent(hour))
        // An available read stops it and forgets the period.
        assertEquals(RecoveryUnknownClock.State(), RecoveryUnknownClock.advance(c, false, 2 * hour, 24))

        // Started at real time (stored as none), then read at 24: still 24 hours.
        var real = RecoveryUnknownClock.advance(RecoveryUnknownClock.State(), true, 0, 1)
        assertEquals(RecoveryUnknownClock.State(0L, null), real)
        real = RecoveryUnknownClock.advance(real, true, 23 * hour, 24)
        assertFalse(real.urgent(23 * hour))
        assertTrue(real.urgent(24 * hour))

        // A phone clock set back starts the clock again, with the scale now.
        val again = RecoveryUnknownClock.advance(RecoveryUnknownClock.State(10 * hour, null), true, 5 * hour, 24)
        assertEquals(RecoveryUnknownClock.State(5 * hour, hour), again)
    }

    @Test
    fun whatCountsAsUnavailable() {
        assertTrue(RecoveryUnknownClock.unavailable("armed", "unavailable"))
        assertTrue(RecoveryUnknownClock.unavailable("unknown", null))
        assertFalse(RecoveryUnknownClock.unavailable("armed", "ok"))
        // A clock set back before the start begins again from now.
        assertEquals(5L, RecoveryUnknownClock.next(10L, true, 5L))
    }
}
