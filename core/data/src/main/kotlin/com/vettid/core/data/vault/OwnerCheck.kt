package com.vettid.core.data.vault

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
    /**
     * (VAULT-MESSAGING 0.29.0 §3.6.8, release L) When the vault locks itself if no check is passed, as it last said
     * (`vault.status`, `vault.held`); null from a vault before L, and until it said again after a check moved the
     * deadline.
     */
    val idleLockAt: Instant? = null,
) {
    /**
     * How long past the deadline the vault locks itself, as it last said ([idleLockAt] − [deadline], on the vault's
     * clock and in its time); null when either is not known.
     */
    val idleAfterSeconds: Long?
        get() {
            val at = idleLockAt ?: return null
            val d = deadline ?: return null
            return Duration.between(d, at).seconds.takeIf { it > 0 }
        }

    /** Past the deadline: the app may send only the check (§3.6.3), whether or not the hold is on. */
    fun gated(now: Instant): Boolean = state != OwnerCheckState.OK || (deadline != null && !now.isBefore(deadline))

    /**
     * From [WARNING] before the deadline until it (§3.6.5: the reference app warns from 1 h ahead); on a vault with a
     * time scale (STAGING-TIME-SCALE, S2) from [WARNING] divided by [timeScale] before it (2.5 minutes at 24).
     */
    fun warning(now: Instant, timeScale: Long = 1): Boolean =
        deadline != null && !gated(now) && !now.isBefore(deadline.minus(warningFor(timeScale)))

    /** The interval as it runs on a vault of [timeScale] (the setting keeps its real units, §3.6.2). */
    fun intervalRunsSeconds(timeScale: Long): Long = TimeScales.divide(intervalSeconds, timeScale)

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

        /** (STAGING-TIME-SCALE, S2) The early warning on a vault of [timeScale]. */
        fun warningFor(timeScale: Long): Duration = Duration.ofSeconds(TimeScales.divide(WARNING.seconds, timeScale))
    }
}

/**
 * (VAULT-MESSAGING 0.29.0 §3.6.8, release L) The vault locked itself because no check was passed in time
 * (`vault.locking{reason: "idle"}`, or, without the notice, a locked vault past the [OwnerCheckView.idleLockAt] the app
 * last read). [afterSeconds]: how long past the deadline that was, when known (for "You hadn't checked in for 7 days").
 */
data class IdleLocked(val afterSeconds: Long?)

/**
 * (Release L) `idle_lock.after_seconds` (§10.8), as the app knows it: [seconds] is what is set; [inForceSeconds] what the
 * vault uses until the next successful check when a longer value was set since the last one (the vault keeps the lowest
 * value since then; owner decision 2026-10-23), else [seconds].
 */
data class IdleLockSetting(val seconds: Long, val inForceSeconds: Long = seconds) {
    /** A longer value than the one in force was set: it takes effect after the next check. */
    val afterNextCheck: Boolean get() = seconds > inForceSeconds

    companion object {
        /** 1 hour to 30 days, default 7 days, no "never" (§3.6.8, VAULT-LOCK L11, L12). */
        const val MIN_S = 3_600L
        const val MAX_S = 2_592_000L
        const val DEFAULT_S = 604_800L

        /** The choices (ANDROID-PLAN §4 "Locking the vault" part 2): 1 hour, 1 day, 7 days, 14 days, 30 days. */
        val CHOICES_S = listOf(3_600L, 86_400L, 604_800L, 1_209_600L, 2_592_000L)
    }
}

/** The answer to a check (§3.6.1). */
sealed interface OwnerCheckOutcome {
    data object Passed : OwnerCheckOutcome

    /**
     * The PIN was wrong ([checksLeft] before the vault locks, when the vault said). [estimated]: (ANDROID-PLAN 0.1.95) the
     * count is the app's estimate, the `vault.status` read after the failure having not come, not a count the vault gave.
     */
    data class BadPin(val checksLeft: Int?, val estimated: Boolean = false) : OwnerCheckOutcome

    /** The PIN was right and the password wrong. [estimated] as in [BadPin]. */
    data class BadPassword(val checksLeft: Int?, val estimated: Boolean = false) : OwnerCheckOutcome

    /** A PIN or password backoff runs; [retryAfterSeconds] 0 when the vault did not say how long. */
    data class Backoff(val retryAfterSeconds: Long) : OwnerCheckOutcome

    /** [shownCode]: (ANDROID-PLAN 0.1.57 (2)) the code `error_vault_code` names ([VaultFailure.shownCode]), or null. */
    data class Failed(
        val kind: FailureKind,
        val code: String?,
        val shownCode: String? = null,
        /**
         * (ANDROID-PLAN 0.1.95, VAULT-MESSAGING 0.73.0) Set only when the check went unanswered ([unanswered]): the app's
         * estimate of the tries left, which already counts this check (it may have counted at the vault).
         */
        val estimatedLeft: Int? = null,
    ) : OwnerCheckOutcome {
        /** The check may have reached the vault and counted, though no answer came: the same kinds that keep the PIN. */
        val unanswered: Boolean get() = kind in UNANSWERED

        companion object {
            /** Failures after which the same check may be sent again with the kept PIN; any other drops it (fail secure). */
            val UNANSWERED = setOf(
                FailureKind.NETWORK, FailureKind.NO_RESPONSE, FailureKind.RATE_LIMITED, FailureKind.VAULT_UNAVAILABLE,
                FailureKind.SERVICE_PAUSED,
            )
        }
    }
}

/**
 * (ANDROID-PLAN 0.1.95, VAULT-MESSAGING 0.73.0) What one read of the vault's state found, with no check sent (no try used):
 * `vault.status` through the relay, then the member API's `GET /api/vault/status`.
 */
enum class VaultProbe {
    /** `vault.status` answered: the vault is open, and [OwnerCheckRepository.ownerCheck] has the count from the answer. */
    OPEN,

    /** The relay sent `vault.locking`, or the member API said `state: locked`; [OwnerCheckRepository.lockedByOwnerCheck] is set. */
    LOCKED,

    /** Neither answered, or the API said `unlocked` (which is not an answer from the vault). */
    NO_ANSWER,
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
@Suppress("TooManyFunctions") // one per thing the screens ask of the owner check
interface OwnerCheckRepository {
    /** Null until the vault reported its owner check (or for a vault older than 0.13.0). */
    val ownerCheck: StateFlow<OwnerCheckView?>

    /** The vault locked after ten consecutive failed checks (`vault.locking{reason: "owner_check"}`, §3.6.4). */
    val lockedByOwnerCheck: StateFlow<Boolean>

    /**
     * (Release L, §3.6.8) The vault locked itself (`vault.locking{reason: "idle"}`); null otherwise. Kept on the phone
     * until the next unlock. Without the notice, see [idleLockedNow].
     */
    val idleLocked: StateFlow<IdleLocked?> get() = NO_IDLE_LOCK

    /**
     * (Release L) The idle-lock setting; null for a vault that does not have it (before L) or before it was read
     * ([refreshIdleLockSetting]).
     */
    val idleLockSetting: StateFlow<IdleLockSetting?> get() = NO_IDLE_SETTING

    /**
     * (Release L) Why the locked vault is locked, if it locked itself: the notice ([idleLocked]), or, lacking it (the
     * phone was off), a time past the [OwnerCheckView.idleLockAt] the app last read ("the app says why from the notice
     * or, lacking it, from `idle_lock_at` as it last read it"), unless the member locked it on this phone since.
     */
    fun idleLockedNow(now: Instant): IdleLocked? = idleLocked.value ?: ownerCheck.value?.let { v ->
        v.idleLockAt?.takeIf { !now.isBefore(it) }?.let { IdleLocked(v.idleAfterSeconds) }
    }

    /** (Release L) The member locked the vault on this phone: a later unlock screen does not say it locked itself. */
    fun onLockedHere() {}

    /** Reads `idle_lock.after_seconds` (`settings.get`); quiet for a vault before L, which does not list it. */
    suspend fun refreshIdleLockSetting() {}

    /**
     * Sets `idle_lock.after_seconds` (one of [IdleLockSetting.CHOICES_S]): a shorter value applies at once, a longer one
     * from the next successful check (§3.6.8).
     */
    suspend fun setIdleLockAfter(seconds: Long) {}

    /** The answer to the check sent right after an unlock past the deadline, for the check screen to show. */
    val unlockCheckOutcome: StateFlow<OwnerCheckOutcome?>

    /**
     * (ANDROID-PLAN 0.1.93) The PIN the unlock had accepted when its check failed on the password (or backed off), once: the
     * held screen then asks for the password only. Null when the app was not in front meanwhile, or nothing is kept.
     */
    fun takeUnlockPin(): String? = null

    /** (ANDROID-PLAN 0.1.93) The app is in front or not; the PIN kept for the retry is forgotten when it is not. */
    fun setForeground(inFront: Boolean) {}

    /** Counts the times the app went to the background: a PIN kept across an out-and-back during a request is dropped. */
    val foregroundGeneration: Int get() = 0

    /** Whether the app is in front (the process, not a screen: in-app navigation and rotation do not change it). */
    val inFront: StateFlow<Boolean> get() = ALWAYS_IN_FRONT

    /**
     * (ANDROID-PLAN 0.1.95) [lockedByOwnerCheck] was set by a read that found the vault locked without knowing why (a check
     * with no answer while tries were estimated to be left): the locked screen, not the "ten wrong checks" text.
     */
    val lockCauseUnknown: StateFlow<Boolean> get() = NOT_SET

    /** (ANDROID-PLAN 0.1.95) Emits each time the relay connection came back (a reconnect starts a read while "probably locked"). */
    val relayReconnects: Flow<Unit> get() = emptyFlow()

    /**
     * (ANDROID-PLAN 0.1.95, VAULT-MESSAGING 0.73.0) Reads the vault's state without sending a check. [lockCauseKnown]: an
     * estimate of 0 names the cause (ten wrong checks); otherwise a lock found is of unknown cause ([lockCauseUnknown]).
     */
    suspend fun probeVault(lockCauseKnown: Boolean): VaultProbe = VaultProbe.NO_ANSWER

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

    /**
     * (Release R, §11.14.5) The last check passed but the vault could not record that the member was here
     * (`recovery.present_recorded: false`): the presence notice shows until a later answer or `vault.status` says the
     * presence was recorded.
     */
    val presenceUnrecorded: StateFlow<Boolean> get() = NOT_SET

    /**
     * (Release R, ANDROID-PLAN "Other screens") An unlock that cancelled a recovery sent its owner check and that check
     * did not pass: the owner-check screen is to be shown at once, so that the member's presence is recorded.
     */
    val checkAfterCancel: StateFlow<Boolean> get() = NOT_SET

    fun consumeCheckAfterCancel() {}

    /** Marks the notices read (`feed.update{status: "read"}`). */
    suspend fun dismissNotices()
}

private val NOT_SET: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
private val ALWAYS_IN_FRONT: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)
private val NO_IDLE_LOCK: StateFlow<IdleLocked?> = kotlinx.coroutines.flow.MutableStateFlow(null)
private val NO_IDLE_SETTING: StateFlow<IdleLockSetting?> = kotlinx.coroutines.flow.MutableStateFlow(null)

/** Turning the hold off (§3.6.7): [until] at most 30 days ahead, or null. */
data class HoldOff(val until: Instant?)

/**
 * Emits each time the vault goes from held or due (§3.6.3) to ok: the check passed, here or on another device, or the
 * hold otherwise ended. A list that failed with [FailureKind.OWNER_CHECK_REQUIRED] reads again on it, instead of
 * keeping "Your daily check is due" until the member taps Try again. No view (locked, signed out) changes nothing.
 */
fun OwnerCheckRepository.checkCleared(): Flow<Unit> = flow {
    var held = false
    ownerCheck.collect { v ->
        if (v == null) return@collect
        val now = v.state != OwnerCheckState.OK
        if (held && !now) emit(Unit)
        held = now
    }
}
