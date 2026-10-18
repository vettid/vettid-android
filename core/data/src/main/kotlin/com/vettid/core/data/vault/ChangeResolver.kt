package com.vettid.core.data.vault

import com.vettid.core.vault.ChangeTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

// VAULT-MESSAGING 0.44.0 §11.14.9 "A lost answer" (ANDROID-PLAN 0.1.45): a PIN or password change whose answer was lost
// is decided, not repeated.

/**
 * The unresolved change stored in `account.bin` before a `pin.change` or `credential.password.change` is sent (§11.14.9
 * step 1): the type, the inner id, the blob version the request carries and its `exp` (epoch ms, the vault's clock as
 * last seen). For a PIN change [fromEpoch] is the epoch of the phone's current record when it was sent; its candidate
 * is the next epoch. No PIN, password or secret.
 */
@Serializable
internal data class UnresolvedChange(
    val type: String,
    val innerId: String,
    val sentVersion: Long,
    val expMs: Long,
    val fromEpoch: Long = 0,
) {
    val kind: ChangeKind get() = if (type == ChangeTypes.PIN_CHANGE) ChangeKind.PIN else ChangeKind.PASSWORD

    override fun toString(): String = "UnresolvedChange($type)"
}

/**
 * (VAULT-MESSAGING 0.45.0 §11.14.9 step 5, N1) A `vault.secret.renew` stored before it was sent: its inner id, the blob
 * version it carried, its `exp` (epoch ms) and the epoch it was sent from (its candidate is the next). No secret.
 */
@Serializable
internal data class UnconfirmedRenew(val innerId: String, val sentVersion: Long, val expMs: Long, val fromEpoch: Long)

/** (N5) A decision the member has not seen yet: [kind] and [verdict] (the [ChangeCheck] class), [failure] for a refusal. */
@Serializable
internal data class SavedDecision(val kind: String, val verdict: String, val failure: String? = null) {
    fun toCheck(): ChangeCheck? {
        val k = runCatching { ChangeKind.valueOf(kind) }.getOrNull() ?: return null
        return when (verdict) {
            APPLIED -> ChangeCheck.Applied(k)
            NOT_APPLIED -> ChangeCheck.NotApplied(k)
            ENDED -> ChangeCheck.Ended(k)
            REFUSED -> ChangeCheck.Refused(k, failure?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() } ?: FailureKind.OTHER)
            else -> null
        }
    }

    companion object {
        private const val APPLIED = "applied"
        private const val NOT_APPLIED = "not_applied"
        private const val ENDED = "ended"
        private const val REFUSED = "refused"

        /** The decided [c] to keep; null for none or an undecided state. */
        fun of(c: ChangeCheck?): SavedDecision? = when (c) {
            is ChangeCheck.Applied -> SavedDecision(c.kind.name, APPLIED)
            is ChangeCheck.NotApplied -> SavedDecision(c.kind.name, NOT_APPLIED)
            is ChangeCheck.Ended -> SavedDecision(c.kind.name, ENDED)
            is ChangeCheck.Refused -> SavedDecision(c.kind.name, REFUSED, c.failure.name)
            else -> null
        }
    }
}

/** §11.14.9 step 3: what an answer to `credential.version` says of an unresolved change. */
internal object ChangeDecision {
    enum class Verdict {
        /** One above the version sent: the change applied (credential operations come only from this holder). */
        APPLIED,

        /** The version sent, in an answer stamped more than [MARGIN] after `exp`: it did not apply and never will. */
        NOT_APPLIED,

        /** The version sent, in an earlier answer: a copy may still reach the vault. */
        UNDECIDED,

        /** No credential, another version, or a clone alarm: the check ends without a decision. */
        ENDED,
    }

    /** How long after the request's `exp` an unchanged version decides "not applied". */
    val MARGIN: Duration = Duration.ofMinutes(1)

    fun of(m: UnresolvedChange, exists: Boolean, version: Long?, alarm: Boolean, ts: Instant): Verdict =
        of(m.sentVersion, m.expMs, exists, version, alarm, ts)

    /** The same for any request that carried blob version [sentVersion] and expired at [expMs] (a renew, N1). */
    @Suppress("LongParameterList")
    fun of(sentVersion: Long, expMs: Long, exists: Boolean, version: Long?, alarm: Boolean, ts: Instant): Verdict = when {
        !exists || version == null || alarm -> Verdict.ENDED
        version == sentVersion + 1 -> Verdict.APPLIED
        version == sentVersion && ts.isAfter(Instant.ofEpochMilli(expMs).plus(MARGIN)) -> Verdict.NOT_APPLIED
        version == sentVersion -> Verdict.UNDECIDED
        else -> Verdict.ENDED
    }
}

/**
 * Decides an unresolved PIN or password change (§11.14.9 steps 2–4): at once, at every reconnection and start
 * ([kick]), and before any other credential operation ([gate]). Each round resends the kept request (the same inner id
 * and body) while its `exp` has not passed, and otherwise asks `credential.version`; an undecided round is tried again
 * after a pause of 30 s, doubling up to 5 minutes. Nothing here sends a PIN or a password that was not already in the
 * request as it was first sent.
 */
internal class ChangeResolver(
    private val scope: CoroutineScope,
    private val ops: Ops,
    private val firstPauseMs: Long = FIRST_PAUSE_MS,
    private val maxPauseMs: Long = MAX_PAUSE_MS,
) {
    /** An answer to the request itself (to the first send or a resend). */
    sealed interface Answer {
        /** `epoch_advanced` or the new blob: the change applied. */
        data object Ok : Answer

        /** A refusal ([failure]); [stale]: `stale_credential`, answered before any secret was tested. */
        data class Refused(val failure: FailureKind, val stale: Boolean = false) : Answer
    }

    /** `credential.version`'s answer and its `ts` (the vault's clock). */
    data class Version(val exists: Boolean, val version: Long?, val alarm: Boolean, val ts: Instant)

    interface Ops {
        /** The unresolved change stored on the phone, or null. */
        fun marker(): UnresolvedChange?

        /**
         * The request kept in memory for [m], sent again once while its `exp` has not passed (or the answer that came
         * meanwhile), its answer settled (the blob kept, or the refusal); null: not held (a restart), `exp` passed, or no
         * answer.
         */
        suspend fun resend(m: UnresolvedChange): Answer?

        /** `credential.version`; throws without an answer. */
        suspend fun version(): Version

        /** The change applied: the latest blob fetched and kept (`credential.get`), the UTKs topped up. */
        suspend fun fetch()

        /** The PIN change's candidate made current and [m] cleared, as one write; false: the write failed. */
        fun confirm(m: UnresolvedChange): Boolean

        /** [m] cleared, the kept request forgotten. */
        fun clear(m: UnresolvedChange)

        /**
         * (0.45.0 step 3) The check of [m] ended without a decision: [m] cleared and, after a PIN change, the PIN marked
         * uncertain.
         */
        fun end(m: UnresolvedChange) = clear(m)

        /** (0.45.0 step 3) The PIN is uncertain after a check that ended without a decision. */
        fun pinUncertain(): Boolean = false

        /** (N5) The decision not yet seen, kept on the phone across a process death. */
        fun savedDecision(): ChangeCheck? = null

        /** (N5) Keeps [c] (a decided state) on the phone until it is seen; null once seen. */
        fun saveDecision(c: ChangeCheck?) = Unit
    }

    private val mutex = Mutex()

    private val state = MutableStateFlow(ops.marker()?.let { ChangeCheck.Checking(it.kind) } ?: ops.savedDecision())

    /** Every change of [state]: a decided state is kept on the phone until it is seen (N5), an open one replaces it. */
    private fun set(c: ChangeCheck?) {
        state.value = c
        ops.saveDecision(c?.takeIf { !it.open })
    }

    /** Where the decision stands; a decided state stays until [acknowledge]. */
    val check: StateFlow<ChangeCheck?> = state.asStateFlow()

    /** Says [c] (for the change's own answer, decided by the caller). */
    fun publish(c: ChangeCheck?) {
        set(c)
    }

    /** The member has read a decided state. */
    fun acknowledge() {
        set(state.value?.takeIf { it.open })
    }

    /**
     * One round of §11.14.9 step 3. Null: nothing is unresolved. Never throws (but cancellation): a failure to reach the
     * vault is [ChangeCheck.Unreachable].
     */
    suspend fun decide(): ChangeCheck? = mutex.withLock {
        val m = ops.marker()
        if (m == null) {
            if (state.value?.open == true) set(null)
            return@withLock null
        }
        if (state.value?.open != true) set(ChangeCheck.Checking(m.kind))
        round(m).also { set(it) }
    }

    @Suppress("ReturnCount")
    private suspend fun round(m: UnresolvedChange): ChangeCheck {
        attempt { ops.resend(m) }?.let { return settled(m, it) }
        val v = attempt { ops.version() } ?: return ChangeCheck.Unreachable(m.kind)
        return when (ChangeDecision.of(m, v.exists, v.version, v.alarm, v.ts)) {
            ChangeDecision.Verdict.APPLIED -> {
                // Decided, but without the blob nothing is written yet: the next round decides again.
                if (attempt { ops.fetch() } == null) return ChangeCheck.Unreachable(m.kind)
                ops.confirm(m) // shown as success even when the write fails: the next round or unlock confirms it
                ChangeCheck.Applied(m.kind)
            }
            ChangeDecision.Verdict.NOT_APPLIED -> {
                ops.clear(m)
                ChangeCheck.NotApplied(m.kind)
            }
            ChangeDecision.Verdict.UNDECIDED -> ChangeCheck.Checking(m.kind)
            ChangeDecision.Verdict.ENDED -> {
                // The candidate is left to the next unlock (§11.14.1 rule 4); the answer follows its own path. After a PIN
                // change the PIN is uncertain until a two-step unlock settles it (0.45.0).
                ops.end(m)
                ChangeCheck.Ended(m.kind)
            }
        }
    }

    /** [block]'s result, or null when it failed (no answer, the vault unreachable); cancellation goes through. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** §11.14.9 step 4: an answer to the request decides the change by itself. */
    private fun settled(m: UnresolvedChange, a: Answer): ChangeCheck = when (a) {
        Answer.Ok -> {
            ops.confirm(m)
            ChangeCheck.Applied(m.kind)
        }
        is Answer.Refused -> {
            ops.clear(m)
            // stale_credential: refused before any secret was tested, so this request did not apply.
            if (a.stale) ChangeCheck.NotApplied(m.kind) else ChangeCheck.Refused(m.kind, a.failure)
        }
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    /** Decides at once, and again after each pause while undecided (a reconnection, a start, a new change). */
    fun kick() {
        wake.trySend(Unit)
        synchronized(this) {
            if (loop?.isActive != true) loop = scope.launch { run() }
        }
    }

    private suspend fun run() {
        var pause = firstPauseMs
        while (true) {
            wake.tryReceive()
            val c = decide() ?: return
            if (!c.open) return
            val woken = withTimeoutOrNull(pause) { wake.receive() } != null
            pause = if (woken) firstPauseMs else minOf(pause * 2, maxPauseMs)
        }
    }

    /**
     * §11.14.9 step 2 (0.45.0 "The gate"): while a change is unresolved, a change, an owner check or another credential
     * operation is refused **at once**, with nothing sent and without waiting for the decision (the check is started):
     * [FailureKind.CHANGE_UNRESOLVED] while the vault cannot be reached, [FailureKind.CHANGE_UNDECIDED] (PIN) or
     * [FailureKind.CHANGE_UNDECIDED_PASSWORD] while it can. While the PIN is uncertain (step 3) an operation that
     * [carriesPin] is refused too ([FailureKind.PIN_UNCERTAIN]).
     */
    suspend fun gate(carriesPin: Boolean) {
        val m = ops.marker()
        if (m == null) {
            if (carriesPin && ops.pinUncertain()) throw VaultFailure(FailureKind.PIN_UNCERTAIN, CODE_PIN_UNCERTAIN)
            return
        }
        kick()
        throw when {
            state.value is ChangeCheck.Unreachable -> VaultFailure(FailureKind.CHANGE_UNRESOLVED, CODE_UNRESOLVED)
            m.kind == ChangeKind.PIN -> VaultFailure(FailureKind.CHANGE_UNDECIDED, CODE_UNDECIDED)
            else -> VaultFailure(FailureKind.CHANGE_UNDECIDED_PASSWORD, CODE_UNDECIDED)
        }
    }

    companion object {
        const val CODE_UNRESOLVED = "change_unresolved"
        const val CODE_UNDECIDED = "change_undecided"
        const val CODE_PIN_UNCERTAIN = "pin_uncertain"
        const val FIRST_PAUSE_MS = 30_000L
        const val MAX_PAUSE_MS = 300_000L
    }
}
