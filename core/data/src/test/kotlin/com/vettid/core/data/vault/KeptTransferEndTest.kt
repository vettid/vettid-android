package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val waitingAtCheck = mutableListOf<Boolean>()
        private fun now() = KeptTransferEnd.Mark(recordGeneration, generation, if (kept) APPROVED else null)
        val end: KeptTransferEnd = KeptTransferEnd(
            pending = { kept },
            mark = { now() },
            check = {
                checks++
                waitingAtCheck += end.waiting.value
                answer()
            },
            writeIf = { m, clear ->
                // As RecordStore.update(since, generation) with the approval's time: dropped when anything moved.
                val moved = m != now()
                if (moved || !kept || writeFails) {
                    false
                } else {
                    if (clear) kept = false
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
    fun theNoticeIsShownBeforeTheVaultIsAsked() = runTest {
        // A fresh start: the flag starts false, and is set before the request, so no erase offer flashes meanwhile.
        val f = Fixture { KeptTransfer.Verdict.NOT_THE_APP }
        assertFalse(f.end.waiting.value)
        f.start()
        assertEquals(listOf(true), f.waitingAtCheck)
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
    fun aWriteThatDoesNotLandClaimsNothingErasesNothingAndTheCheckRunsAgain() = runTest {
        // The record could not be written (unreadable, a wipe that did not finish): the conditional write fails.
        val f = Fixture { KeptTransfer.Verdict.STILL_THE_APP }
        f.writeFails = true
        assertEquals(KeptTransferEnd.Outcome.WAITING, f.start())
        assertEquals(0, f.told)
        assertTrue(f.kept)
        assertTrue(f.end.waiting.value)
        f.answer = { KeptTransfer.Verdict.NOT_THE_APP }
        assertEquals(KeptTransferEnd.Outcome.WAITING, f.start())
        assertEquals("no erase without the write", 0, f.erased)
        f.writeFails = false
        f.answer = { KeptTransfer.Verdict.STILL_THE_APP }
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
        assertFalse(f.end.waiting.value)
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

    // --- through the vault manager's wiring ([KeptTransferEnd.onRecord]) over a real account record ---

    private class OnRecord(var answer: suspend (AccountRecord) -> KeptTransfer.Verdict) {
        var disk: LocalAccount? = null
        val record = AccountRecord(LocalAccount(vaultId = "v-1", transferApprovedAtMs = APPROVED), readable = true, persist = { disk = it })
        var erased = 0
        var told = 0
        var writes = 0
        val end = KeptTransferEnd.onRecord(
            record,
            check = { answer(record) },
            onKept = { told++ },
            erase = { erased++ },
            afterWrite = { writes++ },
        )
    }

    @Test
    fun theVaultManagersWiringDropsAStillTheAppAcrossALock() = runTest {
        // VaultManager's lock() bumps the record's generation (record.bumpGeneration()) while the check waits.
        val r = OnRecord { rec ->
            rec.bumpGeneration()
            KeptTransfer.Verdict.STILL_THE_APP
        }
        assertEquals(KeptTransferEnd.Outcome.WAITING, r.end.run())
        assertEquals(0, r.told)
        assertEquals("the kept transfer stays in the record", APPROVED, r.record.value.transferApprovedAtMs)
        assertNull("nothing written", r.disk)
        // Without a lock meanwhile, the write lands and clears it.
        r.answer = { KeptTransfer.Verdict.STILL_THE_APP }
        assertEquals(KeptTransferEnd.Outcome.KEPT, r.end.run())
        assertEquals(1, r.told)
        assertNull(r.record.value.transferApprovedAtMs)
        assertNull(r.disk!!.transferApprovedAtMs)
    }

    @Test
    fun theVaultManagersWiringErasesNothingAcrossALockOrAReset() = runTest {
        val r = OnRecord { rec ->
            rec.bumpGeneration()
            KeptTransfer.Verdict.NOT_THE_APP
        }
        assertEquals(KeptTransferEnd.Outcome.WAITING, r.end.run())
        assertEquals(0, r.erased)
        // A reset (a new record, here one that keeps a transfer approved at another time): nothing either.
        r.answer = { rec ->
            rec.reset(LocalAccount(vaultId = "v-1", transferApprovedAtMs = APPROVED + 1))
            KeptTransfer.Verdict.NOT_THE_APP
        }
        assertEquals(KeptTransferEnd.Outcome.WAITING, r.end.run())
        assertEquals(0, r.erased)
        // Unchanged: erased, and the record was left as it was by the proving write.
        r.answer = { KeptTransfer.Verdict.NOT_THE_APP }
        assertEquals(KeptTransferEnd.Outcome.ERASED, r.end.run())
        assertEquals(1, r.erased)
        assertEquals(APPROVED + 1, r.record.value.transferApprovedAtMs)
    }

    // --- the new phone's wait (awaitTransferIn) ---

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

    @Test
    fun aDevicePairedAtTenAndAHalfMinutesIsTakenAndNothingIsDropped() = runTest {
        val start = Instant.parse("2026-10-23T10:00:00Z")
        val paired = CompletableDeferred<String>()
        var abandoned = 0
        val wait = async {
            TransferInWait.await(start, { start.plusMillis(currentTime) }, { left -> withTimeout(left.toMillis()) { paired.await() } }) {
                abandoned++
            }
        }
        advanceTimeBy(Duration.ofSeconds(630).toMillis())
        assertEquals("nothing dropped at 10 min 30 s", 0, abandoned)
        paired.complete("member-guid")
        assertEquals("member-guid", wait.await())
        assertEquals(0, abandoned)
    }

    @Test
    fun nothingIsDroppedBeforeElevenMinutes() = runTest {
        val start = Instant.parse("2026-10-23T10:00:00Z")
        var abandoned = 0
        val wait = async {
            runCatching {
                val never = CompletableDeferred<String>()
                TransferInWait.await(start, { start.plusMillis(currentTime) }, { left -> withTimeout(left.toMillis()) { never.await() } }) {
                    abandoned++
                }
            }
        }
        advanceTimeBy(Duration.ofMinutes(11).toMillis() - 1)
        assertEquals(0, abandoned)
        assertFalse(wait.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, abandoned)
        assertTrue(wait.await().exceptionOrNull() is TimeoutCancellationException)
    }

    private companion object {
        const val APPROVED = 1_760_000_000_000L
    }
}
