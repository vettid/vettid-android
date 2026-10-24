package com.vettid.core.data.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * The check with the vault once a kept transfer's 7 days are over (release R, VAULT-MESSAGING §11.14.10 "The old
 * phone's keys after a transfer"; owner decisions 2026-10-16 and 2026-10-23).
 *
 * - [waiting] is set as soon as a kept transfer is past its 7 days, before the vault is asked ("Your vault has not
 *   confirmed the move to this phone…", instead of any erase offer), and cleared only by a decided end.
 * - [check] sends this check's own `device.list` (over this phone's session, bounded) and gives its
 *   [KeptTransfer.Verdict]; it throws [kotlinx.coroutines.CancellationException] when the check itself is cancelled.
 * - **The record is marked before the request** ([mark]: its record generation, its generation (bumped by every lock,
 *   reset and wipe) and the approval's time) **and read again after the answer**: a kept transfer that is gone leaves
 *   nothing to do. Every verdict is then acted on only after a [writeIf] **conditional on the whole mark** succeeded: a
 *   lock, a reset, a wipe or any change of the kept transfer during the up-to-60 s wait acts on nothing and records
 *   nothing, and the check runs again.
 * - **Erase only on a definite answer** that this phone is no longer the vault's app ([KeptTransfer.Verdict.NOT_THE_APP]).
 *   (`device.unlinked{transferred}` erases on its own path, [HolderWatch].) No answer, an unclear one, `token_revoked`, a
 *   locked vault, a timeout or a cancelled check never erase: this phone keeps its keys, and the check runs again at
 *   the next start or return to the foreground.
 * - **"Still the app"** ([KeptTransfer.Verdict.STILL_THE_APP]): the conditional write clears the kept transfer, and only
 *   then is the member told the move did not happen.
 *
 * One check at a time: a start, a return to the foreground and the timer may meet.
 */
internal class KeptTransferEnd(
    private val pending: () -> Boolean,
    private val mark: () -> Mark,
    private val check: suspend () -> KeptTransfer.Verdict,
    /**
     * A write of the account record conditional on [Mark] (both counters and the approval's time unchanged); [clear]:
     * the kept transfer goes, otherwise the record is written as it is (the proof that nothing moved). True when written.
     */
    private val writeIf: suspend (mark: Mark, clear: Boolean) -> Boolean,
    /** After the "kept" write succeeded: the shell says the move did not happen. */
    private val onKept: () -> Unit,
    private val erase: suspend () -> Unit,
) {
    /** The account record as read before the request. */
    data class Mark(val record: Int, val generation: Int, val approvedAtMs: Long?)

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
         * Nothing was decided (no answer, unclear, or the record changed during the wait so that the conditional write
         * did not land): checked again later.
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

    @Suppress("ReturnCount") // each ending that decides nothing returns at once
    suspend fun run(): Outcome {
        if (!one.tryLock()) return Outcome.BUSY
        try {
            if (!pending()) return Outcome.NONE.also { clear() }
            // Past the 7 days: said at once, before the answer, so that no erase offer shows meanwhile.
            waitingFlow.value = true
            val m = mark()
            val v = check()
            if (!pending()) return Outcome.NONE.also { clear() }
            return when (v) {
                KeptTransfer.Verdict.NOT_THE_APP -> if (writeIf(m, false)) {
                    erase()
                    clear()
                    Outcome.ERASED
                } else {
                    Outcome.WAITING
                }
                KeptTransfer.Verdict.STILL_THE_APP -> if (writeIf(m, true)) {
                    clear()
                    onKept()
                    Outcome.KEPT
                } else {
                    Outcome.WAITING
                }
                KeptTransfer.Verdict.UNKNOWN -> Outcome.WAITING
            }
        } finally {
            one.unlock()
        }
    }

    companion object {
        /**
         * The check over the account [record] (the vault manager's): [pending], [mark] and [writeIf] read and write it.
         * [write] runs a write off the main thread (the vault manager's `io`); [afterWrite] follows every write attempt.
         */
        fun onRecord(
            record: AccountRecord,
            check: suspend () -> KeptTransfer.Verdict,
            onKept: () -> Unit,
            erase: suspend () -> Unit,
            write: suspend (() -> Boolean) -> Boolean = { it() },
            afterWrite: () -> Unit = {},
        ) = KeptTransferEnd(
            pending = { record.value.transferApprovedAtMs != null },
            mark = { Mark(record.recordGeneration, record.generation, record.value.transferApprovedAtMs) },
            check = check,
            writeIf = { m, clear ->
                write {
                    try {
                        record.update(m.record, m.generation) { a ->
                            when {
                                a.transferApprovedAtMs == null || a.transferApprovedAtMs != m.approvedAtMs -> null
                                clear -> a.copy(transferApprovedAtMs = null)
                                else -> a
                            }
                        }
                    } finally {
                        afterWrite()
                    }
                }
            },
            onKept = onKept,
            erase = erase,
        )
    }
}
