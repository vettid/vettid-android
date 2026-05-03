package com.vettid.app.features.wallet

/**
 * Wallet information stored in the vault.
 */
data class WalletInfo(
    val walletId: String,
    val label: String,
    val address: String,
    val network: String,
    val cachedBalanceSats: Long = 0,
    val balanceUpdatedAt: Long = 0,
    val isPublic: Boolean = false,
    val isArchived: Boolean = false,
    /**
     * Unix-second timestamp of when the user last opted this wallet's
     * BIP39 seed phrase into Critical Secrets. Zero means "not backed
     * up" — the toggle on the detail screen reads/writes this.
     */
    val seedBackedUpAt: Long = 0,
    /**
     * Reference to the CredentialSecretEntry holding the seed phrase
     * once backed up. Used by the detail screen to deep-link the user
     * into the matching Critical Secret reveal flow.
     */
    val seedBackupSecretId: String? = null,
)

/**
 * Balance details for a wallet, including unconfirmed transactions.
 */
data class BalanceInfo(
    val walletId: String,
    val confirmedSats: Long,
    val unconfirmedSats: Long,
    val totalSats: Long
)

/**
 * Result of a Bitcoin transaction (send or send-to-connection).
 */
data class TxResult(
    val txid: String,
    val rawHex: String? = null,
    val feeSats: Long,
    val estVsize: Int = 0
)

/**
 * Fee estimates from the mempool (sats/vByte).
 */
data class FeeEstimate(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)

/**
 * A single transaction in the wallet history.
 */
data class TxHistoryEntry(
    val txid: String,
    val direction: String, // "sent" or "received"
    val amountSats: Long,
    val feeSats: Long,
    val confirmed: Boolean,
    val blockHeight: Long? = null,
    val blockTime: Long? = null
)

/**
 * A payment request sent to a connection. Serialized as the JSON
 * body of a `btc_payment_request` message.
 *
 * `requestId` is the idempotency key — recipient's reply (receipt
 * or decline) carries the same id so the sender can correlate.
 */
data class PaymentRequest(
    val requestId: String = "",
    val amountSats: Long,
    val address: String,
    val memo: String? = null,
    val walletId: String? = null,
    val expiresAt: String? = null
)

/**
 * Decline payload sent back when the recipient rejects a payment
 * request. Carries the original request_id and a free-text reason
 * so the sender knows which request was rejected and why.
 */
data class BtcPaymentDecline(
    val requestId: String,
    val reason: String,
)

/**
 * A Bitcoin payment receipt for display in conversation messages.
 */
data class BtcPaymentReceipt(
    val txid: String,
    val amountSats: Long,
    val feeSats: Long,
    val paymentRequestId: String? = null
)

/**
 * A Bitcoin address shared in a conversation message.
 */
data class BtcAddress(
    val address: String,
    val label: String? = null,
    val network: String = "mainnet"
)
