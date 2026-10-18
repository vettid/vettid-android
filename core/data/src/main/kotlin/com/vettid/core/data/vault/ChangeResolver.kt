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

    fun of(m: UnresolvedChange, exists: Boolean, version: Long?, alarm: Boolean, ts: Instant): Verdict = when {
        !exists || version == null || alarm -> Verdict.ENDED
        version == m.sentVersion + 1 -> Verdict.APPLIED
        version == m.sentVersion && ts.isAfter(Instant.ofEpochMilli(m.expMs).plus(MARGIN)) -> Verdict.NOT_APPLIED
        version == m.sentVersion -> Verdict.UNDECIDED
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
    }

    private val mutex = Mutex()
    private val state = MutableStateFlow<ChangeCheck?>(ops.marker()?.let { ChangeCheck.Checking(it.kind) })

    /** Where the decision stands; a decided state stays until [acknowledge]. */
    val check: StateFlow<ChangeCheck?> = state.asStateFlow()

    /** Says [c] (for the change's own answer, decided by the caller). */
    fun publish(c: ChangeCheck?) {
        state.value = c
    }

    /** The member has read a decided state. */
    fun acknowledge() {
        state.value = state.value?.takeIf { it.open }
    }

    /**
     * One round of §11.14.9 step 3. Null: nothing is unresolved. Never throws (but cancellation): a failure to reach the
     * vault is [ChangeCheck.Unreachable].
     */
    suspend fun decide(): ChangeCheck? = mutex.withLock {
        val m = ops.marker()
        if (m == null) {
            if (state.value?.open == true) state.value = null
            return@withLock null
        }
        if (state.value?.open != true) state.value = ChangeCheck.Checking(m.kind)
        round(m).also { state.value = it }
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
                // The candidate is left to the next unlock (§11.14.1 rule 4); the answer follows its own path.
                ops.clear(m)
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
     * §11.14.9 step 2: before a change, an owner check or another credential operation, an unresolved change is decided
     * first; while it cannot be, the operation is refused ([FailureKind.CHANGE_UNRESOLVED]) and nothing is sent.
     */
    suspend fun gate() {
        if (ops.marker() == null) return
        val c = decide()
        if (c?.open == true) {
            kick()
            throw VaultFailure(FailureKind.CHANGE_UNRESOLVED, CODE_UNRESOLVED)
        }
    }

    companion object {
        const val CODE_UNRESOLVED = "change_unresolved"
        const val FIRST_PAUSE_MS = 30_000L
        const val MAX_PAUSE_MS = 300_000L
    }
}
