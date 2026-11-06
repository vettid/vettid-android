package com.vettid.core.data.vault

import com.vettid.core.relay.RelayException
import com.vettid.core.vault.CollectorCut
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
 * The settlement window after #307 (moved out of [TransferInSettleFreezeTest] for size): the lead's calls A to C for both
 * apps (a cut's lease from its send + wait, a refused last collect, a cut that belongs to a freeze), iOS's checks (a cut
 * during the last look, older cuts), the background at the decision, and the wait our requests are sent with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferInSettleFollowUpTest {
    /** A monotonic clock with a jump: virtual time plus what a freeze took. */
    private class FrozenClock(private val scope: TestScope) {
        var jumpedMs = 0L
        fun now() = scope.testScheduler.currentTime + jumpedMs
    }

    private fun timingWith(clock: FrozenClock) = TransferInResume.Settle(now = clock::now)

    /**
     * (Call A) A cut leases from its request's send + wait when that is after the notice: a request sent at 98 s with a 25 s wait is
     * cut on our side at 98.5 s while the relay still has it parked; the relay hands it the message at 123 s and leases it
     * to 183 s. The hold goes at 198 s (+ 15 s) and gathers it; from the notice (99 s + V) it would go at 174 s and drop.
     */
    @Test
    fun aCutLeasesFromItsSendPlusWaitNotFromTheNotice() = runTest {
        val clock = FrozenClock(this)
        var cut = false
        var isPaired = false
        var drops = 0
        val finals = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        launch {
            delay(98_500)
            cut = true
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 105_000) finals += clock.now() // past the cap (105 s): the last collect
            if (clock.now() >= 183_000) isPaired = true
        }
        assertTrue(
            TransferInResume.atDeadline(
                { isPaired }, collect, { drops++ }, settle = settle, cuts = { if (cut) 1L else 0L },
                cutLeases = { if (cut) listOf(CollectorCut(1L, clock.now() - 98_000, 25_000)) else emptyList() },
            ),
        )
        assertEquals(0, drops)
        assertEquals(listOf(198_000L), finals)
    }

    /**
     * (Item 10) Our request is recorded with the wait it was actually sent with: a long poll asked for 25 s that went out as
     * wait=0 (the collection runs) leases with no wait added. Sent at the tick of 75 s and frozen 0.1 s later, it leases from
     * the wake (105.1 s, #308 R2), past the cap (105 s): the hold goes at 105.1 + 0 + 60 + 15 = 180.1 s, not 205.1 s.
     */
    @Test
    fun ourRequestIsRecordedWithTheWaitItWasSentWith() = runTest {
        val clock = FrozenClock(this)
        var froze = false
        val finals = mutableListOf<Long>()
        val asked = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        val collect: suspend (Long) -> Unit = { w ->
            if (!froze && clock.now() >= 75_000) {
                froze = true
                asked += w
                delay(100)
                clock.jumpedMs += 30_000
            } else if (froze && w == 0L && clock.now() >= 105_000) {
                finals += clock.now()
            }
        }
        assertFalse(TransferInResume.atDeadline({ false }, collect, {}, settle = settle, sentWait = { 0L }))
        assertEquals("asked for a long poll", listOf(20_000L), asked)
        assertEquals(listOf(180_100L), finals)
    }

    /**
     * (Call B) The relay refuses the last `wait=0` collect with 429: nothing was looked at, so it counts as a cut on the last
     * collect and gets the one extra: a fresh request after its `retry_after`, at most V (600 s asked, 60 s waited).
     */
    @Test
    fun aRateLimitedLastCollectGetsTheExtraAfterItsRetryAfterUpToV() = runTest {
        val clock = FrozenClock(this)
        val finals = mutableListOf<Long>()
        var droppedAt = -1L
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 100_000) {
                finals += clock.now()
                if (finals.size == 1) throw RelayException(429, RelayException.RATE_LIMITED, "", retryAfterSeconds = 600)
            }
        }
        assertFalse(TransferInResume.atDeadline({ false }, collect, { droppedAt = clock.now() }, settle = settle))
        assertEquals(listOf(100_000L, 160_000L), finals)
        assertEquals(160_000L, droppedAt)
    }

    /**
     * (Call B) A stale signature (401 `timestamp_stale`) on the last collect: one more look, signed afresh, at once. A 4xx
     * that is not a refusal to look (403) is no cut: the drop follows.
     */
    @Test
    fun aStaleSignatureOnTheLastCollectGetsTheExtraAnother4xxDoesNot() = runTest {
        for ((status, code, expected) in listOf(
            Triple(401, RelayException.TIMESTAMP_STALE, listOf(100_000L, 100_000L)),
            Triple(403, RelayException.TOKEN_REVOKED, listOf(100_000L)),
        )) {
            val clock = FrozenClock(this)
            val start = testScheduler.currentTime
            val finals = mutableListOf<Long>()
            val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = { clock.now() - start })
            val collect: suspend (Long) -> Unit = { w ->
                if (w == 0L && clock.now() - start >= 100_000) {
                    finals += clock.now() - start
                    if (finals.size == 1) throw RelayException(status, code, "")
                }
            }
            assertFalse(TransferInResume.atDeadline({ false }, collect, {}, settle = settle))
            assertEquals("$status", expected, finals)
        }
    }

    /**
     * (Call C) A cut of a request sent at or before a freeze belongs to the freeze: its lease is within the freeze's, so it
     * moves nothing and the one extra stays unused. The collector sends at the tick of 85 s (wait 25 s) and freezes 12 s; the
     * wake is seen at 98 s, and the freeze's lease starts by the wake + w (#308 R2), runs to 183 s, the hold to 198 s. The cut
     * of that request is counted at 120 s and seen at the hold's look (198 s): it was written before the freeze, so its lease
     * starts by 110 s, not at the notice (198 s, which would move the hold to 273 s and use the extra). A cut during the last
     * collect at 198 s (lease to 258 s) then still gets the extra: a held re-look at 273 s.
     */
    @Test
    fun aCutOfARequestSentBeforeAFreezeBelongsToTheFreeze() = runTest {
        val clock = FrozenClock(this)
        val sends = mutableListOf<Pair<Long, Long>>()
        val leases = mutableListOf<Triple<Long, Long, Long>>() // (cut number, send, wait)
        val finals = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        launch {
            delay(85_000)
            sends += clock.now() to 25_000L
            delay(100)
            clock.jumpedMs += 12_000 // frozen with the collector's request out; wakes at 97.1 s
            delay(22_900)
            leases += Triple(1L, 85_000L, 25_000L) // its cut, seen on the wake's side at 120 s
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 185_000) {
                finals += clock.now()
                if (finals.size == 1) leases += Triple(2L, clock.now(), 0L) // cut during the last collect
            }
        }
        assertFalse(
            TransferInResume.atDeadline(
                { false }, collect, {}, settle = settle, cuts = { leases.size.toLong() },
                collectorSends = { sends.map { (clock.now() - it.first) to it.second } },
                cutLeases = { leases.map { CollectorCut(it.first, clock.now() - it.second, it.third) } },
            ),
        )
        assertEquals(listOf(198_000L, 273_000L), finals)
    }

    /**
     * (iOS a) A cut during the last look whose lease ends within the cap, but after the look began: the extra (a held
     * re-look), not the drop. The last collect starts at 100 s; the collector's request (sent at 100 s, wait 0) is cut then and
     * seen at 101 s: the lease ends at 161 s, the end moves to 176 s. Our request takes 29 s and the round after it 50 s: the
     * look ends at 180 s, past the end.
     */
    @Test
    fun aCutDuringTheLastLookWithinTheCapGetsAHeldReLook() = runTest {
        val clock = FrozenClock(this)
        var cut = false
        var isPaired = false
        var drops = 0
        var rounds = 0
        var slowed = false
        val settle = TransferInResume.Settle(windowMs = 100_000, now = clock::now) // the cap 195 s
        val round: suspend () -> Unit = {
            if (clock.now() >= 100_000 && rounds < 2) {
                if (rounds++ == 0) {
                    cut = true
                    delay(1_000)
                } else {
                    delay(50_000)
                }
            }
        }
        val collect: suspend (Long) -> Unit = {
            if (clock.now() >= 161_000) {
                isPaired = true
            } else if (clock.now() >= 100_000 && !slowed) {
                slowed = true
                delay(29_000)
            }
        }
        assertTrue(
            TransferInResume.atDeadline(
                { isPaired }, collect, { drops++ }, settle = settle, awaitRound = round, cuts = { if (cut) 1L else 0L },
                cutLeases = { if (cut) listOf(CollectorCut(1L, clock.now() - 100_000, 0)) else emptyList() },
            ),
        )
        assertEquals(0, drops)
        assertEquals("re-looked at the end of the look", 180_000L, clock.now())
    }

    /**
     * (iOS d) Cuts counted before the window began are not the window's: the device's cut 5 (from before) leases far ahead,
     * and is ignored; the window's own cut 6 (sent at 49 s, wait 0, seen at 50 s) moves the end to 125 s, where it drops.
     */
    @Test
    fun cutsCountedBeforeTheWindowBeganDoNotCount() = runTest {
        val clock = FrozenClock(this)
        var count = 5L
        var droppedAt = -1L
        launch {
            delay(49_500)
            count = 6L
        }
        val leases: () -> List<CollectorCut> = {
            val before = CollectorCut(5L, 0, 1_000_000L) // from before the window: its lease far ahead
            listOf(before) + if (count == 6L) listOf(CollectorCut(6L, clock.now() - 49_000L, 0)) else emptyList()
        }
        assertFalse(
            TransferInResume.atDeadline(
                { false }, {}, { droppedAt = clock.now() }, settle = timingWith(clock), cuts = { count }, cutLeases = leases,
            ),
        )
        assertEquals(125_000L, droppedAt)
    }

    /**
     * (Item 8) Once the extra is used, nothing grants a new hold: a cut noticed right after the extra after the send was
     * taken (the 4th read of the cuts after the wake: before the second hold's first look) does not hold the re-look. The
     * last collect at 100 s is frozen for 30 s: it leases from the wake (130.1 s, #308 R2) to 190.1 s, the re-look goes at
     * 205.1 s, not at the cut's 230.1 s.
     */
    @Test
    fun afterTheExtraNoNewHoldIsGranted() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var reads = 0
        var flippedAt = -1L
        val finals = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 100_000) {
                finals += clock.now()
                if (finals.size == 1) {
                    delay(100)
                    clock.jumpedMs += 30_000
                }
            }
        }
        val cuts: () -> Long = {
            if (flippedAt < 0 && clock.now() >= 130_000 && ++reads == 4) {
                flippedAt = clock.now()
                cutCount++
            }
            cutCount
        }
        assertFalse(TransferInResume.atDeadline({ false }, collect, {}, settle = settle, cuts = cuts))
        assertTrue("flipped after the wake", flippedAt >= 130_000)
        assertEquals(listOf(100_000L, 205_100L), finals)
    }

    /**
     * (Item 8) After the extra (here a move of the hold), a freeze during the last collect (the app in the background, then
     * back) ends with one more `wait=0` look at once, held for nothing, not a drop after a look made before the freeze.
     */
    @Test
    fun afterTheExtraAFreezeDuringTheLastCollectEndsWithOneMoreLook() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        val finals = mutableListOf<Long>()
        var droppedAt = -1L
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        launch {
            delay(98_500)
            cutCount++ // the hold: 199 s
            delay(51_500)
            cutCount++ // noticed at 199 s: the hold moves to 299 s (the extra)
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 105_000) { // past the cap (105 s): the last collect
                finals += clock.now()
                if (finals.size == 1) {
                    delay(100)
                    clock.jumpedMs += 30_000 // to the background and back, during the last collect
                }
            }
        }
        assertFalse(TransferInResume.atDeadline({ false }, collect, { droppedAt = clock.now() }, settle = settle, cuts = { cutCount }))
        assertEquals(listOf(299_000L, 329_100L), finals)
        assertEquals(329_100L, droppedAt)
    }

    /**
     * (Review F1) A cut never leases from its send alone: a request written at 50 s (wait 0) whose cut is seen at 99 s may have
     * reached the relay, and been answered, as late as that. The lease runs from 99 s to 159 s; the hold goes at 174 s, not
     * at 50 + 60 + 15 = 125 s.
     */
    @Test
    fun aCutLeasesNoEarlierThanItsNotice() = runTest {
        val clock = FrozenClock(this)
        var cut = false
        val finals = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 100_000, maxExtraMs = 5_000, now = clock::now)
        launch {
            delay(98_500)
            cut = true
        }
        val collect: suspend (Long) -> Unit = { w -> if (w == 0L && clock.now() >= 105_000) finals += clock.now() }
        assertFalse(
            TransferInResume.atDeadline(
                { false }, collect, {}, settle = settle, cuts = { if (cut) 1L else 0L },
                cutLeases = { if (cut) listOf(CollectorCut(1L, clock.now() - 50_000, 0)) else emptyList() },
            ),
        )
        assertEquals(listOf(174_000L), finals)
    }

    /**
     * (Review F1) Our last collect goes out at 185 s (F = the cap) and hangs; it is abandoned at its bound, 215 s. It may have
     * reached the relay (after a wait for the network and a slow connect) and been handed the message as late as 215 s: the
     * one extra re-collects at 215 + V + 15 = 290 s, not at the send's 185 + V + 15 = 260 s.
     */
    @Test
    fun anAbandonedLastCollectLeasesFromTheAbandon() = runTest {
        val clock = FrozenClock(this)
        val finals = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 185_000, maxExtraMs = 0, now = clock::now)
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 185_000) {
                finals += clock.now()
                if (finals.size == 1) kotlinx.coroutines.awaitCancellation()
            }
        }
        assertFalse(TransferInResume.atDeadline({ false }, collect, {}, settle = settle))
        assertEquals(listOf(185_000L, 290_000L), finals)
    }

    /**
     * (Review F1) The same for a last collect that fails after 20 s with anything but a relay status (a body that did not
     * read): it may have been answered as late as 205 s, so the extra re-collects at 205 + V + 15 = 280 s.
     */
    @Test
    fun aFailedLastCollectLeasesFromTheFailure() = runTest {
        val clock = FrozenClock(this)
        val finals = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 185_000, maxExtraMs = 0, now = clock::now)
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 185_000) {
                finals += clock.now()
                if (finals.size == 1) {
                    delay(20_000)
                    error("unreadable")
                }
            }
        }
        assertFalse(TransferInResume.atDeadline({ false }, collect, {}, settle = settle))
        assertEquals(listOf(185_000L, 280_000L), finals)
    }

    /**
     * (Review F2) An extra taken before the cap sets no ceiling on the first hold. F = 90 s, the cap 185 s. The last look at
     * 90 s is rate limited (`retry_after` 1 s): the extra re-collects at 91 s, and the collector's request (written at 80 s,
     * wait 25 s) is cut then: its lease runs to 165 s, the end to 180 s. At 175 s the collector's request written at 174 s
     * (wait 25 s) is cut: its lease runs to 259 s, past the cap; the hold goes at 274 s, not at once.
     */
    @Test
    fun anExtraTakenBeforeTheCapDoesNotBlockTheFirstHold() = runTest {
        val clock = FrozenClock(this)
        val cuts = mutableListOf<CollectorCut>() // sentAgoMs holds the send time here; turned into an age when read
        var refused = false
        var reCollected = false
        val lasts = mutableListOf<Long>()
        launch {
            delay(175_000)
            cuts += CollectorCut(2L, 174_000L, 25_000L)
        }
        val collect: suspend (Long) -> Unit = { w ->
            when {
                w == 0L && clock.now() >= 90_000 && !refused -> {
                    refused = true
                    throw RelayException(429, RelayException.RATE_LIMITED, "", retryAfterSeconds = 1)
                }
                refused && !reCollected -> {
                    reCollected = true
                    cuts += CollectorCut(1L, 80_000L, 25_000L)
                }
                clock.now() >= 185_000 -> lasts += clock.now()
            }
        }
        assertFalse(
            TransferInResume.atDeadline(
                { false }, collect, {}, settle = timingWith(clock), cuts = { cuts.size.toLong() },
                cutLeases = { cuts.map { it.copy(sentAgoMs = clock.now() - it.sentAgoMs) } },
            ),
        )
        assertEquals(listOf(274_000L), lasts)
    }

    /**
     * (#308 review M1) The first hold for a lease is no move, also when the target was only the `retry_after` of an extra
     * taken before the cap. F = 104 s, the cap 105 s. The last look at 104 s is rate limited (`retry_after` 30 s): the extra
     * waits to 134 s. The collector's poll (written at 119 s, wait 25 s) is lost at 120 s, seen at 134 s: its lease runs to
     * 204 s, and the hold goes on to 219 s instead of the re-look at 134 s.
     */
    @Test
    fun aLeaseNoticedDuringAnEarlyExtrasRetryAfterStillGetsItsHold() = runTest {
        val clock = FrozenClock(this)
        val cuts = mutableListOf<CollectorCut>() // sentAgoMs holds the send time here; turned into an age when read
        var refused = false
        val lasts = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 104_000, maxExtraMs = 1_000, now = clock::now)
        launch {
            delay(120_000)
            cuts += CollectorCut(1L, 119_000L, 25_000L)
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 104_000) {
                if (!refused) {
                    refused = true
                    throw RelayException(429, RelayException.RATE_LIMITED, "", retryAfterSeconds = 30)
                }
                lasts += clock.now()
            }
        }
        assertFalse(
            TransferInResume.atDeadline(
                { false }, collect, {}, settle = settle, cuts = { cuts.size.toLong() },
                cutLeases = { cuts.map { it.copy(sentAgoMs = clock.now() - it.sentAgoMs) } },
            ),
        )
        assertEquals(listOf(219_000L), lasts)
    }

    /**
     * (#308 R2) A send since the last tick before a freeze may not have left the phone before it froze: its bytes can reach
     * the relay at the wake, which parks it for its wait. F = the cap = 105 s. The collector's poll (wait 25 s) is written at
     * the tick of 103 s and the process freezes 0.1 s later for 30 s; the wake is seen at 134 s. The lease starts by the wake
     * + w = 159 s and runs to 219 s: the hold goes at 234 s, not at 103 + 25 + 60 + 15 = 203 s.
     */
    @Test
    fun aSendAtTheLastTickBeforeAFreezeLeasesFromTheWake() = runTest {
        val clock = FrozenClock(this)
        val sends = mutableListOf<Pair<Long, Long>>()
        val lasts = mutableListOf<Long>()
        val settle = TransferInResume.Settle(windowMs = 105_000, maxExtraMs = 0, now = clock::now)
        launch {
            delay(103_000)
            sends += clock.now() to 25_000L
            delay(100)
            clock.jumpedMs += 30_000
        }
        val collect: suspend (Long) -> Unit = { w -> if (w == 0L && clock.now() >= 135_000) lasts += clock.now() }
        assertFalse(
            TransferInResume.atDeadline(
                { false }, collect, {}, settle = settle, collectorSends = { sends.map { (clock.now() - it.first) to it.second } },
            ),
        )
        assertEquals(listOf(234_000L), lasts)
    }

    /**
     * (Recheck 2, B1) After an extra taken before the cap (no ceiling), the one look after a freeze in the last send still goes
     * at once. V = 300 s, F = 400 s, the cap 401 s. The last look at 400 s is rate limited (`retry_after` 300 s): the extra holds
     * to 700 s. A cut is seen at the end of that hold (undescribed: its lease from 725 s to 1,025 s): the free first lease hold
     * goes to 1,040 s. That last collect is frozen for 30 s: the look goes at the wake (1,070.1 s), not at the freeze's lease
     * + 15 s (1,385.1 s).
     */
    @Test
    fun theLookAfterAFreezeGoesAtOnceAfterAnExtraTakenBeforeTheCap() = runTest {
        val clock = FrozenClock(this)
        var cutCount = 0L
        var refused = false
        val lasts = mutableListOf<Long>()
        val settle = TransferInResume.Settle(
            windowMs = 400_000, maxExtraMs = 1_000, visibilityMs = 300_000, now = clock::now,
        )
        launch {
            delay(699_000)
            cutCount++ // seen at the hold's look at 700 s (undescribed: now + 25 s)
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 400_000) {
                if (!refused) {
                    refused = true
                    throw RelayException(429, RelayException.RATE_LIMITED, "", retryAfterSeconds = 300)
                }
                lasts += clock.now()
                if (lasts.size == 1) {
                    delay(100)
                    clock.jumpedMs += 30_000 // to the background and back, during the last collect
                }
            }
        }
        assertFalse(TransferInResume.atDeadline({ false }, collect, {}, settle = settle, cuts = { cutCount }))
        assertEquals(listOf(1_040_000L, 1_070_100L), lasts)
    }

    /**
     * (Recheck 4, B1′) After an extra taken before the cap with no lease hold honoured yet, the look after a freeze in the
     * last send gets the free first lease hold. F = 90 s, the cap 185 s. The last look at 90 s is rate limited (`retry_after`
     * 1 s): the extra re-collects at 91 s. Its round writes the collector's poll at 94.5 s (wait 25 s) and the app freezes at
     * 94.9 s until 195 s: the poll may reach the relay at the wake, so the lease runs to 195 + 25 + 60 = 280 s. The look is
     * held to 295 s, not sent at once at the wake.
     */
    @Test
    fun theLookAfterAFreezeAfterAnEarlyExtraGetsTheFreeLeaseHold() = runTest {
        val clock = FrozenClock(this)
        val sends = mutableListOf<Pair<Long, Long>>()
        var refused = false
        var froze = false
        val lasts = mutableListOf<Long>()
        val round: suspend () -> Unit = {
            if (refused && !froze && lasts.isNotEmpty()) {
                froze = true
                delay(3_500)
                sends += clock.now() to 25_000L // the collector's poll, written at 94.5 s
                delay(400)
                clock.jumpedMs += 100_100 // frozen 94.9 s to 195 s, after the tick of 94 s
            }
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 90_000) {
                if (!refused) {
                    refused = true
                    throw RelayException(429, RelayException.RATE_LIMITED, "", retryAfterSeconds = 1)
                }
                lasts += clock.now()
            }
        }
        assertFalse(
            TransferInResume.atDeadline(
                { false }, collect, {}, settle = timingWith(clock), awaitRound = round,
                collectorSends = { sends.map { (clock.now() - it.first) to it.second } },
            ),
        )
        assertEquals(listOf(91_000L, 295_000L), lasts)
    }

    /**
     * (Recheck 4, R2 edge) The lead's own timeline: the freeze at exactly 95 s, so the tick of 95 s falls between the poll
     * (94.5 s) and the freeze. A send since one heartbeat before that last tick still leases from the wake: 195 + 25 + 60 =
     * 280 s, and the look is held to 295 s.
     */
    @Test
    fun theLookAfterAFreezeAtExactly95sAfterAnEarlyExtraGetsTheFreeLeaseHold() = runTest {
        val clock = FrozenClock(this)
        val sends = mutableListOf<Pair<Long, Long>>()
        var refused = false
        var froze = false
        val lasts = mutableListOf<Long>()
        val round: suspend () -> Unit = {
            if (refused && !froze && lasts.isNotEmpty()) {
                froze = true
                delay(3_500)
                sends += clock.now() to 25_000L // the collector's poll, written at 94.5 s
                delay(500)
                clock.jumpedMs += 100_000 // frozen 95 s to 195 s, after the tick of 95 s
            }
        }
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && clock.now() >= 90_000) {
                if (!refused) {
                    refused = true
                    throw RelayException(429, RelayException.RATE_LIMITED, "", retryAfterSeconds = 1)
                }
                lasts += clock.now()
            }
        }
        assertFalse(
            TransferInResume.atDeadline(
                { false }, collect, {}, settle = timingWith(clock), awaitRound = round,
                collectorSends = { sends.map { (clock.now() - it.first) to it.second } },
            ),
        )
        assertEquals(listOf(91_000L, 295_000L), lasts)
    }
}
