package com.vettid.core.data.vault

import kotlinx.serialization.Serializable

/**
 * (ANDROID-PLAN 0.1.78 §4 "Recovery and unlock from release R", "A phone that could not tell"; VAULT-MESSAGING 0.59.0
 * §11.14.6, owner decisions of 2026-10-24, option A.) An open the phone sent was accepted by the member API and its answer
 * was never readable; the recovery it registered for has ended. The phone cannot tell whether that open recovered the
 * vault, so it keeps everything and may use the same key K for a new recovery with a new device identity.
 *
 * This file holds the rules and the three sequences as plain code over ports, so that each outcome is tested without a
 * device: [Rules] (when, in what order, what is kept), [NewRecovery] (the claim, the earlier open's answer, the new
 * register), [LostOk] (the lost-ok unlock, newest identity first) and [Pairing] (the new identity's handshake and the
 * fall back to the earlier one). [VaultManager] supplies the ports.
 */

/** The two device identities this phone can hold at once: the one that sent the unanswered open, and the one for the new recovery. */
enum class Identity { EARLIER, NEW }

/**
 * What the phone keeps while it is undecided (stored in the account record): which identity slot is which, each
 * identity's `recovery_id` (its handshake context), the earlier open's `request_id`, and the clocks of the rules. Deleted
 * only after the vault answered `ok` and the pairing completed ([Rules.mayDeleteEarlier]).
 */
@Serializable
internal data class ReuseRecord(
    /** The identity slot ([IdentitySlots]) of the identity that sent the unanswered open. */
    val earlierSlot: Int,
    /** That identity's `recovery_id`: the `ctx` of its handshake. */
    val earlierRecoveryId: String,
    /** The slot of the new identity, once made. */
    val newSlot: Int? = null,
    /** The `recovery_id` the new identity registered for. */
    val newRecoveryId: String? = null,
    /** The vault accepted the new identity's register (`vault.recovery.result` ok). */
    val newRegistered: Boolean = false,
    /** First read of status with K that answered `401` / `410 recovery_ended` (both clocks). */
    val endedSeen: Rules.Clock? = null,
    /** First read that answered `200` (both clocks): the same pair is needed before the phone acts on a `200`. */
    val listedSeen: Rules.Clock? = null,
    /** Two such reads at least 5 minutes apart: the member may be told to start a new recovery. */
    val startNew: Boolean = false,
    /** A `200` on status, or a `409 key_in_use` at a claim: the next step is the lost-ok unlock. */
    val lostOkDue: Boolean = false,
    /** The recovery_id of the last register posted (even if undecided): a repeat for the same code is "maybe registered". */
    val registerTried: String? = null,
    /** A `409 key_in_use` was seen: only then is the lost-ok unlock "the vault took this phone" (a `200` alone is not that). */
    val keyInUse: Boolean = false,
    /** Since when the lost-ok unlock is refused (epoch ms); after a day the contact text. */
    val refusedSinceMs: Long? = null,
)

/** An open the member API took ([rid]), by the identity in [slot] for [recoveryId]; [polled]: its slot was looked at. */
@Serializable
internal data class SentOpen(val rid: String, val recoveryId: String, val slot: Int, val polled: Boolean = false)

/**
 * Every open sent whose answer is not a decisive one. It is sticky and fails secure: an open leaves only on a sealed
 * refusal that proves it stored no step 1 ([REFUSALS]); `wrong` and `expired` prove that only when no earlier open
 * stands (a later `wrong` is the earlier open's recovery resumed). Every other code (`retry`, `stored_mismatch`,
 * `missing`, one this build does not know) leaves the open unanswered: the vault can store step 1 and still answer
 * `retry`. An earlier open never leaves because of a later one's answer. Everything goes together, after `ok`, the
 * pairing and the credential ([completes]).
 */
internal object Ledger {
    /** Sealed refusals that always prove the open stored no step 1: before the spend, or the vault's wait verdicts. */
    val REFUSALS = setOf(
        "busy", "wrong_again", "attempt_lost", "closed", "counter_unavailable", "wait", "quiet",
        // Emitted by the vault after the resume branch and before beginReplace (recovery_open.go): no step 1 stored.
        "exhausted", "not_armed",
        // Decided at step 1 (the assertion is not the registered attestation's key), before anything is written
        // (VAULT-MESSAGING 0.63.1 §11.14.6, owner decision 2026-10-26).
        "attestation",
    )

    /** Prove it only when no earlier open stands. */
    val ALONE_ONLY = setOf("wrong", "expired")

    /** A record of an earlier build that says "unanswered" but has no list keeps that: one entry nothing can answer. */
    fun seed(flag: Boolean, ledger: List<SentOpen>): List<SentOpen> =
        if (flag && ledger.isEmpty()) listOf(SentOpen(LEGACY, "", 0, polled = true)) else ledger

    /** The open sent ([rid] null: not known, a synthetic entry nothing can answer). */
    fun afterSent(flag: Boolean, ledger: List<SentOpen>, rid: String?, recoveryId: String, slot: Int): List<SentOpen> {
        val l = seed(flag, ledger)
        val id = rid ?: "$UNKNOWN${l.size}"
        return if (l.any { it.rid == id }) l else l + SentOpen(id, recoveryId, slot, polled = rid == null)
    }

    /** The sealed answer [code] ([ok]) to the open [rid]: the ledger after it. */
    fun afterAnswer(flag: Boolean, ledger: List<SentOpen>, rid: String?, code: String?, ok: Boolean): List<SentOpen> {
        val l = seed(flag, ledger)
        val i = l.indexOfFirst { it.rid == rid }
        if (i < 0) return l
        // `ok` does not remove it either: everything goes together at [completes], so a restart before the pairing stays
        // undecided.
        val leaves = !ok && (code in REFUSALS || (code in ALONE_ONLY && i == 0))
        return if (leaves) l.filterIndexed { n, _ -> n != i } else l
    }

    // No failure of the post removes an entry, and nothing after it does either (the entry was saved before the post): an
    // HTTP client may send a POST again, so copy 1 can be queued while a later copy is refused (401, 409, 429). A refused
    // post only keeps the phone undecided, which is the safe side.

    /** The opens whose slot is still to be looked at once. */
    fun toPoll(ledger: List<SentOpen>): List<SentOpen> = ledger.filterNot { it.polled }

    fun polled(ledger: List<SentOpen>, rid: String): List<SentOpen> = ledger.map { if (it.rid == rid) it.copy(polled = true) else it }

    /** Nothing is unanswered any more only once `ok`, the pairing and the credential have all completed. */
    fun completes(ok: Boolean, paired: Boolean, credential: Boolean): Boolean = ok && paired && credential

    private const val LEGACY = "legacy"
    private const val UNKNOWN = "unknown-"
}

@Suppress("TooManyFunctions") // one small decision per guard
internal object Rules {
    /** The reads of status with K are at least this far apart. */
    const val MIN_BETWEEN_READS_MS = 5 * 60 * 1000L

    /** The lost-ok unlock refused for this long (staging-scaled): the contact text. */
    const val REFUSED_FOR_MS = 24 * 60 * 60 * 1000L

    /** The wall clock and the monotonic clock may differ this much between two reads, or the pair restarts. */
    const val CLOCK_AGREE_MS = 2 * 60 * 1000L

    /** A moment on both clocks: the wall clock (epoch ms) and the monotonic one (`elapsedRealtime`, which a reboot resets). */
    @Serializable
    data class Clock(val wallMs: Long, val elapsedMs: Long)

    /** What two reads say of the time between them. */
    enum class Gap { FIRST, TOO_SOON, OK, RESTART }

    /**
     * Two reads count only with both clocks at least 5 minutes apart, the two measures agreeing within 2 minutes. A clock
     * that was set, or a reboot (the monotonic clock went back), between them restarts the pair.
     */
    fun pair(seen: Clock?, now: Clock): Gap {
        if (seen == null) return Gap.FIRST
        val wall = now.wallMs - seen.wallMs
        val mono = now.elapsedMs - seen.elapsedMs
        return when {
            wall < 0 || mono < 0 || kotlin.math.abs(wall - mono) > CLOCK_AGREE_MS -> Gap.RESTART
            wall >= MIN_BETWEEN_READS_MS && mono >= MIN_BETWEEN_READS_MS -> Gap.OK
            else -> Gap.TOO_SOON
        }
    }

    /** What status with K answered. */
    sealed interface Read {
        /**
         * `200`: K is the vault's `app_key` or its `app_key_next`; not proof of adoption. [registered]: the recovery still shows
         * `registered`. [unregistered]: an active recovery that is not registered (`pending` or `available`).
         */
        data class Listed(val registered: Boolean, val unregistered: Boolean = false) : Read

        /** `401`, or `410 recovery_ended`. */
        data object Ended : Read

        /** A `401` that is the phone's clock, a network fault, a `5xx`, a `429`, anything else: nothing is concluded. */
        data object Unknown : Read
    }

    sealed interface Decision {
        /** Nothing to decide: no open of this phone's is unanswered. */
        data object NotApplicable : Decision

        /** `200` and the recovery has ended: the lost-ok unlock (not "adopted"). */
        data object LostOk : Decision

        /** `200` and the recovery still shows `registered`: the phone opens again. */
        data object OpenAgain : Decision

        /** One read says ended: another is due at [atMs] or later. */
        data class Wait(val atMs: Long) : Decision

        /** Two reads at least 5 minutes apart say ended: a new recovery may be started with K. */
        data object StartNew : Decision

        /**
         * Two ended reads, but both identities already stand: keep both for good, try the lost-ok unlock first, and allow a new
         * code that reuses N (no third identity).
         */
        data object KeepBoth : Decision

        /** The read concluded nothing. */
        data object Unknown : Decision
    }

    /** What [afterRead] leaves for the next read: the first read of each kind. */
    data class Seen(val ended: Clock? = null, val listed: Clock? = null)

    /**
     * Only after an open the member API took and whose answer was never readable ([openUnanswered]); or while a [ReuseRecord]
     * stands.
     */
    fun applies(openUnanswered: Boolean, reuse: ReuseRecord?): Boolean = openUnanswered || reuse != null

    /**
     * What a read of status with K allows, on both clocks ([pair]). The same pair is needed for an ended answer and for a `200`
     * before the phone acts on either; a read of the other kind restarts the pair. [secondIdentity]: N already stands, so two
     * ended reads give [Decision.KeepBoth]: the lost-ok unlock first, and a new code reusing N.
     */
    fun afterRead(read: Read, seen: Seen, now: Clock, secondIdentity: Boolean = false): kotlin.Pair<Decision, Seen> = when (read) {
        Read.Unknown -> Decision.Unknown to seen
        is Read.Listed -> when (pair(seen.listed, now)) {
            Gap.OK -> (if (read.registered) Decision.OpenAgain else Decision.LostOk) to Seen(listed = seen.listed)
            Gap.TOO_SOON -> Decision.Wait(seen.listed!!.wallMs + MIN_BETWEEN_READS_MS) to Seen(listed = seen.listed)
            Gap.FIRST, Gap.RESTART -> Decision.Wait(now.wallMs + MIN_BETWEEN_READS_MS) to Seen(listed = now)
        }
        Read.Ended -> when (pair(seen.ended, now)) {
            Gap.OK -> (if (secondIdentity) Decision.KeepBoth else Decision.StartNew) to Seen(ended = seen.ended)
            Gap.TOO_SOON -> Decision.Wait(seen.ended!!.wallMs + MIN_BETWEEN_READS_MS) to Seen(ended = seen.ended)
            Gap.FIRST, Gap.RESTART -> Decision.Wait(now.wallMs + MIN_BETWEEN_READS_MS) to Seen(ended = now)
        }
    }

    /** The order of the lost-ok unlock: the newest identity first, only if it registered, then the earlier one. */
    fun lostOkOrder(newRegistered: Boolean): List<Identity> =
        if (newRegistered) listOf(Identity.NEW, Identity.EARLIER) else listOf(Identity.EARLIER)

    /**
     * The contact text: refused since [sinceMs] for a day (staging-scaled by [scale]), the clock checked (a clock set back never
     * shows it early or late).
     */
    fun contactVettid(sinceMs: Long?, nowMs: Long, scale: Long = 1L): Boolean =
        sinceMs != null && nowMs >= sinceMs && nowMs - sinceMs >= REFUSED_FOR_MS / scale.coerceAtLeast(1L)

    /**
     * Whether a lost-ok answer counts as a refusal: not a wrong PIN, a backoff, or a failure of the service or the network.
     * Only the vault refusing this phone counts.
     */
    fun countsAsRefusal(code: String): Boolean = code !in NOT_REFUSALS

    private val NOT_REFUSALS = setOf(
        "bad_pin", "backoff", "retry", "busy", "counter_unavailable", "no_answer", "timeout", "network", "service", "rate_limited",
    )

    /** A recovery code or register answer that names another vault than the earlier open's is refused, nothing registered. */
    fun sameVault(own: String, named: String?): Boolean = named == null || named == own

    /**
     * Whether a failed register left N unregistered: it did not reach the vault (the release starting or the vault
     * unavailable, a rate limit, `recovery_not_available`, no network before the POST; [status] null) AND this POST was the
     * FIRST for this code AND no `202` came for it ([accepted]: after a `202` any failure, a failed poll too, is "maybe
     * registered"). A repeat for the same code ([repeatForSameCode], decided per POST) may have been the second copy of a
     * register that went through: the member API answers `409` to any repeat once the first was recorded.
     */
    fun registerFailedBeforeVault(repeatForSameCode: Boolean, accepted: Boolean, status: Int?, code: String?): Boolean =
        !repeatForSameCode && !accepted &&
            (status == null || status == HTTP_TOO_MANY || status == HTTP_UNAVAILABLE || code == "recovery_not_available")

    /**
     * Whether the POST about to be sent is a repeat for its code, decided per POST: an earlier register flow tried this code
     * ([triedBefore]), or ANY earlier POST was built in this flow ([postedEarlierInFlow]: an `expired` slot, or a
     * `409 instance_moved`, posts again).
     */
    fun isRepeatPost(triedBefore: Boolean, postedEarlierInFlow: Boolean): Boolean = triedBefore || postedEarlierInFlow

    /** What a failed register does with N (VAULT-MESSAGING §11.14.6 "A phone that could not tell"). */
    enum class RegisterOutcome {
        /** Keep N's state; status signed with K decides. */
        MAYBE_REGISTERED,

        /** Nothing reached the vault: N and the earlier identity are restored, the same N, K and code may try again. */
        DROP_AND_RETRY,

        /**
         * A first POST was answered `recovery_not_available` / a readable refusal: the earlier identity is in use again, N is
         * kept and the same N, K and code may try again; the refusal is shown.
         */
        DROP_REFUSED,

        /** `401` on the first POST of an N never registered: N's registration and identity are dropped. */
        DROP_UNAUTHORIZED,

        /** Fail secure: N stays unknown, nothing dropped, the failure is shown (thrown or refused). */
        KEEP_UNKNOWN,
    }

    /**
     * The outcome of a register POST that failed with [status] (null: no answer, an IOException) and [code]. [triedBefore]: an
     * earlier flow tried this code; [postedEarlierInFlow]: an earlier POST was built in this flow; [acceptedThisPost]: this
     * POST got its `202` (any failure after it is maybe registered); [earlierAccepted]: an earlier POST of this flow got
     * its `202` (this POST's answer settles only itself); [beforeNewRegistered]: N was registered before this flow.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun registerFailure(
        triedBefore: Boolean,
        postedEarlierInFlow: Boolean,
        acceptedThisPost: Boolean,
        earlierAccepted: Boolean,
        beforeNewRegistered: Boolean,
        status: Int?,
        code: String?,
    ): RegisterOutcome {
        if (acceptedThisPost || earlierAccepted) return RegisterOutcome.MAYBE_REGISTERED
        val repeat = isRepeatPost(triedBefore, postedEarlierInFlow)
        return when (code) {
            // K became a key in use: nothing recorded, but an N that was (or may have been) registered loses nothing.
            "unauthorized" ->
                if (beforeNewRegistered || postedEarlierInFlow) RegisterOutcome.MAYBE_REGISTERED else RegisterOutcome.DROP_UNAUTHORIZED
            // No 202 and not a 401: N stays unknown, whatever the POST (fail secure).
            "recovery_unavailable" -> RegisterOutcome.KEEP_UNKNOWN
            "recovery_not_available" -> if (repeat) RegisterOutcome.MAYBE_REGISTERED else RegisterOutcome.DROP_REFUSED
            else ->
                if (registerFailedBeforeVault(repeat, false, status, code)) RegisterOutcome.DROP_AND_RETRY else RegisterOutcome.KEEP_UNKNOWN
        }
    }

    /**
     * The outcome of a register POST that was answered (sealed, readable) not-ok with [readableCode] (null: unreadable).
     * A refusal settles only its own POST: after an earlier `202` in the flow ([earlierAccepted]) N may still be registered.
     */
    fun registerRefusal(repeatForSameCode: Boolean, earlierAccepted: Boolean, readableCode: String?): RegisterOutcome = when {
        earlierAccepted || maybeRegistered(repeatForSameCode, readableCode) -> RegisterOutcome.MAYBE_REGISTERED
        else -> RegisterOutcome.DROP_REFUSED
    }

    /** A repeat for the same code answered `recovery_not_available`, or a readable `used`: maybe registered, not refused. */
    fun maybeRegistered(repeatForSameCode: Boolean, code: String?): Boolean =
        repeatForSameCode && (code == "recovery_not_available" || code == "used")

    /** N's state and keys are never replaced by new ones once N was (or may have been) registered. */
    fun mayMakeNewKeysForN(newRegistered: Boolean, newRecoveryId: String?): Boolean = !newRegistered && newRecoveryId == null

    /** The answers of a fresh open that rule out recovery until the owner's app arms a new generation. */
    val RULED_OUT = setOf("exhausted", "closed", "not_armed")

    private const val HTTP_TOO_MANY = 429
    private const val HTTP_UNAVAILABLE = 503

    /** What a new code does: starts afresh, reuses K, or is refused. */
    enum class Gate { FRESH, REUSE, REFUSE }

    /**
     * A new code reuses the identity N (made once, re-registered with the same K and the earlier `unlock_secret`) when two
     * reads allowed it; there is no third identity, so there is nothing else to refuse but a code while the rule has not
     * allowed it.
     */
    fun gateNewCode(undecided: Boolean, startNew: Boolean): Gate = when {
        startNew && undecided -> Gate.REUSE
        undecided -> Gate.REFUSE
        else -> Gate.FRESH
    }

    /** Forgetting K (start over, a fresh device, dropping the setup code, new keys) is never allowed while undecided. */
    fun mayForget(undecided: Boolean): Boolean = !undecided

    /** A session on a slot with missing keys makes new ones only when nothing is undecided; otherwise it fails secure. */
    fun mayGenerateKeys(undecided: Boolean): Boolean = !undecided

    /** After `transfer_incomplete` the open repeats the recovery of the identity registered for the current one. */
    fun slotForOpenAgain(newRegistered: Boolean, newSlot: Int?, earlierSlot: Int): Int =
        if (newRegistered && newSlot != null) newSlot else earlierSlot

    /** The identity in use after a refused or unauthorized register of the new identity: the earlier one. */
    fun slotAfterRefusedRegister(earlierSlot: Int): Int = earlierSlot

    /** The new identity's open answered `ok`: its pairing has the fall back to the earlier identity. */
    fun pairsWithFallback(newSlot: Int?, activeSlot: Int): Boolean = newSlot != null && activeSlot == newSlot

    /** "Your vault took this phone…": only after `409 key_in_use`, a real refusal, for a day. */
    fun showContact(keyInUse: Boolean, refusal: Boolean, sinceMs: Long?, nowMs: Long, scale: Long = 1L): Boolean =
        keyInUse && refusal && contactVettid(sinceMs, nowMs, scale)

    /**
     * "This phone could not recover your vault…": both identities stand, K is not the vault's key, and a fresh open's answer
     * ruled recovery out until the owner's app arms a new generation. Not after a day of ordinary lost-ok refusals. Not
     * terminal: a later recovery still reuses N.
     */
    fun showCouldNotRecover(keyInUse: Boolean, secondIdentity: Boolean, openCode: String?): Boolean =
        !keyInUse && secondIdentity && openCode in RULED_OUT

    /** K is forgotten, or the phone starts over, never while undecided (also on `410 recovery_ended`). */
    fun undecided(openUnanswered: Boolean, reuse: ReuseRecord?): Boolean = applies(openUnanswered, reuse)

    /** The earlier identity and its records go only after the vault answered `ok` and the handshake completed. */
    fun mayDeleteEarlier(openOk: Boolean, pairingComplete: Boolean): Boolean = openOk && pairingComplete
}

/** The claim of a new code with K. */
internal sealed interface ClaimAnswer {
    data object Claimed : ClaimAnswer

    /** `409 key_in_use`: K is the vault's `app_key` or a live `app_key_pending`. */
    data object KeyInUse : ClaimAnswer

    data class Refused(val code: String) : ClaimAnswer
}

/** The earlier open's slot, looked at once. */
internal sealed interface EarlierOpen {
    /** A readable `ok`: it decides; the earlier identity pairs. */
    data object Ok : EarlierOpen

    /** A readable answer that is not `ok`, or one that cannot be read, or no slot: the new recovery goes on. */
    data object Undecided : EarlierOpen
}

/** The register of the new identity. */
internal sealed interface RegisterAnswer {
    data object Registered : RegisterAnswer

    /** `401`: K is no claim key any more (it became a key in use between the claim and the register). */
    data object Unauthorized : RegisterAnswer

    data class Refused(val code: String) : RegisterAnswer
}

/**
 * The new recovery's first steps, in order: claim the new code with K; look once at the earlier open's slot; register a new
 * identity with the same K. Nothing is deleted by any outcome.
 */
internal class NewRecovery(private val ports: Ports) {
    interface Ports {
        suspend fun claim(): ClaimAnswer

        suspend fun pollEarlierOpen(): EarlierOpen

        /** Makes the new identity if there is none, switches to it and registers it with the same K and the earlier phone secret. */
        suspend fun registerNew(): RegisterAnswer

        /** Records that a lost-ok unlock is due (after `409 key_in_use`, or a `401` at the register followed by one). */
        suspend fun lostOkDue()
    }

    sealed interface Result {
        /** The new identity is registered: the member enters PIN and password, the open carries the earlier `unlock_secret`. */
        data object Registered : Result

        /** `409 key_in_use` at the claim: the phone is the vault's app; the lost-ok unlock, newest identity first. */
        data object KeyInUse : Result

        /** The earlier open's slot held a readable `ok`: that identity continues (handshake with its `recovery_id`). */
        data object EarlierOk : Result

        /** `401` at the register: nothing was recorded for the new identity; a new claim may follow. */
        data object RegisterUnauthorized : Result

        data class Refused(val code: String) : Result
    }

    @Suppress("ReturnCount")
    suspend fun run(): Result {
        when (val c = ports.claim()) {
            ClaimAnswer.KeyInUse -> {
                ports.lostOkDue()
                return Result.KeyInUse
            }
            is ClaimAnswer.Refused -> return Result.Refused(c.code)
            ClaimAnswer.Claimed -> Unit
        }
        if (ports.pollEarlierOpen() == EarlierOpen.Ok) return Result.EarlierOk
        return when (val r = ports.registerNew()) {
            RegisterAnswer.Registered -> Result.Registered
            RegisterAnswer.Unauthorized -> Result.RegisterUnauthorized
            is RegisterAnswer.Refused -> Result.Refused(r.code)
        }
    }
}

/** The answer of the lost-ok unlock (`vault.unlock` with the identity's phone secret). */
internal sealed interface LostOkAnswer {
    /** `ok` with the vault's bundle: this identity is the vault's app. */
    data object Ok : LostOkAnswer

    /** `transfer_incomplete`: the state is still under `DEK_0`; only an open repeats the recovery. */
    data object TransferIncomplete : LostOkAnswer

    /** Any other refusal (`device_secret`, `bad_pin`, a wait, no answer, no bundle). */
    data class Refused(val code: String) : LostOkAnswer
}

/** The lost-ok unlock in [Rules.lostOkOrder]: the first identity answered `ok` is the one that pairs. */
internal class LostOk(private val ports: Ports) {
    interface Ports {
        suspend fun unlock(identity: Identity): LostOkAnswer
    }

    sealed interface Result {
        data class Ok(val identity: Identity) : Result

        data class TransferIncomplete(val identity: Identity) : Result

        /** Neither unlock succeeded: keep everything, read status again later; [code] is the last refusal. */
        data class NoneOk(val code: String) : Result
    }

    @Suppress("ReturnCount")
    suspend fun run(newRegistered: Boolean): Result {
        var last = "no_answer"
        for (id in Rules.lostOkOrder(newRegistered)) {
            when (val a = ports.unlock(id)) {
                LostOkAnswer.Ok -> return Result.Ok(id)
                LostOkAnswer.TransferIncomplete -> return Result.TransferIncomplete(id)
                is LostOkAnswer.Refused -> last = a.code
            }
        }
        return Result.NoneOk(last)
    }
}

/** What the handshake of an identity gave. */
internal enum class PairAnswer {
    /** `hs.resp` arrived and the pairing completed. */
    PAIRED,

    /** No `hs.resp` within the pairing timeout (the vault answers a handshake it does not accept with nothing). */
    NO_RESPONSE,
}

/**
 * After the new open answered `ok`: `hs.init` with the new identity and `ctx` = the new `recovery_id`; if no `hs.resp`
 * arrives within the pairing timeout, the vault's invitation is the earlier identity's: the lost-ok unlock with the
 * earlier identity and `hs.init` with `ctx` = the earlier `recovery_id`. Each identity pairs only with its own
 * `recovery_id`.
 */
internal class Pairing(private val ports: Ports) {
    interface Ports {
        /** Switches to [identity] and sends `hs.init` with `ctx` = that identity's `recovery_id`. */
        suspend fun pair(identity: Identity): PairAnswer

        suspend fun unlockEarlier(): LostOkAnswer
    }

    sealed interface Result {
        data class Paired(val identity: Identity) : Result

        /** The fall back did not work either: everything is kept. */
        data class Failed(val code: String) : Result
    }

    @Suppress("ReturnCount")
    suspend fun run(): Result {
        if (ports.pair(Identity.NEW) == PairAnswer.PAIRED) return Result.Paired(Identity.NEW)
        return when (val a = ports.unlockEarlier()) {
            LostOkAnswer.Ok ->
                if (ports.pair(Identity.EARLIER) == PairAnswer.PAIRED) Result.Paired(Identity.EARLIER) else Result.Failed("no_hs_resp")
            LostOkAnswer.TransferIncomplete -> Result.Failed("transfer_incomplete")
            is LostOkAnswer.Refused -> Result.Failed(a.code)
        }
    }
}
