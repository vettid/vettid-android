package com.vettid.feature.wallet

import com.vettid.core.chain.AddressFamily
import com.vettid.core.chain.BroadcastRefusal
import com.vettid.core.chain.Network
import com.vettid.core.chain.Payee
import com.vettid.core.chain.PayeeCheck
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.wallet.BroadcastCause
import com.vettid.core.wallet.CapReason
import com.vettid.core.wallet.ChainOpen
import com.vettid.core.wallet.FeeTargets
import com.vettid.core.wallet.SendCodes
import java.net.URLDecoder
import java.time.Instant

// Send (W2-2; ANDROID-PLAN §4 "Sending"): the screens' pure parts. Every value type prints its type name only: an address, an amount
// or a limit never reaches a log through a string template.

/** Why the whole of Send shows a notice and no form (Receive's holds, and the holds of [com.vettid.core.wallet.SendHold]). */
enum class SendBlock {
    NOT_BACKED_UP,
    SETTINGS_UNREADABLE,
    CAPS_PENDING,
    CAPS_DUPLICATE,

    /** A `wallet.sign` may still apply at the vault (also after a restart that lost its answer): no new send for the wallet. */
    SIGN_UNSETTLED,
    NEEDS_NETWORK,
    NOT_OFFERED,
    MISMATCH,

    /** The chain server's privacy note was not shown for this wallet yet. */
    NOTE_NEEDED,
}

enum class SendStep { FORM, REVIEW, DONE }

enum class AmountUnit { BTC, SATS }

enum class FeeChoice { SLOW, NORMAL, FAST, CUSTOM }

/** About how long a payment takes to confirm, from the fee target ([SendFees.timeFor]). */
enum class ConfirmTime { MINUTES_20, HOUR, DAY }

/** The address field's own problem, found as it is typed: before anything is sent anywhere. */
sealed interface AddressIssue {
    /** A valid address of another network: "This is a {network} address. This wallet is on {other network}." */
    data class WrongNetwork(val found: AddressFamily) : AddressIssue

    data object NotAnAddress : AddressIssue
}

/** The limit that stopped a payment as the vault says it (`cap_exceeded`, or the inspect's `cap`); [limitSats] may come from the wallet. */
data class CapText(
    val reason: CapReason,
    val limitSats: Long?,
    val remainingSats: Long?,
    val allowedAfter: Instant?,
    /** The vault's reason word when it is neither `per_tx` nor `per_period`. */
    val other: String? = null,
) {
    override fun toString(): String = "CapText"
}

/** "Counts toward your limit for the last 24 hours: {outflow}. Left after this: {left}". */
data class LimitLine(val outflowSats: Long, val leftSats: Long) {
    override fun toString(): String = "LimitLine"
}

/**
 * What the review shows: **the vault's summary** (its payee, change, fee, `vsize` and the total leaving the wallet), never the app's
 * own figures. [capRefusal] is the inspect's `cap` when it says `ok: false`: the password is not asked then.
 */
@Suppress("LongParameterList") // one value per line of the review
class Review(
    val address: String,
    val payeeSats: Long,
    val changeSats: Long,
    val feeSats: Long,
    val vsize: Long,
    /** `sending_sats + fee_sats`: what the limits count (the vault's "outflow"). */
    val totalOutSats: Long,
    /** The remainder that was too small to make an output and went to the fee. */
    val toFeeSats: Long,
    val sendAll: Boolean,
    /** Coins a Send all did not spend. */
    val leftBehind: Int,
    /** The vault's inspect answered a `cap` (it implements spending limits). */
    val hasCap: Boolean,
    val capRefusal: CapText? = null,
    /** From the wallet's limits, when the vault implements them and the wallet has a per-period limit. */
    val limit: LimitLine? = null,
) {
    /** The fee in sat/vB as the vault's `vsize` reads it. */
    val feeRate: Double get() = if (vsize > 0) feeSats.toDouble() / vsize else 0.0

    override fun toString(): String = "Review"
}

/** Why a broadcast did not go through, for the sentence "Signed, but {host} didn't accept it: {reason}." */
enum class BroadcastReason {
    NO_ANSWER,
    NO_SERVER,
    INPUTS_SPENT,
    FEE_TOO_LOW,
    CONFLICT,
    DUST,
    SCRIPT_FAILED,
    MALFORMED,
    OTHER,
    ;

    companion object {
        fun of(cause: BroadcastCause): BroadcastReason = when (cause) {
            is BroadcastCause.Server -> NO_ANSWER
            is BroadcastCause.NoChain -> NO_SERVER
            is BroadcastCause.Refused -> when (cause.refusal) {
                BroadcastRefusal.INPUTS_MISSING_OR_SPENT -> INPUTS_SPENT
                BroadcastRefusal.FEE_TOO_LOW -> FEE_TOO_LOW
                BroadcastRefusal.CONFLICT -> CONFLICT
                BroadcastRefusal.DUST -> DUST
                BroadcastRefusal.SCRIPT_FAILED -> SCRIPT_FAILED
                BroadcastRefusal.MALFORMED -> MALFORMED
                BroadcastRefusal.ALREADY_KNOWN, BroadcastRefusal.OTHER -> OTHER
            }
        }
    }
}

/** How `invalid_psbt`'s reason is told (ANDROID-PLAN §4, the `invalid_psbt` texts). */
enum class PsbtText { FEE_RATE, OUTPUT_AMOUNT, TECHNICAL, WRONG_SEED, OTHER }

object PsbtTexts {
    /** The reasons the plan lists as "VettID built a payment your vault won't sign". */
    val TECHNICAL = setOf(
        "previous_tx", "witness_utxo", "foreign_input", "derivation_path", "derivation_key", "derivation_script", "internal_key",
        "input_script", "duplicate_input", "malformed", "shape", "size", "version", "sighash", "amount", "fee", "no_account",
        "output_script",
    )

    /**
     * The text for [reason]. What the app itself found wrong in what it built ([byVault] false) is always the technical text: it is
     * VettID's payment, not the vault's word.
     */
    fun of(reason: String, byVault: Boolean = true): PsbtText = when {
        reason == "fee_rate" -> PsbtText.FEE_RATE
        reason == "output_amount" -> PsbtText.OUTPUT_AMOUNT
        reason == SendCodes.WRONG_SEED -> PsbtText.WRONG_SEED
        reason in TECHNICAL || !byVault -> PsbtText.TECHNICAL
        else -> PsbtText.OTHER
    }
}

/** What the screen after approval says. */
sealed interface SendOutcome {
    /** "Sent. It will confirm in about {time}." */
    data class Sent(val time: ConfirmTime) : SendOutcome

    /** "Signed, but {host} didn't accept it: {reason}. Try again, or choose another server." */
    data class BroadcastFailed(val host: String?, val reason: BroadcastReason) : SendOutcome {
        /** The server did not answer: the payment may have gone through (see [SendUiState.discardLocked]). */
        val mayBeSent: Boolean get() = reason == BroadcastReason.NO_ANSWER

        override fun toString(): String = "BroadcastFailed"
    }

    /** `cap_exceeded` at the signing: nothing was signed. */
    data class CapExceeded(val cap: CapText) : SendOutcome

    /** `invalid_psbt` at the signing, with its reason: nothing was signed. */
    data class InvalidPsbt(val reason: String, val alarm: Boolean, val byVault: Boolean = true) : SendOutcome {
        override fun toString(): String = "InvalidPsbt"
    }

    /** The signed transaction was not the one reviewed: dropped, nothing broadcast. */
    data object SignedDiffers : SendOutcome

    /** The vault answered `ok` but the answer does not read: the payment may have been signed. */
    data object SignedUnreadable : SendOutcome

    /** This payment was sent to the vault to be signed already. */
    data object AlreadySigned : SendOutcome

    /** The vault has not answered: Try again sends the same request. The payment may have been signed. */
    data object Unanswered : SendOutcome

    /** The vault will not apply the unanswered signing any more; it may have signed it before. */
    data object Lapsed : SendOutcome

    /** Another payment from this wallet was signed after this one was prepared. */
    data object Superseded : SendOutcome

    data object DuplicateWallet : SendOutcome

    /** A signed payment (or an unanswered signing) from an earlier visit is waiting. */
    data object Unsent : SendOutcome
}

/** Which fee guard is asked about: "much higher than needed right now" (above twice Fast), or more than 10% of what is sent. */
enum class BigFee { MUCH_HIGHER, TEN_PERCENT }

/** A problem of the form after Review was asked, shown under the form. */
sealed interface SendProblem {
    data object SelfSend : SendProblem

    data object BadAmount : SendProblem

    data object BadFee : SendProblem

    data object TooManyInputs : SendProblem

    /** The coins do not cover the amount and the fee. */
    data class NotEnough(val availableSats: Long, val neededSats: Long) : SendProblem {
        override fun toString(): String = "NotEnough"
    }

    data object ServerFailed : SendProblem

    /** The chain server cannot be used ([open]): the way to the Chain server screen is offered. */
    data class NoChain(val open: ChainOpen) : SendProblem {
        override fun toString(): String = "NoChain"
    }

    /** `invalid_psbt` before the password (the inspect), or the app's own check of what it built. */
    data class Refused(val reason: String, val alarm: Boolean, val byVault: Boolean) : SendProblem {
        override fun toString(): String = "Refused"
    }

    /** "Your vault saw a different payment from the one you entered. Nothing was signed." */
    data object Different : SendProblem

    data class Failed(val kind: FailureKind) : SendProblem
}

/** The parts of the form that have rules, apart from the screen. */
object SendFees {
    const val CUSTOM_MIN = 1.0
    const val CUSTOM_MAX = 1_000.0

    /** The characters a custom rate takes: digits and one `.`. */
    fun filterCustom(text: String): String = BtcFormat.filter(text).replace(',', '.')

    /** The custom rate typed, in sat/vB; null unless it is within [CUSTOM_MIN] and [CUSTOM_MAX]. */
    fun customRate(text: String): Double? =
        text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it in CUSTOM_MIN..CUSTOM_MAX }

    /** The fee is more than 10% of [amountSats] (what the payee receives): `fee > amount / 10`, strictly. */
    fun overTenPercent(feeSats: Long, amountSats: Long): Boolean = feeSats * TEN > amountSats

    /** The smallest transaction the wallet builds is about this many vB: a fee rate times it is a floor for the fee before it is built. */
    const val MIN_VSIZE = 110L
    private const val TEN = 10L

    /** The rate of [choice] from the server's [estimates] (sat/vB by target), or the custom text; null while it is not known. */
    fun rate(choice: FeeChoice, estimates: Map<Int, Double>?, custom: String): Double? = when (choice) {
        FeeChoice.CUSTOM -> customRate(custom)
        FeeChoice.SLOW -> estimates?.let { FeeTargets.rate(it, FeeTargets.SLOW) }
        FeeChoice.NORMAL -> estimates?.let { FeeTargets.rate(it, FeeTargets.NORMAL) }
        FeeChoice.FAST -> estimates?.let { FeeTargets.rate(it, FeeTargets.FAST) }
    }

    /** Above twice the Fast rate: "This fee is much higher than needed right now. Pay it anyway?" (not asked without estimates). */
    fun muchHigher(rate: Double, estimates: Map<Int, Double>?): Boolean {
        val fast = estimates?.let { FeeTargets.rate(it, FeeTargets.FAST) } ?: return false
        return FeeTargets.muchHigher(rate, fast)
    }

    /** About how long [rate] takes: the quickest target whose rate it reaches (Slow when it reaches none). */
    fun timeFor(rate: Double, estimates: Map<Int, Double>?): ConfirmTime {
        val fast = estimates?.let { FeeTargets.rate(it, FeeTargets.FAST) }
        val normal = estimates?.let { FeeTargets.rate(it, FeeTargets.NORMAL) }
        return when {
            fast != null && rate >= fast -> ConfirmTime.MINUTES_20
            normal != null && rate >= normal -> ConfirmTime.HOUR
            else -> ConfirmTime.DAY
        }
    }

    /** [rate] for people: whole numbers without decimals, else one decimal. */
    fun show(rate: Double): String = if (rate == Math.floor(rate)) rate.toLong().toString() else "%.1f".format(java.util.Locale.US, rate)
}

/** What the member typed, pasted or scanned as the payee. */
object SendInput {
    /**
     * The address and, from a BIP21 URI, the amount in satoshis (other parameters are ignored). [isLink]: it was a `bitcoin:` link
     * (a link without an amount clears the amount field). [refused]: the whole link is refused (an unknown `req-*` parameter, a bad
     * or doubled `amount`) and nothing is filled in from it.
     */
    class Parsed(val address: String, val amountSats: Long?, val isLink: Boolean = false, val refused: Boolean = false) {
        override fun toString(): String = "Parsed"
    }

    private val BECH32 = Regex("(bc|tb|bcrt)1[ac-hj-np-z02-9]+")

    /**
     * [text] as a payee: **leading and trailing spaces are removed** ([Payee] refuses them), and a `bitcoin:` URI gives its address
     * and `amount` (in bitcoin). A bech32 address in capitals (a QR code's alphanumeric mode) is written in lower case. BIP21 says a
     * client that does not understand a `req-*` parameter must not pay: such a link, a negative or non-numeric amount, one with more than
     * 8 decimals, and two amounts are refused whole.
     */
    @Suppress("ReturnCount")
    fun parse(text: String): Parsed {
        val t = text.trim()
        if (!t.startsWith("bitcoin:", ignoreCase = true)) return Parsed(clean(t), null)
        val rest = t.substring("bitcoin:".length)
        val address = clean(rest.substringBefore('?').trim())
        val params = rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        val amounts = ArrayList<String>()
        for (pair in params) {
            val key = pair.substringBefore('=').lowercase()
            if (key.startsWith("req-")) return Parsed("", null, isLink = true, refused = true)
            if (key == "amount") amounts += pair.substringAfter('=', "")
        }
        if (amounts.size > 1) return Parsed("", null, isLink = true, refused = true)
        val sats = amounts.singleOrNull()?.let { v ->
            val decoded = try {
                URLDecoder.decode(v, "UTF-8")
            } catch (_: IllegalArgumentException) {
                v
            }
            (BtcFormat.parse(decoded) as? BtcFormat.Amount.Valid)?.sats ?: return Parsed("", null, isLink = true, refused = true)
        }
        return Parsed(address, sats, isLink = true)
    }

    private fun clean(a: String): String = if (a == a.uppercase() && BECH32.matches(a.lowercase())) a.lowercase() else a

    /** What is wrong with [address] for a wallet on [network]; null if it is fine (or empty: nothing to say yet). */
    fun issue(network: Network, address: String): AddressIssue? {
        if (address.isEmpty()) return null
        return when (val c = Payee.check(network, address)) {
            is PayeeCheck.Ok -> null
            is PayeeCheck.WrongNetwork -> AddressIssue.WrongNetwork(c.found)
            PayeeCheck.Invalid -> AddressIssue.NotAnAddress
        }
    }
}

/** Numbers and times as Send writes them. */
object SendFormat {
    /** A time the vault gave (`allowed_after`), in the member's zone and language. */
    fun whenAt(
        at: Instant,
        locale: java.util.Locale = java.util.Locale.getDefault(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String =
        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
            .withLocale(locale).withZone(zone).format(at)
}
