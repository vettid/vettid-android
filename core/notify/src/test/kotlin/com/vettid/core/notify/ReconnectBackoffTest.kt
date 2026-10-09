package com.vettid.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The on-phone service's reconnect schedule (ANDROID-PLAN 0.1.23, Notification modes 3 and 11). */
class ReconnectBackoffTest {
    private var now = 0L
    private val backoff = ReconnectBackoff(nowMs = { now }, random = Random(7))

    @Test
    fun oneSecondDoublingToFiveMinutesWithFullJitter() {
        val caps = (0 until 12).map {
            val cap = backoff.nextCapMs
            val d = backoff.nextDelayMs()
            assertTrue("$d within [0, $cap]", d in 0..cap)
            cap
        }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 64_000, 128_000, 256_000, 300_000, 300_000, 300_000), caps)
    }

    @Test
    fun fiveMinutesConnectedStartsTheScheduleOver() {
        repeat(5) { backoff.nextDelayMs() }
        backoff.onConnected()
        now += 60_000
        backoff.onDropped() // a minute: the backoff goes on
        assertEquals(32_000L, backoff.nextCapMs)
        backoff.onConnected()
        now += ReconnectBackoff.RESET_MS
        backoff.onDropped()
        assertEquals(1_000L, backoff.nextCapMs)
    }

    @Test
    fun aValidatedNetworkResets() {
        repeat(8) { backoff.nextDelayMs() }
        backoff.reset()
        assertEquals(1_000L, backoff.nextCapMs)
    }
}
