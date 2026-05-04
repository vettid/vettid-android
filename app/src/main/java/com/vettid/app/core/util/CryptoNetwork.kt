package com.vettid.app.core.util

/**
 * Pre-populated catalog of common cryptocurrency networks. Used by:
 *
 * - Wallet creation (today: BTC only; tomorrow: any of these natively).
 * - Cryptocurrency / Crypto Key secrets, where the user records a key
 *   or address for a chain we don't yet create wallets for.
 *
 * Both surfaces draw from the same list so a peer-visible "BTC"
 * tag means the same string everywhere.
 *
 * The ordering is roughly market-cap as of 2026 — most-likely picks
 * first. The list is intentionally short; a free-text "Other" slot
 * covers anything not represented.
 */
data class CryptoNetwork(
    val ticker: String,
    val displayName: String,
)

object CryptoNetworks {
    val BTC = CryptoNetwork("BTC", "Bitcoin")
    val ETH = CryptoNetwork("ETH", "Ethereum")
    val USDC = CryptoNetwork("USDC", "USD Coin")
    val USDT = CryptoNetwork("USDT", "Tether")
    val SOL = CryptoNetwork("SOL", "Solana")
    val DOGE = CryptoNetwork("DOGE", "Dogecoin")
    val LTC = CryptoNetwork("LTC", "Litecoin")
    val XRP = CryptoNetwork("XRP", "Ripple")
    val ADA = CryptoNetwork("ADA", "Cardano")
    val AVAX = CryptoNetwork("AVAX", "Avalanche")
    val DOT = CryptoNetwork("DOT", "Polkadot")
    val MATIC = CryptoNetwork("MATIC", "Polygon")
    val LINK = CryptoNetwork("LINK", "Chainlink")
    val ATOM = CryptoNetwork("ATOM", "Cosmos")
    val BCH = CryptoNetwork("BCH", "Bitcoin Cash")
    val OTHER = CryptoNetwork("OTHER", "Other")

    val all: List<CryptoNetwork> = listOf(
        BTC, ETH, USDC, USDT, SOL, DOGE, LTC, XRP, ADA,
        AVAX, DOT, MATIC, LINK, ATOM, BCH, OTHER,
    )

    fun fromTicker(ticker: String?): CryptoNetwork? {
        if (ticker.isNullOrBlank()) return null
        val normalized = ticker.trim().uppercase()
        return all.firstOrNull { it.ticker == normalized }
    }
}
