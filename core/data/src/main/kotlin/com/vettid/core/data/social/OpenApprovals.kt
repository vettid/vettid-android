package com.vettid.core.data.social

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import java.time.Duration
import java.time.Instant

/**
 * What the approvals indicator and the drawer's Approvals badge show (ANDROID-PLAN §4 "The approvals indicator", D9):
 * [count] open approvals, and [critical] while one of them is time-critical. One source for both.
 */
data class OpenApprovals(val count: Int = 0, val critical: CriticalApproval? = null)

/**
 * The time-critical state: [deadline] is the soonest time-critical entry's `exp` (null while none of them has one: the
 * clock alone), [label] that entry's name for the description (null when it names none), and [urgent] whether any
 * time-critical entry is other than a sign-in (urgency colours; sign-ins alone are neutral).
 */
data class CriticalApproval(val deadline: Instant?, val urgent: Boolean, val label: String?)

/** The rules of §4 "The approvals indicator" 1, 3 and 5, pure. */
object OpenApprovalRules {
    /** An entry with a deadline becomes time-critical in its last 2 minutes (`APPROVAL_URGENT_SECONDS`). */
    const val APPROVAL_URGENT_SECONDS = 120L
    private val URGENT_WINDOW: Duration = Duration.ofSeconds(APPROVAL_URGENT_SECONDS)

    /**
     * An open approval at [now] (1): an entry waiting for this member's decision ([needsDecision]: a connection request
     * waiting for the handshake or for the other side is not) whose deadline has not passed (`exp`; an entry with none
     * counts until it is decided). Decided and expired entries, a location request answered Not now, and anything that
     * is not an Approvals entry (a vote, a notice) are not in the list or not counted.
     */
    fun counts(a: Approval, now: Instant): Boolean = a.needsDecision && a.exp?.let { now.isBefore(it) } != false

    /**
     * Time-critical from arrival (5a): a `connection.authenticate` challenge of any purpose, a `device.session.pending`
     * request and an `approval.pending` request.
     */
    fun criticalFromArrival(a: Approval): Boolean =
        a is Approval.Authentication || (a is Approval.DeviceRequest && (a.isSession || a.isHeld))

    /** Time-critical at [now] (5): from arrival, or in the last [APPROVAL_URGENT_SECONDS] before the entry's deadline. */
    fun timeCritical(a: Approval, now: Instant): Boolean =
        criticalFromArrival(a) || a.exp?.let { !now.plus(URGENT_WINDOW).isBefore(it) } == true

    /** A sign-in challenge (`purpose: "signin"`) gets the neutral countdown, every other time-critical entry the urgent one. */
    fun signIn(a: Approval): Boolean = a is Approval.Authentication && a.purpose == AuthPurpose.SIGNIN

    fun of(list: List<Approval>, now: Instant): OpenApprovals {
        val open = list.filter { counts(it, now) }
        val count = entries(open)
        val critical = open.filter { timeCritical(it, now) }
        if (critical.isEmpty()) return OpenApprovals(count)
        val soonest = critical.filter { it.exp != null }.minByOrNull { it.exp!! }
        return OpenApprovals(
            count,
            CriticalApproval(deadline = soonest?.exp, urgent = critical.any { !signIn(it) }, label = soonest?.let(::label)),
        )
    }

    /** The instants at which [of] can change by time alone: each deadline, and the start of its last 2 minutes. */
    fun changesAt(list: List<Approval>): List<Instant> =
        list.filter { it.needsDecision }.mapNotNull { it.exp }.flatMap { listOf(it.minus(URGENT_WINDOW), it) }

    /**
     * The number of entries among [open] (1): a connection's asks within 10 minutes of the first are one batch, one entry, as
     * the Approvals list shows them ([AskBatching]); every other approval is an entry of its own.
     */
    private fun entries(open: List<Approval>): Int {
        val (asks, others) = open.partition { AskBatching.connectionOf(it) != null }
        return others.size + asks.groupBy { AskBatching.connectionOf(it) }.values.sumOf { AskBatching.split(it).size }
    }

    /** The name an entry carries: its connection's, a connection request's own, else its device's; null when it names none. */
    private fun label(a: Approval): String? {
        val own = when (a) {
            is Approval.ConnectionRequest -> a.name
            is Approval.OutgoingRequest -> a.name
            is Approval.DeviceRequest -> a.deviceName
            else -> null
        }
        return a.connectionName?.takeIf { it.isNotBlank() } ?: own?.takeIf { it.isNotBlank() }
    }
}

/**
 * What time it is for deadlines: emits now, then again at each of [points] still to come (one sleep to the next, never a
 * polling loop; it ends when nothing is left). A sleep does not run while the phone is in deep sleep, so [resumed] (the app
 * came back to the front) reads the time again and starts the sleeps over from it. [now] is the clock (tests pass a
 * virtual one).
 */
fun deadlineClock(
    points: Flow<List<Instant>>,
    resumed: Flow<Unit> = emptyFlow(),
    now: () -> Instant = Instant::now,
): Flow<Instant> =
    combine(points.distinctUntilChanged(), resumed.onStart { emit(Unit) }) { list, _ -> list }.flatMapLatest { list ->
        flow {
            emit(now())
            for (at in list.distinct().sorted()) {
                val wait = at.toEpochMilli() - now().toEpochMilli()
                if (wait > 0) delay(wait)
                // The sleep ended at the point: report at least that instant.
                if (wait >= 0) emit(maxOf(now(), at))
            }
        }
    }
