package com.vettid.core.data.vault

import com.vettid.core.altchan.EnrollAnswer
import com.vettid.core.altchan.PendingEnroll

/**
 * (A6 audit N3) Enrollment that resumes instead of posting twice (VAULT-MESSAGING §11.3, MEMBER-API "response slots").
 *
 * The enrollment request (and its nonce) is stored before it is posted ([Ops.pending]). A retry or a restart then
 * looks at what that request did before anything new is posted: a second `vault.enroll` for a member whose first one
 * made a provisional vault is answered `vault_exists` for 24 hours (§11.3), which the app used to show as "Another phone
 * holds your vault". The rules:
 *
 * - `vault.enrolled` already applied (the vault is pinned): nothing to post; on to the handshake.
 * - The request's result was `ok`: only its `vault.enrolled` is awaited (the relay keeps it for 14 days).
 * - Otherwise the request's slot is read again: `ok` as above; a refusal ends it (and is the member's answer);
 *   `expired`, `etk_unknown` or random bytes mean it made nothing, so a new one may be posted.
 * - The slot is gone (`404`): less than [SLOT_YOUNG_MS] after the request was built, it was never posted (a slot lives
 *   15 minutes), so a new one may be posted. Older, nobody can tell: its `vault.enrolled` is awaited once, and if none
 *   comes, a new request is posted.
 * - A new request needs the PIN: without one (a restart, where the PIN typed before is not in memory) the member is
 *   asked for it ([Result.NeedsPin]), and nothing that may already hold the member's earlier PIN is ever posted over.
 *
 * Every step is stored before the next one, so a process kill at any step resumes from the step it reached.
 */
internal class EnrolmentResume(private val ops: Ops, private val nowMs: () -> Long = System::currentTimeMillis) {
    interface Ops {
        /** `vault.enrolled` has been applied: the vault is pinned on this phone. */
        fun vaultPinned(): Boolean

        /** The enrollment request outstanding, or null. */
        fun pending(): PendingEnroll?

        /** What the slot of [requestId] says now (transport failures are thrown). */
        suspend fun answer(requestId: String): EnrollAnswer

        /** The pending request's result was `ok`. */
        suspend fun markAnswered()

        /** The pending request made no vault: it goes. */
        suspend fun drop()

        /** Waits for `vault.enrolled`; false when it did not come in time. */
        suspend fun awaitEnrolled(): Boolean

        /** Builds, stores and posts a new request with [pin], and returns its opened result. */
        suspend fun post(pin: String): Posted
    }

    /** The opened result of a request posted by [Ops.post]. */
    data class Posted(val ok: Boolean, val code: String?)

    sealed interface Result {
        /** The vault is pinned: the first handshake follows. */
        data object Pinned : Result

        /** Nothing is outstanding and no PIN was given: the member chooses the PIN (a new request). */
        data object NeedsPin : Result
    }

    /** Thrown for a refusal of the enclave ([code], from `vault.enroll.result`). */
    class Refused(val code: String?) : Exception("enrollment refused: $code")

    /** `ok`, but no `vault.enrolled` in time: the request stays outstanding and is awaited again at the next try. */
    class Waiting : Exception("vault.enrolled not received yet")

    /**
     * Resumes the outstanding request, or posts a new one with [pin] when nothing can still make a vault. Throws
     * [Refused], [Waiting], or the transport's failure (the request then stays outstanding).
     */
    @Suppress("ReturnCount") // one return per state of the outstanding request
    suspend fun run(pin: String?): Result {
        if (ops.vaultPinned()) return Result.Pinned
        val q = ops.pending()
        val p = if (q == null || q.answered) q else settle(q)
        if (pinned) return Result.Pinned
        if (p != null) return enrolled()
        if (ops.vaultPinned()) return Result.Pinned
        pin ?: return Result.NeedsPin
        val r = ops.post(pin)
        if (!r.ok) refused(r.code)
        ops.markAnswered()
        return enrolled()
    }

    /** [settle] found the vault pinned (the `vault.enrolled` of a request whose slot was gone). */
    private var pinned = false

    /**
     * Reads the slot of [p], whose answer was never seen: the request answered `ok` ([p] answered, to be awaited), null
     * when it made nothing (dropped) or its vault is already pinned ([pinned] set), or a refusal ([Refused]).
     */
    @Suppress("ReturnCount") // one return per answer of the slot
    private suspend fun settle(p: PendingEnroll): PendingEnroll? {
        when (val a = ops.answer(p.requestId)) {
            is EnrollAnswer.Answered -> when {
                a.ok -> {
                    ops.markAnswered()
                    return p.copy(answered = true)
                }
                // A manifest the enclave did not accept: posted again with a fresh one, as a first try is.
                a.code == CODE_MANIFEST -> return dropped()
                else -> refused(a.code)
            }
            EnrollAnswer.NotProcessed -> return dropped()
            EnrollAnswer.Gone -> {
                if (nowMs() - p.postedMs in 0 until SLOT_YOUNG_MS) return dropped()
                // Nobody can tell whether it made a vault: if it did, its vault.enrolled waits on the relay.
                if (ops.awaitEnrolled() || ops.vaultPinned()) {
                    pinned = true
                    return null
                }
                return dropped()
            }
        }
    }

    /** The request was answered `ok`: its vault.enrolled is awaited ([Waiting] when it does not come in time). */
    private suspend fun enrolled(): Result {
        if (ops.awaitEnrolled() || ops.vaultPinned()) return Result.Pinned
        throw Waiting()
    }

    private suspend fun refused(code: String?): Nothing {
        ops.drop()
        throw Refused(code)
    }

    private suspend fun dropped(): PendingEnroll? {
        ops.drop()
        return null
    }

    companion object {
        /** A slot lives 15 minutes after it is made (MEMBER-API); a request younger than this whose slot is gone was never posted. */
        const val SLOT_YOUNG_MS = 14L * 60 * 1000
        const val CODE_MANIFEST = "manifest"
    }
}
