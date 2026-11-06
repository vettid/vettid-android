package com.vettid.core.data.vault

import com.vettid.core.data.vault.TransferInResume.Action
import com.vettid.core.relay.RelayException
import com.vettid.core.vault.TransferInState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * (A6 audit N1; VAULT-MESSAGING 0.56.2 §11.14.10 rule 5, ANDROID-PLAN 0.1.76) A start after a kill at each step of a
 * transfer to this phone, and the one 11-minute deadline with its collect before the drop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferInResumeTest {
    private val at = Instant.parse("2026-10-23T10:00:00Z")
    private val shown = TransferInState(at, "042817", paired = false, userGuid = null)
    private val paired = TransferInState(at, "042817", paired = true, userGuid = "m-1")

    @Test
    fun aKillAtEachStepResumesFromThatStep() {
        val before = at.plusSeconds(60)
        // Nothing started.
        assertEquals(Action.NONE, TransferInResume.decide(null, null, before))
        // Killed after hs.init, before hs.resp: no code, no hs.fin sent: dropped.
        assertEquals(Action.DROP, TransferInResume.decide(TransferInState(at, null, paired = false, userGuid = null), null, before))
        // Killed after the code was shown (hs.fin may have reached the vault), started before the deadline: waiting.
        assertEquals(Action.WAIT, TransferInResume.decide(shown, null, before))
        // Killed after device.paired was applied, before the credential was taken over: completed.
        assertEquals(Action.COMPLETE, TransferInResume.decide(paired, "dev-2", before))
        // A vault release before 0.17.0 sends no user_guid: completed all the same.
        assertEquals(Action.COMPLETE, TransferInResume.decide(paired.copy(userGuid = null), "dev-2", before))
    }

    /** One deadline, the stored start of the hs.init plus 11 minutes; a paired transfer completes at any age. */
    @Test
    fun theDeadlineIsTheHsInitPlusElevenMinutes() {
        val deadline = at.plus(Duration.ofMinutes(11))
        assertEquals(Action.WAIT, TransferInResume.decide(shown, null, deadline.minusMillis(1)))
        // Killed before the deadline, started after it: the collect and the drop at that start.
        assertEquals(Action.DEADLINE, TransferInResume.decide(shown, null, deadline))
        assertEquals(Action.DEADLINE, TransferInResume.decide(shown, null, deadline.plus(Duration.ofDays(3))))
        assertEquals(Action.COMPLETE, TransferInResume.decide(paired, "dev-2", deadline.plus(Duration.ofDays(30))))
        assertEquals(Action.DROP, TransferInResume.decide(TransferInState(at, null, paired = false, userGuid = null), null, deadline))
    }

    private fun TestScope.timing(windowMs: Long = TransferInResume.SETTLE_WINDOW_MS) =
        TransferInResume.Settle(windowMs = windowMs, now = { testScheduler.currentTime })

    private val TestScope.t get() = testScheduler.currentTime

    /**
     * A mailbox whose device.paired is leased until [leaseEndsAt] (virtual ms) to a request that is gone: a collect
     * (its [wait], after [roundWaitMs] of waiting for the collection's round) finds nothing before that time and gets the
     * message at the moment the lease ends, if that is within its wait. A long poll takes its wait, like the relay's.
     */
    private class LeasedMailbox(
        private val scope: TestScope,
        private var leaseEndsAt: Long,
        private val roundWaitMs: Long = 0,
    ) {
        var isPaired = false
        val waits = mutableListOf<Long>()

        /**
         * A poll that is cut stays parked at the relay until its wait is over: a message that comes free meanwhile is
         * handed to that dead request and leased for 60 s more (the bug).
         */
        suspend fun collect(waitMs: Long) {
            waits += waitMs
            if (roundWaitMs > 0) delay(roundWaitMs)
            val end = scope.testScheduler.currentTime + waitMs
            try {
                while (true) {
                    val now = scope.testScheduler.currentTime
                    if (now >= leaseEndsAt) { isPaired = true; return }
                    if (now >= end) return
                    delay(minOf(end, leaseEndsAt) - now)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                val now = scope.testScheduler.currentTime
                if (leaseEndsAt in (now + 1)..end) leaseEndsAt += LEASE_MS
                throw e
            }
        }

        companion object {
            const val LEASE_MS = 60_000L
        }
    }

    /** At the deadline a device.paired waiting on the relay is applied by the first collect: completed, not dropped. */
    @Test
    fun aWaitingDevicePairedIsAppliedAtTheDeadline() = runTest {
        var isPaired = false
        var collects = 0
        var dropped = false
        val r = TransferInResume.atDeadline({ isPaired }, { collects++; isPaired = true }, { dropped = true }, settle = timing())
        assertTrue(r)
        assertEquals(1, collects)
        assertFalse(dropped)
    }

    @Test
    fun nothingWaitingIsDroppedAfterTheFullWindowOfCollects() = runTest {
        val box = LeasedMailbox(this, leaseEndsAt = Long.MAX_VALUE)
        var droppedAt = -1L
        assertFalse(TransferInResume.atDeadline({ box.isPaired }, box::collect, { droppedAt = t }, settle = timing()))
        assertTrue("collected through the window, not once", box.waits.size > 1)
        assertEquals("the full window, never earlier", TransferInResume.SETTLE_WINDOW_MS, droppedAt)
        assertEquals("ends with a wait=0 collect", 0L, box.waits.last())
    }

    @Test
    fun theWindowIsTheAdvertisedVisibilityTimeoutPlusThePollCapPlusAMargin() {
        assertEquals(90_000L, TransferInResume.SETTLE_WINDOW_MS)
        assertEquals(60_000L + 25_000L + 5_000L, TransferInResume.windowMs(60))
        assertEquals(90_000L, TransferInResume.windowMs(null))
        assertEquals(45_000L + 25_000L + 5_000L, TransferInResume.windowMs(45))
    }

    /** A request that hangs is given its wait plus the transport bound, is then abandoned, and the window goes on. */
    @Test
    fun aCollectThatHangsIsBoundedByItsWaitPlusTransportThenAbandoned() = runTest {
        var droppedAt = -1L
        val starts = mutableListOf<Long>()
        val waits = mutableListOf<Long>()
        TransferInResume.atDeadline(
            { false },
            { w -> waits += w; starts += t; CompletableDeferred<Unit>().await() },
            { droppedAt = t },
            settle = timing(),
        )
        assertEquals("never cut before its wait plus 30 s: first starts at 0, next no earlier than 55 s", 0L, starts[0])
        assertEquals(25_000L, waits[0])
        assertTrue(starts[1] >= 25_000L + 30_000L)
        assertTrue("an abandoned request may hold a lease: the window moved out", droppedAt > TransferInResume.SETTLE_WINDOW_MS)
        assertEquals(400_000L, droppedAt)
    }

    @Test
    fun pairedAtTheFirstCollectReturnsAtOnce() = runTest {
        var isPaired = false
        var collects = 0
        assertTrue(TransferInResume.atDeadline({ isPaired }, { collects++; isPaired = true }, { error("dropped") }, settle = timing()))
        assertEquals(1, collects)
        assertEquals(0, t)
    }

    /**
     * The restart after a kill: the vault's device.paired is leased for 60 s to the dead app's parked request. Collects
     * before the lease ends find nothing; the first one after it (a long poll running through it hands it over) gets
     * the message and pairs. The old single collect dropped here.
     */
    @Test
    fun aMessageLeasedByADeadRequestIsCollectedWhenTheLeaseEnds() = runTest {
        val box = LeasedMailbox(this, leaseEndsAt = 60_000)
        var drops = 0
        assertTrue(TransferInResume.atDeadline({ box.isPaired }, box::collect, { drops++ }, settle = timing()))
        assertEquals(0, drops)
        assertTrue("paired when the lease ended: $t", t in 60_000..61_000)
        assertTrue("long polls were made", box.waits.any { it > 0 })
    }

    /**
     * (review H1) The lease of a message handed to our own long poll must not be cut short: with the collection's round
     * waiting 20 s before every request and the message leased until 89.9 s, the old 30 s bound cut the poll (and the
     * message stayed leased to a dead request past the window); now no request is cut before its wait plus 30 s, and the
     * message is gathered when it comes free.
     */
    @Test
    fun ourOwnPollIsNotCutWhileItCanHoldAMessage() = runTest {
        val box = LeasedMailbox(this, leaseEndsAt = 89_900, roundWaitMs = 20_000)
        var drops = 0
        assertTrue(TransferInResume.atDeadline({ box.isPaired }, box::collect, { drops++ }, settle = timing()))
        assertEquals(0, drops)
    }

    /**
     * (recheck L-d) The same with the message coming free while the first request is parked (round wait 20 s, then its
     * 25 s poll): a request bounded at 30 s instead of its wait + 30 s is cut at 30 s, the relay hands the message to
     * that dead request at 40 s and leases it until 100 s, past the window.
     */
    @Test
    fun aRequestIsBoundedByItsWaitPlusTransportNotByTransportAlone() = runTest {
        val box = LeasedMailbox(this, leaseEndsAt = 40_000, roundWaitMs = 20_000)
        var drops = 0
        assertTrue(TransferInResume.atDeadline({ box.isPaired }, box::collect, { drops++ }, settle = timing()))
        assertEquals(0, drops)
        assertTrue("paired when it came free, by the first request: $t", t <= 46_000)
    }

    /**
     * (recheck L-d) A request that hangs is abandoned at 55 s and may have been handed the message when it comes free at
     * 100 s (after the first end, 90 s): the extended window (to 145 s) is what still pairs it.
     */
    @Test
    fun aCutRequestExtendsTheWindowSoALateLeaseIsStillGathered() = runTest {
        var isPaired = false
        var calls = 0
        var drops = 0
        val collect: suspend (Long) -> Unit = { w ->
            if (calls++ == 0) CompletableDeferred<Unit>().await() else if (t >= 100_000) isPaired = true else delay(w)
        }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = timing()))
        assertEquals(0, drops)
        assertTrue("after the first end, within the cap: $t", t in 100_000..150_000)
    }

    /** (recheck L-b) A failure that is not a relay status may have lost the answer to a leased message: it extends too. */
    @Test
    fun aFailureWithoutAStatusExtendsTheWindow() = runTest {
        var isPaired = false
        var drops = 0
        val collect: suspend (Long) -> Unit = { if (t >= 100_000) isPaired = true else throw IOException("body lost") }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }, settle = timing()))
        assertEquals(0, drops)
    }

    /** (recheck L-b) A relay status came back: nothing was leased, no extension; the drop is at the first end. */
    @Test
    fun aRelayStatusDoesNotExtendTheWindow() = runTest {
        var droppedAt = -1L
        val collect: suspend (Long) -> Unit = { throw RelayException(500, "internal", "no") }
        assertFalse(TransferInResume.atDeadline({ false }, collect, { droppedAt = t }, settle = timing()))
        assertEquals(TransferInResume.SETTLE_WINDOW_MS, droppedAt)
    }

    /**
     * (review of #302, 1) With the collection running, a request is: the collection's round (25 s), our own collect
     * (10 s), the round again (25 s). The round waits are not under the request's bound: the request gets its own wait
     * plus 30 s. A bound that covered all of it (60 s here, against 30 s at the end of the window) cut our request while
     * it was out at the relay.
     */
    @Test
    fun theCollectionsRoundIsNotUnderOurRequestsBound() = runTest {
        var isPaired = false
        var drops = 0
        var requestCut = false
        val round: suspend () -> Unit = { delay(40_000) }
        val request: suspend (Long) -> Unit = {
            try {
                delay(10_000)
                isPaired = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                requestCut = true
                throw e
            }
        }
        // A window already at its end: the one final request (wait=0, bound 30 s) decides.
        val settle = TransferInResume.Settle(windowMs = 1_000, now = { testScheduler.currentTime })
        assertTrue(TransferInResume.atDeadline({ isPaired }, request, { drops++ }, settle = settle, awaitRound = round))
        assertFalse("our request was not cut", requestCut)
        assertEquals(0, drops)
    }

    /** A round wait that never ends is bounded on its own and is not an abandoned request: the window is not extended. */
    @Test
    fun aRoundThatNeverEndsIsBoundedAndMovesNothing() = runTest {
        var droppedAt = -1L
        val round: suspend () -> Unit = { CompletableDeferred<Unit>().await() }
        val settle = timing()
        assertFalse(TransferInResume.atDeadline({ false }, {}, { droppedAt = t }, settle = settle, awaitRound = round))
        // Each step waits 55 s twice for the round (before and after its request): the loop's one step and the final one;
        // nothing was pushed out by the rounds (a round is no abandoned request).
        assertEquals(4 * 55_000L, droppedAt)
    }

    /** (review of #302, 5) Abandoned requests move the end out by at most the clamped visibility timeout. */
    @Test
    fun theExtensionIsCappedAtTheVisibilityTimeout() = runTest {
        assertEquals(95_000L, TransferInResume.SETTLE_MAX_EXTRA_MS)
        assertEquals(60_000L, TransferInResume.visibilityMs(null))
        assertEquals(300_000L, TransferInResume.visibilityMs(3_600))
        assertEquals(0L, TransferInResume.visibilityMs(0))
        var droppedAt = -1L
        val settle = TransferInResume.Settle(maxExtraMs = 10_000, now = { testScheduler.currentTime })
        TransferInResume.atDeadline({ false }, { CompletableDeferred<Unit>().await() }, { droppedAt = t }, settle = settle)
        // The END stays within first end 90 s + 10 s; the abandoned requests' leases (up to a visibility timeout past their
        // notice, past the cap) are waited for by the one held last collect, then the drop: bounded, 301 s here.
        assertEquals(301_000L, droppedAt)
    }

    /**
     * (recheck of 278b0b0a) The abandon is refused from the vault's 10 minutes (from the stored start) on, not only once
     * the window has begun at 11: for a transfer with a code, not paired.
     */
    @Test
    fun theAbandonIsRefusedFromTheVaultsTenMinutes() {
        val ten = at.plus(Duration.ofMinutes(10))
        assertFalse(TransferInResume.pastVaultWindow(shown, ten.minusMillis(1)))
        assertTrue(TransferInResume.pastVaultWindow(shown, ten))
        assertTrue("in the minute before the window too", TransferInResume.pastVaultWindow(shown, at.plus(Duration.ofSeconds(630))))
        assertTrue(TransferInResume.pastVaultWindow(shown, at.plus(Duration.ofMinutes(12))))
        assertFalse("no code shown: nothing to protect", TransferInResume.pastVaultWindow(shown.copy(sas = null), ten))
        assertFalse("paired: completed, not abandoned", TransferInResume.pastVaultWindow(paired, ten))
        assertFalse(TransferInResume.pastVaultWindow(null, ten))
    }

    /** (recheck L-a) A relay's advertised visibility timeout is trusted within 0 to 300 s only. */
    @Test
    fun theAdvertisedVisibilityTimeoutIsClamped() {
        assertEquals(300_000L + 30_000L, TransferInResume.windowMs(3_600))
        assertEquals(30_000L, TransferInResume.windowMs(0))
        assertEquals(30_000L, TransferInResume.windowMs(-5))
        assertEquals(90_000L, TransferInResume.windowMs(60))
    }

    /**
     * (review M1) A delivery at the very edge of the window is handled in full: no cancelling timeout cuts the step; the
     * final wait=0 collect gets it.
     */
    @Test
    fun aDeliveryAtTheWindowsEdgeIsHandledInFull() = runTest {
        val edge = TransferInResume.SETTLE_WINDOW_MS
        val box = LeasedMailbox(this, leaseEndsAt = edge)
        var drops = 0
        assertTrue(TransferInResume.atDeadline({ box.isPaired }, box::collect, { drops++ }, settle = timing()))
        assertEquals(0, drops)
        // And a collect that is slow to handle what it brings (65 s + 2 s) is not cut either.
        var isPaired = false
        var d2 = 0
        val slow: suspend (Long) -> Unit = { w -> if (w == 0L) { delay(2_000); isPaired = true } }
        val before = t
        assertTrue(TransferInResume.atDeadline({ isPaired }, slow, { d2++ }, settle = timing()))
        assertEquals(0, d2)
        assertTrue("only the last requests of the window bring it", t - before >= TransferInResume.SETTLE_WINDOW_MS - 10_000)
    }

    @Test
    fun failingCollectsDoNotEndTheWindowEarly() = runTest {
        var droppedAt = -1L
        var collects = 0
        assertFalse(
            TransferInResume.atDeadline(
                { false },
                { collects++; throw RelayException(503, "unavailable", "relay unreachable") },
                { droppedAt = t },
                settle = timing(),
            ),
        )
        assertTrue(collects > 1)
        assertEquals(TransferInResume.SETTLE_WINDOW_MS, droppedAt)
    }

    @Test
    fun aCollectFailingThenSucceedingPairs() = runTest {
        var isPaired = false
        var collects = 0
        val r = TransferInResume.atDeadline(
            { isPaired },
            { if (++collects < 3) throw IOException("down") else isPaired = true },
            { error("dropped") },
            settle = timing(),
        )
        assertTrue(r)
        assertEquals(3, collects)
    }

    /** A rejection or the member's abandon (the transfer no longer alive) ends the window at once, with no drop here. */
    @Test
    fun aRejectionOrAbandonEndsTheWindowAtOnce() = runTest {
        var alive = true
        var drops = 0
        var collects = 0
        val r = TransferInResume.atDeadline(
            { false }, { collects++; if (collects == 3) alive = false }, { drops++ }, alive = { alive }, settle = timing(),
        )
        assertFalse(r)
        assertEquals(0, drops)
        assertEquals(3, collects)
        assertTrue(t < TransferInResume.SETTLE_WINDOW_MS)
    }

    @Test
    fun failedCollectsBackOff() = runTest {
        val at = mutableListOf<Long>()
        TransferInResume.atDeadline({ false }, { at += t; throw IOException("down") }, {}, settle = timing())
        val gaps = at.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), gaps.take(4))
        // the last gap is the held final collect (a lost attempt may hold a lease); the others are the backoff
        // after the 1, 2, 4 s backoff: 8 s steps to the cap, then the held last collect (V + 5 s later) and the one extra (V + 5 s)
        assertEquals(List(21) { 8_000L } + listOf(65_000L, 65_000L), gaps.drop(4))
    }

    @Test
    fun cancellationDuringTheWindowPropagatesWithoutADrop() = runTest {
        var drops = 0
        val box = LeasedMailbox(this, leaseEndsAt = Long.MAX_VALUE)
        val job = async { TransferInResume.atDeadline({ false }, box::collect, { drops++ }, settle = timing()) }
        testScheduler.advanceTimeBy(20_000)
        job.cancel()
        try {
            job.await()
            org.junit.Assert.fail("cancelled")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertEquals(0, drops)
    }

    @Test
    fun anAlreadyPairedTransferIsNotCollectedForOrDropped() = runTest {
        var collects = 0
        assertTrue(TransferInResume.atDeadline({ true }, { collects++ }, { error("dropped") }, settle = timing()))
        assertEquals(0, collects)
    }

    /**
     * (#189 recheck) The wait counts from the start the device stored just before the hs.init (kept across restarts), not
     * from when this call began: 5 minutes after that start, 6 minutes are left.
     */
    @Test
    fun theWaitCountsFromTheStoredStart() = runTest {
        val now = at.plus(Duration.ofMinutes(5))
        var asked: Duration? = null
        val r = TransferInResume.awaitApproval({ shown }, { now }, { left -> asked = left; "m-1" }) { error("no deadline") }
        assertEquals(Duration.ofMinutes(6), asked)
        assertEquals(TransferInResume.Approved("m-1"), r)
        // Nothing stored: nothing to wait for (not 11 minutes counted from now), and no deadline step either.
        asked = null
        assertEquals(null, TransferInResume.awaitApproval({ null }, { now }, { left -> asked = left; "x" }) { error("no deadline") })
        assertEquals(null, asked)
    }

    /** The wait ran out: the deadline's collect decides, applied (paired) or dropped (null). */
    @Test
    fun theWaitRunningOutGoesToTheDeadline() = runTest {
        val late = at.plus(Duration.ofMinutes(12))
        var asked: Duration? = null
        val timeout: suspend (Duration) -> String? = { left ->
            asked = left
            kotlinx.coroutines.withTimeout(1) { CompletableDeferred<String?>().await() }
        }
        assertEquals(TransferInResume.Approved("m-1"), TransferInResume.awaitApproval({ shown }, { late }, timeout) { paired })
        assertEquals("past the deadline: the least wait", Duration.ofSeconds(1), asked)
        assertEquals(null, TransferInResume.awaitApproval({ shown }, { late }, timeout) { null })
    }

    /**
     * (#187 recheck) The answer is read after the drop: a handler that won the device's lock during it (the drop refused
     * a paired device) makes it paired, and it must say so.
     */
    @Test
    fun theAnswerIsReadAfterTheDrop() = runTest {
        var isPaired = false
        val r = TransferInResume.atDeadline({ isPaired }, {}, { isPaired = true }, settle = timing())
        assertTrue(r)
    }

    /**
     * (#187 recheck) Two deadline steps at once: the first one's collect leases the device.paired and applies it a moment
     * later; the second waits for it and, no longer due, drops nothing and answers paired.
     */
    @Test
    fun twoDeadlineStepsAtOnceNeverDropALeasedPairing() = runTest {
        var isPaired = false
        var drops = 0
        val leased = CompletableDeferred<Unit>()
        val serial = TransferInResume.Serial()
        val due = { !isPaired }
        val first = async { serial.run(due, { isPaired }, { leased.await(); isPaired = true }, { drops++ }, settle = timing()) }
        val second = async { serial.run(due, { isPaired }, {}, { drops++ }, settle = timing()) }
        testScheduler.advanceTimeBy(1_000)
        assertFalse("the second waits for the first", second.isCompleted)
        leased.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals("nothing dropped", 0, drops)
    }

    /**
     * (#187 recheck) The screen's step 1 ms before the stored deadline (a timeout firing early, a clock set back) still
     * collects and decides; a paired or a dropped transfer is no longer due.
     */
    @Test
    fun theScreensStepJustBeforeTheDeadlineStillCollects() = runTest {
        val early = at.plus(Duration.ofMinutes(11)).minusMillis(1)
        assertTrue(TransferInResume.deadlineDue(shown, null, early))
        assertTrue(TransferInResume.deadlineDue(shown, null, at.plus(Duration.ofMinutes(11))))
        assertFalse(TransferInResume.deadlineDue(paired, "dev-2", early))
        assertFalse(TransferInResume.deadlineDue(null, null, early))
        var collects = 0
        var drops = 0
        val r = TransferInResume.Serial().run(
            due = { TransferInResume.deadlineDue(shown, null, early) },
            paired = { false },
            collect = { collects++ },
            drop = { drops++ },
            settle = timing(),
        )
        assertFalse(r)
        assertTrue("collected", collects >= 1)
        assertEquals("then decided: dropped", 1, drops)
    }
}
