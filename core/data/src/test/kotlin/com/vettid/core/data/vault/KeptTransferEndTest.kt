package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The check at the end of a kept transfer's 7 days (VAULT-MESSAGING §11.14.10 "The old phone's keys after a transfer";
 * owner decision 2026-10-23): an erase only on a definite answer; no answer keeps the keys and checks again later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptTransferEndTest {

    /**
     * The vault manager's side, faked: the kept transfer in the record with its two counters (as RecordStore's: a lock
     * bumps [generation]; a wipe or reset bumps both), the conditional write, the erase.
     */
    private class Fixture(var answer: suspend () -> KeptTransfer.Verdict) {
        var kept = true
        var recordGeneration = 0
        var generation = 0
        var erased = 0
        var told = 0
        var checks = 0
        var writeFails = false
        val end = KeptTransferEnd(
            pending = { kept },
            mark = { KeptTransferEnd.Mark(recordGeneration, generation) },
            unchanged = { m -> m.record == recordGeneration && m.generation == generation },
            check = {
                checks++
                answer()
            },
            recordKept = { m ->
                // As RecordStore.update(since, generation): dropped when either counter moved, or nothing is kept.
                val moved = m != KeptTransferEnd.Mark(recordGeneration, generation)
                if (moved || !kept || writeFails) {
                    false
                } else {
                    kept = false
                    true
                }
            },
            onKept = { told++ },
            erase = {
                erased++
                kept = false
            },
        )
        fun lock() {
            generation++
        }
    }

    /** At the start, at each return to the foreground: the same check, run again. */
    private suspend fun Fixture.start() = end.run()

    @Test
    fun aLockedOrUnansweringVaultErasesNothingAndIsCheckedAgainAtTheNextStart() = runTest {
        val f = Fixture { KeptTransfer.Verdict.UNKNOWN }
        assertEquals(KeptTransferEnd.Outcome.WAITING, f.start())
        assertEquals(0, f.erased)
        assertTrue("the keys are kept", f.kept)
        assertTrue("the notice is shown", f.end.waiting.value)
        assertEquals(0, f.told)
        // The next start (or return to the foreground) asks again; the vault now answers definitely.
        f.answer = { KeptTransfer.Verdict.NOT_THE_APP }
        assertEquals(KeptTransferEnd.Outcome.ERASED, f.start())
        assertEquals(2, f.checks)
        assertEquals(1, f.erased)
        assertFalse(f.end.waiting.value)
    }

    @Test
    fun aDefiniteNotTheAppErases() = runTest {
        val f = Fixture { KeptTransfer.Verdict.NOT_THE_APP }
        assertEquals(KeptTransferEnd.Outcome.ERASED, f.start())
        assertEquals(1, f.erased)
        assertEquals(0, f.told)
        assertFalse(f.end.waiting.value)
    }

    @Test
    fun aDefiniteStillTheAppKeepsTheKeysAndSaysTheMoveDidNotHappen() = runTest {
        val f = Fixture { KeptTransfer.Verdict.STILL_THE_APP }
        assertEquals(KeptTransferEnd.Outcome.KEPT, f.start())
        assertEquals(0, f.erased)
        assertEquals(1, f.told)
        assertFalse("the kept state is gone", f.kept)
        assertFalse(f.end.waiting.value)
        // Nothing kept any more: a later start does nothing.
        assertEquals(KeptTransferEnd.Outcome.NONE, f.start())
        assertEquals(1, f.checks)
    }

    @Test
    fun aCancelledCheckNeverErases() = runTest {
        val gate = CompletableDeferred<KeptTransfer.Verdict>()
        val f = Fixture { gate.await() }
        val run = async { f.start() }
        runCurrent()
        run.cancel()
        runCurrent()
        assertTrue(run.isCancelled)
        assertEquals(0, f.erased)
        assertEquals(0, f.told)
        assertTrue(f.kept)
        // The check is free again: the next start runs it.
        f.answer = { KeptTransfer.Verdict.UNKNOWN }
        assertEquals(KeptTransferEnd.Outcome.WAITING, f.start())
        assertEquals(0, f.erased)
    }

    @Test
    fun aCheckThatThrowsCancellationErasesNothing() = runTest {
        val f = Fixture { throw CancellationException("cancelled") }
        val r = runCatching { f.start() }
        assertTrue(r.exceptionOrNull() is CancellationException)
        assertEquals(0, f.erased)
        assertTrue(f.kept)
    }

    @Test
    fun aLockDuringTheWaitActsOnNothingAndRecordsNothing() = runTest {
        // The vault answers "still the app", but a lock (or a reset, a wipe) moved the record's generation meanwhile.
        val f = Fixture { KeptTransfer.Verdict.STILL_THE_APP }
        f.answer = {
            f.lock()
            KeptTransfer.Verdict.STILL_THE_APP
        }
        assertEquals(KeptTransferEnd.Outcome.WAITING, f.start())
        assertEquals("the member is not told the phone keeps its keys", 0, f.told)
        assertEquals(0, f.erased)
        assertTrue("the kept transfer stays", f.kept)
        assertTrue(f.end.waiting.value)
        // Not an erase either, on a definite "not the app" across a lock.
        f.answer = {
            f.lock()
            KeptTransfer.Verdict.NOT_THE_APP
        }
        assertEquals(KeptTransferEnd.Outcome.WAITING, f.start())
        assertEquals(0, f.erased)
        // The next start checks again, and this time the write lands.
        f.answer = { KeptTransfer.Verdict.STILL_THE_APP }
        assertEquals(KeptTransferEnd.Outcome.KEPT, f.start())
        assertEquals(1, f.told)
        assertFalse(f.end.waiting.value)
    }

    @Test
    fun aWipeOrResetDuringTheWaitActsOnNothing() = runTest {
        val f = Fixture { KeptTransfer.Verdict.NOT_THE_APP }
        f.answer = {
            f.recordGeneration++
            f.generation++
            KeptTransfer.Verdict.NOT_THE_APP
        }
        assertEquals(KeptTransferEnd.Outcome.WAITING, f.start())
        assertEquals(0, f.erased)
        assertEquals(0, f.told)
    }

    @Test
    fun aKeptWriteThatDoesNotLandClaimsNothingAndTheCheckRunsAgain() = runTest {
        // The record could not be written (unreadable, a wipe that did not finish): the conditional write fails.
        val f = Fixture { KeptTransfer.Verdict.STILL_THE_APP }
        f.writeFails = true
        assertEquals(KeptTransferEnd.Outcome.WAITING, f.start())
        assertEquals(0, f.told)
        assertTrue(f.kept)
        assertTrue(f.end.waiting.value)
        f.writeFails = false
        assertEquals(KeptTransferEnd.Outcome.KEPT, f.start())
        assertEquals(1, f.told)
    }

    @Test
    fun afterTokenRevokedEachStartSendsOneCheckAndNeverErases() = runTest {
        // The relay refuses this phone's deposits (`token_revoked`): every check ends without an answer (UNKNOWN).
        val f = Fixture { KeptTransfer.Verdict.UNKNOWN }
        repeat(3) { assertEquals(KeptTransferEnd.Outcome.WAITING, f.start()) }
        assertEquals("one check at each start or return to the foreground", 3, f.checks)
        assertEquals(0, f.erased)
        assertTrue(f.kept)
        assertTrue(f.end.waiting.value)
    }

    @Test
    fun aTransferThatEndedDuringTheCheckIsLeftAlone() = runTest {
        // device.unlinked{transferred} (which erases on its own path) or "The move didn't finish" during the wait.
        val f = Fixture { KeptTransfer.Verdict.NOT_THE_APP }
        f.answer = {
            f.kept = false
            KeptTransfer.Verdict.NOT_THE_APP
        }
        assertEquals(KeptTransferEnd.Outcome.NONE, f.start())
        assertEquals(0, f.erased)
        assertEquals(0, f.told)
    }

    @Test
    fun oneCheckAtATime() = runTest {
        val gate = CompletableDeferred<KeptTransfer.Verdict>()
        val f = Fixture { gate.await() }
        val first = async { f.start() }
        runCurrent()
        assertEquals(KeptTransferEnd.Outcome.BUSY, f.start())
        gate.complete(KeptTransfer.Verdict.UNKNOWN)
        assertEquals(KeptTransferEnd.Outcome.WAITING, first.await())
        assertEquals(1, f.checks)
    }

    @Test
    fun theNewPhoneWaitsElevenMinutesFromItsOwnStart() {
        val started = Instant.parse("2026-10-23T10:00:00Z")
        assertEquals(Duration.ofMinutes(11), TransferInWait.WAIT)
        assertEquals(Duration.ofMinutes(11), TransferInWait.left(started, started))
        // Past the vault's 10 minutes, still waiting about a minute.
        assertEquals(Duration.ofSeconds(50), TransferInWait.left(started, started.plus(Duration.ofSeconds(610))))
        // At or after 11 minutes: the least wait, once.
        assertEquals(Duration.ofSeconds(1), TransferInWait.left(started, started.plus(Duration.ofMinutes(11))))
        assertEquals(Duration.ofSeconds(1), TransferInWait.left(started, started.plus(Duration.ofMinutes(30))))
    }
}
