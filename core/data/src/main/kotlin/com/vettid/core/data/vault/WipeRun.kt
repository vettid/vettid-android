package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * One wipe of this phone ([VaultManager]'s), in an order that fails secure:
 *
 * 1. the account record's guard shuts ([AccountRecord.beginWipe]);
 * 2. [detach]: the session, its observer and the relay connection stop, so that none of them outlives a wipe attempt;
 * 3. [mark]: the wipe's marker, which makes a later start finish it, tried [attempts] times;
 * 4. [erase]: everything else; true when nothing is left.
 *
 * Whatever happens, the guard then opens on the fresh record ([AccountRecord.endWipe]) and [onEnd] hears the phase to
 * show: SignedOut only after a complete erase; otherwise [failedPhase], which says that this phone was not erased and
 * offers to try again (a marker that could not be written leaves nothing for a later start to finish).
 */
internal class WipeRun(
    private val record: AccountRecord,
    private val detach: suspend () -> Unit,
    private val mark: () -> Unit,
    private val erase: suspend () -> Boolean,
    private val onEnd: (AppPhase) -> Unit,
    private val attempts: Int = MARK_ATTEMPTS,
    private val retryMs: Long = MARK_RETRY_MS,
) {
    suspend fun run() {
        record.beginWipe()
        var phase: AppPhase = failedPhase
        try {
            detach()
            if (!marked()) return
            if (erase()) phase = AppPhase.SignedOut
        } finally {
            record.endWipe()
            onEnd(phase)
        }
    }

    @Suppress("TooGenericExceptionCaught") // a marker that cannot be written, for any reason, is tried again
    private suspend fun marked(): Boolean {
        repeat(attempts) { i ->
            try {
                mark()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (i < attempts - 1) delay(retryMs)
            }
        }
        return false
    }

    companion object {
        const val MARK_ATTEMPTS = 3
        const val MARK_RETRY_MS = 500L

        /** A wipe that did not finish (or never started): said, with "Try again", never as a clean welcome screen. */
        val failedPhase: AppPhase = AppPhase.Unreachable(FailureKind.ERASE_FAILED)

        /** The error of an enrolment refused while a wipe is unfinished. */
        const val CODE_WIPE_PENDING = "wipe_pending"

        /**
         * Before an enrolment (a setup code's redeem, a recovery's claim, a transfer to this phone): an unfinished wipe
         * ([pending]) is finished first ([finish]); while it stays unfinished the enrolment is refused, so that a later
         * start never erases a new vault's keys.
         */
        @Suppress("TooGenericExceptionCaught") // whatever stopped the wipe, the check below decides
        suspend fun beforeEnrolment(pending: () -> Boolean, finish: suspend () -> Unit) {
            if (!pending()) return
            try {
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // said below
            }
            if (pending()) throw VaultFailure(FailureKind.ERASE_FAILED, CODE_WIPE_PENDING)
        }
    }
}
