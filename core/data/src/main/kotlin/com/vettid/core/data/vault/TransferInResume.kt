package com.vettid.core.data.vault

import com.vettid.core.relay.RelayException
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
 * minutes), kept across restarts; no action of the member moves it. Past it the settlement collects for a window
 * ([Settle.windowMs]: [windowMs] of the relay's advertised visibility timeout) before it drops the transfer:
 * VAULT-MESSAGING §11.14.10 rule 5 erratum (owner decision a, 2026-11-05): collect for one relay visibility timeout
 * (RELAY-PROTOCOL §6.3) before dropping. A single collect could miss a `device.paired` that a killed app's parked
 * long-poll had leased for 60 s. The window is
 * VAULT-MESSAGING 0.85.2 §11.14.10 rule 5, ANDROID-PLAN 0.1.121 (vettid.org #537)
 * (owner decisions a and a/a, 2026-11-05): the
 * visibility timeout plus the long-poll cap plus a margin, because a dead request may have been taken up to a long poll
 * before the settlement began and its lease then ends that much later. A request that is out at the relay is never
 * cancelled while it can still hold a message: each one is sized to what is left of the window and given its wait plus a
 * transport bound; one that still had to be abandoned moves the end of the window out by a whole window (capped).
 */
internal object TransferInResume {
    enum class Action {
        /** Nothing pending. */
        NONE,

        /** The vault paired this phone: the take-over runs (never `credential.create`), at any age. */
        COMPLETE,

        /** The code was shown and the deadline has not passed: the screen shows it again and goes on waiting. */
        WAIT,

        /** The code was shown and the deadline passed while the app was not running: [atDeadline]; at a start it runs
         * only when the transfer-in screen's wait does (the start publishes the waiting phase, pinned until then). */
        DEADLINE,

        /** No code was shown, so no `hs.fin` was sent: nothing can complete it, and it is dropped. */
        DROP,
    }

    /** The transport bound added to a request's own wait (30 seconds); a request is never given less than wait + this. */
    const val DEADLINE_COLLECT_MS = 30_000L

    private const val MS = 1_000L

    /** The relay's visibility timeout when it advertises none (RELAY-PROTOCOL §6.3). */
    const val DEFAULT_VISIBILITY_S = 60L

    /** The advertised visibility timeout is trusted only within these bounds (a relay must not pin the keys for an hour). */
    const val MIN_VISIBILITY_S = 0L
    const val MAX_VISIBILITY_S = 300L

    /** The long poll of each collect of the settlement (the relay's cap, RELAY-PROTOCOL §6.3). */
    const val SETTLE_POLL_S = 25L

    /** The margin after the visibility timeout and the long-poll cap. */
    const val SETTLE_MARGIN_MS = 5_000L

    /**
     * The settlement window (the lead's rule): the relay's advertised visibility timeout ([visibilitySeconds], 60 s when
     * it advertises none, kept within 0 to 300 s) + the long-poll cap (25 s) + 5 s:
     * 90 s today. VAULT-MESSAGING 0.85.2 §11.14.10 rule 5, ANDROID-PLAN 0.1.121 (vettid.org #537).
     */
    fun windowMs(visibilitySeconds: Long? = null): Long =
        visibilityMs(visibilitySeconds) + SETTLE_POLL_S * MS + SETTLE_MARGIN_MS

    /** The window with the relay's visibility timeout at its default. */
    val SETTLE_WINDOW_MS: Long = windowMs()

    /** The pause between two collects of the settlement. */
    const val SETTLE_PAUSE_MS = 1_000L

    /** A failed collect doubles the pause, at most this many times (1 s, 2 s, 4 s, 8 s). */
    const val MAX_BACKOFF_FACTOR = 3

    /** What is kept back from the end of the window for handling what a request brings. */
    const val HANDLING_MARGIN_MS = 5_000L

    /** See [Settle.freezeMarginMs]. */
    const val FREEZE_MARGIN_MS = 10_000L

    /** The most that abandoned requests can move the end of the window out by (by default one visibility timeout). */
    const val SETTLE_MAX_EXTRA_MS = DEFAULT_VISIBILITY_S * MS

    /** The advertised visibility timeout in ms, kept within 0 to 300 s (60 s when none). */
    fun visibilityMs(visibilitySeconds: Long? = null): Long =
        (visibilitySeconds ?: DEFAULT_VISIBILITY_S).coerceIn(MIN_VISIBILITY_S, MAX_VISIBILITY_S) * MS

    /**
     * The timing of a settlement, all of it a parameter for tests (which pass [now] from the test scheduler).
     * [now] is a monotonic clock in milliseconds that keeps counting while the phone sleeps (`elapsedRealtime`): a suspend
     * with a request out then shows as a phase far past its bound (a freeze), where `nanoTime` would not.
     */
    class Settle(
        val windowMs: Long = SETTLE_WINDOW_MS,
        val pauseMs: Long = SETTLE_PAUSE_MS,
        val transportMs: Long = DEADLINE_COLLECT_MS,
        val pollMs: Long = SETTLE_POLL_S * MS,
        val handlingMs: Long = HANDLING_MARGIN_MS,
        val maxExtraMs: Long = SETTLE_MAX_EXTRA_MS,
        val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
        /**
         * How far past its own bound a phase of the step (a round wait, a request, a pause) may run before the monotonic
         * clock is taken to show a process freeze. A timeout fires at its bound give or take scheduling noise (seconds at
         * the worst: a busy main thread, a GC); a frozen process has its connections killed and wakes up long after. 10 s
         * is clear of the noise; a freeze shorter than that is not told from it (only a cut collector request is).
         */
        val freezeMarginMs: Long = FREEZE_MARGIN_MS,
    )

    fun decide(t: TransferInState?, deviceId: String?, now: Instant): Action = when {
        t == null -> Action.NONE
        t.paired && deviceId != null -> Action.COMPLETE
        t.sas == null -> Action.DROP
        now.isBefore(t.startedAt.plus(TransferInWait.WAIT)) -> Action.WAIT
        else -> Action.DEADLINE
    }

    /**
     * Whether the vault's 10 minutes have passed for a transfer whose code was shown and that is not paired: the old phone
     * can no longer approve it, and the member's abandon is no longer offered (a `device.paired` may be leased from here
     * to the end of the settlement window). From the stored start, so the screen and the repository agree.
     */
    fun pastVaultWindow(t: TransferInState?, now: Instant): Boolean =
        t != null && t.sas != null && !t.paired && !now.isBefore(t.startedAt.plus(TransferInWait.VAULT_WINDOW))

    /**
     * (#187 recheck) Whether a deadline step still has to run: the transfer still waits ([Action.WAIT] or
     * [Action.DEADLINE]). WAIT counts too: the screen's wait can end a moment before the stored deadline (a timeout fires
     * up to a millisecond early, a clock set back), and its step must still collect and decide. After a first step the
     * decision is COMPLETE or NONE, so a second one answers without dropping.
     */
    fun deadlineDue(t: TransferInState?, deviceId: String?, now: Instant): Boolean =
        decide(t, deviceId, now) in setOf(Action.WAIT, Action.DEADLINE)

    /**
     * At the deadline: unless [paired] already, [collect] (given the wait of its request, in ms) and look at [paired]
     * again, until paired or until [Settle.windowMs] has passed since this step began (§11.14.10 rule 5 erratum, owner
     * decision a, 2026-11-05). No request that is out at the relay is cancelled while it can hold a message: each is
     * sized to what is left of the window (its wait is what is left minus [Settle.handlingMs], at most [Settle.pollMs])
     * and bounded by its wait plus [Settle.transportMs]; one that had to be abandoned may hold a message leased for a
     * visibility timeout, so the end of the window moves to at least a whole window from then (at most
     * [Settle.maxExtraMs] past the first end; so does a request that failed with anything but a [RelayException], since
     * a status means nothing was leased). The window ends with one final `wait=0` collect, itself bounded by
     * [Settle.transportMs] like every request, before the decision: a relay that hangs longer than that at that point can
     * still hold a lease past the drop (an accepted trade-off). Waiting for the collection's own round is [awaitRound],
     * before and after each request and never under the request's bound: it has its own (the poll cap plus the
     * transport bound), and one that runs out is no abandoned request (nothing of ours was out), so it moves nothing.
     * The window is also extended, as for an abandoned request, when the collection's in-flight request was cut
     * ([cuts] counts those: a stop or a reconnect with a request out) and when a phase of a step runs far past its own
     * bound on the monotonic clock (a process freeze; the frozen time itself does not count against the window). The
     * extension is a whole window from then, capped at [Settle.maxExtraMs] past the (freeze-compensated) first end; and it
     * is checked after the final collect too.
     * Between collects [Settle.pauseMs]; after a failure the pause
     * doubles (up to [MAX_BACKOFF_FACTOR] times). [alive] is false once this transfer was rejected (the member's abandon
     * and a new code are not offered during the
     * window) or the session is gone: the step ends at once and nothing is dropped here. True when paired; otherwise [drop] ran once the
     * window had passed. Cancellation propagates, with no drop.
     */
    suspend fun atDeadline(
        paired: () -> Boolean,
        collect: suspend (waitMs: Long) -> Unit,
        drop: suspend () -> Unit,
        alive: () -> Boolean = { true },
        settle: Settle = Settle(),
        awaitRound: suspend () -> Unit = {},
        cuts: () -> Long = { 0L },
    ): Boolean {
        if (paired()) return true
        val out = Requests(settle, collect, awaitRound, cuts)
        while (alive() && !paired()) {
            if (out.left() > 0) {
                out.send((out.left() - settle.handlingMs).coerceIn(0L, settle.pollMs))
                if (alive() && !paired()) out.pause()
            } else {
                // The window's end: one final wait=0 collect. A cut or a freeze during it extends the end (left() > 0
                // again) and the loop goes on, instead of dropping with a message that may be leased.
                out.send(0L)
                if (out.left() <= 0) break
            }
        }
        // The drop refuses a paired device under its lock, so a device.paired the collection applied meanwhile is kept:
        // the answer is read after it.
        if (!paired() && alive()) drop()
        return paired()
    }

    /** The requests of one settlement: its window's end (which abandoned requests and freezes move) and its failures. */
    private class Requests(
        private val settle: Settle,
        private val collect: suspend (waitMs: Long) -> Unit,
        private val round: suspend () -> Unit,
        private val cuts: () -> Long,
    ) {
        private var firstEnd = settle.now() + settle.windowMs
        private var endAt = firstEnd
        private var failures = 0
        private var seenCuts = cuts()

        fun left(): Long = endAt - settle.now()

        /** The pause before the next request: the base, doubled after each failure in a row, not past the end. */
        suspend fun pause() {
            val ms = minOf(settle.pauseMs * (1L shl minOf(maxOf(failures - 1, 0), MAX_BACKOFF_FACTOR)), maxOf(left(), 0L))
            timed(ms) { delay(ms) }
        }

        /** A request that may have been taken up at the relay and its answer lost: the message may be leased for a while. */
        private fun mayHoldLease() {
            endAt = minOf(maxOf(endAt, settle.now() + settle.windowMs), firstEnd + settle.maxExtraMs)
        }

        /**
         * Runs [block], which should take about [boundMs] at most. If the monotonic clock says it took far more
         * ([Settle.freezeMarginMs] past the bound), the process was frozen meanwhile: its connections died, a request of the
         * collection that was out may have been handed a message and lease it for a visibility timeout. The frozen time does
         * not count against the window (both ends move by it, so the cap stays at the same awake time), and the end is
         * extended as for an abandoned request.
         */
        private suspend fun <T> timed(boundMs: Long, block: suspend () -> T): T {
            val t0 = settle.now()
            try {
                return block()
            } finally {
                val over = settle.now() - t0 - boundMs
                if (over > settle.freezeMarginMs) {
                    firstEnd += over
                    endAt += over
                    mayHoldLease()
                }
                noteCuts()
            }
        }

        /** The collection's in-flight request was cut (it was stopped or restarted) since the last look: the same. */
        private fun noteCuts() {
            val c = cuts()
            if (c != seenCuts) {
                seenCuts = c
                mayHoldLease()
            }
        }

        /** One step of the window: the collection's round, our request, the collection's round again. */
        suspend fun send(waitMs: Long) {
            awaitRound()
            request(waitMs)
            awaitRound()
        }

        /** The collection's round in flight may hold a leased message: waited for, within its own bound. */
        @Suppress("TooGenericExceptionCaught") // a round that fails is no reason to stop
        private suspend fun awaitRound() {
            val bound = settle.pollMs + settle.transportMs
            try {
                timed(bound) { withTimeoutOrNull(bound) { round() } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // nothing of ours was out
            }
        }

        @Suppress("TooGenericExceptionCaught") // whatever ends a collect, the next one follows
        private suspend fun request(waitMs: Long) {
            val bound = waitMs + settle.transportMs
            try {
                // Never shorter than its own wait: a poll parked at the relay is not cut while it can hold a message.
                if (timed(bound) { withTimeoutOrNull(bound) { collect(waitMs) } } == null) {
                    failures++
                    mayHoldLease()
                } else {
                    failures = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RelayException) {
                failures++ // a status came back: nothing was leased; tried again with backoff
            } catch (_: Exception) {
                failures++ // e.g. a body that could not be read: it may have been answered and leased
                mayHoldLease()
            }
        }
    }

    /** The paired transfer's `user_guid` (null from a vault release before 0.17.0). */
    data class Approved(val userGuid: String?)

    /**
     * The wait for the approval: [await] for what is left until the one deadline, counted from the start the device stored
     * just before the `hs.init` ([pending]), then [atDeadline] (collects for the settle window, then applied or dropped).
     * [Approved] when paired; null when dropped, and at once when no transfer is pending (nothing to wait for).
     */
    suspend fun awaitApproval(
        pending: () -> TransferInState?,
        now: () -> Instant,
        await: suspend (Duration) -> String?,
        atDeadline: suspend () -> TransferInState?,
    ): Approved? {
        val started = pending()?.startedAt ?: return null
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
            collect: suspend (waitMs: Long) -> Unit,
            drop: suspend () -> Unit,
            alive: () -> Boolean = { true },
            settle: Settle = Settle(),
            awaitRound: suspend () -> Unit = {},
            cuts: () -> Long = { 0L },
        ): Boolean = lock.withLock {
            if (due()) atDeadline(paired, collect, drop, alive, settle, awaitRound, cuts) else paired()
        }
    }
}
