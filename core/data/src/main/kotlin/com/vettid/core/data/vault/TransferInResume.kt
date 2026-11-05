package com.vettid.core.data.vault

import com.vettid.core.vault.TransferInState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/**
 * (A6 audit N1) A transfer to this phone that is not finished (VAULT-MESSAGING 0.56.2 §6.7.1, failures, and §11.14.10
 * rule 5; ANDROID-PLAN 0.1.76). One deadline: the stored start of the `hs.init` plus [TransferInWait.WAIT] (11
 * minutes), kept across restarts; no action of the member moves it. Past it the settlement collects for one relay
 * visibility timeout ([SETTLE_WINDOW_MS]) before it drops the transfer: VAULT-MESSAGING §11.14.10 rule 5 erratum (owner
 * decision a, 2026-11-05): collect for one relay visibility timeout (RELAY-PROTOCOL §6.3) before dropping. A single
 * collect could miss a `device.paired` that a killed app's parked long-poll had leased for 60 s.
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

    /** The bound of each collect of the settlement, its transport retries included (30 seconds). */
    const val DEADLINE_COLLECT_MS = 30_000L

    /**
     * How long the settlement keeps collecting past the deadline before it drops: the relay's visibility timeout of 60 s
     * (RELAY-PROTOCOL §6.3) plus a margin of 5 s, so that a `device.paired` leased to a dead request is handed out again.
     */
    const val SETTLE_WINDOW_MS = 65_000L

    /** The pause between two collects of the settlement. */
    const val SETTLE_PAUSE_MS = 1_000L

    /** A failed collect doubles the pause, at most this many times (1 s, 2 s, 4 s, 8 s). */
    const val MAX_BACKOFF_FACTOR = 3

    /** The long poll of each collect of the settlement (the relay's cap, RELAY-PROTOCOL §6.3). */
    const val SETTLE_POLL_S = 25L

    fun decide(t: TransferInState?, deviceId: String?, now: Instant): Action = when {
        t == null -> Action.NONE
        t.paired && deviceId != null -> Action.COMPLETE
        t.sas == null -> Action.DROP
        now.isBefore(t.startedAt.plus(TransferInWait.WAIT)) -> Action.WAIT
        else -> Action.DEADLINE
    }

    /**
     * (#187 recheck) Whether a deadline step still has to run: the transfer still waits ([Action.WAIT] or
     * [Action.DEADLINE]). WAIT counts too: the screen's wait can end a moment before the stored deadline (a timeout fires
     * up to a millisecond early, a clock set back), and its step must still collect and decide. After a first step the
     * decision is COMPLETE or NONE, so a second one answers without dropping.
     */
    fun deadlineDue(t: TransferInState?, deviceId: String?, now: Instant): Boolean =
        decide(t, deviceId, now) in setOf(Action.WAIT, Action.DEADLINE)

    /**
     * At the deadline: unless [paired] already, [collect] (each bounded by [boundMs]; the handler applies a
     * `device.paired{transfer}` it brings) and look at [paired] again, until paired or until [windowMs] has passed since
     * this step began (§11.14.10 rule 5 erratum, owner decision a, 2026-11-05: one relay visibility timeout). Between
     * collects [pauseMs]; after a failed or timed-out collect the pause doubles (up to [MAX_BACKOFF_FACTOR] times), and
     * the window goes on. [alive] is false once this transfer was rejected by the old phone or abandoned by the member
     * (or replaced by a new one): the window ends at once and nothing is dropped here. True when paired; otherwise
     * [drop] ran once the window had passed. Cancellation propagates, with no drop.
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList") // whatever ends a collect, the next one follows
    suspend fun atDeadline(
        paired: () -> Boolean,
        collect: suspend () -> Unit,
        drop: suspend () -> Unit,
        boundMs: Long = DEADLINE_COLLECT_MS,
        windowMs: Long = SETTLE_WINDOW_MS,
        pauseMs: Long = SETTLE_PAUSE_MS,
        alive: () -> Boolean = { true },
    ): Boolean {
        if (!paired()) {
            withTimeoutOrNull(windowMs) {
                var failures = 0
                while (alive() && !paired()) {
                    try {
                        val done = withTimeoutOrNull(boundMs) { collect() }
                        failures = if (done == null) failures + 1 else 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failures++ // a failed collect: tried again, with backoff, until the window ends
                    }
                    if (!alive() || paired()) break
                    delay(pauseMs * (1L shl minOf(maxOf(failures - 1, 0), MAX_BACKOFF_FACTOR)))
                }
            }
        }
        // The drop refuses a paired device under its lock, so a device.paired the collection applied meanwhile is kept:
        // the answer is read after it.
        if (!paired() && alive()) drop()
        return paired()
    }

    /** The paired transfer's `user_guid` (null from a vault release before 0.17.0). */
    data class Approved(val userGuid: String?)

    /**
     * The wait for the approval: [await] for what is left until the one deadline, counted from the start the device stored
     * just before the `hs.init` ([pending]; [now] when nothing is stored), then [atDeadline] (collects for the settle window, then
     * applied or dropped). [Approved] when paired; null when dropped.
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

    /**
     * (#187 recheck) One deadline step at a time. Two may meet (the screen's wait reaching the deadline, and a start or a
     * refresh deciding DEADLINE): without this, one's collect could lease the device.paired while the other dropped the
     * keyring before that message was handled. Inside the lock the step is decided again ([due]): one that is no longer
     * due (paired, or dropped by the first) only answers [paired].
     */
    class Serial {
        private val lock = kotlinx.coroutines.sync.Mutex()

        suspend fun run(
            due: () -> Boolean,
            paired: () -> Boolean,
            collect: suspend () -> Unit,
            drop: suspend () -> Unit,
            boundMs: Long = DEADLINE_COLLECT_MS,
            windowMs: Long = SETTLE_WINDOW_MS,
            pauseMs: Long = SETTLE_PAUSE_MS,
            alive: () -> Boolean = { true },
        ): Boolean = lock.withLock {
            if (due()) atDeadline(paired, collect, drop, boundMs, windowMs, pauseMs, alive) else paired()
        }
    }
}
