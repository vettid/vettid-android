package com.vettid.core.data.vault

import com.vettid.core.data.vault.TransferInResume.Action
import com.vettid.core.vault.TransferInState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

    /** At the deadline a device.paired waiting on the relay is applied by the one collect: completed, not dropped. */
    @Test
    fun aWaitingDevicePairedIsAppliedAtTheDeadline() = runTest {
        var isPaired = false
        var collects = 0
        var dropped = false
        val r = TransferInResume.atDeadline({ isPaired }, { collects++; isPaired = true }, { dropped = true })
        assertTrue(r)
        assertEquals(1, collects)
        assertFalse(dropped)
    }

    @Test
    fun nothingWaitingIsDropped() = runTest {
        var collects = 0
        var dropped = false
        assertFalse(TransferInResume.atDeadline({ false }, { collects++ }, { dropped = true }))
        assertTrue("collected through the window, not once", collects > 1)
        assertTrue(dropped)
    }

    @Test
    fun aFailedCollectIsDropped() = runTest {
        var dropped = false
        assertFalse(TransferInResume.atDeadline({ false }, { throw IOException("relay unreachable") }, { dropped = true }))
        assertTrue(dropped)
    }

    /** A collect that hangs is bounded (30 s each); the window (65 s) still ends the step; then dropped. */
    @Test
    fun aCollectThatHangsIsBoundedThenDropped() = runTest {
        var dropped = false
        var collects = 0
        val start = testScheduler.currentTime
        assertFalse(
            TransferInResume.atDeadline({ false }, { collects++; CompletableDeferred<Unit>().await() }, { dropped = true }),
        )
        assertTrue(dropped)
        assertTrue("each hung collect timed out and was retried", collects >= 2)
        assertEquals(TransferInResume.SETTLE_WINDOW_MS, testScheduler.currentTime - start)
    }

    @Test
    fun pairedAtTheFirstCollectReturnsAtOnce() = runTest {
        var isPaired = false
        var collects = 0
        val start = testScheduler.currentTime
        assertTrue(TransferInResume.atDeadline({ isPaired }, { collects++; isPaired = true }, { error("dropped") }))
        assertEquals(1, collects)
        assertEquals(0, testScheduler.currentTime - start)
    }

    /**
     * The restart after a kill: the vault's device.paired is leased for 60 s to the dead app's parked request. Collects
     * before the lease ends find nothing; the first one after it gets the message and pairs. The old single collect
     * dropped here.
     */
    @Test
    fun aMessageLeasedByADeadRequestIsCollectedWhenTheLeaseEnds() = runTest {
        var isPaired = false
        var drops = 0
        val start = testScheduler.currentTime
        val collect: suspend () -> Unit = {
            if (testScheduler.currentTime - start >= 60_000) isPaired = true
        }
        assertTrue(TransferInResume.atDeadline({ isPaired }, collect, { drops++ }))
        assertEquals(0, drops)
        val took = testScheduler.currentTime - start
        assertTrue("paired after the lease, within the window: $took", took in 60_000..TransferInResume.SETTLE_WINDOW_MS)
    }

    @Test
    fun neverPairedIsDroppedOnlyOnceTheWindowHasPassed() = runTest {
        val start = testScheduler.currentTime
        var droppedAt = -1L
        assertFalse(
            TransferInResume.atDeadline({ false }, {}, { droppedAt = testScheduler.currentTime - start }),
        )
        assertEquals(TransferInResume.SETTLE_WINDOW_MS, droppedAt)
    }

    @Test
    fun failingCollectsDoNotEndTheWindowEarly() = runTest {
        val start = testScheduler.currentTime
        var droppedAt = -1L
        var collects = 0
        assertFalse(
            TransferInResume.atDeadline(
                { false },
                { collects++; throw IOException("relay unreachable") },
                { droppedAt = testScheduler.currentTime - start },
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
        val start = testScheduler.currentTime
        val r = TransferInResume.atDeadline(
            { false }, { collects++; if (collects == 3) alive = false }, { drops++ }, alive = { alive },
        )
        assertFalse(r)
        assertEquals(0, drops)
        assertEquals(3, collects)
        assertTrue(testScheduler.currentTime - start < TransferInResume.SETTLE_WINDOW_MS)
    }

    @Test
    fun failedCollectsBackOff() = runTest {
        val at = mutableListOf<Long>()
        TransferInResume.atDeadline({ false }, { at += testScheduler.currentTime; throw IOException("down") }, {})
        val gaps = at.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), gaps.take(4))
        assertTrue(gaps.all { it <= 8_000L })
    }

    @Test
    fun cancellationDuringTheWindowPropagatesWithoutADrop() = runTest {
        var drops = 0
        val job = async { TransferInResume.atDeadline({ false }, {}, { drops++ }) }
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
        assertTrue(TransferInResume.atDeadline({ true }, { collects++ }, { error("dropped") }))
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
        // Nothing stored: the whole 11 minutes.
        TransferInResume.awaitApproval({ null }, { now }, { left -> asked = left; null }) { error("no deadline") }
        assertEquals(Duration.ofMinutes(11), asked)
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
        val r = TransferInResume.atDeadline({ isPaired }, {}, { isPaired = true })
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
        val first = async { serial.run(due, { isPaired }, { leased.await(); isPaired = true }, { drops++ }) }
        val second = async { serial.run(due, { isPaired }, {}, { drops++ }) }
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
        )
        assertFalse(r)
        assertTrue("collected", collects >= 1)
        assertEquals("then decided: dropped", 1, drops)
    }
}
