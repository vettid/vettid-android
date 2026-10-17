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

    @Test
    fun whatCountsAsUnavailable() {
        assertTrue(RecoveryUnknownClock.unavailable("armed", "unavailable"))
        assertTrue(RecoveryUnknownClock.unavailable("unknown", null))
        assertFalse(RecoveryUnknownClock.unavailable("armed", "ok"))
        // A clock set back before the start begins again from now.
        assertEquals(5L, RecoveryUnknownClock.next(10L, true, 5L))
    }
}
