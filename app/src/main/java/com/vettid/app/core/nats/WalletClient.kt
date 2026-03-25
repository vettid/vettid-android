package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.JsonObject
import com.vettid.app.features.wallet.BalanceInfo
import com.vettid.app.features.wallet.FeeEstimate
import com.vettid.app.features.wallet.TxHistoryEntry
import com.vettid.app.features.wallet.TxResult
import com.vettid.app.features.wallet.WalletInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for Bitcoin wallet operations via vault NATS handlers.
 *
 * All wallet operations are performed inside the enclave vault.
 * Private keys never leave the vault — signing, address derivation,
 * and transaction construction happen entirely within the enclave.
 *
 * Handlers:
 * - `wallet.create` - Create a new HD wallet
 * - `wallet.list` - List all wallets
 * - `wallet.get-balance` - Get confirmed/unconfirmed balance
 * - `wallet.get-address` - Get current receive address
 * - `wallet.send` - Send BTC to an external address
 * - `wallet.send-to-connection` - Send BTC to a VettID connection
 * - `wallet.request-payment` - Request payment from a connection
 * - `wallet.get-fees` - Get current fee estimates
 * - `wallet.get-history` - Get transaction history
 * - `wallet.delete` - Delete a wallet
 * - `wallet.set-visibility` - Set wallet public/private visibility
 */
@Singleton
class WalletClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) {
    companion object {
        private const val TAG = "WalletClient"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val SEND_TIMEOUT_MS = 60_000L
    }

    /**
     * Create a new HD wallet in the vault.
     *
     * @param label Human-readable label for the wallet
     * @param network Bitcoin network ("mainnet" or "testnet")
     * @return Created wallet info including address
     */
    suspend fun createWallet(label: String, network: String = "mainnet"): Result<WalletInfo> {
        val payload = JsonObject().apply {
            addProperty("label", label)
            addProperty("network", network)
        }

        return sendAndAwait("wallet.create", payload) { result ->
            parseWalletInfo(result)
        }
    }

    /**
     * List all wallets in the vault.
     *
     * @return List of wallet info records
     */
    suspend fun listWallets(): Result<List<WalletInfo>> {
        val payload = JsonObject()

        return sendAndAwait("wallet.list", payload) { result ->
            val wallets = mutableListOf<WalletInfo>()
            result.getAsJsonArray("wallets")?.forEach { element ->
                wallets.add(parseWalletInfo(element.asJsonObject))
            }
            wallets.toList()
        }
    }

    /**
     * Get the balance of a wallet including unconfirmed transactions.
     *
     * @param walletId The wallet ID
     * @return Balance details with confirmed, unconfirmed, and total sats
     */
    suspend fun getBalance(walletId: String): Result<BalanceInfo> {
        val payload = JsonObject().apply {
            addProperty("wallet_id", walletId)
        }

        return sendAndAwait("wallet.get-balance", payload) { result ->
            BalanceInfo(
                walletId = result.get("wallet_id")?.asString ?: walletId,
                confirmedSats = result.get("confirmed_sats")?.asLong ?: 0,
                unconfirmedSats = result.get("unconfirmed_sats")?.asLong ?: 0,
                totalSats = result.get("total_sats")?.asLong ?: 0
            )
        }
    }

    /**
     * Get the current receive address for a wallet.
     *
     * @param walletId The wallet ID
     * @return Bitcoin address string
     */
    suspend fun getAddress(walletId: String): Result<String> {
        val payload = JsonObject().apply {
            addProperty("wallet_id", walletId)
        }

        return sendAndAwait("wallet.get-address", payload) { result ->
            result.get("address")?.asString
                ?: throw NatsException("No address in response")
        }
    }

    /**
     * Send BTC to an external Bitcoin address.
     *
     * @param walletId The wallet to send from
     * @param toAddress Destination Bitcoin address
     * @param amountSats Amount in satoshis
     * @param feeRate Fee rate in sats/vByte
     * @return Transaction result with txid and fee
     */
    suspend fun send(
        walletId: String,
        toAddress: String,
        amountSats: Long,
        feeRate: Int
    ): Result<TxResult> {
        val payload = JsonObject().apply {
            addProperty("wallet_id", walletId)
            addProperty("to_address", toAddress)
            addProperty("amount_sats", amountSats)
            addProperty("fee_rate", feeRate)
        }

        return sendAndAwait("wallet.send", payload, SEND_TIMEOUT_MS) { result ->
            parseTxResult(result)
        }
    }

    /**
     * Send BTC to a VettID connection using their wallet address.
     *
     * The vault resolves the connection's public wallet address and
     * constructs the transaction.
     *
     * @param walletId The wallet to send from
     * @param connectionId The VettID connection ID to send to
     * @param amountSats Amount in satoshis
     * @param feeRate Fee rate in sats/vByte
     * @return Transaction result with txid and fee
     */
    suspend fun sendToConnection(
        walletId: String,
        connectionId: String,
        amountSats: Long,
        feeRate: Int
    ): Result<TxResult> {
        val payload = JsonObject().apply {
            addProperty("wallet_id", walletId)
            addProperty("connection_id", connectionId)
            addProperty("amount_sats", amountSats)
            addProperty("fee_rate", feeRate)
        }

        return sendAndAwait("wallet.send-to-connection", payload, SEND_TIMEOUT_MS) { result ->
            parseTxResult(result)
        }
    }

    /**
     * Request a payment from a connection.
     *
     * Sends a payment request to the peer's vault with the amount and memo.
     *
     * @param connectionId The connection to request payment from
     * @param walletId The wallet to receive payment to
     * @param amountSats Amount in satoshis
     * @param memo Optional memo/description for the request
     * @return Success indicator
     */
    suspend fun requestPayment(
        connectionId: String,
        walletId: String,
        amountSats: Long,
        memo: String?
    ): Result<Boolean> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            addProperty("wallet_id", walletId)
            addProperty("amount_sats", amountSats)
            memo?.let { addProperty("memo", it) }
        }

        return sendAndAwait("wallet.request-payment", payload) { result ->
            result.get("success")?.asBoolean ?: true
        }
    }

    /**
     * Get current Bitcoin network fee estimates.
     *
     * @return Fee estimates for various confirmation targets
     */
    suspend fun getFeeEstimates(): Result<FeeEstimate> {
        val payload = JsonObject()

        return sendAndAwait("wallet.get-fees", payload) { result ->
            FeeEstimate(
                fastestFee = result.get("fastest_fee")?.asInt ?: 0,
                halfHourFee = result.get("half_hour_fee")?.asInt ?: 0,
                hourFee = result.get("hour_fee")?.asInt ?: 0,
                economyFee = result.get("economy_fee")?.asInt ?: 0,
                minimumFee = result.get("minimum_fee")?.asInt ?: 0
            )
        }
    }

    /**
     * Get transaction history for a wallet.
     *
     * @param walletId The wallet ID
     * @param limit Maximum number of transactions to return
     * @return List of transaction history entries
     */
    suspend fun getHistory(walletId: String, limit: Int = 20): Result<List<TxHistoryEntry>> {
        val payload = JsonObject().apply {
            addProperty("wallet_id", walletId)
            addProperty("limit", limit)
        }

        return sendAndAwait("wallet.get-history", payload) { result ->
            val entries = mutableListOf<TxHistoryEntry>()
            result.getAsJsonArray("transactions")?.forEach { element ->
                val tx = element.asJsonObject
                entries.add(TxHistoryEntry(
                    txid = tx.get("txid")?.asString ?: "",
                    direction = tx.get("direction")?.asString ?: "",
                    amountSats = tx.get("amount_sats")?.asLong ?: 0,
                    feeSats = tx.get("fee_sats")?.asLong ?: 0,
                    confirmed = tx.get("confirmed")?.asBoolean ?: false,
                    blockHeight = tx.get("block_height")?.asLong,
                    blockTime = tx.get("block_time")?.asLong
                ))
            }
            entries.toList()
        }
    }

    /**
     * Delete a wallet from the vault.
     *
     * @param walletId The wallet ID to delete
     * @return Success indicator
     */
    suspend fun deleteWallet(walletId: String): Result<Boolean> {
        val payload = JsonObject().apply {
            addProperty("wallet_id", walletId)
        }

        return sendAndAwait("wallet.delete", payload) { result ->
            result.get("success")?.asBoolean ?: true
        }
    }

    /**
     * Set whether a wallet address is publicly visible to connections.
     *
     * @param walletId The wallet ID
     * @param isPublic Whether the wallet address should be visible to connections
     * @return Success indicator
     */
    suspend fun setVisibility(walletId: String, isPublic: Boolean): Result<Boolean> {
        val payload = JsonObject().apply {
            addProperty("wallet_id", walletId)
            addProperty("is_public", isPublic)
        }

        return sendAndAwait("wallet.set-visibility", payload) { result ->
            result.get("success")?.asBoolean ?: true
        }
    }

    // MARK: - Response Parsing

    private fun parseWalletInfo(json: JsonObject): WalletInfo {
        return WalletInfo(
            walletId = json.get("wallet_id")?.asString ?: "",
            label = json.get("label")?.asString ?: "",
            address = json.get("address")?.asString ?: "",
            network = json.get("network")?.asString ?: "mainnet",
            cachedBalanceSats = json.get("cached_balance_sats")?.asLong ?: 0,
            balanceUpdatedAt = json.get("balance_updated_at")?.asLong ?: 0,
            isPublic = json.get("is_public")?.asBoolean ?: false,
            isArchived = json.get("is_archived")?.asBoolean ?: false
        )
    }

    private fun parseTxResult(json: JsonObject): TxResult {
        return TxResult(
            txid = json.get("txid")?.asString ?: "",
            rawHex = json.get("raw_hex")?.asString,
            feeSats = json.get("fee_sats")?.asLong ?: 0,
            estVsize = json.get("est_vsize")?.asInt ?: 0
        )
    }

    // MARK: - Request Helper

    /**
     * Send a request using OwnerSpaceClient.sendAndAwaitResponse() for proper
     * request-response correlation by event_id.
     */
    private suspend fun <T> sendAndAwait(
        messageType: String,
        payload: JsonObject,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        transform: (JsonObject) -> T
    ): Result<T> {
        Log.d(TAG, "Sending $messageType request via OwnerSpaceClient")

        return try {
            val response = ownerSpaceClient.sendAndAwaitResponse(messageType, payload, timeoutMs)

            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        Log.d(TAG, "$messageType response received")
                        try {
                            val parsed = transform(response.result)
                            Result.success(parsed)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse $messageType response", e)
                            Result.failure(NatsException("Failed to parse response: ${e.message}"))
                        }
                    } else {
                        val error = response.error ?: "Request failed"
                        Log.w(TAG, "$messageType failed: $error")
                        Result.failure(NatsException(error))
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "$messageType error: ${response.code} - ${response.message}")
                    Result.failure(NatsException(response.message))
                }
                null -> {
                    Log.e(TAG, "$messageType request timed out")
                    Result.failure(NatsException("Request timed out"))
                }
                else -> {
                    Log.w(TAG, "$messageType unexpected response type: ${response::class.simpleName}")
                    Result.failure(NatsException("Unexpected response type"))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // Rethrow cancellation
        } catch (e: Exception) {
            Log.e(TAG, "$messageType failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}
