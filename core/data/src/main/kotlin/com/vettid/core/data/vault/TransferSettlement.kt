package com.vettid.core.data.vault

import com.vettid.core.relay.RelayException
import com.vettid.core.vault.TransferInState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/**
 * (A6 audit N1; VAULT-MESSAGING 0.85.2 §11.14.10 rule 5, ANDROID-PLAN 0.1.121 (vettid.org #537))
 * What the settlement of a transfer to this phone needs of the device, so that the step can be tested without the manager.
 * [VaultManager] gives it over the real device.
 */
internal interface SettleDevice {
    val transferInPending: TransferInState?
    val deviceId: String?

    /** Whether the device's own collection is running (it long-polls already). */
    val collecting: Boolean

    /** How many times the collection's in-flight request was cut (stopped or restarted with a request out). */
    val collectorCuts: Long

    /** The relay's advertised `visibility_timeout_seconds`; may throw. */
    suspend fun relayVisibilitySeconds(): Long?

    suspend fun awaitCollectorRound()

    /** One request of ours (a long poll of [wait], `wait=0` while the collection runs); handles and acks what it brings. */
    suspend fun collectOnce(wait: Duration)

    /** Drops the transfer that started at [onlyStartedAt], if it is still the pending one and not paired. */
    suspend fun abandonTransfer(onlyStartedAt: Instant)

    /** Ends with the rejection (as an exception) still in the device's inbox, or with a timeout. */
    suspend fun awaitTransfer(timeout: Duration)
}

/** How a settlement ended. A rejection is thrown by [SettleDevice.awaitTransfer] and is not an outcome. */
internal sealed interface Settled {
    /** Paired: the transfer, to be completed by the take-over. */
    data class Paired(val transfer: TransferInState) : Settled

    /** Dropped, or no longer pending (abandoned by the member). */
    object Dropped : Settled

    /** The session went away (a wipe, a forget, a sign-out, a dead mailbox) before the window ended: nothing was decided. */
    object SessionGone : Settled
}

private const val RELAY_LIMITS_BOUND_MS = 5_000L
private const val REJECTION_LOOK_MS = 50L

/** The relay's advertised visibility timeout, within a short bound; null when it cannot be read (the default applies). */
private suspend fun readVisibility(d: SettleDevice): Long? = try {
    withTimeoutOrNull(RELAY_LIMITS_BOUND_MS) { d.relayVisibilitySeconds() }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/**
 * Ended by a rejection (or an abandon): a rejection is still in the device's inbox and ends the wait as REJECTED, as it
 * would have before the deadline; otherwise a short timeout.
 */
private suspend fun lookForRejection(d: SettleDevice) {
    try {
        d.awaitTransfer(Duration.ofMillis(REJECTION_LOOK_MS))
    } catch (_: TimeoutCancellationException) {
        // none: abandoned
    }
}

/**
 * The settlement of a transfer to this phone past its deadline (see [TransferInResume.atDeadline]): the window's length
 * from the relay's advertised value (default 60 s), the request wiring (`wait=0` while the collection runs, a long poll
 * otherwise, the collection's round awaited outside the request's bound), the drop of this transfer only, and a
 * rejection that ended the window ending as the exception [SettleDevice.awaitTransfer] throws.
 */
internal suspend fun settleTransfer(
    d: SettleDevice,
    sessionIsCurrent: () -> Boolean,
    serial: TransferInResume.Serial,
    now: () -> Instant = Instant::now,
    timing: (visibilitySeconds: Long?) -> TransferInResume.Settle = {
        TransferInResume.Settle(windowMs = TransferInResume.windowMs(it), maxExtraMs = TransferInResume.visibilityMs(it))
    },
): Settled {
    val visibility = readVisibility(d)
    val settle = timing(visibility)
    val run = SettleRun(d, sessionIsCurrent, now)
    val paired = serial.run(
        due = run::due,
        paired = { d.transferInPending?.paired == true && d.deviceId != null },
        collect = run::collect,
        drop = run::drop,
        alive = run::alive,
        settle = settle,
        awaitRound = { d.awaitCollectorRound() },
        cuts = { d.collectorCuts },
    )
    val ended = !paired && !run.dropped && (run.mailboxGone || !sessionIsCurrent())
    // A mailbox that is gone leaves nothing to wait for: the dead handshake goes, so that a new code or a recovery is possible.
    if (run.mailboxGone && !paired) run.started?.let { d.abandonTransfer(onlyStartedAt = it) }
    val undecided = !paired && !run.dropped && !run.mailboxGone
    if (undecided && sessionIsCurrent()) lookForRejection(d)
    return outcome(d, ended)
}

/** The state of one settlement: the transfer it settles (read inside the lock), whether it dropped, a gone mailbox. */
private class SettleRun(
    private val d: SettleDevice,
    private val sessionIsCurrent: () -> Boolean,
    private val now: () -> Instant,
) {
    var started: Instant? = null
        private set
    var dropped = false
        private set

    /** `404 mailbox_unknown` (the mailbox is gone): the session ended, nothing can come any more. */
    var mailboxGone = false
        private set

    fun due(): Boolean {
        val t = d.transferInPending
        started = t?.startedAt
        return TransferInResume.deadlineDue(t, d.deviceId, now())
    }

    /**
     * This transfer on this session only: a rejection (the handler dropped the handshake), a wipe, a forget or a gone
     * mailbox ends the window at once. The member's abandon and a new code are not offered during the window.
     */
    fun alive(): Boolean {
        if (started == null || mailboxGone) return false
        return sessionIsCurrent() && d.transferInPending?.startedAt == started
    }

    suspend fun collect(waitMs: Long) {
        try {
            d.collectOnce(if (d.collecting) Duration.ZERO else Duration.ofMillis(waitMs))
        } catch (e: RelayException) {
            if (e.code == RelayException.MAILBOX_UNKNOWN) mailboxGone = true
            throw e
        }
    }

    suspend fun drop() {
        dropped = true
        started?.let { d.abandonTransfer(onlyStartedAt = it) }
    }
}

/** Paired, or the session went away before a decision ([ended]), or dropped. */
private fun outcome(d: SettleDevice, ended: Boolean): Settled {
    val pending = d.transferInPending
    return when {
        pending?.paired == true && d.deviceId != null -> Settled.Paired(pending)
        ended -> Settled.SessionGone
        else -> Settled.Dropped
    }
}

/**
 * One run at a time of a job that must outlive its callers: callers [run] it with a key (the transfer's start); a second
 * caller with the same key joins the one running; the job runs in [scope], so a caller that is cancelled (the screen
 * left) stops waiting and nothing else. Its failure is handed to whoever waits, and to nobody when nobody does.
 */
internal class SingleFlight<T>(private val scope: CoroutineScope) {
    private val lock = Mutex()
    private var key: Any? = null
    private var job: Deferred<Result<T>>? = null

    /** Whether a run is going on now. */
    val active: Boolean get() = job?.isActive == true

    @Suppress("TooGenericExceptionCaught") // handed to the waiter
    suspend fun run(k: Any?, block: suspend () -> T): T {
        val running = lock.withLock {
            val current = job
            if (current != null && current.isActive && key == k) {
                current
            } else {
                scope.async {
                    try {
                        Result.success(block())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }.also {
                    job = it
                    key = k
                }
            }
        }
        return running.await().getOrThrow()
    }
}
