package com.vettid.app.features.wallet

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "SendBtcViewModel"

/**
 * State for the send flow.
 */
sealed interface SendState {
    data object Idle : SendState
    data object Loading : SendState
    data class Success(val txid: String) : SendState
    data class Error(val message: String) : SendState
}

/**
 * ViewModel for the Bitcoin send flow.
 *
 * Handles:
 * - Fee estimation from the mempool
 * - Transaction creation and signing (in the enclave)
 * - Transaction broadcast
 * - Loading wallet list for the wallet selector
 */
@HiltViewModel
class SendBtcViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialWalletId: String? = savedStateHandle["walletId"]

    private val gson = Gson()

    private val _wallets = MutableStateFlow<List<WalletInfo>>(emptyList())
    val wallets: StateFlow<List<WalletInfo>> = _wallets.asStateFlow()

    private val _selectedWalletId = MutableStateFlow(initialWalletId ?: "")
    val selectedWalletId: StateFlow<String> = _selectedWalletId.asStateFlow()

    private val _feeEstimates = MutableStateFlow<FeeEstimate?>(null)
    val feeEstimates: StateFlow<FeeEstimate?> = _feeEstimates.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _effects = MutableSharedFlow<SendBtcEffect>()
    val effects: SharedFlow<SendBtcEffect> = _effects.asSharedFlow()

    init {
        loadWallets()
        loadFees()
    }

    private fun loadWallets() {
        viewModelScope.launch {
            try {
                val result = sendAndAwait("wallet.list", JsonObject()) { json ->
                    json.getAsJsonArray("wallets")?.map { item ->
                        val obj = item.asJsonObject
                        WalletInfo(
                            walletId = obj.get("wallet_id")?.asString ?: "",
                            label = obj.get("label")?.asString ?: "",
                            address = obj.get("address")?.asString ?: "",
                            network = obj.get("network")?.asString ?: "mainnet",
                            cachedBalanceSats = obj.get("cached_balance_sats")?.asLong ?: 0L,
                            balanceUpdatedAt = obj.get("balance_updated_at")?.asLong ?: 0L
                        )
                    }?.filter { !it.isArchived } ?: emptyList()
                }

                result.onSuccess { walletList ->
                    _wallets.value = walletList

                    if (_selectedWalletId.value.isBlank() && walletList.size == 1) {
                        _selectedWalletId.value = walletList.first().walletId
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading wallets", e)
            }
        }
    }

    /**
     * Load fee estimates from the mempool via the enclave.
     */
    fun loadFees() {
        viewModelScope.launch {
            try {
                val result = sendAndAwait("wallet.fee-estimates", JsonObject()) { json ->
                    FeeEstimate(
                        fastestFee = json.get("fastest_fee")?.asInt ?: 10,
                        halfHourFee = json.get("half_hour_fee")?.asInt ?: 5,
                        hourFee = json.get("hour_fee")?.asInt ?: 3,
                        economyFee = json.get("economy_fee")?.asInt ?: 1,
                        minimumFee = json.get("minimum_fee")?.asInt ?: 1
                    )
                }

                result.onSuccess { fees ->
                    _feeEstimates.value = fees
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading fee estimates", e)
            }
        }
    }

    fun selectWallet(walletId: String) {
        _selectedWalletId.value = walletId
    }

    /**
     * Send Bitcoin to the specified address.
     *
     * The transaction is signed inside the enclave and broadcast from the vault.
     * The app never sees the private key.
     */
    fun send(walletId: String, toAddress: String, amountSats: Long, feeRate: Int) {
        viewModelScope.launch {
            try {
                _sendState.value = SendState.Loading

                val payload = JsonObject().apply {
                    addProperty("wallet_id", walletId)
                    addProperty("to_address", toAddress)
                    addProperty("amount_sats", amountSats)
                    addProperty("fee_rate", feeRate)
                }

                val result = sendAndAwait("wallet.send", payload) { json ->
                    json.get("txid")?.asString ?: ""
                }

                result.onSuccess { txid ->
                    _sendState.value = SendState.Success(txid)
                    Log.i(TAG, "Transaction sent: $txid")
                }.onFailure { e ->
                    _sendState.value = SendState.Error(e.message ?: "Transaction failed")
                    Log.e(TAG, "Send failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending transaction", e)
                _sendState.value = SendState.Error("Error: ${e.message}")
            }
        }
    }

    fun resetSendState() {
        _sendState.value = SendState.Idle
    }

    /**
     * Send a request to the vault via OwnerSpaceClient and await the response.
     */
    private suspend fun <T> sendAndAwait(
        messageType: String,
        payload: JsonObject,
        timeoutMs: Long = 30_000,
        transform: (JsonObject) -> T
    ): Result<T> {
        Log.d(TAG, "Sending $messageType request via OwnerSpaceClient")

        return try {
            val response = ownerSpaceClient.sendAndAwaitResponse(messageType, payload, timeoutMs)

            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        try {
                            val parsed = transform(response.result)
                            Result.success(parsed)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse $messageType response", e)
                            Result.failure(e)
                        }
                    } else {
                        val error = response.error ?: "Request failed"
                        Log.w(TAG, "$messageType failed: $error")
                        Result.failure(Exception(error))
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "$messageType error: ${response.code} - ${response.message}")
                    Result.failure(Exception(response.message))
                }
                null -> {
                    Log.e(TAG, "$messageType request timed out")
                    Result.failure(Exception("Request timed out"))
                }
                else -> {
                    Log.w(TAG, "$messageType unexpected response: ${response::class.simpleName}")
                    Result.failure(Exception("Unexpected response type"))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$messageType failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}

sealed interface SendBtcEffect {
    data class ShowError(val message: String) : SendBtcEffect
}
