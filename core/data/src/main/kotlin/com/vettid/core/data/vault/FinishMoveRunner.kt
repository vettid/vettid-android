package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Finish the move" (release R, VAULT-MESSAGING §11.14.10 step 4): `vault.lock`, then `vault.unlock` with this phone's
 * own secret. **Once asked for, it completes**, as the member's lock does ([LockSequence]): it runs in [scope], the
 * vault manager's process-long scope, so a screen that goes away (the hold's bound sends the root to the unlock screen
 * and clears the screen that asked) only stops waiting; the unlock goes on, opens the vault and publishes its phase, or
 * its failure lands in [failure] for the unlock screen.
 *
 * [finishing] holds the screen that asked for at most [holdMs]. [onOutcome] hears every answer of the unlock;
 * [onOpened] only a success that nothing overtook: [generation] (bumped by every lock and every wipe) unchanged since
 * the run's own lock, and not [signedOut]. It gets that generation, to check it again as one step with its write (a
 * wipe runs on another thread). A wipe that lands during the unlock leaves its success standing, but nothing of the
 * vault it erased is written into the fresh record.
 *
 * One run at a time: a [run] while one is on its way starts nothing and waits for that one's answer.
 */
internal class FinishMoveRunner(
    private val scope: CoroutineScope,
    private val holdMs: Long,
    private val lock: suspend () -> Unit,
    private val unlock: suspend (String) -> UnlockAttempt,
    private val generation: () -> Int,
    private val signedOut: () -> Boolean,
    private val onOutcome: suspend (UnlockAttempt) -> Unit = {},
    private val onOpened: suspend (generation: Int) -> Unit = {},
) {
    private val finishingFlow = MutableStateFlow(false)
    val finishing: StateFlow<Boolean> = finishingFlow.asStateFlow()

    private val failureFlow = MutableStateFlow<FailureKind?>(null)

    /** Why the last run did not open the vault; null after a success, [clearFailure] or before any run. */
    val failure: StateFlow<FailureKind?> = failureFlow.asStateFlow()

    fun clearFailure() {
        failureFlow.value = null
    }

    private val runs = Any()

    /** The last run; under [runs]. Active from its start until its end, its hold's release included. */
    private var current: Deferred<UnlockAttempt>? = null

    /**
     * Runs the move in [scope] and waits for it; a caller that is cancelled only stops waiting. While a run is on its
     * way, another caller joins it (its [pin] is not used), as the screen ignores a second tap while busy: two runs
     * never overlap, so one run's end never releases another's hold or overwrites its [failure].
     */
    suspend fun run(pin: String): UnlockAttempt = synchronized(runs) {
        current?.takeIf { it.isActive } ?: scope.async { body(pin) }.also { current = it }
    }.await()

    @Suppress("TooGenericExceptionCaught") // any failure is recorded for the unlock screen, then rethrown
    private suspend fun body(pin: String): UnlockAttempt {
        failureFlow.value = null
        finishingFlow.value = true
        val bound = scope.launch {
            delay(holdMs)
            finishingFlow.value = false
        }
        try {
            lock()
            // Read after the run's own lock, which bumps it: a later change is a lock or a wipe during the unlock.
            val gen = generation()
            val r = unlock(pin)
            failureFlow.value = FinishSetupGate.failureOf(r)
            onOutcome(r)
            if (r == UnlockAttempt.Success && gen == generation() && !signedOut()) onOpened(gen)
            return r
        } catch (e: Exception) {
            // Recorded for the unlock screen, then rethrown. A cancellation comes only from the manager's own scope ending
            // (the process): nothing is left to tell then.
            when (e) {
                is VaultFailure -> failureFlow.value = e.kind
                is CancellationException -> Unit
                else -> failureFlow.value = FailureKind.OTHER
            }
            throw e
        } finally {
            bound.cancel()
            finishingFlow.value = false
        }
    }
}
