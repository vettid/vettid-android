package com.vettid.core.data.vault

import com.vettid.core.relay.RelayException
import com.vettid.core.vault.CollectorCut
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
     * What the held last collect adds to a lease's end: 15 s. The device stamps each send as it is written (OkHttp's
     * `requestHeadersStart`), so this covers the network between that write and the relay, and the one send still noted
     * before the transport retry and the connect: our own request's `sentAt`, whose cut or failure never leases earlier
     * than its notice anyway.
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
        /** V, the (clamped) visibility timeout: a cut or a freeze moves the end to at least its lease start + V (+ 15 s). */
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
     * **The extension rule.** On a cut or a detected freeze the end moves to `min(max(end, lease end + 15 s), cap)`, the
     * cap being F + [Settle.maxExtraMs] (V + 35 s, V = [Settle.visibilityMs]), fixed on the window's clock; nothing shifts F,
     * the frozen time counts (a freeze also moves the end to at least the wake + V). A lease runs from its start + V, the
     * start being s + w, s the send time and w the long-poll wait of the request that may hold it (the relay can hand a
     * parked long poll a message up to w after the send, also after a failure on our side). The collector's sends are stamped
     * as each attempt is written (OkHttp's `requestHeadersStart`, after any wait for the network and the connect, retries
     * included). For a cut the start is never earlier than the notice, `max(s + w, notice)`, since a request may reach the
     * relay as late as its failure is seen: ours (abandoned or failed: `max(now, s + w)`), or the collector's ([cutLeases],
     * only the cuts [cuts] counted since the window began; one not described is taken as now + the long-poll cap). For a
     * freeze it is s + w of the latest request sent at or before the freeze began, ours or the collector's, kept after the
     * request ended ([collectorAge], [collectorSends]); for a send since one heartbeat before the last tick before the
     * freeze it is the wake + w (its bytes may reach the relay only at the wake, which parks it for its wait: the lead's call
     * R2). A cut of a request written at or before a freeze belongs to the freeze: the freeze's lease already covers it
     * (s + w, or the wake + w for such a send), so it is taken as its s + w and moves nothing (call C). Our own requests
     * are recorded with the wait they were sent with ([sentWait]: 0 while the collection runs). A cut is: a request of ours
     * abandoned or failed with anything but a relay status, or the collector's in-flight request cut or lost. A freeze is
     * a gap of more than [Settle.freezeMarginMs] between the ticks of a 1 s heartbeat that lives only as long as the window.
     * The check is made after the final collect too, so a cut or a freeze just before the end extends and loops instead of
     * dropping.
     *
     * **The last collect.** A lease that runs past the cap (its end + 15 s) is recorded however and whenever it was noticed
     * (before the cap or after). The final `wait=0` collect then goes at `max(now, latest lease end + 15 s)`
     * ([HOLD_MARGIN_MS]). The settlement has ONE extra in all: the hold target moves once (a cut or freeze noticed during the
     * hold; setting the first hold is no move; a later move is ignored, since a collector failing offline would otherwise
     * move it at every failure for ever); or, after the final send, one more held final collect, for a lease noticed during
     * it that outlives its start (past the cap, or within it but ending after the send began), or for a final collect the
     * relay refused (call B: 429, or 401 with a stale signature; any other status is no cut). In production the relay client
     * itself first retries a 429 or a stale 401 (up to 3 attempts, each signed afresh, honoring `retry_after`): a refusal
     * reaches the window only after those, and is then held for its `retry_after`, at most V, and sent as a new request; a
     * `retry_after` over the client's cap (30 s, `RelayClient.CallLimits`) is not waited for: it reaches the window at once
     * as that refusal, held the same way; one the client waits for that runs past the request's bound (wait + 30 s) ends
     * it as an abandoned request first, a cut held to max(now, s + w) + V + 15 s. Once the extra is used at or after the cap, nothing raises a hold past what was due when it
     * was taken (a background or a return grants nothing; one used before the cap leaves the first hold due); but a freeze
     * during the last send (the app in the background) earns one more `wait=0` look, so the drop never follows only a look
     * made before the freeze: at once and held for nothing when a lease hold was already honored or the extra was taken at
     * or after the cap; after an extra taken before the cap with no lease hold yet, held by the free first lease hold (the
     * freeze's lease + 15 s, no move). A cut during the last send after an extra taken before the cap, with no lease hold
     * honored yet, gets the same free first lease hold: one more `wait=0` collect at its lease end + 15 s (no extra, no
     * ceiling; spent once honored). Then the drop (not when paired, not when the end moved past now).
     *
     * **The bound** (cap = F + V + 35 s): the last ordinary step ends within 148 s of the cap (a round wait 55 s, a request
     * 30 s at wait 0, a round wait 55 s, a pause up to 8 s); its cut is noticed then, so the hold ends within V + 40 s more
     * (the lease starts by max(s + w, notice), at most the notice + 25 s, w being up to 25 s, the long-poll cap, ours and the
     * collector's; then the 15 s margin); the final
     * send takes at most 140 s (55 + 30 + 55); the extra (a hold move, a second held collect after the first send, or a
     * refused collect's `retry_after`, at most V) is at most V + 40 s more, and a second final send 140 s; the one look after
     * a freeze in the last send 140 s, at once (held for nothing): at most cap + 2V + 648 s in all (cap + 768 s at the
     * default V), plus any frozen time, for every V. An extra taken before the cap ends before it, so its `retry_after` hold
     * ends by cap + V; then the free first lease hold (V + 40 s) and the last send (140 s), or the last send and then the
     * free lease hold for a freeze in it (it is spent once), and the look (140 s); or, if a cut moved the end again, ordinary
     * steps to the cap (148 s) and the same: at most cap + max(2V + 320, V + 468) s, inside the main bound. With the free
     * lease hold after the last send for a cut in it, then the look for a freeze in the held collect: the last send 140 s,
     * the hold V + 40 s, the held collect 140 s and the look 140 s, at most cap + max(2V + 460, V + 608) s, inside it too.
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
        cutLeases: () -> List<CollectorCut> = { emptyList() },
        sentWait: (waitMs: Long) -> Long = { it },
    ): Boolean {
        if (paired()) return true
        val out = Requests(settle, collect, awaitRound, cuts, collectorAge, collectorSends, cutLeases, sentWait)
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
                        // beyond its start, or a refused last collect, earns ONE more held final collect in all; a freeze
                        // during the last send after that earns one more look (at once, or held by the free first lease
                        // hold after an early extra with none honored yet); then the drop.
                        out.awaitHold()
                        val sentAt = settle.now()
                        out.send(0L)
                        out.look()
                        if (out.left() <= 0 && !out.againAfterLastSend(sentAt)) break
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
    @Suppress("LongParameterList", "TooManyFunctions")
    private class Requests(
        private val settle: Settle,
        private val collect: suspend (waitMs: Long) -> Unit,
        private val round: suspend () -> Unit,
        private val cuts: () -> Long,
        private val collectorAge: () -> Long?,
        private val collectorSends: () -> List<Pair<Long, Long>>,
        private val cutLeases: () -> List<CollectorCut>,
        private val sentWait: (waitMs: Long) -> Long,
    ) {
        private val firstEnd = settle.now() + settle.windowMs
        private val cap = firstEnd + settle.maxExtraMs

        @Volatile private var endAt = firstEnd

        @Volatile private var lastTick = settle.now()

        /** The latest send (window clock) of any request, ours or the collector's, as of the last tick; kept after it ends. */
        @Volatile private var sampledSend: Long? = null

        /** The send times of our own latest requests (the last few) and the wait each carried; kept after they end. */
        private val ourSends = ArrayDeque<Pair<Long, Long>>()

        /** The latest end of a lease that runs past the cap (window clock), 0 when none: the last collect holds for it. */
        @Volatile private var maxLeaseEnd = 0L

        /** The latest end of any lease noticed (window clock), 0 when none. */
        @Volatile private var latestLeaseEnd = 0L

        /** The one extra of the settlement has been used. */
        @Volatile private var extraUsed = false

        /** Once the extra is used, no hold goes past this (what was due when it was taken): nothing grants a new hold. */
        @Volatile private var ceiling = Long.MAX_VALUE

        /** The extra's own hold: a refused last collect's `retry_after`, or a lease that ended during the last send. */
        @Volatile private var notBefore = 0L

        /** The latest send (window clock) a freeze seen took into its lease: sends at or before it belong to that freeze. */
        @Volatile private var freezeCovers = Long.MIN_VALUE

        /** The wake (window clock) of the latest freeze seen, 0 when none. */
        @Volatile private var lastWake = 0L

        /** A hold for a lease past the cap has been waited out (the free first lease hold is then spent). */
        @Volatile private var leaseHoldHonored = false

        /** The one look given after the extra, for a freeze during the last send, has been used. */
        private var lastLookUsed = false

        /** The latest request of ours was refused with 429 or a stale signature: its `retry_after` in ms (0 when none). */
        private var refusedRetryMs: Long? = null
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
         * failure). Once the extra is used, nothing raises the target past what was due when it was taken.
         */
        suspend fun awaitHold() {
            look()
            var target = holdDue()
            // Whether the target held for a lease: the first lease hold is no move (also when the target was only an
            // extra's own `retry_after`, taken before the cap), so it does not need the extra.
            var leaseHeld = maxLeaseEnd != 0L
            while (true) {
                val w = target - settle.now()
                if (w > 0) delay(w)
                look()
                val moved = holdDue()
                if (moved <= target || (leaseHeld && !takeExtra(moved))) break
                target = moved
                leaseHeld = maxLeaseEnd != 0L
            }
            if (leaseHeld) leaseHoldHonored = true
        }

        private fun holdTarget(): Long = if (maxLeaseEnd == 0L) 0L else maxLeaseEnd + settle.holdMarginMs

        private fun holdDue(): Long = minOf(maxOf(holdTarget(), notBefore), ceiling)

        /**
         * The one extra of the settlement: a move of the hold target, or another held final collect after the send. [due]
         * is the hold it grants: when it is taken at or after the cap, no later hold goes past it. One taken before the cap
         * (a refused or late last look at a first end the window then moved past) sets no ceiling: the first hold for a
         * lease past the cap is still due (it is no move), and the bound holds, since that extra lies inside the window.
         */
        @Synchronized
        private fun takeExtra(due: Long): Boolean {
            if (extraUsed) return false
            extraUsed = true
            if (settle.now() >= cap) ceiling = due
            return true
        }

        /**
         * After the last collect, at the end: whether to look once more. A lease noticed during it that outlives its start (one
         * past the cap, or one within it that ended during the send), or a last collect the relay refused (429, a stale
         * signature, after the client's own retries: nothing was looked at; held for its `retry_after`, at most V, and sent as a
         * new request, signed afresh), earns the one extra, if
         * still unused. Once it is used, a freeze during the last send (the app was in the background) earns one more look,
         * so the drop never follows a look made before the freeze: at once when a lease hold was honored or the extra was
         * taken at or after the cap, else held by the free first lease hold. A lease past the cap noticed during it (a cut)
         * after an extra taken before the cap, with no lease hold honored yet, also gets the free first lease hold, once.
         */
        fun againAfterLastSend(sentAt: Long): Boolean {
            val n = settle.now()
            val refused = refusedRetryMs
            val leases = maxOf(holdTarget(), if (latestLeaseEnd == 0L) 0L else latestLeaseEnd + settle.holdMarginMs)
            val due = if (refused != null) maxOf(leases, n + minOf(refused, settle.visibilityMs)) else leases
            val again = when {
                (refused != null || due > sentAt) && takeExtra(due) -> {
                    notBefore = due
                    true
                }
                freeLeaseHoldDue(n) -> true
                extraUsed && !lastLookUsed && lastWake > sentAt -> {
                    lastLookUsed = true
                    // At once, held for nothing, once a lease hold was honored or the extra was taken at or after the cap
                    // (it set a ceiling). After an extra taken before the cap with no lease hold yet, the look still gets the
                    // free first lease hold (the freeze's lease + 15 s): no move, within the bound.
                    synchronized(this) { if (leaseHoldHonored || ceiling != Long.MAX_VALUE) ceiling = minOf(ceiling, n) }
                    true
                }
                else -> false
            }
            return again
        }

        /**
         * A lease past the cap noticed during the last send (a cut, or a freeze) after an extra taken before the cap, with no
         * lease hold honored yet: the free first lease hold is still due (no extra, no ceiling; it is spent once the hold is
         * honored, so this answers true once).
         */
        private fun freeLeaseHoldDue(n: Long): Boolean =
            extraUsed && ceiling == Long.MAX_VALUE && !leaseHoldHonored && holdTarget() > n

        /**
         * A cut or a freeze: the message may be leased for a visibility timeout from [leaseStart] (a request's send + its
         * long-poll wait). The end moves to the lease's end + [Settle.holdMarginMs], never past the cap; a lease that runs
         * past the cap is recorded for the held last collect.
         */
        @Synchronized
        private fun mayHoldLease(leaseStart: Long) {
            val leaseEnd = leaseStart + settle.visibilityMs
            latestLeaseEnd = maxOf(latestLeaseEnd, leaseEnd)
            endAt = minOf(maxOf(endAt, leaseEnd + settle.holdMarginMs), cap)
            if (leaseEnd + settle.holdMarginMs > cap) maxLeaseEnd = maxOf(maxLeaseEnd, leaseEnd)
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
         * the margin before the wake; it is kept after the request ended): it runs to s + w + V (the relay hands a message
         * out up to w after the send). The end is extended, and a lease past the cap is recorded for the last collect to hold
         * for, whether the wake was before the cap or after it.
         */
        @Synchronized
        private fun tick() {
            val n = settle.now()
            val gap = n - lastTick
            val before = sampledSend
            val prevTick = lastTick
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
                lastWake = n
                freezeCovers = maxOf(freezeCovers, froze, n - settle.freezeMarginMs)
                // s: the lease start of the latest send at or before the freeze began: the last tick's sample, or a later send
                // up to a tick after it, or one older than the margin before the wake (per send: a restart right after the wake
                // does not hide the send before the freeze). A send since one heartbeat before the last tick before the freeze
                // may not have left the phone before it froze (a tick can fall between the send and the freeze): its bytes can
                // reach the relay at the wake, which parks it for its wait, so its lease starts by the wake + w (the lead's call
                // R2; the wake is this freeze's notice, so still the notice + 25 s).
                val eligible = sends.filter { it.first <= froze || it.first <= n - settle.freezeMarginMs }
                    .map { (sent, w) -> if (sent >= prevTick - settle.heartbeatMs) n + w else sent + w }
                val s = (eligible + listOfNotNull(before)).maxOrNull()
                if (s != null) mayHoldLease(s) // the lease of the request that was out
                endAt = minOf(maxOf(endAt, n + settle.visibilityMs), cap)
            }
            sampledSend = latest
        }

        /**
         * Looks for a freeze and for cut collector requests (stopped, restarted or lost with a request out). A cut's lease
         * starts by its request's send + wait ([cutLeases]: the device's latest cuts, numbered as [cuts] counts them; only
         * those counted since the window began, or since the last look, are new). A cut the device did not describe is taken
         * as now + the long-poll cap. A cut of a request sent at or before a freeze has a lease within the freeze's, so it
         * moves nothing: it belongs to the freeze.
         */
        fun look() {
            tick()
            val c = cuts()
            if (c != seenCuts) {
                val n = settle.now()
                val fresh = cutLeases().filter { it.number in (seenCuts + 1)..c }.map { cutLeaseStart(it, n) }
                val described = fresh.size.toLong() >= c - seenCuts
                seenCuts = c
                val start = if (described) fresh.max() else maxOf(fresh.maxOrNull() ?: Long.MIN_VALUE, n + settle.pollMs)
                mayHoldLease(start)
            }
        }

        /**
         * A cut's lease start: by its request's send + wait (a request cut on our side may still be parked at the relay), and
         * no earlier than the notice [n] (a request may reach the relay as late as the failure is seen). A request written at
         * or before a freeze belongs to the freeze: it reached the relay before it, so it leases by s + w, within the
         * freeze's own lease (s + w of the latest such send), and moves nothing (call C).
         */
        private fun cutLeaseStart(cut: CollectorCut, n: Long): Long {
            val sent = n - cut.sentAgoMs
            return if (sent <= freezeCovers) sent + cut.waitMs else maxOf(sent + cut.waitMs, n)
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
            refusedRetryMs = null
            val sentAt = settle.now()
            // The lease of this request starts by its send + the wait it is actually sent with (wait=0 while the collection
            // runs), at most the one asked.
            val leaseStart = sentAt + minOf(sentWait(waitMs), waitMs)
            try {
                synchronized(this) {
                    ourSends.addLast(sentAt to leaseStart - sentAt)
                    if (ourSends.size > MAX_SENDS) ourSends.removeFirst()
                }
                // Never shorter than its own wait: a poll parked at the relay is not cut while it can hold a message.
                if (withTimeoutOrNull(bound) { collect(waitMs) } == null) {
                    failures++
                    // It may have reached the relay, and been answered, as late as it was abandoned.
                    mayHoldLease(maxOf(settle.now(), leaseStart))
                } else {
                    failures = 0
                }
                look()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                failures++ // a status came back: nothing was leased; tried again with backoff
                // The relay refused to look (rate limited, or a signature it found stale): at the end, one more look.
                if (e.status == RelayException.HTTP_TOO_MANY || e.staleSignature) refusedRetryMs = e.retryAfterSeconds * MS
            } catch (_: Exception) {
                failures++ // e.g. a body that could not be read: it may have been answered and leased, as late as now
                mayHoldLease(maxOf(settle.now(), leaseStart))
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
            cutLeases: () -> List<CollectorCut> = { emptyList() },
            sentWait: (waitMs: Long) -> Long = { it },
        ): Boolean = lock.withLock {
            if (due()) {
                atDeadline(paired, collect, drop, alive, settle, awaitRound, cuts, collectorAge, collectorSends, cutLeases, sentWait)
            } else {
                paired()
            }
        }
    }
}
