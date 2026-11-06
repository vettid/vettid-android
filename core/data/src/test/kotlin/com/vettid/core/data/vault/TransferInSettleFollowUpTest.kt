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
     * wait=0 (the collection runs) leases from its send, not 25 s later. Sent at 75 s, frozen, woken past the cap (105 s): the
     * hold goes at 75 + 60 + 15 = 150 s, not 175 s.
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
        assertEquals(listOf(150_000L), finals)
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
     * moves nothing and the one extra stays unused. The collector sends at 85 s (wait 25 s), freezes 12 s; the freeze's
     * lease runs to 170 s, the hold to 185 s. The cut of that request is counted at 120 s, after the wake: it was written
     * before the freeze, so its lease starts by 110 s, not at the notice (120 s, which would hold to 195 s): no move.
     * A cut during the last collect at 185 s (lease to 245 s) then still gets the extra: a held re-look at 260 s.
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
        assertEquals(listOf(185_000L, 260_000L), finals)
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
     * last collect at 100 s is frozen for 30 s: its lease runs to 160 s, the re-look goes at 175 s, not at the cut's 230 s.
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
        assertEquals(listOf(100_000L, 175_000L), finals)
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
}
