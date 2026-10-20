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

/** What one read of `GET /api/vault/status` says of a vault being deleted. */
enum class DeletionCheck {
    /** `{vault: null}`, or `401 unauthorized` to the app key: the row, and with it the app key, is gone. */
    GONE,

    /** The vault is still listed, whatever its `state`. */
    LISTED,

    /** The member API could not be reached (or answered something else): nothing is concluded. */
    UNREACHABLE,
}

/**
 * The bound and the re-reads of "being deleted" (VAULT-MESSAGING §12.5: the member API reads the vault as gone only after
 * step 5, MEMBER-API "Vault deletion").
 */
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
        else -> DeletionCheck.GONE
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

    /** What a failed read says: `401 unauthorized` to the app key is the row removed; anything else concludes nothing. */
    fun ofFailure(kind: FailureKind): DeletionCheck =
        if (kind == FailureKind.UNAUTHORIZED) DeletionCheck.GONE else DeletionCheck.UNREACHABLE
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
        if (nowMs() >= m.untilMs) return onSlow()
        while (true) {
            val now = nowMs()
            delay(minOf(VaultDeletion.nextReadMs(m.sinceMs, now), m.untilMs) - now)
            if (check() == DeletionCheck.GONE) return
            // At the bound: still listed (or not reachable) is "still finishing", and nothing more runs on a timer.
            if (nowMs() >= m.untilMs) return onSlow()
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
            if (held == null) flow.value = v
        }

    /** Whether "being deleted" holds. */
    val deleting: Boolean @Synchronized get() = held != null

    fun asStateFlow(): StateFlow<AppPhase> = flow.asStateFlow()

    suspend fun collect(c: FlowCollector<AppPhase>): Nothing = flow.collect(c)

    /** "Being deleted" from now on ([d]: waiting, or still finishing after the bound). */
    @Synchronized
    fun deleting(d: AppPhase.Deleting) {
        held = d
        flow.value = d
    }

    /** The hold ends with [next] ("no vault"). */
    @Synchronized
    fun endDeleting(next: AppPhase) {
        held = null
        flow.value = next
    }
}
