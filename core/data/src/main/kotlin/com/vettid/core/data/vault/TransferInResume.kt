package com.vettid.core.data.vault

import com.vettid.core.relay.RelayException
import com.vettid.core.vault.TransferInState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /**
     * What the held last collect adds to a lease's end: 15 s. The send time of a request is noted before the client's
     * transport retry and its connect, so the request may reach the relay (and its lease start) some seconds later.
     */
    const val HOLD_MARGIN_MS = 15_000L

    /** See [Settle.freezeMarginMs]. */
    const val FREEZE_MARGIN_MS = 10_000L

    private const val MAX_SENDS = 16

    /** See [Settle.heartbeatMs]. */
    const val HEARTBEAT_MS = 1_000L

    /**
     * What the cap adds to a visibility timeout: the long-poll cap (25 s) + the handling margin (5 s) + 5 s. A collector
     * request can be out up to about 30 s after the window's first end, so a cut then leases past first end + V.
     */
    const val CAP_MARGIN_MS = 35_000L

    /** The most that cuts and freezes can move the end of the window out by: V + [CAP_MARGIN_MS] (V at its default). */
    const val SETTLE_MAX_EXTRA_MS = DEFAULT_VISIBILITY_S * MS + CAP_MARGIN_MS

    /** The advertised visibility timeout in ms, kept within 0 to 300 s (60 s when none). */
    fun visibilityMs(visibilitySeconds: Long? = null): Long =
        (visibilitySeconds ?: DEFAULT_VISIBILITY_S).coerceIn(MIN_VISIBILITY_S, MAX_VISIBILITY_S) * MS

    /**
     * The timing of a settlement, all of it a parameter for tests (which pass [now] from the test scheduler).
     * [now] is a monotonic clock in milliseconds that keeps counting while the phone sleeps (`elapsedRealtime`): a suspend
     * with a request out then shows as a gap in the heartbeat (a freeze), where `nanoTime` would not.
     */
    class Settle(
        val windowMs: Long = SETTLE_WINDOW_MS,
        val pauseMs: Long = SETTLE_PAUSE_MS,
        val transportMs: Long = DEADLINE_COLLECT_MS,
        val pollMs: Long = SETTLE_POLL_S * MS,
        val handlingMs: Long = HANDLING_MARGIN_MS,
        /** The cap: the end never moves past the first end + [maxExtraMs] (V + 35 s), on the window's clock. */
        val maxExtraMs: Long = SETTLE_MAX_EXTRA_MS,
        /** V, the (clamped) visibility timeout: a cut or a freeze moves the end to at least now + V. */
        val visibilityMs: Long = DEFAULT_VISIBILITY_S * MS,
        // A unit test's stub of SystemClock answers 0 (isReturnDefaultValues): a window on that clock would never end, so the
        // default fails loudly there instead of hanging; a test passes its own clock. On a phone it is never 0 (since boot).
        val now: () -> Long = { android.os.SystemClock.elapsedRealtime().also { check(it > 0) { "Settle.now: pass a clock in tests" } } },
        /**
         * The heartbeat: while the window runs a ticker marks the window's clock every [heartbeatMs]. A gap of more than
         * [freezeMarginMs] since the last tick, wherever in a step it falls, is a process freeze (or a suspend): its
         * connections died, and a request of the collection that was out may have been handed a message and lease it for a
         * visibility timeout. The margin is clear of scheduling noise (a busy main thread or a GC delays a tick by seconds at
         * the worst) and well below one visibility timeout (60 s), so a freeze long enough to matter is seen whatever phase it
         * falls in; a shorter one is not told from noise (only a cut collector request is).
         */
        val freezeMarginMs: Long = FREEZE_MARGIN_MS,
        val heartbeatMs: Long = HEARTBEAT_MS,
        /** See [HOLD_MARGIN_MS]: the held last collect goes at the latest lease end + this. */
        val holdMarginMs: Long = HOLD_MARGIN_MS,
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
     * again, until paired or until the window ([Settle.windowMs] since this step began, the first end F) has passed
     * (VAULT-MESSAGING §11.14.10 rule 5, the lead's pending erratum). No request that is out at the relay is cancelled
     * while it can hold a message: each is sized to what is left of the window (its wait is what is left minus
     * [Settle.handlingMs], at most [Settle.pollMs]) and bounded by its wait plus [Settle.transportMs].
     *
     * **The extension rule.** On a cut or a detected freeze the end moves to `min(max(end, now + V), cap)`, V being
     * [Settle.visibilityMs] and the cap F + [Settle.maxExtraMs] (V + 35 s), both fixed on the window's clock; nothing
     * shifts F, the frozen time counts. A cut is: a request of ours abandoned or failed with anything but a relay status,
     * or the collector's in-flight request cut or lost ([cuts]). A freeze is a gap of more than [Settle.freezeMarginMs]
     * between the ticks of a 1 s heartbeat that lives only as long as the window. The check is made after the final collect
     * too, so a cut or a freeze just before the end extends and loops instead of dropping.
     *
     * **The last collect.** A lease taken by a cut or a freeze (s + w + V for a freeze, s being the send time and w the long-poll wait of
     * the latest
     * request
     * sent at or before it began, ours or the collector's, kept after the request ended via [collectorAge]; the notice time
     * + V for a cut) that runs past the cap is recorded however and whenever it was noticed (before the cap or after).
     * The final `wait=0` collect then goes at `max(now, latest lease end + 15 s)` ([HOLD_MARGIN_MS]). The settlement has ONE extra in all: either
     * the hold target moves once (a cut or freeze noticed during the hold; a later move is ignored, since a collector
     * failing offline would otherwise move it at every failure for ever), or, after the final send, a lease noticed during it
     * that outlives its start earns one more held final collect. Then the drop (not when paired, not when the end moved
     * past now).
     *
     * **The bound** (cap = F + V + 35 s): the last ordinary step ends within 148 s of the cap (a round wait 55 s, a request
     * 30 s at wait 0, a round wait 55 s, a pause up to 8 s); its cut is noticed then, so the hold ends within V + 40 s more
     * (the lease starts by s + w, w up to 25 s, the long-poll cap, ours and the collector's; then the 15 s margin); the final
     * send takes at most 140 s (55 + 30 + 55); the extra (a hold move, or a second held collect after the first send) is at
     * most V + 40 s more, and a second final send 140 s: at most cap + 2V + 508 s in all (cap + 628 s at the default V).
     *
     * Waiting for the collection's own round is [awaitRound], before and after each request and never under the request's
     * bound: it has its own (the poll cap plus the transport bound), and one that runs out is no abandoned request.
     * Between collects [Settle.pauseMs]; after a failure the pause doubles (up to [MAX_BACKOFF_FACTOR] times). [alive] is
     * false once this transfer was rejected (the member's abandon and a new code are not offered during the window) or the
     * session is gone: the step ends at once and nothing is dropped here. True when paired; otherwise [drop] ran once the
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
        collectorAge: () -> Long? = { null },
        collectorSends: () -> List<Pair<Long, Long>> = { emptyList() },
    ): Boolean {
        if (paired()) return true
        val out = Requests(settle, collect, awaitRound, cuts, collectorAge, collectorSends)
        // The heartbeat lives only as long as the window: cancelled when the loop ends, however it ends.
        coroutineScope {
            val beat = launch { out.heartbeat() }
            try {
                while (alive() && !paired()) {
                    out.look()
                    if (out.left() > 0) {
                        out.send((out.left() - settle.handlingMs).coerceIn(0L, settle.pollMs))
                        if (alive() && !paired()) out.pause()
                    } else {
                        // The window's end: one final wait=0 collect, held until every lease that runs past the cap has
                        // run out (max(now, s + w + V + 15 s), however and whenever the cut or freeze was noticed). A cut or a
                        // freeze during the hold moves it. One that is noticed during the send itself, with a lease
                        // beyond its start, earns ONE more held final collect in all, then the drop.
                        out.awaitHold()
                        val sentAt = settle.now()
                        out.send(0L)
                        out.look()
                        if (out.left() <= 0 && !out.takeExtraAfterSend(sentAt)) break
                    }
                }
            } finally {
                beat.cancel()
            }
        }
        // The drop refuses a paired device under its lock, so a device.paired the collection applied meanwhile is kept:
        // the answer is read after it.
        if (!paired() && alive()) drop()
        return paired()
    }

    /** The requests of one settlement: its window's end (which cuts and freezes move, within the cap) and its failures. */
    private class Requests(
        private val settle: Settle,
        private val collect: suspend (waitMs: Long) -> Unit,
        private val round: suspend () -> Unit,
        private val cuts: () -> Long,
        private val collectorAge: () -> Long?,
        private val collectorSends: () -> List<Pair<Long, Long>>,
    ) {
        private val firstEnd = settle.now() + settle.windowMs
        private val cap = firstEnd + settle.maxExtraMs

        @Volatile private var endAt = firstEnd

        @Volatile private var lastTick = settle.now()

        /** The latest send (window clock) of any request, ours or the collector's, as of the last tick; kept after it ends. */
        @Volatile private var sampledSend: Long? = null

        /** The send times of our own latest requests (the last few); kept after they end. */
        private val ourSends = ArrayDeque<Pair<Long, Long>>()

        /** The latest end of a lease that runs past the cap (window clock), 0 when none: the last collect holds for it. */
        @Volatile private var maxLeaseEnd = 0L

        /** The one extra held final collect has been used. */
        @Volatile private var extraUsed = false
        private var failures = 0
        private var seenCuts = cuts()

        fun left(): Long = endAt - settle.now()

        /** The pause before the next request: the base, doubled after each failure in a row, not past the end. */
        suspend fun pause() {
            val ms = minOf(settle.pauseMs * (1L shl minOf(maxOf(failures - 1, 0), MAX_BACKOFF_FACTOR)), maxOf(left(), 0L))
            delay(ms)
            look()
        }

        /**
         * Before the last collect: waits until every lease that runs past the cap has run out (+ [Settle.holdMarginMs]). The
         * target may move ONCE (a cut or freeze noticed during the wait, which uses up the one extra of the settlement); a
         * later move is ignored, so the hold, and the pin, cannot go on for ever (a collector failing offline moves it at every
         * failure).
         */
        suspend fun awaitHold() {
            look()
            var target = holdTarget()
            while (true) {
                val w = target - settle.now()
                if (w > 0) delay(w)
                look()
                val moved = holdTarget()
                if (moved <= target) return
                // The first hold, set while nothing was due yet, is not a move: it does not use up the extra.
                if (target != 0L && !takeExtra()) return
                target = moved
            }
        }

        private fun holdTarget(): Long = if (maxLeaseEnd == 0L) 0L else maxLeaseEnd + settle.holdMarginMs

        /** The one extra of the settlement: a move of the hold target, or another held final collect after the send. */
        @Synchronized
        private fun takeExtra(): Boolean {
            if (extraUsed) return false
            extraUsed = true
            return true
        }

        /** After the last collect: a lease noticed during it that outlives its start earns the one extra, if still unused. */
        fun takeExtraAfterSend(sentAt: Long): Boolean = holdTarget() > sentAt && takeExtra()

        /** A cut or a freeze: the message may be leased for a visibility timeout from [from]. The end never passes the cap. */
        @Synchronized
        private fun mayHoldLease(from: Long = settle.now()) {
            endAt = minOf(maxOf(endAt, from + settle.visibilityMs), cap)
            if (from + settle.visibilityMs > cap) maxLeaseEnd = maxOf(maxLeaseEnd, from + settle.visibilityMs)
        }

        /** The ticker: marks the window's clock every [Settle.heartbeatMs] and looks at the gap since the last mark. */
        suspend fun heartbeat() {
            while (true) {
                delay(settle.heartbeatMs)
                tick()
            }
        }

        /**
         * A tick, or a look from the loop: if the window's clock moved on by more than [Settle.freezeMarginMs] since the last
         * tick, the process was frozen meanwhile (see [Settle.freezeMarginMs]). The lease is that of the latest request sent
         * at or before the freeze began (s: the last tick's sample, or a later send up to a tick after it, or one older than
         * the margin before the wake; it is kept after the request ended): it runs to s + w + V (the relay hands a message out up to w
         * after
         * the send). The end is extended, and a lease
         * past the cap is recorded for the last collect to hold for, whether the wake was before the cap or after it.
         */
        @Synchronized
        private fun tick() {
            val n = settle.now()
            val gap = n - lastTick
            val before = sampledSend
            val froze = lastTick + settle.heartbeatMs
            lastTick = n
            // Every send known: (send time, long-poll wait). The device's short history of its collector's sends (a round that
            // ended on the wake and a new one sent before the first tick must not hide the send before the freeze), its retries,
            // and ours. A long poll's lease starts when the relay returns, up to its wait after the send: the lease starts by
            // send + wait.
            val sends = (listOfNotNull(collectorAge()?.let { n - it to 0L }) + collectorSends().map { (n - it.first) to it.second }) +
                ourSends.toList()
            val latest = sends.maxOfOrNull { it.first + it.second }
            if (gap > settle.freezeMarginMs) {
                // s: the lease start of the latest send at or before the freeze began: the last tick's sample, or a later send
                // up to a tick after it, or one older than the margin before the wake (per send: a restart right after the wake
                // does not hide the send before the freeze).
                val eligible = sends.filter { it.first <= froze || it.first <= n - settle.freezeMarginMs }.map { it.first + it.second }
                val s = (eligible + listOfNotNull(before)).maxOrNull()
                if (s != null) mayHoldLease(s) // the lease of the request that was out
                endAt = minOf(maxOf(endAt, n + settle.visibilityMs), cap)
            }
            sampledSend = latest
        }

        /** Looks for a freeze and for a cut collector request (it was stopped or restarted with a request out). */
        fun look() {
            tick()
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
                withTimeoutOrNull(bound) { round() }
                look()
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
                synchronized(this) {
                    ourSends.addLast(settle.now() to waitMs)
                    if (ourSends.size > MAX_SENDS) ourSends.removeFirst()
                }
                // Never shorter than its own wait: a poll parked at the relay is not cut while it can hold a message.
                if (withTimeoutOrNull(bound) { collect(waitMs) } == null) {
                    failures++
                    mayHoldLease()
                } else {
                    failures = 0
                }
                look()
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
            collectorAge: () -> Long? = { null },
            collectorSends: () -> List<Pair<Long, Long>> = { emptyList() },
        ): Boolean = lock.withLock {
            if (due()) atDeadline(paired, collect, drop, alive, settle, awaitRound, cuts, collectorAge, collectorSends) else paired()
        }
    }
}
