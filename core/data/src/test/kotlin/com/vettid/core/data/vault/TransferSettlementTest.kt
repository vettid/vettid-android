package com.vettid.core.data.vault

import com.vettid.core.vault.PairingRejectedException
import com.vettid.core.vault.TransferInState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * (review of #302, 6) The settlement's wiring ([settleTransfer]) over a fake device, on virtual time: a rejection ends as
 * REJECTED, `wait=0` while the collection runs, a session that went away is no drop, only this transfer is dropped; and
 * [SingleFlight]: leaving the screen does not cancel the settlement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferSettlementTest {
    private val at = Instant.parse("2026-10-23T10:00:00Z")
    private val pastDeadline = at.plus(Duration.ofMinutes(12))
    private val shown = TransferInState(at, "042817", paired = false, userGuid = null)
    private val paired = TransferInState(at, "042817", paired = true, userGuid = "m-1")

    private class FakeDevice(var pending: TransferInState?, private val scope: TestScope) : SettleDevice {
        override val transferInPending get() = pending
        override var deviceId: String? = null
        override var collecting = false
        var visibility: Long? = 60
        var rejected = false
        var pairedAtMs: Long? = null
        val waits = mutableListOf<Duration>()
        val abandoned = mutableListOf<Instant>()
        var roundMs = 0L

        override suspend fun relayVisibilitySeconds() = visibility

        override suspend fun awaitCollectorRound() { if (roundMs > 0) delay(roundMs) }

        override suspend fun collectOnce(wait: Duration) {
            waits += wait
            delay(wait.toMillis())
            val p = pairedAtMs
            if (p != null && scope.testScheduler.currentTime >= p) {
                pending = pending?.copy(paired = true, userGuid = "m-1")
                deviceId = "dev-2"
            }
        }

        override suspend fun abandonTransfer(onlyStartedAt: Instant) {
            abandoned += onlyStartedAt
            if (pending?.startedAt == onlyStartedAt && deviceId == null) pending = null
        }

        override suspend fun awaitTransfer(timeout: Duration) {
            if (rejected) throw PairingRejectedException()
            withTimeout(1) { CompletableDeferred<Unit>().await() }
        }
    }

    private suspend fun TestScope.settle(
        d: FakeDevice,
        current: () -> Boolean = { true },
        windowOf: (Long?) -> Long = { TransferInResume.windowMs(it) },
    ) = settleTransfer(
        d, current, TransferInResume.Serial(), { pastDeadline },
        { v -> TransferInResume.Settle(windowMs = windowOf(v), now = { testScheduler.currentTime }) },
    )

    private fun TestScope.run(
        d: FakeDevice,
        current: () -> Boolean = { true },
        windowOf: (Long?) -> Long = { TransferInResume.windowMs(it) },
    ) = async { settle(d, current, windowOf) }

    @Test
    fun pairedEndsTheSettlementWithTheTransfer() = runTest {
        val d = FakeDevice(shown, this).apply { pairedAtMs = 0 }
        val r = run(d).await()
        assertTrue(r is Settled.Paired)
        assertTrue(d.abandoned.isEmpty())
    }

    @Test
    fun neverPairedIsDroppedAtTheWindowFromTheRelaysValueAndOnlyThatTransfer() = runTest {
        val d = FakeDevice(shown, this).apply { visibility = 0 } // clamped to 0: just the poll cap and the margin
        assertEquals(Settled.Dropped, run(d).await())
        assertEquals(listOf(at), d.abandoned)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun anUnknownRelayValueFallsBackToTheDefaultWindow() = runTest {
        val d = FakeDevice(shown, this).apply { visibility = null }
        assertEquals(Settled.Dropped, run(d).await())
        assertEquals(TransferInResume.SETTLE_WINDOW_MS, testScheduler.currentTime)
    }

    /** With the collection running our collects are wait=0; without it they are long polls. */
    @Test
    fun waitIsZeroWhileTheCollectionRuns() = runTest {
        val running = FakeDevice(shown, this).apply { collecting = true }
        run(running).await()
        assertTrue(running.waits.isNotEmpty())
        assertTrue("all wait=0: ${running.waits}", running.waits.all { it == Duration.ZERO })
        val idle = FakeDevice(shown, this)
        run(idle).await()
        assertTrue("long polls: ${idle.waits}", idle.waits.any { it >= Duration.ofSeconds(10) })
    }

    /** A rejection that ended the window ends as the rejection (REJECTED on the screen), not as a drop. */
    @Test
    fun aRejectionDuringTheWindowEndsAsRejected() = runTest {
        val d = FakeDevice(shown, this)
        val job = async { runCatching { settle(d) } }
        testScheduler.advanceTimeBy(5_000)
        d.pending = null // the handler dropped the handshake
        d.rejected = true
        assertTrue(job.await().exceptionOrNull() is PairingRejectedException)
        assertTrue("nothing was dropped by the settlement", d.abandoned.isEmpty())
        assertTrue(testScheduler.currentTime < TransferInResume.SETTLE_WINDOW_MS)
    }

    @Test
    fun anAbandonDuringTheWindowEndsItQuietly() = runTest {
        val d = FakeDevice(shown, this)
        val job = run(d)
        testScheduler.advanceTimeBy(5_000)
        d.pending = null // the member abandoned it
        assertEquals(Settled.Dropped, job.await())
        assertTrue(d.abandoned.isEmpty())
    }

    /** A session that went away mid-window is not a drop: its own outcome. */
    @Test
    fun aSessionThatWentAwayIsNotReportedAsDropped() = runTest {
        val d = FakeDevice(shown, this)
        var current = true
        val job = run(d, { current })
        testScheduler.advanceTimeBy(5_000)
        current = false
        assertEquals(Settled.SessionGone, job.await())
        assertTrue(d.abandoned.isEmpty())
    }

    @Test
    fun theCollectionsRoundIsAwaitedOutsideTheRequestsBound() = runTest {
        val d = FakeDevice(shown, this).apply { collecting = true; roundMs = 40_000; pairedAtMs = 0 }
        assertTrue(run(d, windowOf = { 1_000L }).await() is Settled.Paired)
    }

    /** An app stopped during the window runs a full window again at its next start, from that start. */
    @Test
    fun killedDuringTheWindowRunsAFullWindowAtTheNextStart() = runTest {
        val first = FakeDevice(shown, this)
        val job = run(first)
        testScheduler.advanceTimeBy(40_000)
        job.cancel() // the process died: nothing was dropped, the transfer is still pending
        assertTrue(first.abandoned.isEmpty())
        assertEquals(shown, first.pending)
        val restartedAt = testScheduler.currentTime
        val second = FakeDevice(first.pending, this)
        assertEquals(Settled.Dropped, run(second).await())
        assertEquals(TransferInResume.SETTLE_WINDOW_MS, testScheduler.currentTime - restartedAt)
    }

    /** Killed before the deadline, started after it: the window at the start, then applied. */
    @Test
    fun killedBeforeTheDeadlineStartedAfterItSettlesAtTheStart() = runTest {
        assertEquals(
            TransferInResume.Action.DEADLINE,
            TransferInResume.decide(shown, null, pastDeadline),
        )
        val d = FakeDevice(shown, this).apply { pairedAtMs = 60_000 }
        assertTrue(run(d).await() is Settled.Paired)
    }

    // --- SingleFlight ---

    /** Leaving the screen (the caller cancelled) mid-window does not stop the settlement: it still ends dropped. */
    @Test
    fun leavingTheScreenMidWindowStillEndsInADrop() = runTest {
        val outer = CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
        val flight = SingleFlight<Settled>(outer)
        val d = FakeDevice(shown, this)
        var ran = 0
        val screen = async {
            flight.run(at) { ran++; settleTransfer(d, { true }, TransferInResume.Serial(), { pastDeadline }, { v ->
                TransferInResume.Settle(windowMs = TransferInResume.windowMs(v), now = { testScheduler.currentTime })
            }) }
        }
        testScheduler.advanceTimeBy(20_000)
        screen.cancel()
        testScheduler.advanceTimeBy(120_000)
        testScheduler.runCurrent()
        assertEquals("the settlement ran to its end", listOf(at), d.abandoned)
        assertEquals(1, ran)
        // A second screen later finds nothing pending: the first run already ended.
        assertEquals(null, d.pending)
        outer.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun aSecondCallerJoinsTheRunningSettlement() = runTest {
        val outer = CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
        val flight = SingleFlight<String>(outer)
        var ran = 0
        val gate = CompletableDeferred<Unit>()
        val a = async { flight.run(at) { ran++; gate.await(); "done" } }
        val b = async { flight.run(at) { ran++; "second" } }
        testScheduler.runCurrent()
        assertTrue("one window at a time: a run is active", flight.active)
        gate.complete(Unit)
        assertEquals("done", a.await())
        assertEquals("done", b.await())
        assertFalse(flight.active)
        assertEquals(1, ran)
        // Another transfer (another key) is its own run.
        assertEquals("other", flight.run(at.plusSeconds(1)) { "other" })
        outer.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun aFailureReachesTheWaiterAndNobodyElse() = runTest {
        val outer = CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
        val flight = SingleFlight<String>(outer)
        try {
            flight.run(at) { throw PairingRejectedException() }
            fail("thrown")
        } catch (_: PairingRejectedException) {
            // expected
        }
        // Nobody waiting: a failure is simply dropped.
        val left = async { flight.run(at.plusSeconds(1)) { delay(10); throw PairingRejectedException() } }
        left.cancel()
        testScheduler.advanceTimeBy(100)
        assertFalse(outer.coroutineContext[kotlinx.coroutines.Job]!!.isCancelled)
        outer.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
