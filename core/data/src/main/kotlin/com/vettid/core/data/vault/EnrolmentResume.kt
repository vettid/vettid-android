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
 * - The slot is gone (`404`): less than [SLOT_YOUNG_MS] (14 minutes) after the request was built, it was never posted
 *   (a slot lives 15 minutes from its post, which comes after the build; the minute is a margin), so a new one may be
 *   posted. Older, nobody can tell: its `vault.enrolled` is awaited once. If none comes **and** the relay was read during
 *   that wait (so it is not waiting there), the request made no vault and a new one is posted; if the relay could not
 *   be read, the request is kept and the try fails ([Unreachable]), to be tried again.
 * - A new request needs the PIN: without one (a restart, where the PIN typed before is not in memory) the member is
 *   asked for it ([Result.NeedsPin]), and nothing that may already hold the member's earlier PIN is ever posted over.
 * - A run with a PIN resumes only a request this process posted with that same PIN ([Ops.postedHere]); any other one
 *   is [NotThisPin], and is resumed without a PIN.
 *
 * Every step is stored before the next one, so a process kill at any step resumes from the step it reached.
 */
internal class EnrolmentResume(private val ops: Ops, private val nowMs: () -> Long = System::currentTimeMillis) {
    @Suppress("TooManyFunctions") // one per step of the resume
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

        /**
         * (Review F1) Whether the request [requestId] was posted by this process with [pin]: only then may a run with a
         * PIN resume it. Any other PIN may not be the one the vault holds.
         */
        fun postedHere(requestId: String, pin: String): Boolean

        /**
         * (Review m1) What [postedHere] keeps of the PIN is no longer needed (the vault is pinned, or the request is
         * dropped): it goes, zeroed.
         */
        fun forgetPin()

        /** A mark of now, for [relayReadSince]. */
        fun relayMark(): Long

        /** Whether the device's mailbox was read on the relay (a collect that reached it) after [mark]. */
        fun relayReadSince(mark: Long): Boolean

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
     * (Review F1) A run with a PIN found a request outstanding that this process did not post with that PIN (a new screen
     * after Back, a restart): nothing is posted or accepted with the PIN; the request is resumed without one.
     */
    class NotThisPin : Exception("an enrollment posted with another PIN, or before a restart, is outstanding")

    /** The relay could not be read while an old request's `vault.enrolled` was awaited: the request is kept. */
    class Unreachable : Exception("the relay was not read")

    /**
     * Resumes the outstanding request, or posts a new one with [pin] when nothing can still make a vault. Throws
     * [Refused], [Waiting], or the transport's failure (the request then stays outstanding).
     */
    suspend fun run(pin: String?): Result = runOutstanding(pin).also { if (it == Result.Pinned) ops.forgetPin() }

    @Suppress("ReturnCount") // one return per state of the outstanding request
    private suspend fun runOutstanding(pin: String?): Result {
        if (ops.vaultPinned()) return Result.Pinned
        val q = ops.pending()
        if (pin != null && q != null && !ops.postedHere(q.requestId, pin)) throw NotThisPin()
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
                val mark = ops.relayMark()
                if (ops.awaitEnrolled() || ops.vaultPinned()) {
                    pinned = true
                    return null
                }
                // Only a read of the mailbox that found nothing shows that it made none; otherwise it is kept.
                if (!ops.relayReadSince(mark)) throw Unreachable()
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
        ops.forgetPin()
        throw Refused(code)
    }

    private suspend fun dropped(): PendingEnroll? {
        ops.drop()
        ops.forgetPin()
        return null
    }

    companion object {
        /**
         * A slot lives 15 minutes after it is made (MEMBER-API), at the post, which follows the build: a request built
         * less than 14 minutes ago whose slot is gone was never posted (the minute is a margin for the build and the
         * phone's clock).
         */
        const val SLOT_YOUNG_MS = 14L * 60 * 1000
        const val CODE_MANIFEST = "manifest"
    }
}
