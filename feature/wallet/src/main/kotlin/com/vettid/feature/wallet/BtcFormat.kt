package com.vettid.feature.wallet

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Amounts as the wallet screens write them (ANDROID-PLAN §4 "Wallet", screens table): bitcoin with up to 8 decimals and no trailing
 * zeros ("0.00123"), and satoshis with the locale's grouping ("123,000"). Only the numbers: the unit words are the screens' strings.
 */
object BtcFormat {
    private const val SATS_PER_BTC = 100_000_000L
    private const val DECIMALS = 8

    /** The most bitcoin there will ever be, in satoshis: an amount above it is not an amount. */
    const val MAX_SATS = 21_000_000L * SATS_PER_BTC

    /** [sats] in bitcoin: "0.00123", "1", "0"; a negative amount keeps its sign. Always a plain decimal, never an exponent. */
    fun btc(sats: Long): String {
        val d = BigDecimal.valueOf(sats, DECIMALS).stripTrailingZeros()
        return if (d.signum() == 0) "0" else d.toPlainString()
    }

    /** [sats] with the locale's digit grouping: "123,000". */
    fun sats(sats: Long, locale: Locale = Locale.getDefault()): String = NumberFormat.getIntegerInstance(locale).format(sats)

    /** What was typed in the amount field of Receive (bitcoin). */
    sealed interface Amount {
        /** Nothing typed: the address alone. */
        data object None : Amount

        /** More than zero, at most 8 decimals, at most [MAX_SATS]. */
        data class Valid(val sats: Long) : Amount

        data object Invalid : Amount
    }

    /** [text] as an amount in bitcoin: digits with one `.` (or `,`), at most 8 decimals, more than zero. */
    fun parse(text: String): Amount {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return Amount.None
        val sats = if (AMOUNT.matches(t)) t.toBigDecimalOrNull()?.movePointRight(DECIMALS)?.toLongExact() else null
        return if (sats != null && sats in 1..MAX_SATS) Amount.Valid(sats) else Amount.Invalid
    }

    /** The characters an amount field takes: digits and one decimal separator. */
    fun filter(text: String): String {
        var seen = false
        return text.filter { c ->
            when {
                c in '0'..'9' -> true
                (c == '.' || c == ',') && !seen -> true.also { seen = true }
                else -> false
            }
        }
    }

    private val AMOUNT = Regex("""\d+(\.\d{1,8})?|\.\d{1,8}|\d+\.""")

    private fun BigDecimal.toLongExact(): Long? = try {
        longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}

/** What Receive shows and encodes for an address (BIP21; ANDROID-PLAN §4 screens table, "Receive"). */
object Bip21 {
    /**
     * The QR code's content: `BITCOIN:` and the address in upper case (a denser code: QR's alphanumeric mode; bech32 addresses
     * are valid in either case, never mixed), then `?amount=<btc>` when an amount is given. The key stays lower case.
     */
    fun uri(address: String, sats: Long? = null): String {
        // Upper case only for bech32 / bech32m (QR's alphanumeric mode); a base58 address is case-sensitive and kept as it is.
        val base = "BITCOIN:" + if (BECH32.matches(address)) address.uppercase() else address
        return if (sats != null) "$base?amount=${BtcFormat.btc(sats)}" else base
    }

    /**
     * What Share sends (the owner's decision 1a, 2026-11-01): with an amount, the BIP21 URI `bitcoin:<address>?amount=<btc>`, so the
     * amount travels with the address and shows in the shared text; without one, the plain address. The address is kept as the vault
     * gave it (lower case for bech32): this is text for people and apps, not a QR code.
     */
    fun share(address: String, sats: Long? = null): String =
        if (sats != null) "bitcoin:$address?amount=${BtcFormat.btc(sats)}" else address

    /** The address as text in groups of four, separated by spaces. */
    fun groups(address: String): String = address.chunked(GROUP).joinToString(" ")

    private const val GROUP = 4

    /** An hrp (`bc`, `tb`, `bcrt`), the separator `1`, then bech32 data characters, all lower case as the vault gives them. */
    private val BECH32 = Regex("(bc|tb|bcrt)1[ac-hj-np-z02-9]+")
}
