package com.vettid.core.data.vault

import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.Instant

/** The vault's owner-check state (VAULT-MESSAGING §3.6, `vault.status` `owner_check.state`). */
enum class OwnerCheckState {
    /** Before the deadline. */
    OK,

    /** Past the deadline with the hold off (§3.6.7): the app is gated all the same; the rest of the vault runs. */
    DUE,

    /** Past the deadline with the hold on (§3.6.3). */
    HELD,
}

/** `vault.held`'s counts of what arrived since the deadline (§3.6.3): no names, no content. */
data class WaitingCounts(val messages: Int = 0, val requests: Int = 0, val calls: Int = 0, val other: Int = 0) {
    val isEmpty: Boolean get() = messages == 0 && requests == 0 && calls == 0 && other == 0
}

/**
 * The daily owner check as this app knows it (§3.6): from `vault.status`, the check's answers, `vault.held`,
 * `sync.event{owner_check}` and `owner_check_required` refusals; kept on the phone so that a locked vault past
 * its deadline can ask for the PIN and the password on one screen (§3.6.5).
 */
data class OwnerCheckView(
    val state: OwnerCheckState,
    val deadline: Instant?,
    val intervalSeconds: Long,
    /** Consecutive failed checks (§3.6.4); at [MAX_FAILURES] the vault locks. */
    val failures: Int,
    /** `owner_check.hold` (§3.6.7). */
    val hold: Boolean,
    /** When a hold turned off comes back on by itself; null: until the member turns it on. */
    val holdOffUntil: Instant?,
    /** The latest `vault.held` counts, while gated. */
    val waiting: WaitingCounts? = null,
) {
    /** Past the deadline: the app may send only the check (§3.6.3), whether or not the hold is on. */
    fun gated(now: Instant): Boolean = state != OwnerCheckState.OK || (deadline != null && !now.isBefore(deadline))

    /** From [WARNING] before the deadline until it (§3.6.5: the reference app warns from 1 h ahead). */
    fun warning(now: Instant): Boolean = deadline != null && !gated(now) && !now.isBefore(deadline.minus(WARNING))

    /** Whether the hold is off now (an end date in the past means it came back on). */
    fun holdOff(now: Instant): Boolean = !hold && (holdOffUntil == null || now.isBefore(holdOffUntil))

    /** Failed checks left before the vault locks (10 − `failures`, §3.6.5). */
    val checksLeft: Int get() = (MAX_FAILURES - failures).coerceAtLeast(0)

    companion object {
        const val MAX_FAILURES = 10

        /** `owner_check.interval_seconds`: 1–24 h (§3.6.2). */
        const val MIN_INTERVAL_S = 3_600L
        const val MAX_INTERVAL_S = 86_400L

        /** The longest a hold may stay off (§3.6.7). */
        val MAX_HOLD_OFF: Duration = Duration.ofDays(30)

        val WARNING: Duration = Duration.ofHours(1)
    }
}

/** The answer to a check (§3.6.1). */
sealed interface OwnerCheckOutcome {
    data object Passed : OwnerCheckOutcome

    /** The PIN was wrong ([checksLeft] before the vault locks, when the vault said). */
    data class BadPin(val checksLeft: Int?) : OwnerCheckOutcome

    /** The PIN was right and the password wrong. */
    data class BadPassword(val checksLeft: Int?) : OwnerCheckOutcome

    /** A PIN or password backoff runs; [retryAfterSeconds] 0 when the vault did not say how long. */
    data class Backoff(val retryAfterSeconds: Long) : OwnerCheckOutcome

    data class Failed(val kind: FailureKind, val code: String?) : OwnerCheckOutcome
}

/** An `owner_check.*` activity-feed item (§3.6.4, §3.6.7) the app shows once the vault is open. */
data class OwnerCheckNotice(
    val itemId: String,
    /** `owner_check.failed`, `owner_check.locked` or `owner_check.hold_changed`. */
    val kind: String,
    /** `pin` / `password`, the failure count, or `on` / `off` / `off_until:<ts>` / `on:expired`. */
    val ref: String?,
    val at: Instant?,
    val urgent: Boolean,
)

/** The daily owner check (§3.6) for the app's screens. Every failure is a [VaultFailure] or an [OwnerCheckOutcome]. */
interface OwnerCheckRepository {
    /** Null until the vault reported its owner check (or for a vault older than 0.13.0). */
    val ownerCheck: StateFlow<OwnerCheckView?>

    /** The vault locked after ten consecutive failed checks (`vault.locking{reason: "owner_check"}`, §3.6.4). */
    val lockedByOwnerCheck: StateFlow<Boolean>

    /** The answer to the check sent right after an unlock past the deadline, for the check screen to show. */
    val unlockCheckOutcome: StateFlow<OwnerCheckOutcome?>

    /** Unread `owner_check.*` feed items. */
    val notices: StateFlow<List<OwnerCheckNotice>>

    /** Re-reads `vault.status`. */
    suspend fun refreshOwnerCheck()

    /**
     * Sends the check with [pin] and [password]. [holdOff] non-null turns the hold off (§3.6.7), until the
     * given time or, with [HoldOff.until] null, until the member turns it on.
     */
    suspend fun check(pin: String, password: String, holdOff: HoldOff? = null): OwnerCheckOutcome

    /** `owner_check.interval_seconds` (§3.6.2): shorter at once, longer from the next check. */
    suspend fun setCheckInterval(seconds: Long)

    /** Turns the hold back on (`settings.set`, no check needed, §3.6.7). */
    suspend fun turnHoldOn()

    fun consumeUnlockCheckOutcome()

    /** Marks the notices read (`feed.update{status: "read"}`). */
    suspend fun dismissNotices()
}

/** Turning the hold off (§3.6.7): [until] at most 30 days ahead, or null. */
data class HoldOff(val until: Instant?)
