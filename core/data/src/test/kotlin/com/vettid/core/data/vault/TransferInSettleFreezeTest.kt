package com.vettid.core.data.vault

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settlement window's cuts and freezes (the lead's pending erratum on the extension rule): the cap, the heartbeat, the
 * final collect after a freeze past the cap. On a virtual clock with a jump for a freeze or a suspend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferInSettleFreezeTest {
    // --- the collector's own request cut, or a process freeze, during the window ---

    /** A monotonic clock with a jump: virtual time plus what a freeze took. */
    private class FrozenClock(private val scope: TestScope) {
        var jumpedMs = 0L
        fun now() = scope.testScheduler.currentTime + jumpedMs
    }

    private fun TestScope.timingWith(clock: FrozenClock, maxExtraMs: Long = TransferInResume.SETTLE_MAX_EXTRA_MS) =
        TransferInResume.Settle(maxExtraMs = maxExtraMs, now = clock::now)

    /**
     * The collector's in-flight request is cut (a stop or a reconnect) at 70 s; the message it was handed is leased for 60 s
     * more, to 130 s, past the window's first end (90 s). The cut extends the window and the message is gathered. Without
     * the extension the final collect at 90 s finds nothing and the transfer is dropped.
     */
    @Test
    fun aCutCollectorRequestExtendsTheWindowSoTheLeasedMessageIsGathered() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var isPaired = false
        var drops = 0
        var cutDone = false
        val round: suspend () -> Unit = {
            if (!cutDone && clock.now() >= 70_000) {
                cutDone = true
                cutCount++ // the collector's request was cut now; the relay leases the message until 130 s
            }
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (cutDone && clock.now() >= 130_000) isPaired = true else delay(w.coerceAtMost(5_000))
        }
        assertTrue(
            TransferInResume.atDeadline(
                { isPaired }, collect, { drops++ }, settle = timingWith(clock), awaitRound = round, cuts = { cutCount },
            ),
        )
        assertEquals(0, drops)
        assertTrue("paired after the first end: ${clock.now()}", clock.now() in 130_000..150_000)
    }

    /**
     * A long freeze (10 minutes) wakes far past the cap (F + V + 35 s = 185 s): there is no further extension, one final
     * `wait=0` collect goes at once and gathers a message whose lease ended before the cap.
     */
    @Test
    fun aLongFreezeWakesPastTheCapAndOneFinalCollectGathersAnExpiredLease() = runTest {
        val clock = FrozenClock(this)
        var isPaired = false
        var drops = 0
        var calls = 0
        val collect: suspend (Long) -> Unit = {
            calls++
            if (calls == 1) clock.jumpedMs += 600_000 // the freeze, in the middle of the first request
            if (clock.now() >= 150_000 && calls > 1) isPaired = true // free since 150 s (before the cap)
        }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = timingWith(clock)))
        assertEquals(0, drops)
        assertEquals("the request that was out, and one final collect", 2, calls)
    }

    /** The same freeze with nothing there: the drop comes at once after the one final collect, with no further extension. */
    @Test
    fun aLongFreezeWithNothingThereDropsAtOnceAfterOneFinalCollect() = runTest {
        val clock = FrozenClock(this)
        var drops = 0
        var calls = 0
        var droppedAt = -1L
        val collect: suspend (Long) -> Unit = {
            calls++
            if (calls == 1) clock.jumpedMs += 600_000
        }
        assertFalse(TransferInResume.atDeadline({ false }, collect, { drops++; droppedAt = clock.now() }, settle = timingWith(clock)))
        assertEquals(1, drops)
        assertEquals("the request that was out, and one final collect", 2, calls)
        assertTrue("dropped at the wake: $droppedAt", droppedAt - 600_000 <= 1_000)
    }

    /**
     * A freeze begins with a collector request out since s (just before the cap), and wakes after the cap but before s + V:
     * its lease runs to s + V. The final collect waits until s + V + 5 s and gathers the message; going at once it would
     * find nothing.
     */
    @Test
    fun theFinalCollectAfterAFreezePastTheCapWaitsForTheLeaseOfTheRequestThatWasOut() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var isPaired = false
        var drops = 0
        var outSince: Long? = null
        val settle = TransferInResume.Settle(windowMs = 100_000, now = clock::now) // F = 100 s, the cap F + 95 s = 195 s
        launch {
            // Cuts keep the window going to the cap (each moves the end to now + V, at most 195 s).
            delay(60_000)
            cutCount++
            delay(50_000)
            cutCount++
            delay(50_000)
            cutCount++
            delay(30_000)
            outSince = clock.now() // s = 190 s: the collector's request goes out, just before the cap
            delay(1_500)
            clock.jumpedMs += 30_000 // frozen: wakes at ~221 s, past the cap, before s + V = 250 s
        }
        val collect: suspend (Long) -> Unit = { w ->
            val s = outSince
            if (s != null && clock.now() >= s + 60_000) isPaired = true else delay(w.coerceAtMost(2_000))
        }
        val age: () -> Long? = { outSince?.let { clock.now() - it } }
        assertTrue(
            TransferInResume.atDeadline(
                { isPaired }, collect, { drops++ }, settle = settle, cuts = { cutCount }, collectorAge = age,
            ),
        )
        assertEquals(0, drops)
        assertTrue("not before s + V + 5 s: ${clock.now()} vs ${outSince!! + 65_000}", clock.now() >= outSince!! + 65_000)
    }

    /**
     * A freeze inside the final send itself: the window runs to the cap (195 s), the final `wait=0` collect goes out, and the
     * collector's request is out since s (~195 s) when the process freezes and wakes (~226 s) past the cap but before s + V.
     * The end cannot move, yet one more final collect is due, after s + V + 5 s: it gathers the message.
     */
    @Test
    fun aFreezeInsideTheFinalSendEarnsOneMoreFinalCollect() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var isPaired = false
        var drops = 0
        var outSince: Long? = null
        var finalCollected = false
        var froze = false
        val settle = TransferInResume.Settle(windowMs = 100_000, now = clock::now) // F = 100 s, the cap 195 s
        launch {
            delay(60_000)
            cutCount++
            delay(50_000)
            cutCount++
            delay(50_000)
            cutCount++
        }
        val round: suspend () -> Unit = {
            if (finalCollected && !froze) {
                froze = true
                outSince = clock.now() // the collector's request goes out, at the end of the final send
                delay(1_500)
                clock.jumpedMs += 30_000 // frozen: wakes past the cap, before s + V
            }
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (clock.now() >= 195_000) finalCollected = true
            val s = outSince
            if (s != null && clock.now() >= s + 60_000) isPaired = true else delay(w.coerceAtMost(2_000))
        }
        val age: () -> Long? = { outSince?.let { clock.now() - it } }
        assertTrue(
            TransferInResume.atDeadline(
                { isPaired }, collect, { drops++ }, settle = settle, awaitRound = round, cuts = { cutCount }, collectorAge = age,
            ),
        )
        assertEquals(0, drops)
        assertTrue("not before s + V + 5 s: ${clock.now()} vs ${outSince!! + 65_000}", clock.now() >= outSince!! + 65_000)
    }

    /**
     * A freeze inside the final send with nothing out (no request of ours or the collector's at the freeze): no lease can be
     * stranded, so the settlement ends after that send, with no extra round.
     */
    @Test
    fun aFreezeInsideTheFinalSendWithNothingOutEndsAfterThatSend() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var drops = 0
        var finalCollects = 0
        var froze = false
        val settle = TransferInResume.Settle(windowMs = 100_000, now = clock::now)
        launch {
            delay(60_000)
            cutCount++
            delay(50_000)
            cutCount++
            delay(50_000)
            cutCount++
        }
        val round: suspend () -> Unit = {
            if (finalCollects > 0 && !froze) {
                froze = true
                delay(1_500)
                clock.jumpedMs += 30_000 // frozen with nothing out
            }
        }
        val collect: suspend (Long) -> Unit = {
            if (clock.now() >= 195_000) finalCollects++
        }
        assertFalse(
            TransferInResume.atDeadline({ false }, collect, { drops++ }, settle = settle, awaitRound = round, cuts = { cutCount }),
        )
        assertEquals(1, drops)
        assertEquals("one final collect, no extra round", 1, finalCollects)
    }

    /**
     * A request sent less than a tick before the freeze (after the last tick's sample) is still the one whose lease the final
     * collect waits for.
     */
    @Test
    fun aRequestSentJustBeforeTheFreezeIsTheOneTheFinalCollectWaitsFor() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var isPaired = false
        var drops = 0
        var outSince: Long? = null
        val settle = TransferInResume.Settle(windowMs = 100_000, now = clock::now)
        launch {
            delay(60_000)
            cutCount++
            delay(50_000)
            cutCount++
            delay(50_000)
            cutCount++
            delay(30_500)
            outSince = clock.now() // between two ticks: the last sample (at 190 s) did not see it
            delay(100)
            clock.jumpedMs += 30_000
        }
        val collect: suspend (Long) -> Unit = { w ->
            val s = outSince
            if (s != null && clock.now() >= s + 60_000) isPaired = true else delay(w.coerceAtMost(2_000))
        }
        val age: () -> Long? = { outSince?.let { clock.now() - it } }
        assertTrue(
            TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = settle, cuts = { cutCount }, collectorAge = age),
        )
        assertEquals(0, drops)
    }

    /**
     * Cuts at 60 s and 110 s (F + 20 s, F = 90 s): the second leases until 170 s, past F + V (150 s) but before the cap
     * F + V + 35 s (185 s). It is gathered; a cap of F + V would have dropped the transfer first.
     */
    @Test
    fun aCutTwentySecondsPastTheFirstEndIsStillGatheredWithinTheCap() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var isPaired = false
        var drops = 0
        launch {
            delay(60_000)
            cutCount++
            delay(50_000)
            cutCount++
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (clock.now() >= 168_000) isPaired = true else delay(w.coerceAtMost(5_000))
        }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = timingWith(clock), cuts = { cutCount }))
        assertEquals(0, drops)
    }

    /**
     * A cut during the final wait=0 collect (at the window's end, 90 s) leaves a message leased until 110 s: the window goes
     * on after that collect (the end moved out) and gathers it, instead of dropping with a leased message.
     */
    @Test
    fun aCutDuringTheFinalCollectContinuesTheWindow() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var isPaired = false
        var drops = 0
        var freeAt = Long.MAX_VALUE
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 90_000 && freeAt == Long.MAX_VALUE) {
                cutCount++ // cut during the final collect; the relay leases the message until 110 s
                freeAt = 110_000
            } else if (clock.now() >= freeAt) {
                isPaired = true
            } else {
                delay(w.coerceAtMost(5_000))
            }
        }
        assertTrue(
            TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = timingWith(clock), cuts = { cutCount }),
        )
        assertEquals(0, drops)
        assertTrue("gathered after the first end: ${clock.now()}", clock.now() >= 110_000)
    }

    /**
     * A freeze of about 30 s in the middle of a long round wait (25 s of waiting, 30 s frozen: the phase took 55 s by the
     * clock, inside its own 55 s bound plus the old rule's 10 s margin, so the phase-overrun rule never saw it). The
     * heartbeat sees a gap of 30 s between ticks and extends the end to now + V (about 101 s). The message the collector was
     * handed is free at 100 s, past the window's first end (90 s); the extended window gathers it,
     * where without the detection the transfer is dropped at 90 s.
     */
    @Test
    fun aThirtySecondFreezeDuringALongRoundWaitIsSeenByTheHeartbeat() = runTest {
        val clock = FrozenClock(this)
        var isPaired = false
        var drops = 0
        var froze = false
        val round: suspend () -> Unit = {
            if (!froze) {
                froze = true
                delay(10_000)
                clock.jumpedMs += 30_000 // the freeze
                delay(15_000)
            }
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (froze && clock.now() >= 100_000) isPaired = true else delay(w.coerceAtMost(5_000))
        }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = timingWith(clock), awaitRound = round))
        assertEquals(0, drops)
    }

    /** The heartbeat lives only as long as the window: nothing is left scheduled once it ended. */
    @Test
    fun theHeartbeatEndsWithTheWindow() = runTest {
        val clock = FrozenClock(this)
        TransferInResume.atDeadline({ false }, {}, {}, settle = timingWith(clock))
        testScheduler.advanceUntilIdle() // would never return with a ticker left
        assertEquals("no ticker left running on", TransferInResume.SETTLE_WINDOW_MS, testScheduler.currentTime)
    }

    /** Repeated cuts never extend the window past its first end plus the cap (a fixed point on the window's clock). */
    @Test
    fun repeatedCutsAndFreezesStayWithinTheCap() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var drops = 0
        var droppedAt = -1L
        val round: suspend () -> Unit = { cutCount++ } // a cut at every step
        TransferInResume.atDeadline(
            { false }, {}, { drops++; droppedAt = clock.now() }, settle = timingWith(clock, maxExtraMs = 20_000), awaitRound = round,
            cuts = { cutCount },
        )
        assertEquals(1, drops)
        assertTrue("dropped at the cap, first end + 20 s: $droppedAt", droppedAt in 110_000..111_000)
    }

    /** No cut and no freeze: the window behaves as before (dropped exactly at its end). */
    @Test
    fun noCutAndNoFreezeLeavesTheWindowAsItWas() = runTest {
        val clock = FrozenClock(this)
        var droppedAt = -1L
        TransferInResume.atDeadline({ false }, {}, { droppedAt = clock.now() }, settle = timingWith(clock))
        assertEquals(TransferInResume.SETTLE_WINDOW_MS, droppedAt)
    }
}
