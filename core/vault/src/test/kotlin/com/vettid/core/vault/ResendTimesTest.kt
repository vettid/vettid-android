package com.vettid.core.vault

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

/**
 * (VAULT-MESSAGING 0.97.0 §10.10 "Placing a call") `call.start`'s times on a test clock, at the spec's own values: copies
 * at 10 s and 20 s after the first send, given up at 30 s, with the device's 2 s wait after a late first deposit.
 */
class ResendTimesTest {
    private val times = ResendTimes(listOf(Duration.ofSeconds(10), Duration.ofSeconds(20)), Duration.ofSeconds(30), Duration.ofSeconds(2))

    /** One exchange on a clock that moves only as its steps say. */
    private class Run(
        val startAt: Long = 1_000,
        val firstTakes: Long = 0,
        val againTakes: Long = 0,
        val answerAt: Long? = null,
    ) {
        var now = startAt
        val sends = mutableListOf<Long>()
        var bounds = mutableListOf<Long>()
        var endedAt = -1L

        fun at(ms: Long) = ms - startAt

        suspend fun on(t: ResendTimes): String? = t.run(
            elapsed = { now },
            first = { bound ->
                bounds += bound
                val made = firstTakes <= bound
                now += minOf(firstTakes, bound)
                if (made) sends += at(now)
                made
            },
            again = { bound ->
                bounds += bound
                now += minOf(againTakes, bound)
                if (againTakes <= bound) sends += at(now)
            },
            answerWithin = { ms ->
                val a = answerAt?.plus(startAt)
                if (a != null && a <= now + ms) {
                    now = maxOf(now, a)
                    "answer"
                } else {
                    now += ms
                    null
                }
            },
        ).also { endedAt = at(now) }
    }

    @Test
    fun noAnswerSendsAt0And10And20AndGivesUpAt30() = runBlocking {
        val r = Run()
        assertNull(r.on(times))
        assertEquals(listOf(0L, 10_000L, 20_000L), r.sends)
        assertEquals(30_000L, r.endedAt)
        assertEquals("each deposit bounded by what is left of the 30 s", listOf(30_000L, 20_000L, 10_000L), r.bounds)
    }

    @Test
    fun anAnswerEndsTheWaitAndNothingMoreGoes() = runBlocking {
        val r = Run(answerAt = 15_000)
        assertEquals("answer", r.on(times))
        assertEquals(listOf(0L, 10_000L), r.sends)
        assertEquals(15_000L, r.endedAt)
        val atTheEnd = Run(answerAt = 30_000)
        assertEquals("an answer at the deadline is taken", "answer", atTheEnd.on(times))
    }

    @Test
    fun aSlowFirstDepositSkipsTheTimesThatPassedAndWaitsAtLeast2s() = runBlocking {
        val r = Run(firstTakes = 12_000)
        assertNull(r.on(times))
        assertEquals("10 s passed while it was made", listOf(12_000L, 20_000L), r.sends)
        assertEquals(30_000L, r.endedAt)

        val onTheTime = Run(firstTakes = 10_000)
        assertNull(onTheTime.on(times))
        assertEquals("a deposit that ends on the 10 s time is that copy", listOf(10_000L, 20_000L), onTheTime.sends)

        val late = Run(firstTakes = 29_000)
        assertNull(late.on(times))
        assertEquals("no copy back to back", listOf(29_000L), late.sends)
        assertEquals("waited 2 s after the deposit", 31_000L, late.endedAt)
    }

    @Test
    fun aFirstDepositNotMadeBy30sIsNoAnswerAndNothingMoreGoes() = runBlocking {
        val r = Run(firstTakes = 40_000)
        assertNull(r.on(times))
        assertEquals(emptyList<Long>(), r.sends)
        assertEquals(30_000L, r.endedAt)
    }

    @Test
    fun aSlowReSendSkipsTheNextAndAHeldOneDoesNotHoldTheEnd() = runBlocking {
        val slow = Run(againTakes = 11_000)
        assertNull(slow.on(times))
        assertEquals("20 s passed while the 10 s copy was made", listOf(0L, 21_000L), slow.sends)
        assertEquals(30_000L, slow.endedAt)

        val held = Run(againTakes = 60_000)
        assertNull(held.on(times))
        assertEquals(listOf(0L), held.sends)
        assertEquals("bounded by the 30 s", 30_000L, held.endedAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun resendTimesOutOfOrderAreRefused() {
        ResendTimes(listOf(Duration.ofSeconds(20), Duration.ofSeconds(10)), Duration.ofSeconds(30), Duration.ZERO)
    }
}
