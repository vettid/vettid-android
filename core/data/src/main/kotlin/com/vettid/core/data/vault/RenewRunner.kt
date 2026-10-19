package com.vettid.core.data.vault

import com.vettid.core.vault.SentRequest
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * A new phone secret (`vault.secret.renew`) and the decision of one whose answer was lost (VAULT-MESSAGING 0.45.0
 * §11.14.9 step 5): the renew is stored before it is sent and runs in the vault manager's scope; no PIN change is sent
 * while it may have applied ([settle]).
 */
internal class RenewRunner(private val scope: CoroutineScope, private val ops: Ops) {
    interface Ops {
        /** Stores [r] before it is sent; false: not written, and nothing may be sent. */
        fun store(r: SentRequest): Boolean

        /** The renew [innerId] applied, or did not: decided, its marker gone. */
        fun decide(innerId: String, applied: Boolean)

        /** The renew stored and not decided yet, or null. */
        fun unconfirmed(): UnconfirmedRenew?

        /** Waits (bounded) until no credential operation of this phone can still reach the vault. */
        suspend fun awaitSettled(): Boolean

        /** `credential.version` with its `ts`; throws without an answer. */
        suspend fun version(): ChangeResolver.Version

        /** Applied without its answer: the latest blob fetched, the UTKs topped up. */
        suspend fun fetch()
    }

    /** What [settle] found. */
    enum class State { NONE, UNSETTLED, UNDECIDABLE }

    /**
     * Sends a renew through [send], which calls `onSending` with the request just before it goes out and `onAnswered` when
     * the renew's own answer is `ok`. Only that answer decides "applied", and only a refusal of `vault.secret.renew`
     * itself decides "not applied". Anything else (no answer, or an error of a step around it: the blob fetched after a
     * `stale_credential`, a refused `credential.ack`) leaves it stored, to be decided by [settle] (Q1); a failure after
     * an `ok` answer does not undo it.
     */
    @Suppress("ThrowsCount")
    suspend fun send(
        send: suspend (onSending: suspend (SentRequest) -> Unit, onAnswered: suspend (String) -> Unit) -> Unit,
    ) {
        scope.async {
            var sent: SentRequest? = null
            var answered: String? = null
            try {
                send(
                    { req ->
                        if (!ops.store(req)) throw VaultFailure(FailureKind.LOCAL_STATE, CODE_NOT_STORED)
                        sent = req
                    },
                    { id -> answered = id },
                )
            } catch (e: VaultOpException) {
                val s = sent ?: throw e
                when {
                    answered != null -> Unit // decided below: applied
                    e.type == TYPE_RENEW -> {
                        ops.decide(s.innerId, applied = false)
                        throw e
                    }
                    else -> throw e // a step around the renew failed: left for [settle]
                }
            }
            answered?.let { ops.decide(it, applied = true) }
            Unit
        }.await()
    }

    /**
     * (N1) An unconfirmed renew decided by `credential.version` once nothing else is in flight: one above the version it
     * carried, applied (the blob fetched); the same in an answer stamped more than a minute after its `exp`, not applied.
     * Otherwise it stays: [State.UNSETTLED] while it may still apply, [State.UNDECIDABLE] when the version cannot tell
     * (a lock and unlock settles it, at no cost: the PIN did not change).
     */
    @Suppress("ReturnCount")
    suspend fun settle(): State {
        val r = ops.unconfirmed() ?: return State.NONE
        if (!ops.awaitSettled()) return State.UNSETTLED
        val v = ops.version()
        return when (ChangeDecision.of(r.sentVersion, r.expMs, v.exists, v.version, v.alarm, v.ts)) {
            ChangeDecision.Verdict.APPLIED -> {
                ops.fetch()
                ops.decide(r.innerId, applied = true)
                State.NONE
            }
            ChangeDecision.Verdict.NOT_APPLIED -> {
                ops.decide(r.innerId, applied = false)
                State.NONE
            }
            ChangeDecision.Verdict.UNDECIDED -> State.UNSETTLED
            ChangeDecision.Verdict.ENDED -> State.UNDECIDABLE
        }
    }

    companion object {
        const val TYPE_RENEW = "vault.secret.renew"
        const val CODE_NOT_STORED = "renew_not_stored"
    }
}
