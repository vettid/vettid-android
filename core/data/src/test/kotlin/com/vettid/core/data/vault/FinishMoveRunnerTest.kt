package com.vettid.core.data.vault

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
        val runner = FinishMoveRunner(
            manager, holdMs = 90_000,
            lock = { locked++ },
            unlock = { answer.await() },
            onOutcome = { outcomes += it },
        )
    }

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
}
