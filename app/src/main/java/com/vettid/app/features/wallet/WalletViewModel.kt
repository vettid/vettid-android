package com.vettid.app.features.wallet

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.google.gson.Gson
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "WalletViewModel"

/**
 * ViewModel for managing the wallet list.
 *
 * Communicates with the vault via NATS OwnerSpaceClient to:
 * - List wallets stored in the vault
 * - Create new wallets (key generation happens in the enclave)
 * - Refresh balances from the blockchain
 *
 * All private key material stays in the enclave; the app only sees
 * addresses, labels, and cached balance information.
 */
@HiltViewModel
class WalletViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector
) : ViewModel() {

    private val gson = Gson()

    private val _wallets = MutableStateFlow<List<WalletInfo>>(emptyList())
    val wallets: StateFlow<List<WalletInfo>> = _wallets.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _effects = MutableSharedFlow<WalletEffect>()
    val effects: SharedFlow<WalletEffect> = _effects.asSharedFlow()

    init {
        loadWallets()
    }

    /**
     * Load the wallet list from the vault.
     */
    fun loadWallets() {
        viewModelScope.launch {
            try {
                _isLoading.value = _wallets.value.isEmpty()
                _isRefreshing.value = _wallets.value.isNotEmpty()

                val result = sendAndAwait("wallet.list", JsonObject()) { json ->
                    val walletsJson = json.getAsJsonArray("wallets")
                    walletsJson?.map { item ->
                        val obj = item.asJsonObject
                        WalletInfo(
                            walletId = obj.get("wallet_id")?.asString ?: "",
                            label = obj.get("label")?.asString ?: "",
                            address = obj.get("address")?.asString ?: "",
                            network = obj.get("network")?.asString ?: "mainnet",
                            cachedBalanceSats = obj.get("cached_balance_sats")?.asLong ?: 0L,
                            balanceUpdatedAt = obj.get("balance_updated_at")?.asLong ?: 0L,
                            isPublic = obj.get("is_public")?.asBoolean ?: false,
                            isArchived = obj.get("is_archived")?.asBoolean ?: false
                        )
                    } ?: emptyList()
                }

                result.onSuccess { walletList ->
                    _wallets.value = walletList.filter { !it.isArchived }
                    Log.i(TAG, "Loaded ${walletList.size} wallets")
                }.onFailure { e ->
                    Log.e(TAG, "Failed to load wallets: ${e.message}")
                    _effects.emit(WalletEffect.ShowError("Failed to load wallets"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading wallets", e)
                _effects.emit(WalletEffect.ShowError("Error loading wallets: ${e.message}"))
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Create a new Bitcoin wallet in the enclave.
     *
     * The private key is generated and stored inside the enclave.
     * Only the address and metadata are returned to the app.
     */
    fun createWallet(label: String, network: String = "mainnet") {
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("label", label)
                    addProperty("network", network)
                }

                val result = sendAndAwait("wallet.create", payload) { json ->
                    WalletInfo(
                        walletId = json.get("wallet_id")?.asString ?: "",
                        label = label,
                        address = json.get("address")?.asString ?: "",
                        network = network
                    )
                }

                result.onSuccess { walletInfo ->
                    _wallets.value = _wallets.value + walletInfo
                    Log.i(TAG, "Created wallet: ${walletInfo.walletId}")
                    _effects.emit(WalletEffect.WalletCreated(walletInfo))
                }.onFailure { e ->
                    Log.e(TAG, "Failed to create wallet: ${e.message}")
                    _effects.emit(WalletEffect.ShowError("Failed to create wallet"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating wallet", e)
                _effects.emit(WalletEffect.ShowError("Error creating wallet: ${e.message}"))
            }
        }
    }

    /**
     * Refresh the balance for a single wallet from the blockchain.
     */
    fun refreshBalance(walletId: String) {
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("wallet_id", walletId)
                }

                val result = sendAndAwait("wallet.balance", payload) { json ->
                    val confirmedSats = json.get("confirmed_sats")?.asLong ?: 0L
                    val unconfirmedSats = json.get("unconfirmed_sats")?.asLong ?: 0L
                    val totalSats = json.get("total_sats")?.asLong ?: (confirmedSats + unconfirmedSats)
                    totalSats
                }

                result.onSuccess { totalSats ->
                    _wallets.value = _wallets.value.map { wallet ->
                        if (wallet.walletId == walletId) {
                            wallet.copy(
                                cachedBalanceSats = totalSats,
                                balanceUpdatedAt = System.currentTimeMillis()
                            )
                        } else wallet
                    }
                    Log.i(TAG, "Refreshed balance for $walletId: $totalSats sats")
                }.onFailure { e ->
                    Log.e(TAG, "Failed to refresh balance: ${e.message}")
                    _effects.emit(WalletEffect.ShowError("Failed to refresh balance"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing balance", e)
                _effects.emit(WalletEffect.ShowError("Error refreshing balance: ${e.message}"))
            }
        }
    }

    /**
     * Toggle a wallet's public visibility.
     */
    fun togglePublicVisibility(walletId: String, isPublic: Boolean) {
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("wallet_id", walletId)
                    addProperty("is_public", isPublic)
                }

                val result = sendAndAwait("wallet.set-visibility", payload) { json ->
                    json.get("success")?.asBoolean ?: true
                }

                result.onSuccess {
                    _wallets.value = _wallets.value.map { wallet ->
                        if (wallet.walletId == walletId) wallet.copy(isPublic = isPublic)
                        else wallet
                    }
                }.onFailure {
                    _effects.emit(WalletEffect.ShowError("Failed to update visibility"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling visibility", e)
                _effects.emit(WalletEffect.ShowError("Error updating visibility: ${e.message}"))
            }
        }
    }

    /**
     * Send a request to the vault via OwnerSpaceClient and await the response.
     *
     * Follows the same pattern as ConnectionsClient.sendAndAwait() for proper
     * request-response correlation by event_id.
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
                        Log.d(TAG, "$messageType response received")
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
                    Log.w(TAG, "$messageType unexpected response type: ${response::class.simpleName}")
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

/**
 * Side effects emitted by WalletViewModel.
 */
sealed interface WalletEffect {
    data class ShowError(val message: String) : WalletEffect
    data class ShowSuccess(val message: String) : WalletEffect
    data class WalletCreated(val wallet: WalletInfo) : WalletEffect
}
