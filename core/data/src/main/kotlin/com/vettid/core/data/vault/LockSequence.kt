package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How the member's lock runs, so that **a lock once asked for always completes**:
 *
 * - It runs in [scope], the process-long scope of the vault manager, not in the caller's: the screen that asked
 *   for the lock may go away (its ViewModel scope is cancelled) and the caller stops waiting, the lock goes on.
 * - What other parts of the app want to finish while the vault still collects ([addHook]: ending a call, so that
 *   its hang-up reaches the vault) runs first, each hook in a job of its own and all of them side by side, and is
 *   waited for [hookBudgetMs] at most, then cancelled and left behind. A hook that suspends for ever, throws, or
 *   blocks its thread cannot hold the lock back longer, nor keep the other hooks from running (a call's hang-up
 *   waiting for the vault's answer does not starve the clears registered after it). A hook that fails with an
 *   [Error] does not cancel the others either: the error goes on to the scope's handler. Without hooks nothing is
 *   waited for.
 * - `after` runs whatever became of `lock`: the phone's own state ends locked also when the vault could not be told.
 */
internal class LockSequence(private val scope: CoroutineScope, private val hookBudgetMs: Long = HOOK_BUDGET_MS) {
    private val hooks = CopyOnWriteArrayList<suspend () -> Unit>()

    /**
     * Adds something to finish before every lock. A hook must be quick, must not call the lock itself, and runs beside
     * the others within [hookBudgetMs]: when the budget is over it is cancelled and the lock proceeds.
     */
    fun addHook(hook: suspend () -> Unit) {
        hooks += hook
    }

    /**
     * Runs the hooks (bounded), then [lock], then [after] in every outcome. Returns when all of it is done, or
     * throws what [lock] threw; a caller that is cancelled only stops waiting.
     */
    suspend fun run(lock: suspend () -> Unit, after: () -> Unit) {
        scope.async {
            try {
                runHooks()
                lock()
            } finally {
                after()
            }
        }.await()
    }

    private suspend fun runHooks() {
        if (hooks.isEmpty()) return
        // A supervisor: a hook that fails with what is not caught below (an Error) goes to the scope's handler, as it
        // did before, and cancels none of the others.
        val job = SupervisorJob(scope.coroutineContext[Job])
        for (hook in hooks) {
            // Each in its own child: a slow one does not hold the others back.
            scope.launch(job) {
                try {
                    hook()
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                    // One hook's failure is not another's, and never the lock's.
                }
            }
        }
        job.complete()
        withTimeoutOrNull(hookBudgetMs) { job.join() }
        // Not joined after the cancel: a hook that blocks its thread is left to finish by itself.
        job.cancel()
    }

    companion object {
        /** What every hook together gets before the vault is locked. */
        const val HOOK_BUDGET_MS = 2_000L
    }
}
