package com.vettid.core.data.vault

import com.vettid.core.vault.TransferInState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/**
 * (A6 audit N1) A transfer to this phone that is not finished (VAULT-MESSAGING 0.56.2 §6.7.1, failures, and §11.14.10
 * rule 5; ANDROID-PLAN 0.1.76). One deadline: the stored start of the `hs.init` plus [TransferInWait.WAIT] (11
 * minutes), kept across restarts; no action of the member moves it.
 */
internal object TransferInResume {
    enum class Action {
        /** Nothing pending. */
        NONE,

        /** The vault paired this phone: the take-over runs (never `credential.create`), at any age. */
        COMPLETE,

        /** The code was shown and the deadline has not passed: the screen shows it again and goes on waiting. */
        WAIT,

        /** The code was shown and the deadline passed while the app was not running: [atDeadline] now, first. */
        DEADLINE,

        /** No code was shown, so no `hs.fin` was sent: nothing can complete it, and it is dropped. */
        DROP,
    }

    /** The bound of the deadline's one collect, its transport retries included (§11.14.10 rule 5: 30 seconds in all). */
    const val DEADLINE_COLLECT_MS = 30_000L

    fun decide(t: TransferInState?, deviceId: String?, now: Instant): Action = when {
        t == null -> Action.NONE
        t.paired && deviceId != null -> Action.COMPLETE
        t.sas == null -> Action.DROP
        now.isBefore(t.startedAt.plus(TransferInWait.WAIT)) -> Action.WAIT
        else -> Action.DEADLINE
    }

    /**
     * At the deadline: unless [paired] already, one [collect] (bounded by [boundMs]; the handler applies a
     * `device.paired{transfer}` it brings), then [paired] again. True when paired; otherwise [drop] ran, also after a
     * collect that failed or timed out (an unreachable relay is no reason to keep the keys longer).
     */
    @Suppress("TooGenericExceptionCaught") // whatever ends the collect, the drop follows
    suspend fun atDeadline(
        paired: () -> Boolean,
        collect: suspend () -> Unit,
        drop: suspend () -> Unit,
        boundMs: Long = DEADLINE_COLLECT_MS,
    ): Boolean {
        if (!paired()) {
            try {
                withTimeoutOrNull(boundMs) { collect() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // a failed collect: dropped below
            }
        }
        // The drop refuses a paired device under its lock, so a device.paired the collection applied meanwhile is kept:
        // the answer is read after it.
        if (!paired()) drop()
        return paired()
    }

    /** The paired transfer's `user_guid` (null from a vault release before 0.17.0). */
    data class Approved(val userGuid: String?)

    /**
     * The wait for the approval: [await] for what is left until the one deadline, counted from the start the device stored
     * just before the `hs.init` ([pending]; [now] when nothing is stored), then [atDeadline] (the collect, then applied or
     * dropped). [Approved] when paired; null when dropped.
     */
    suspend fun awaitApproval(
        pending: () -> TransferInState?,
        now: () -> Instant,
        await: suspend (Duration) -> String?,
        atDeadline: suspend () -> TransferInState?,
    ): Approved? {
        val started = pending()?.startedAt ?: now()
        return try {
            Approved(await(TransferInWait.left(started, now())))
        } catch (_: TimeoutCancellationException) {
            atDeadline()?.let { Approved(it.userGuid) }
        }
    }
}
