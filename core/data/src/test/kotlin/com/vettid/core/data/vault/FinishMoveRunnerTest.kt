package com.vettid.core.data.vault

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Finish the move" completes once asked for: the screen that asked may go away (its ViewModel scope is cancelled when
 * the hold's bound sends the root to the unlock screen), and the unlock still lands, opened or failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinishMoveRunnerTest {
    private class Fixture(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) {
        val manager = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val answer = CompletableDeferred<UnlockAttempt>()
        var locked = 0
        val outcomes = mutableListOf<UnlockAttempt>()

        /** As the vault manager's: every lock and every wipe bumps it; a wipe ends signed out with a fresh record. */
        var generation = 0
        var signedOut = false
        var record = Record()
        val runner = FinishMoveRunner(
            manager, holdMs = 90_000,
            lock = {
                locked++
                generation++
            },
            unlock = { answer.await() },
            generation = { generation },
            signedOut = { signedOut },
            onOutcome = { outcomes += it },
            onOpened = { record = record.copy(rotateOffered = true) },
        )

        fun wipe() {
            generation++
            record = Record()
            signedOut = true
        }
    }

    /** The part of the account record the rotate offer is kept in. */
    private data class Record(val rotateOffered: Boolean = false)

    @Test
    fun aCancelledCallerStillGetsTheVaultOpened() = runTest {
        val f = Fixture(testScheduler)
        val caller = launch { runCatching { f.runner.run("975310") } }
        runCurrent()
        assertEquals(1, f.locked)
        assertTrue(f.runner.finishing.value)
        // The hold's bound ends, the screen goes, its scope is cancelled: the move goes on.
        advanceTimeBy(90_001)
        assertFalse(f.runner.finishing.value)
        caller.cancel()
        advanceUntilIdle()
        f.answer.complete(UnlockAttempt.Success)
        advanceUntilIdle()
        assertEquals(listOf<UnlockAttempt>(UnlockAttempt.Success), f.outcomes)
        assertNull(f.runner.failure.value)
        f.manager.cancel()
    }

    @Test
    fun aCancelledCallerStillGetsTheFailureShown() = runTest {
        val f = Fixture(testScheduler)
        val caller = launch { runCatching { f.runner.run("975310") } }
        runCurrent()
        caller.cancel()
        advanceUntilIdle()
        f.answer.complete(UnlockAttempt.Failed(FailureKind.TRANSFER_INCOMPLETE, "transfer_incomplete"))
        advanceUntilIdle()
        assertEquals(FailureKind.TRANSFER_INCOMPLETE, f.runner.failure.value)
        assertFalse(f.runner.finishing.value)
        f.manager.cancel()
    }

    @Test
    fun aVaultFailureIsRecorded() = runTest {
        val manager = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = FinishMoveRunner(manager, 90_000, lock = {}, unlock = { throw VaultFailure(FailureKind.NETWORK) })
        val r = runCatching { runner.run("975310") }
        assertTrue(r.isFailure)
        assertEquals(FailureKind.NETWORK, runner.failure.value)
        manager.cancel()
    }

    @Test
    fun aSuccessOffersTheRotation() = runTest {
        val f = Fixture(testScheduler)
        val caller = launch { f.runner.run("975310") }
        runCurrent()
        f.answer.complete(UnlockAttempt.Success)
        advanceUntilIdle()
        assertTrue(caller.isCompleted)
        assertTrue(f.record.rotateOffered)
        f.manager.cancel()
    }

    @Test
    fun aSuccessThatAWipeOvertookOffersNothing() = runTest {
        val f = Fixture(testScheduler)
        val caller = launch { f.runner.run("975310") }
        runCurrent()
        // `unknown_device` or `device.unlinked` while the unlock is on its way: the phone is wiped, then the unlock answers.
        f.wipe()
        f.answer.complete(UnlockAttempt.Success)
        advanceUntilIdle()
        assertTrue(caller.isCompleted)
        assertEquals(listOf<UnlockAttempt>(UnlockAttempt.Success), f.outcomes)
        assertFalse(f.record.rotateOffered)
        f.manager.cancel()
    }

    @Test
    fun aSecondRunJoinsTheOneOnItsWay() = runTest {
        val f = Fixture(testScheduler)
        val first = async { f.runner.run("975310") }
        runCurrent()
        val second = async { f.runner.run("111111") }
        runCurrent()
        // One lock, one unlock, one hold: the second caller started nothing.
        assertEquals(1, f.locked)
        assertTrue(f.runner.finishing.value)
        f.answer.complete(UnlockAttempt.Success)
        advanceUntilIdle()
        assertEquals(UnlockAttempt.Success, first.await())
        assertEquals(UnlockAttempt.Success, second.await())
        assertEquals(1, f.outcomes.size)
        assertFalse(f.runner.finishing.value)
        // Once it has ended, the next run is a run of its own.
        launch { f.runner.run("975310") }
        advanceUntilIdle()
        assertEquals(2, f.locked)
        assertEquals(2, f.outcomes.size)
        f.manager.cancel()
    }
}
