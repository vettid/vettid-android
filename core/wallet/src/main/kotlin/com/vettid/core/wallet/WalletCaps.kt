package com.vettid.core.wallet

/**
 * A wallet's spending limits (VAULT-MESSAGING §10.18, 0.56.0; ANDROID-PLAN §4 "Spending limits"): the most one payment may take
 * out and the most in any rolling 24 hours, both in satoshis, each null for no limit. Prints its type name only.
 */
data class WalletCaps(val perTxSats: Long?, val perPeriodSats: Long?) {
    /** No limit at all: the default choice, and nothing to set. */
    val none: Boolean get() = perTxSats == null && perPeriodSats == null

    override fun toString(): String = "WalletCaps"
}

/** The rules the vault applies to limits (`wallet.caps.set`, `wallet.create{caps}`), checked here before anything is sent. */
object CapsRules {
    /** The most a limit can be: 2,100,000,000,000,000 satoshis (21 million bitcoin). */
    const val MAX_SATS = 2_100_000_000_000_000L

    enum class Problem {
        /** A value under 1 or over [MAX_SATS]. */
        OUT_OF_RANGE,

        /** `per_tx_sats` above `per_period_sats`: one payment could take more than the period allows. */
        TX_ABOVE_PERIOD,
    }

    /** What is wrong with the pair, or null when the vault would take it. */
    fun problem(perTxSats: Long?, perPeriodSats: Long?): Problem? = when {
        perTxSats != null && perTxSats !in 1..MAX_SATS -> Problem.OUT_OF_RANGE
        perPeriodSats != null && perPeriodSats !in 1..MAX_SATS -> Problem.OUT_OF_RANGE
        perTxSats != null && perPeriodSats != null && perTxSats > perPeriodSats -> Problem.TX_ABOVE_PERIOD
        else -> null
    }

    fun problem(caps: WalletCaps): Problem? = problem(caps.perTxSats, caps.perPeriodSats)
}
