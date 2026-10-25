package com.vettid.core.data.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * (ANDROID-PLAN 0.1.54 "A vault being deleted") The marker `deleting {vault_id, since, until}` (epoch ms, this phone's
 * clock): when `device.unlinked{vault_deleted}` arrived, and the bound until which the status is re-read on a timer.
 */
@Serializable
internal data class DeletingMarker(val vaultId: String, val sinceMs: Long, val untilMs: Long)

/**
 * (ANDROID-PLAN 0.1.78 §4 "A vault being deleted", 5) The marker `deleted_api {vault_id, since, until, code}`: the member
 * API said `410 vault_deleted` or `410 account_deleted` (signal (b) alone, no word from the vault). [untilMs] is
 * [sinceMs] + 10 minutes, not scaled by the staging time scale; [code] is the 410's `code`.
 */
@Serializable
internal data class DeletedApiMarker(val vaultId: String, val sinceMs: Long, val untilMs: Long, val code: String) {
    /** The same times as the watch's marker, so that one [DeletionWatch] serves both states. */
    fun watch(): DeletingMarker = DeletingMarker(vaultId, sinceMs, untilMs)
}

/** What one read of `GET /api/vault/status` says of a vault being deleted. */
enum class DeletionCheck {
    /**
     * A definite word that the vault is gone, **reachable only from `410 vault_deleted` / `410 account_deleted`** (MEMBER-API
     * 2.11.0; [VaultDeletion.readCheck]): neither `{vault: null}` (advisory) nor a bare `401` (the member API gives the same
     * 401 for ts, nonce, signature and other faults) is definite. The app acts on it by erasing only with the vault's own
     * word as well ([VaultDeletion.resolve]); a 410 alone is [API_ONLY].
     */
    GONE,

    /**
     * The member API said `410 vault_deleted` / `410 account_deleted`, and the phone has no word from the vault (no
     * `device.unlinked{vault_deleted}`, no answer to its own `vault.delete`): "Your vault was deleted", nothing erased.
     */
    API_ONLY,

    /** The vault is still listed, whatever its `state`. */
    LISTED,

    /**
     * The member API answered, but not definitely: `{vault: null}` (advisory) or a `401` of any kind. This phone cannot
     * tell a deleted vault from a clock, nonce, signature or key fault, so nothing is erased.
     */
    UNCONFIRMED,

    /** As [UNCONFIRMED], and the 401 was for the phone's clock (a date/time hint is shown). Never erases. */
    UNCONFIRMED_CLOCK,

    /** The member API could not be reached (network, timeout, 5xx, paused): nothing is concluded. */
    UNREACHABLE,
}

/**
 * The bound and the re-reads of "being deleted" (VAULT-MESSAGING §12.5: the member API reads the vault as gone only after
 * step 5, MEMBER-API "Vault deletion").
 */
@Suppress("TooManyFunctions") // the rules of one state, each a pure function
object VaultDeletion {
    /** The bound: 10 minutes after the event. */
    const val BOUND_MS = 10 * 60 * 1000L

    /** After this phone's own `vault.delete`: its `exp` plus 2 minutes, when that is later. */
    const val AFTER_EXP_MS = 2 * 60 * 1000L

    /** The waits between the timed re-reads: 5, 10 and 30 s, then 60 s each. */
    val FIRST_WAITS_MS: List<Long> = listOf(5_000L, 10_000L, 30_000L)
    const val THEN_EVERY_MS = 60_000L

    /** The bound of a deletion whose event arrived at [sinceMs]; [deleteExpMs]: the `exp` of a `vault.delete` this phone sent. */
    fun untilMs(sinceMs: Long, deleteExpMs: Long?): Long {
        val bound = sinceMs + BOUND_MS
        return if (deleteExpMs != null && deleteExpMs + AFTER_EXP_MS > bound) deleteExpMs + AFTER_EXP_MS else bound
    }

    /** The timed re-read after [sinceMs] that comes after [nowMs] (5, 15, 45, 105, 165 s, … after the event). */
    fun nextReadMs(sinceMs: Long, nowMs: Long): Long {
        var at = sinceMs
        var i = 0
        while (at <= nowMs) {
            at += FIRST_WAITS_MS.getOrElse(i) { THEN_EVERY_MS }
            i++
        }
        return at
    }

    /**
     * Whether the status's `state` says the vault is open: only `unlocked`. Any other value, one this app does not know
     * included, is locked. (MEMBER-API 2.10.0, VAULT-MESSAGING 0.51.0: the status never says a vault is being deleted; the
     * app shows "being deleted" only after the vault's own `device.unlinked{vault_deleted}`, never from the status.)
     */
    fun statusUnlocked(state: String?): Boolean = state == STATE_UNLOCKED

    private const val STATE_UNLOCKED = "unlocked"

    /**
     * What a status answer says: [vaultListed] false is `{vault: null}`. While the service is paused ([servicePaused]) an
     * answer concludes nothing (MEMBER-API 1.2.0): the vault is not taken as gone.
     */
    fun ofStatus(vaultListed: Boolean, servicePaused: Boolean = false): DeletionCheck = when {
        servicePaused -> DeletionCheck.UNREACHABLE
        vaultListed -> DeletionCheck.LISTED
        // `{vault: null}` is advisory (as in VaultManager.evaluate()): it never erases this phone.
        else -> DeletionCheck.UNCONFIRMED
    }

    /**
     * Stores the deletion's marker with [write] (true when written), tried up to [attempts] times: a write dropped because
     * a reset or a persist failed is tried again with the record as it is then. False when it was never written.
     */
    @Suppress("TooGenericExceptionCaught") // a failed persist is one more try, never a crash of the deletion
    fun saveMarker(attempts: Int = MARKER_ATTEMPTS, write: () -> Boolean): Boolean {
        repeat(attempts) {
            val ok = try {
                write()
            } catch (_: Exception) {
                false
            }
            if (ok) return true
        }
        return false
    }

    private const val MARKER_ATTEMPTS = 3

    /**
     * What a failed read says: nothing definite, whatever the kind. A 401 of any kind ([FailureKind.UNAUTHORIZED], and
     * [FailureKind.CLOCK], a 401 for the clock) is UNCONFIRMED: the member API's bare 401 does not tell a deleted vault
     * from a missing Date header, a nonce clash or a key fault. Everything else (network, timeout, 5xx, paused, 403) is
     * UNREACHABLE.
     */
    fun ofFailure(kind: FailureKind): DeletionCheck =
        when (kind) {
            FailureKind.UNAUTHORIZED -> DeletionCheck.UNCONFIRMED
            FailureKind.CLOCK -> DeletionCheck.UNCONFIRMED_CLOCK
            else -> DeletionCheck.UNREACHABLE
        }

    /**
     * What a failed read says, looking at the error itself: a 403 (`forbidden`, which [VaultManager.memberFailure] maps to
     * OTHER) is UNCONFIRMED like a 401 (the API answered, but not definitely); 5xx, 429 and the rest stay as [ofFailure].
     */
    fun ofFailure(f: VaultFailure): DeletionCheck {
        if (isGone410(f)) return DeletionCheck.GONE
        val forbidden = f.code == "forbidden" || (f.cause as? com.vettid.core.altchan.MemberApiException)?.status == HTTP_FORBIDDEN
        return if (forbidden) DeletionCheck.UNCONFIRMED else ofFailure(f.kind)
    }

    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_GONE = 410

    /** The `code`s of the member API's `410` that say the vault is gone (MEMBER-API 2.11.0). Not `release_unavailable`. */
    const val CODE_VAULT_DELETED = "vault_deleted"
    const val CODE_ACCOUNT_DELETED = "account_deleted"

    /**
     * Whether [f] is the member API's definite `410 vault_deleted` / `410 account_deleted`: read from the error itself (status
     * and code), never from a [FailureKind] alone, so that no other refusal can be taken for it.
     */
    fun isGone410(f: VaultFailure): Boolean {
        val e = f.cause as? com.vettid.core.altchan.MemberApiException ?: return false
        return e.status == HTTP_GONE && (e.code == CODE_VAULT_DELETED || e.code == CODE_ACCOUNT_DELETED)
    }

    /**
     * What a [DeletionCheck.GONE] allows (owner decisions of 2026-10-24, VAULT-MESSAGING 0.57.0 §12.5): with the vault's own
     * word ([vaultWord]: the phone is in "being deleted" from `device.unlinked{vault_deleted}` or its own `vault.delete`
     * answer) the phone erases; with the 410 alone it is [DeletionCheck.API_ONLY]. Every other check is as it is.
     */
    fun resolve(check: DeletionCheck, vaultWord: Boolean): DeletionCheck =
        if (check == DeletionCheck.GONE && !vaultWord) DeletionCheck.API_ONLY else check

    /** The bound of a 410 without the vault's word: 10 minutes after the first 410, never scaled. */
    fun apiUntilMs(sinceMs: Long): Long = sinceMs + BOUND_MS

    /** What the event observer does with a vault event while a 410 without the vault's word stands. */
    enum class EventAction {
        /** `device.unlinked{vault_deleted}`: the vault's own word, with the 410: erase at once. */
        ERASE,

        /** `device.unlinked{transferred|replaced}`: proofs that this phone was replaced, handled as always (HolderWatch). */
        HOLDER,

        /** Everything else is not taken. */
        DROP,
    }

    /** The action for an event of [type] with [reason] while [apiStanding]; without a standing 410 the ordinary handling applies (null). */
    fun eventAction(type: String, reason: String?, apiStanding: Boolean): EventAction? = when {
        !apiStanding -> null
        type != HolderPolicy.DEVICE_UNLINKED -> EventAction.DROP
        reason == "vault_deleted" -> EventAction.ERASE
        reason == HolderPolicy.REASON_TRANSFERRED || reason == HolderPolicy.REASON_REPLACED -> EventAction.HOLDER
        else -> EventAction.DROP
    }

    /** Whether the event erases the phone (it is the vault's own word while a 410 stands). */
    fun erasesOnEvent(type: String, reason: String?, apiStanding: Boolean): Boolean =
        eventAction(type, reason, apiStanding) == EventAction.ERASE

    /**
     * Whether a session may be opened: never while "being deleted", except the mailbox of a phone that holds a 410 without
     * the vault's word ([mailboxOnly] and [apiStanding]).
     */
    fun allowsSession(deleting: Boolean, mailboxOnly: Boolean, apiStanding: Boolean): Boolean =
        !deleting || (mailboxOnly && apiStanding)

    /** A 410 without the vault's word closes an open (or paired) vault with a fail-secure lock; a locked or unset phone needs none. */
    fun locksOnApiDeleted(phase: AppPhase): Boolean =
        phase is AppPhase.Unlocked || (phase is AppPhase.Setup && phase.stage in PAIRED_STAGES)

    /** Whether a 410 without the vault's word starts the state: not twice, not on a phone with no vault, not before a vault is paired. */
    fun api410Applies(phase: AppPhase, deleting: Boolean): Boolean =
        !deleting && phase != AppPhase.SignedOut && (phase !is AppPhase.Setup || phase.stage in PAIRED_STAGES)

    /** Setup stages of a phone that is paired with its vault (it can open it), where a 410 also applies. */
    val PAIRED_STAGES = setOf(SetupStage.NEEDS_CREDENTIAL, SetupStage.FINISHING)

    /** A status that lists the vault again removes the `deleted_api` marker and returns to the unlock screen. */
    fun clearsApiMarker(check: DeletionCheck): Boolean = check == DeletionCheck.LISTED

    /** What one status answer showed: whether it lists a vault, and whether the service is paused. */
    data class StatusSeen(val vaultListed: Boolean, val servicePaused: Boolean)

    /**
     * One read of the status mapped to a [DeletionCheck] (the whole of what [VaultManager] does with the answer): no answer
     * or failure here is ever GONE. [onPaused] gets the answer's pause flag.
     */
    @Suppress("TooGenericExceptionCaught") // whatever the read throws concludes nothing
    suspend fun readCheck(onPaused: (Boolean) -> Unit = {}, read: suspend () -> StatusSeen): DeletionCheck = try {
        val a = read()
        onPaused(a.servicePaused)
        ofStatus(vaultListed = a.vaultListed, servicePaused = a.servicePaused)
    } catch (e: VaultFailure) {
        ofFailure(e)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        DeletionCheck.UNREACHABLE
    }

    /**
     * Whether a deletion that began at [sinceMs] is past its bound at [nowMs]. A clock that went back before [sinceMs] is
     * past it too (the wall clock cannot be trusted to count down), so "Check again" and "Remove" are never hidden by it.
     * A step back of up to [CLOCK_TOLERANCE_MS] (an NTP correction) does not count.
     */
    fun pastBound(sinceMs: Long, untilMs: Long, nowMs: Long): Boolean = nowMs >= untilMs || nowMs < sinceMs - CLOCK_TOLERANCE_MS

    private const val CLOCK_TOLERANCE_MS = 60_000L

    internal fun pastBound(m: DeletingMarker, nowMs: Long = System.currentTimeMillis()): Boolean = pastBound(m.sinceMs, m.untilMs, nowMs)
}

/**
 * Re-reads the status of a vault being deleted until it is gone: on the timer of [VaultDeletion] up to the marker's bound,
 * and once at every start, return to the foreground and "Check again" ([check]). At the bound a vault still listed is
 * "still finishing" ([onSlow]); after it nothing starts a new timer or a new bound. [read] reads the status once; [onGone]
 * moves the app to "no vault" (it may be called more than once and must act once). One read at a time: a [check] while
 * one runs waits for it.
 */
internal class DeletionWatch(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val read: suspend () -> DeletionCheck,
    private val onGone: suspend () -> Unit,
    private val onSlow: () -> Unit,
) {
    private var timer: Job? = null
    private var inFlight: Deferred<DeletionCheck>? = null

    /** The timer runs for [m] (from a start: [readNow] reads at once too). A watch that already runs is left as it is. */
    @Synchronized
    fun start(m: DeletingMarker, readNow: Boolean) {
        if (timer?.isActive == true) return
        timer = scope.launch { run(m, readNow) }
    }

    /** The vault is gone, or this phone was wiped: nothing more is read. */
    @Synchronized
    fun stop() {
        timer?.cancel()
        timer = null
    }

    /** One read now, or the one that runs. */
    suspend fun check(): DeletionCheck {
        val d = synchronized(this) {
            inFlight?.takeIf { it.isActive } ?: scope.async { readOnce() }.also { inFlight = it }
        }
        return d.await()
    }

    private suspend fun readOnce(): DeletionCheck {
        val r = read()
        if (r == DeletionCheck.GONE) onGone()
        return r
    }

    @Suppress("ReturnCount") // gone, past the bound, and at the bound
    private suspend fun run(m: DeletingMarker, readNow: Boolean) {
        if (readNow && check() == DeletionCheck.GONE) return
        // Past the bound (a start after it): "still finishing" at once, with no timer.
        if (VaultDeletion.pastBound(m.sinceMs, m.untilMs, nowMs())) return onSlow()
        while (true) {
            val now = nowMs()
            // Capped: a clock that moved back must not make the wait as long as the move.
            delay((minOf(VaultDeletion.nextReadMs(m.sinceMs, now), m.untilMs) - now).coerceIn(0L, VaultDeletion.THEN_EVERY_MS))
            if (check() == DeletionCheck.GONE) return
            // At the bound: still listed (or not reachable) is "still finishing", and nothing more runs on a timer.
            if (VaultDeletion.pastBound(m.sinceMs, m.untilMs, nowMs())) return onSlow()
        }
    }
}

/**
 * The phase as [VaultManager] publishes it, with "being deleted" above every other (ANDROID-PLAN 0.1.54): while
 * [deleting] holds, no other write is published (an unlock, a lock or a refresh that was on its way, an event), so that
 * nothing reopens a vault that can never run again. Only [endDeleting] (the vault gone, or a wipe) ends it.
 */
internal class PhaseHolder(initial: AppPhase) {
    private val flow = MutableStateFlow(initial)
    private var held: AppPhase.Deleting? = null

    var value: AppPhase
        get() = flow.value
        set(v) = synchronized(this) {
            if (held == null) publish(v)
        }

    /**
     * (ANDROID-PLAN 0.1.57 (1)) "This phone can't use your vault" goes when the vault stops being this phone's open vault:
     * a lock, a sign-out or wipe, a setup, a deletion. Only on a move into one of those phases: a refusal raised while
     * an unlock is still opening the vault (its owner check) stays, and shows once the app opens.
     */
    private fun publish(v: AppPhase) {
        val was = flow.value
        flow.value = v
        val ends = when (v) {
            AppPhase.Locked, AppPhase.SignedOut, is AppPhase.Setup, is AppPhase.Deleting -> true
            else -> false
        }
        if (ends && v != was) CredentialRequired.clear()
    }

    /** Whether "being deleted" holds. */
    val deleting: Boolean @Synchronized get() = held != null

    fun asStateFlow(): StateFlow<AppPhase> = flow.asStateFlow()

    suspend fun collect(c: FlowCollector<AppPhase>): Nothing = flow.collect(c)

    /** "Being deleted" from now on ([d]: waiting, or still finishing after the bound). */
    @Synchronized
    fun deleting(d: AppPhase.Deleting) {
        held = d
        publish(d)
    }

    /** The hold ends with [next] ("no vault"). */
    @Synchronized
    fun endDeleting(next: AppPhase) {
        held = null
        publish(next)
    }
}
