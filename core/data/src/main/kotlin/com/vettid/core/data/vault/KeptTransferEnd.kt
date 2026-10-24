package com.vettid.core.data.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * The check with the vault once a kept transfer's 7 days are over (release R, VAULT-MESSAGING §11.14.10 "The old
 * phone's keys after a transfer"; owner decisions 2026-10-16 and 2026-10-23).
 *
 * - [check] sends this check's own `device.list` (over this phone's session, bounded) and gives its
 *   [KeptTransfer.Verdict]; it throws [kotlinx.coroutines.CancellationException] when the check itself is cancelled.
 * - **The record is marked before the request** ([mark]: its record generation and its generation, bumped by every
 *   lock, reset and wipe) **and read again after the answer**: a kept transfer that is gone, or a mark that moved
 *   ([unchanged] false: a lock, a reset, a wipe during the up-to-60 s wait), acts on nothing and records nothing.
 * - **Erase only on a definite answer** that this phone is no longer the vault's app ([KeptTransfer.Verdict.NOT_THE_APP]).
 *   (`device.unlinked{transferred}` erases on its own path, [HolderWatch].) No answer, an unclear one, `token_revoked`, a
 *   locked vault, a timeout or a cancelled check never erase: this phone keeps its keys, [waiting] says so ("Your vault
 *   has not confirmed the move to this phone…", instead of any erase offer), and the check runs again at the next start
 *   or return to the foreground.
 * - **"Still the app"** ([KeptTransfer.Verdict.STILL_THE_APP]): the kept state goes and the member is told the move did
 *   not happen, but only when [recordKept] stored that, conditionally on the mark: a write that did not land claims
 *   nothing, and the check runs again.
 *
 * One check at a time: a start, a return to the foreground and the timer may meet.
 */
internal class KeptTransferEnd(
    private val pending: () -> Boolean,
    private val mark: () -> Mark,
    private val unchanged: (Mark) -> Boolean,
    private val check: suspend () -> KeptTransfer.Verdict,
    /** Clears the kept transfer in the account record, conditionally on the [Mark]; true only when written. */
    private val recordKept: suspend (Mark) -> Boolean,
    /** After [recordKept] succeeded: the shell says the move did not happen. */
    private val onKept: () -> Unit,
    private val erase: suspend () -> Unit,
) {
    /** The account record's counters, read before the request: [record] (wipes, resets) and [generation] (also locks). */
    data class Mark(val record: Int, val generation: Int)

    enum class Outcome {
        /** Another check runs. */
        BUSY,

        /** No kept transfer (any more). */
        NONE,

        /** The vault said this phone is no longer its app: erased. */
        ERASED,

        /** The vault said this phone is still its app, and that was stored. */
        KEPT,

        /**
         * Nothing was decided (no answer, unclear, the record changed during the wait, or the "kept" write did not
         * land): checked again later.
         */
        WAITING,
    }

    private val waitingFlow = MutableStateFlow(false)

    /** The 7 days are over and the vault has not confirmed the move: "Your vault has not confirmed the move…". */
    val waiting: StateFlow<Boolean> = waitingFlow.asStateFlow()

    private val one = Mutex()

    /** The kept transfer ended otherwise (a move back, an erase): no notice. */
    fun clear() {
        waitingFlow.value = false
    }

    private fun waitAgain(): Outcome {
        waitingFlow.value = true
        return Outcome.WAITING
    }

    @Suppress("ReturnCount") // each ending that decides nothing returns at once
    suspend fun run(): Outcome {
        if (!one.tryLock()) return Outcome.BUSY
        try {
            if (!pending()) return Outcome.NONE.also { clear() }
            val m = mark()
            val v = check()
            if (!pending()) return Outcome.NONE.also { clear() }
            if (!unchanged(m)) return waitAgain()
            return when (v) {
                KeptTransfer.Verdict.NOT_THE_APP -> {
                    erase()
                    clear()
                    Outcome.ERASED
                }
                KeptTransfer.Verdict.STILL_THE_APP -> if (recordKept(m)) {
                    clear()
                    onKept()
                    Outcome.KEPT
                } else {
                    waitAgain()
                }
                KeptTransfer.Verdict.UNKNOWN -> waitAgain()
            }
        } finally {
            one.unlock()
        }
    }
}
