package com.vettid.core.wallet

import com.vettid.core.chain.BroadcastRefusal
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.CoinSelection
import com.vettid.core.chain.InspectSummary
import com.vettid.core.chain.Network
import com.vettid.core.chain.PayeeCheck
import com.vettid.core.chain.SummaryDifference
import com.vettid.core.vault.VaultOpException
import kotlinx.serialization.json.JsonObject
import java.time.Instant

// The types of sending (W2-1; ANDROID-PLAN §4 "Sending", VAULT-MESSAGING §10.18). Memory only. Each prints its type name only: an
// address, an amount or a transaction never reaches a log through a string template.

/** The codes of the send pipeline's refusals, for the screens (W2-2) that give each its text. */
object SendCodes {
    const val CAP_EXCEEDED = "cap_exceeded"
    const val INVALID_PSBT = "invalid_psbt"
    const val CREDENTIAL_LOCKED = "credential_locked"
    const val DUPLICATE_WALLET = "duplicate_wallet"

    /** The vault's summary differs from what was built: "Your vault saw a different payment from the one you entered…". */
    const val SUMMARY_MISMATCH = "summary_mismatch"

    /** The signed transaction is not the one that was reviewed: nothing was broadcast. */
    const val SIGNED_MISMATCH = "signed_mismatch"

    /** `invalid_psbt`'s reason that must never happen: the vault's phrase does not give the stored account keys. An alarm. */
    const val WRONG_SEED = "wrong_seed"
    const val BROADCAST_FAILED = "broadcast_failed"
}

/** The spending-limit check `wallet.psbt.inspect` returns (0.56.0): whether `wallet.sign` would pass now, and if not why. */
data class SpendCap(
    val ok: Boolean,
    val reason: String?,
    val remainingSats: Long?,
    val allowedAfter: Instant?,
    /** `limit_sats` (VAULT-MESSAGING 0.82.2): the limit that would stop it; null from a vault that does not say. */
    val limitSats: Long? = null,
) {
    override fun toString(): String = "SpendCap"
}

/** The vault's answer to `wallet.psbt.inspect`: its summary, and its `cap` (null from a vault without spending limits). */
class PsbtInspection(val summary: InspectSummary, val cap: SpendCap?) {
    override fun toString(): String = "PsbtInspection"
}

/** The signed transaction `wallet.sign` returned. Memory only. */
class SignedTx(val tx: ByteArray, val txid: String) {
    override fun toString(): String = "SignedTx"
}

/** What the vault said to `wallet.sign`: [Ok] with the answer's body, or a [Refused] with the refusal and its code. */
sealed interface SignReply {
    /** `ok`: [body] is `{tx, txid, ...}`; the vault may have signed even if it does not read. */
    class Ok(val body: JsonObject) : SignReply {
        override fun toString(): String = "Ok"
    }

    /** A refusal: final, and it counted nothing (§10.18). [error] holds the code. */
    class Refused(val error: VaultOpException) : SignReply {
        val code: String get() = error.code

        override fun toString(): String = "Refused"
    }
}

/**
 * What one `wallet.sign` came to, as a result and not as an exception, so that nothing depends on a marker surviving a coroutine's
 * copy of an exception:
 * - [NotSent]: the failure came **before** the request went out; nothing reached the vault (the payment is given back);
 * - [Answered]: the vault's answer, `ok` or a refusal;
 * - [Unanswered]: sent, and no answer yet; [Unanswered.resend] sends **the same request** (same inner id and body; the vault answers
 *   from its cache and a spending limit counts it once) and never builds a request again.
 * A failure after the send without an answer is thrown from the call: the request may have applied.
 */
sealed interface SignOutcome {
    class NotSent(val failure: Exception) : SignOutcome {
        override fun toString(): String = "NotSent"
    }

    class Answered(val reply: SignReply) : SignOutcome {
        override fun toString(): String = "Answered"
    }

    class Unanswered(
        val forget: () -> Unit = {},
        /** The vault's clock is past the request's `exp` plus the margin: it can no longer apply, and only then is it given up. */
        val expired: () -> Boolean = { false },
        val resend: suspend () -> SignOutcome,
    ) : SignOutcome {
        override fun toString(): String = "Unanswered"
    }
}

/** One payee of a transaction the vault signed. */
data class WalletPayee(val address: String, val amountSats: Long) {
    override fun toString(): String = "WalletPayee"
}

/** A row of `wallet.history`: what this vault signed. */
data class WalletTx(
    val txid: String,
    val at: Instant?,
    val sendingSats: Long,
    val changeSats: Long,
    val feeSats: Long,
    val payees: List<WalletPayee>,
    val connectionId: String?,
    val invocationId: String?,
) {
    override fun toString(): String = "WalletTx"
}

/** What the member asked for; [amountSats] null is **Send all**. [address] is kept as entered (the vault's summary is compared with it). */
class SendRequest(
    val address: String,
    val amountSats: Long?,
    /** sat/vB, 1 to 1,000. */
    val feeRate: Double,
    val includeUnconfirmed: Boolean = false,
) {
    override fun toString(): String = "SendRequest"
}

/**
 * A payment built, inspected by the vault and compared: what the review shows (the **vault's** [vault] summary, not the app's own
 * figures) and what [WalletsManager.approveSend] signs. Memory only.
 */
@Suppress("LongParameterList") // one value per figure of the review; built in one place
class PreparedSend internal constructor(
    val walletId: String,
    val network: Network,
    val address: String,
    /** The vault's summary: the payees, the change, the fee and the total leaving the wallet. */
    val vault: InspectSummary,
    /** The vault's spending-limit check; null for a vault without spending limits (the screen then says nothing about limits). */
    val cap: SpendCap?,
    val payeeSats: Long,
    val changeSats: Long,
    val feeSats: Long,
    val vsize: Long,
    /** What was added to the fee because the remainder was too small to make an output; the review says so. */
    val toFeeSats: Long,
    val sendAll: Boolean,
    /** Coins a Send all did not spend. */
    val leftBehind: Int,
    internal val psbtBase64: String,
    internal val unsignedTxid: String,
    internal val generation: Long,
    /** How many signings had gone out for the wallet when this was prepared: a later one makes this payment out of date. */
    internal val signCount: Long = 0L,
) {
    /** The fee in sat/vB as the vault reads it. */
    val feeRate: Double get() = vault.feeSats.toDouble() / vault.vsize

    override fun toString(): String = "PreparedSend"
}

/** Why a send is held back before anything is built: as Receive is. */
enum class SendHold {
    CAPS_PENDING,
    CAPS_DUPLICATE,
    SETTINGS_UNREADABLE,

    /** A `wallet.sign` for this wallet may still apply (sent, not decided: also after a lock or a restart): no new one is sent. */
    SIGN_UNSETTLED,
}

/** The outcome of [WalletsManager.prepareSend]; nothing is signed by it. */
sealed interface PrepareResult {
    class Ready(val prepared: PreparedSend) : PrepareResult {
        override fun toString(): String = "Ready"
    }

    /** The limit the member chose is not set yet, or this phone's wallet settings cannot be read: no send, as no Receive. */
    data class Held(val hold: SendHold) : PrepareResult

    data object NeedsNetwork : PrepareResult

    data object NotOffered : PrepareResult

    /** The chain server's privacy note was not shown for this wallet: nothing is sent to the server before it is. */
    data object NoteNeeded : PrepareResult

    /** The app's derivation differs from the vault's addresses: "This wallet's addresses don't match your vault's. Nothing was sent." */
    data object AddressMismatch : PrepareResult

    /** The address is of another network ([PayeeCheck.WrongNetwork]) or not an address ([PayeeCheck.Invalid]). */
    data class BadAddress(val check: PayeeCheck) : PrepareResult {
        override fun toString(): String = "BadAddress"
    }

    /**
     * The payee is one of this wallet's own addresses (review note 6): the vault would show that output as change and the compare
     * would end in `summary_mismatch`. Named before anything is built; nothing is sent to the chain server or the vault.
     */
    data object SelfSend : PrepareResult

    /** A signed payment of this wallet waits to be broadcast, or a signing waits for its answer: decide it before a new payment. */
    data object Unsent : PrepareResult

    data object BadFeeRate : PrepareResult

    data object BadAmount : PrepareResult

    data object TooManyInputs : PrepareResult

    /** The coins do not cover the amount and the fee. */
    data class NotEnough(val availableSats: Long, val neededSats: Long) : PrepareResult {
        override fun toString(): String = "NotEnough"
    }

    /** The chain server cannot be used ([open] is not a [ChainOpen.Ready]). */
    class NoChain(val open: ChainOpen) : PrepareResult {
        override fun toString(): String = "NoChain"
    }

    /** The chain server failed while the coins were read. */
    class ServerFailed(val error: ChainError) : PrepareResult {
        override fun toString(): String = "ServerFailed"
    }

    /**
     * `invalid_psbt` ([code] is [SendCodes.INVALID_PSBT]) with its [reason], from the vault, or from the app's own check of what it
     * built (a builder error); [alarm] for `wrong_seed`. Nothing was signed.
     */
    data class Refused(val reason: String, val alarm: Boolean, val byVault: Boolean) : PrepareResult {
        val code: String get() = SendCodes.INVALID_PSBT
    }

    /** The vault's summary differs from what was built or entered ([kinds]): "Your vault saw a different payment…". Nothing was signed. */
    data class Different(val kinds: Set<SummaryDifference>) : PrepareResult {
        val code: String get() = SendCodes.SUMMARY_MISMATCH
    }

    /** The vault was closed (locked, held, wiped) meanwhile: nothing of it is kept. */
    data object Cleared : PrepareResult
}

/** Why a broadcast did not go through; the signed transaction is kept, and Try again sends the same bytes. */
sealed interface BroadcastCause {
    /** The server refused it, in Bitcoin Core's words as [refusal]. */
    class Refused(val refusal: BroadcastRefusal) : BroadcastCause {
        override fun toString(): String = "Refused"
    }

    /** The server did not answer, or answered what Esplora does not. */
    class Server(val error: ChainError) : BroadcastCause {
        override fun toString(): String = "Server"
    }

    /** No server could be used ([open] is not a [ChainOpen.Ready]). */
    class NoChain(val open: ChainOpen) : BroadcastCause {
        override fun toString(): String = "NoChain"
    }
}

/** `cap_exceeded`'s reason. */
enum class CapReason { PER_TX, PER_PERIOD, OTHER }

/** The outcome of [WalletsManager.approveSend], [WalletsManager.retrySign] and [WalletsManager.retryBroadcast]. */
sealed interface SendResult {
    /** Broadcast. [alreadyKnown]: the server already had it (an earlier try got through). */
    data class Sent(val txid: String, val alreadyKnown: Boolean = false) : SendResult {
        override fun toString(): String = "Sent"
    }

    /** Signed, not broadcast ([code] is [SendCodes.BROADCAST_FAILED]). The transaction is kept in memory; Try again resends it. */
    class BroadcastFailed(val cause: BroadcastCause) : SendResult {
        val code: String get() = SendCodes.BROADCAST_FAILED

        override fun toString(): String = "BroadcastFailed"
    }

    /**
     * `cap_exceeded` ([SendCodes.CAP_EXCEEDED]), refused before the password was tried: nothing was signed. [limitSats] is the limit that
     * stopped it; [remainingSats] and [allowedAfter] only for [CapReason.PER_PERIOD].
     */
    data class CapExceeded(
        val reason: CapReason,
        val limitSats: Long?,
        val remainingSats: Long?,
        val allowedAfter: Instant?,
    ) : SendResult {
        val code: String get() = SendCodes.CAP_EXCEEDED

        override fun toString(): String = "CapExceeded"
    }

    /** `invalid_psbt` at the signing, with its [reason]; [alarm] for `wrong_seed` (it should never happen). */
    data class Refused(val reason: String, val alarm: Boolean) : SendResult {
        val code: String get() = SendCodes.INVALID_PSBT
    }

    /** The vault says the unlock window is closed even after the app opened it: ask for the password again. */
    data object CredentialLocked : SendResult

    /** `duplicate_wallet`: the vault holds another copy of this wallet. */
    data object DuplicateWallet : SendResult

    /** The answer to the signing has not come: [WalletsManager.retrySign] sends the same request again. Nothing is broadcast. */
    data object Unanswered : SendResult

    /** The signed transaction is not the one that was reviewed: it is dropped and nothing is broadcast. */
    data object SignedDiffers : SendResult {
        val code: String get() = SendCodes.SIGNED_MISMATCH
    }

    /**
     * This payment was already sent to the vault to be signed (its signing was answered, or may have been): signing it again would
     * build it again and count again against the spending limit. Prepare a new one.
     */
    data object AlreadySigned : SendResult

    /**
     * The vault answered `ok` but the answer does not read as a signed transaction. It may have signed (and counted) it; the payment
     * stays consumed. Check the wallet before sending again.
     */
    data object SignedUnreadable : SendResult

    /**
     * Another `wallet.sign` went out for this wallet after this payment was prepared (review note 3): its coins may be spent by that
     * one, so it is not signed. Nothing was signed; prepare it again.
     */
    data object Superseded : SendResult

    /**
     * An unanswered signing that the vault will not apply any more (its `exp` passed, or the credential moved on): it is dropped from
     * memory. **It may have been signed** (and counted against the limit) before: check the wallet before sending again.
     */
    data object SignLapsed : SendResult

    /** The chain server answered that it does not know the signed transaction ([WalletsManager.checkSent]): it is still kept. */
    data object NotOnServer : SendResult

    /** Nothing to send again (no kept transaction, no unanswered signing). */
    data object NothingToSend : SendResult

    /** The send was held back ([SendHold]) as Receive is. */
    data class Held(val hold: SendHold) : SendResult

    data object Cleared : SendResult
}

/** The chain server's fee rates ([WalletsManager.feeEstimates]). */
sealed interface FeeEstimates {
    /** sat/vB by confirmation target in blocks. */
    class Done(val byTarget: Map<Int, Double>) : FeeEstimates {
        override fun toString(): String = "Done"
    }

    class NoChain(val open: ChainOpen) : FeeEstimates {
        override fun toString(): String = "NoChain"
    }

    class Failed(val error: ChainError) : FeeEstimates {
        override fun toString(): String = "Failed"
    }
}

/** Slow, Normal and Fast (ANDROID-PLAN §4 "Sending", step 2) as confirmation targets, and the rules around the rate. */
object FeeTargets {
    const val SLOW = 144
    const val NORMAL = 6
    const val FAST = 2

    /** The rate for [target] blocks: its own entry, else the nearest quicker one, else the nearest slower; clamped to 1 to 1,000. */
    fun rate(byTarget: Map<Int, Double>, target: Int): Double? {
        val key = byTarget.keys.filter { it <= target }.maxOrNull() ?: byTarget.keys.minOrNull() ?: return null
        return byTarget.getValue(key).coerceIn(CoinSelection.MIN_FEE_RATE, CoinSelection.MAX_FEE_RATE)
    }

    /** "This fee is much higher than needed right now": above twice the Fast rate. */
    fun muchHigher(rate: Double, fast: Double): Boolean = rate > 2 * fast
}

/** The outcome of [WalletsManager.discardUnsent]. */
enum class DiscardResult {
    DROPPED,

    /**
     * The signed transaction may have reached the network (a broadcast without a definite answer) and has not been found absent: it is
     * kept until [WalletsManager.checkSent] has said that a reachable server does not have it. Dropping it would let a second
     * payment from other coins confirm beside it.
     */
    MUST_CHECK,
}

/**
 * What the vault's recent signed payments of a wallet look like on the chain server ([WalletsManager.recentSends]): a payment the member
 * approved may still be on its way, whatever became of this phone's memory (a lock, a restart). The worst applies.
 */
enum class RecentSends {
    /** Nothing signed in the last 24 hours that is not confirmed. */
    CLEAR,

    /** Signed, and in the server's mempool: waiting for confirmation. */
    WAITING,

    /** Signed, and the server does not have it: it may have gone through (a broadcast that got no answer), or never left the phone. */
    MAY_HAVE_GONE,

    /** The history or the server could not be read: nothing is known. */
    UNREADABLE,
}
