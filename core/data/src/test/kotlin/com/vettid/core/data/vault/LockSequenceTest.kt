package com.vettid.core.data.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The member's lock (review D1 of C1): a lock once asked for always completes, and nothing that is finished before
 * it can hold it back. On real time and real dispatchers: one of the cases is a hook that blocks its thread.
 * `VaultManager.lock()` is this sequence around `api.lock()` (the manager itself needs a phone to be built).
 */
class LockSequenceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val budget = 300L
    private val locked = AtomicBoolean(false)
    private val after = AtomicInteger(0)

    @After
    fun stop() = scope.cancel()

    private fun sequence(vararg hooks: suspend () -> Unit) = LockSequence(scope, budget).apply { hooks.forEach(::addHook) }

    private fun timed(block: suspend () -> Unit): Long {
        val t = System.nanoTime()
        runBlocking { block() }
        return (System.nanoTime() - t) / 1_000_000
    }

    private suspend fun LockSequence.lock() = run(lock = { locked.set(true) }, after = { after.incrementAndGet() })

    @Test
    fun withoutAHookOrWithOneThatHasNothingToDoThereIsNoDelay() {
        assertTrue(timed { sequence().lock() } < budget)
        assertTrue(locked.get())
        locked.set(false)
        assertTrue(timed { sequence({ }).lock() } < budget)
        assertTrue(locked.get())
        assertEquals(2, after.get())
    }

    @Test
    fun aHookThatNeverFinishesIsGivenTheBudgetAndNoMore() {
        val ms = timed { sequence({ awaitCancellation() }).lock() }
        assertTrue("$ms ms", ms in budget..budget + 700)
        assertTrue(locked.get())
    }

    @Test
    fun aHookThatThrowsStopsNeitherTheNextHookNorTheLock() {
        val second = AtomicBoolean(false)
        val ms = timed { sequence({ throw IllegalStateException("x") }, { second.set(true) }).lock() }
        assertTrue(second.get() && locked.get())
        assertTrue("$ms ms", ms < budget)
    }

    @Test
    fun aHookThatBlocksItsThreadCannotHoldTheLockBack() {
        // Before the fix the bound was cooperative: this held the lock for as long as the thread was blocked.
        val ms = timed { sequence({ Thread.sleep(3_000) }).lock() }
        assertTrue("$ms ms", ms in budget..budget + 700)
        assertTrue(locked.get())
    }

    @Test
    fun aCallerThatIsCancelledOnlyStopsWaitingTheLockCompletes() {
        // The screen that asked for the lock goes away while a hook runs (its ViewModel scope is cancelled). Before
        // the fix the vault was never locked.
        val hookStarted = AtomicBoolean(false)
        val s = sequence({
            hookStarted.set(true)
            awaitCancellation()
        })
        runBlocking {
            val caller = CoroutineScope(Dispatchers.Default).launch { s.lock() }
            delay(100)
            caller.cancelAndJoin()
            assertFalse("not yet: the hook still has its time", locked.get())
            delay(budget + 500)
        }
        assertTrue(hookStarted.get())
        assertTrue(locked.get())
        assertEquals(1, after.get())
    }

    @Test
    fun aCallerCancelledDuringTheLockItselfDoesNotStopItEither() {
        val s = sequence()
        runBlocking {
            val caller = CoroutineScope(Dispatchers.Default).launch {
                s.run(
                    lock = {
                        delay(300) // the vault's answer is on its way
                        locked.set(true)
                    },
                    after = { after.incrementAndGet() },
                )
            }
            delay(100)
            caller.cancelAndJoin()
            delay(600)
        }
        assertTrue(locked.get())
        assertEquals(1, after.get())
    }

    @Test
    fun whenTheVaultCannotBeToldThePhoneStillEndsLockedAndTheCallerLearnsWhy() {
        val failure = runCatching {
            runBlocking {
                sequence().run(
                    lock = { throw java.io.IOException("relay and member API unreachable") },
                    after = { after.incrementAndGet() },
                )
            }
        }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertEquals("the phone's own state was set to locked", 1, after.get())
    }
}
