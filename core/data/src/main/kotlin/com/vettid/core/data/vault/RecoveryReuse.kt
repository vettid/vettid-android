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
    /** First read of status with K that answered `401` / `410 recovery_ended` (epoch ms, this phone's clock). */
    val endedSeenAtMs: Long? = null,
    /** Two such reads at least 5 minutes apart: the member may be told to start a new recovery. */
    val startNew: Boolean = false,
    /** A `200` on status, or a `409 key_in_use` at a claim: the next step is the lost-ok unlock. */
    val lostOkDue: Boolean = false,
    /** A `409 key_in_use` was seen: only then is the lost-ok unlock "the vault took this phone" (a `200` alone is not that). */
    val keyInUse: Boolean = false,
    /** Since when the lost-ok unlock is refused (epoch ms); after a day the contact text. */
    val refusedSinceMs: Long? = null,
)

/** An open the member API took ([rid]), by the identity in [slot] for [recoveryId]; [polled]: its slot was looked at. */
@Serializable
internal data class SentOpen(val rid: String, val recoveryId: String, val slot: Int, val polled: Boolean = false)

/**
 * Every open sent whose answer was never readable. It is sticky: a later open's answer, of whatever kind, says nothing of
 * an earlier open that may have stored step 1 (a later `wrong` is the earlier one's recovery resumed), so only the
 * answered open leaves; the rest go with the cleanup after `ok` and the pairing ([Rules.mayDeleteEarlier]).
 */
internal object Ledger {
    fun afterSent(ledger: List<SentOpen>, o: SentOpen): List<SentOpen> = if (ledger.any { it.rid == o.rid }) ledger else ledger + o

    /** A readable answer to the open [rid]: it is answered; its predecessors stay unanswered. */
    fun afterAnswer(ledger: List<SentOpen>, rid: String?): List<SentOpen> = ledger.filterNot { it.rid == rid }

    /** The opens whose slot is still to be looked at once. */
    fun toPoll(ledger: List<SentOpen>): List<SentOpen> = ledger.filterNot { it.polled }

    fun polled(ledger: List<SentOpen>, rid: String): List<SentOpen> = ledger.map { if (it.rid == rid) it.copy(polled = true) else it }
}

@Suppress("TooManyFunctions") // one small decision per guard
internal object Rules {
    /** The reads of status with K are at least this far apart. */
    const val MIN_BETWEEN_READS_MS = 5 * 60 * 1000L

    /** The lost-ok unlock refused for this long: "Your vault took this phone, but it cannot be unlocked right now". */
    const val REFUSED_FOR_MS = 24 * 60 * 60 * 1000L

    /** What status with K answered. */
    sealed interface Read {
        /**
         * `200`: K is the vault's `app_key` or its `app_key_next`; not proof of adoption. [registered]: the recovery still shows
         * `registered`.
         */
        data class Listed(val registered: Boolean) : Read

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

        /** The read concluded nothing. */
        data object Unknown : Decision
    }

    /**
     * Only after an open the member API took and whose answer was never readable ([openUnanswered]); or while a [ReuseRecord]
     * stands.
     */
    fun applies(openUnanswered: Boolean, reuse: ReuseRecord?): Boolean = openUnanswered || reuse != null

    /**
     * What a read of status with K allows. [seenAtMs] is the first ended read; the clock is checked: a phone clock that went
     * back past it starts the count again. Returns the decision and the new [seenAtMs].
     */
    fun afterRead(read: Read, seenAtMs: Long?, nowMs: Long): Pair<Decision, Long?> = when (read) {
        is Read.Listed -> (if (read.registered) Decision.OpenAgain else Decision.LostOk) to null
        Read.Unknown -> Decision.Unknown to seenAtMs
        Read.Ended -> when {
            seenAtMs == null || nowMs < seenAtMs -> Decision.Wait(nowMs + MIN_BETWEEN_READS_MS) to nowMs
            nowMs - seenAtMs >= MIN_BETWEEN_READS_MS -> Decision.StartNew to seenAtMs
            else -> Decision.Wait(seenAtMs + MIN_BETWEEN_READS_MS) to seenAtMs
        }
    }

    /** The order of the lost-ok unlock: the newest identity first, only if it registered, then the earlier one. */
    fun lostOkOrder(newRegistered: Boolean): List<Identity> =
        if (newRegistered) listOf(Identity.NEW, Identity.EARLIER) else listOf(Identity.EARLIER)

    /** The contact text: refused since [sinceMs] for a day, the clock checked (a clock set back never shows it early or late). */
    fun contactVettid(sinceMs: Long?, nowMs: Long): Boolean = sinceMs != null && nowMs >= sinceMs && nowMs - sinceMs >= REFUSED_FOR_MS

    /** What a new code does: starts afresh, reuses K, or is refused. */
    enum class Gate { FRESH, REUSE, REFUSE }

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

    /** "Your vault took this phone…": only after `409 key_in_use`, a refusal that is not a PIN answer, for a day. */
    fun showContact(keyInUse: Boolean, refusal: Boolean, sinceMs: Long?, nowMs: Long): Boolean =
        keyInUse && refusal && contactVettid(sinceMs, nowMs)

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
