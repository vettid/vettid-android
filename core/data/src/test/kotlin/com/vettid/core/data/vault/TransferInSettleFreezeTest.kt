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
    private companion object {
        const val EXPECTED_OFFLINE_DROP = 290_000L
        const val EXPECTED_CUTS_DROP = 309_000L
        const val FIX3_FINAL = 300_000L
    }

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

    /**
     * The same freeze with nothing there: no further extension; the one final collect waits only for the lease of the request
     * that was out when it froze (#308 R2: its bytes may reach the relay at the wake, 600 s, which parks it for its 25 s
     * wait: the lease to 685 s, the collect at 700 s), then the drop.
     */
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
        assertEquals("dropped after the wake + w + V + 15 s", 700_000L, droppedAt)
    }

    /**
     * A freeze begins with a collector request out since s (just before the cap), and wakes after the cap but before s + V:
     * its lease runs to s + V. The final collect waits until s + V + 15 s and gathers the message; going at once it would
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
        assertTrue("not before s + V + 15 s: ${clock.now()} vs ${outSince!! + 75_000}", clock.now() >= outSince!! + 75_000)
    }

    /**
     * A freeze inside the final send itself: the window runs to the cap (195 s), the final `wait=0` collect goes out, and the
     * collector's request is out since s (~195 s) when the process freezes and wakes (~226 s) past the cap but before s + V.
     * The end cannot move, yet one more final collect is due, after s + V + 15 s: it gathers the message.
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
        assertTrue("not before s + V + 15 s: ${clock.now()} vs ${outSince!! + 75_000}", clock.now() >= outSince!! + 75_000)
    }

    /**
     * A freeze inside the final send: the latest request sent (its send time is kept after it ended) may still have a lease,
     * so at most ONE extra held final collect is given, then the drop.
     */
    @Test
    fun aFreezeInsideTheFinalSendEarnsAtMostOneExtraCollect() = runTest {
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
        assertEquals("one final collect and one extra", 2, finalCollects)
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
    fun repeatedCutsStayWithinTheCap() = runTest {
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
        // The end stays at the cap (110 s); a cut's lease past it is waited for by the held last collect (one extra at most).
        assertEquals("held for the last cut's lease, then dropped", EXPECTED_CUTS_DROP, droppedAt)
    }

    /** No cut and no freeze: the window behaves as before (dropped exactly at its end). */
    @Test
    fun noCutAndNoFreezeLeavesTheWindowAsItWas() = runTest {
        val clock = FrozenClock(this)
        var droppedAt = -1L
        TransferInResume.atDeadline({ false }, {}, { droppedAt = clock.now() }, settle = timingWith(clock))
        assertEquals(TransferInResume.SETTLE_WINDOW_MS, droppedAt)
    }

    /**
     * (round 3, M1) A request that ends on the wake, before any tick, still counts: its send time is kept. Our request is sent
     * at ~96 s, the process freezes 0.2 s later and the request returns after the wake (30 s later); the message it was handed
     * is leased to it until sent + V, past the cap (105 s). The last collect holds for it.
     */
    @Test
    fun aRequestThatEndsOnTheWakeBeforeAnyTickStillSetsTheHold() = runTest {
        val clock = FrozenClock(this)
        var isPaired = false
        var drops = 0
        var sentAt: Long? = null
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        val collect: suspend (Long) -> Unit = { w ->
            val s = sentAt
            if (s != null && clock.now() >= s + 60_000) {
                isPaired = true
            } else if (s == null && clock.now() >= 95_500) {
                sentAt = clock.now()
                delay(200)
                clock.jumpedMs += 30_000 // frozen; the request returns after the wake, before the next tick
            } else {
                delay(w.coerceAtMost(500))
            }
        }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = settle))
        assertEquals(0, drops)
        assertTrue("held to sent + V + 15 s", clock.now() >= sentAt!! + 75_000)
    }

    /**
     * (round 3, M2) A freeze in every final send never earns more than ONE extra held final collect; after it, the freeze in the
     * last send earns one more look, held for nothing (item 8): three in all, then the drop.
     */
    @Test
    fun atMostOneExtraHeldFinalCollectPerSettlement() = runTest {
        val clock = FrozenClock(this)
        var drops = 0
        var finals = 0
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 100_000) {
                finals++
                delay(100)
                clock.jumpedMs += 30_000 // every final send is frozen
            } else {
                delay(w.coerceAtMost(500))
            }
        }
        assertFalse(TransferInResume.atDeadline({ false }, collect, { drops++ }, settle = settle))
        assertEquals(1, drops)
        assertEquals("one final collect, one extra and the one look after the freeze, no more", 3, finals)
    }

    /**
     * (round 3, M3) A freeze that wakes BEFORE the cap still holds the last collect: a request out at 150 s, frozen 150 to 170 s,
     * the end 185 s (the cap), the message leased until 210 s. The last collect goes at 225 s and gathers it.
     */
    @Test
    fun aFreezeThatWakesBeforeTheCapStillHoldsTheLastCollect() = runTest {
        val clock = FrozenClock(this)
        var isPaired = false
        var drops = 0
        var sentAt: Long? = null
        val settle = TransferInResume.Settle(windowMs = 185_000, maxExtraMs = 0, now = clock::now) // F = the cap = 185 s
        launch {
            delay(150_000)
            sentAt = clock.now() // the collector's request goes out
            delay(500)
            clock.jumpedMs += 20_000 // frozen, wakes at ~170 s
        }
        val collect: suspend (Long) -> Unit = { w ->
            val s = sentAt
            if (s != null && clock.now() >= s + 60_000) isPaired = true else delay(w.coerceAtMost(2_000))
        }
        val age: () -> Long? = { sentAt?.let { clock.now() - it } }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = settle, collectorAge = age))
        assertEquals(0, drops)
        assertTrue("held to s + V + 15 s: ${clock.now()}", clock.now() >= sentAt!! + 75_000)
    }

    /**
     * (iOS point 1) A cut during the hold moves the hold: the last collect goes once, after the new lease. Cuts at 98 s (lease
     * 183 s, hold 198 s) and 150 s, during the hold (noticed at 198 s: lease 283 s, hold 298 s); undescribed cuts lease from
     * their notice + the long-poll cap.
     */
    @Test
    fun aCutDuringTheHoldMovesTheHold() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var isPaired = false
        var drops = 0
        val finalTimes = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        launch {
            delay(98_000)
            cutCount++
            delay(52_000)
            cutCount++
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 105_000) finalTimes += clock.now()
            if (clock.now() >= 210_000) isPaired = true else delay(w.coerceAtMost(500))
        }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = settle, cuts = { cutCount }))
        assertEquals(0, drops)
        assertEquals("one final collect, after the new lease: $finalTimes", 1, finalTimes.size)
        assertTrue(finalTimes[0] >= 298_000) // the notice is at the look after the first hold (198 s): 198 + 25 + 60 + 15
    }

    /** (R1) A second move of the hold is ignored: cuts at 98, 150 and 200 s; the hold moves once (to 298 s), then the drop. */
    @Test
    fun aSecondMoveOfTheHoldIsIgnored() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var drops = 0
        var droppedAt = -1L
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        launch {
            delay(98_000)
            cutCount++
            delay(52_000)
            cutCount++
            delay(50_000)
            cutCount++
        }
        assertFalse(
            TransferInResume.atDeadline(
                { false }, {}, { drops++; droppedAt = clock.now() }, settle = settle, cuts = { cutCount },
            ),
        )
        assertEquals(1, drops)
        assertEquals("one move of the hold, then the drop", 298_000L, droppedAt)
    }

    /**
     * (R1) The collector fails every 30 s through the hold (offline: every failed attempt is a cut): the settlement still drops
     * at a bounded time, EXPECTED below, however long it fails.
     */
    @Test
    fun aCollectorFailingEvery30SecondsCannotKeepTheHoldGoing() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var droppedAt = -1L
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        backgroundScope.launch {
            while (true) {
                delay(30_000)
                cutCount++
            }
        }
        assertFalse(TransferInResume.atDeadline({ false }, {}, { droppedAt = clock.now() }, settle = settle, cuts = { cutCount }))
        assertEquals(EXPECTED_OFFLINE_DROP, droppedAt)
    }

    /**
     * (R2) The collector sends in the last second before a freeze, after the last tick; on the wake its round ends and a new one
     * goes out before the first tick. Both sends are in the device's history: s is the first, the hold waits for ITS lease.
     */
    @Test
    fun aRoundRestartedOnTheWakeDoesNotHideTheSendBeforeTheFreeze() = runTest {
        val clock = FrozenClock(this)
        var isPaired = false
        var drops = 0
        val sends = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 105_000, maxExtraMs = 0, now = clock::now) // F = the cap = 105 s
        launch {
            delay(103_500)
            sends += clock.now() // A: just before the freeze, after the last tick
            delay(100)
            clock.jumpedMs += 30_000 // frozen; the wake
            sends += clock.now() // B: the new round, before any tick
        }
        val ours = mutableListOf<Long>()
        val collect: suspend (Long) -> Unit = {
            ours += clock.now()
            if (sends.isNotEmpty() && clock.now() >= sends[0] + 60_000) isPaired = true
        }
        val ages: () -> List<Pair<Long, Long>> = { sends.map { (clock.now() - it) to 0L } }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = settle, collectorSends = ages))
        assertEquals(0, drops)
        assertTrue("held for A's lease: ${clock.now()} ours=$ours A=${sends[0]}", clock.now() >= sends[0] + 75_000)
    }

    /**
     * (round 4) A long poll's lease starts when the relay returns, up to its wait after the send. The collector's request goes
     * out at 103.5 s with a 25 s wait, the process freezes and wakes past the cap (105 s). The message is handed to it at
     * 128.5 s (A + wait) and leased until A + 25 + 60 s = 188.5 s: the hold runs to A + wait + V + 15 s.
     */
    @Test
    fun aLongPollsLeaseCountsItsWait() = runTest {
        val clock = FrozenClock(this)
        var isPaired = false
        var drops = 0
        var sentAt: Long? = null
        val settle = TransferInResume.Settle(windowMs = 105_000, maxExtraMs = 0, now = clock::now) // F = the cap = 105 s
        launch {
            delay(103_500)
            sentAt = clock.now()
            delay(100)
            clock.jumpedMs += 30_000
        }
        val collect: suspend (Long) -> Unit = {
            val a = sentAt
            if (a != null && clock.now() >= a + 25_000 + 60_000) isPaired = true
        }
        val sends: () -> List<Pair<Long, Long>> = { listOfNotNull(sentAt?.let { (clock.now() - it) to 25_000L }) }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = settle, collectorSends = sends))
        assertEquals(0, drops)
        assertTrue("held to A + w + V + 15 s: ${clock.now()}", clock.now() >= sentAt!! + 25_000 + 75_000)
    }

    /**
     * (Fix 3) The first hold, set while nothing was due yet, is not a move. A cut is recorded between the two looks at the
     * start of the hold (the cuts lambda flips on its 4th read at the end: the pause's look, the loop's, and the hold's two):
     * the hold adopts it without using up the one extra. A later cut during the hold then still moves it (once).
     * F = 100 s, the cap 105 s. The cuts are not described, so each lease starts by its notice + the long-poll cap (25 s). The
     * first cut, noticed at 100 s, leases to 185 s: the hold is 200 s. The second, at 130 s, is noticed at 200 s and leases to
     * 285 s: the hold moves to 300 s, where the one final collect goes.
     */
    @Test
    fun aLeaseRecordedBetweenTheHoldsTwoLooksIsNotAMove() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var readsAtEnd = 0
        var flippedAt = -1L
        val finalTimes = mutableListOf<Long>()
        var droppedAt = -1L
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        launch {
            delay(130_000)
            cutCount++ // the second cut, during the hold
        }
        val cuts: () -> Long = {
            if (flippedAt < 0 && clock.now() >= 100_000 && ++readsAtEnd == 4) {
                flippedAt = clock.now()
                cutCount++ // between the hold's first look and its second
            }
            cutCount
        }
        val collect: suspend (Long) -> Unit = { w -> if (w == 0L && clock.now() >= 100_000) finalTimes += clock.now() }
        assertFalse(
            TransferInResume.atDeadline({ false }, collect, { droppedAt = clock.now() }, settle = settle, cuts = cuts),
        )
        assertEquals("the flip came at the end", 100_000L, flippedAt)
        assertEquals("one final collect, after the moved hold: $finalTimes", listOf(FIX3_FINAL), finalTimes)
        assertEquals(FIX3_FINAL, droppedAt)
    }
}
