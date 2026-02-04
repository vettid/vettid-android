package com.vettid.app.features.vault

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.NatsConnectionState
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.network.HandlerSummary
import com.vettid.app.core.network.InstalledHandler
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "VaultPreferencesViewModel"

/**
 * Vault server status.
 *
 * With Nitro Enclave architecture, vault is always ENCLAVE_READY.
 * The old EC2-based statuses (STOPPED, STARTING, STOPPING) are deprecated.
 */
enum class VaultServerStatus {
    UNKNOWN,
    LOADING,
    ENCLAVE_READY,  // New: Nitro Enclave is always available
    RUNNING,        // Legacy: EC2 instance running
    STOPPED,        // Legacy: EC2 instance stopped
    STARTING,       // Legacy: EC2 instance starting
    STOPPING,       // Legacy: EC2 instance stopping
    PENDING,
    ERROR
}

/**
 * State for vault preferences.
 */
data class VaultPreferencesState(
    val sessionTtlMinutes: Int = 15,
    val installedHandlerCount: Int = 0,
    val availableHandlerCount: Int = 0,
    val archiveAfterDays: Int = 7,
    val deleteAfterDays: Int = 30,
    val isLoading: Boolean = false,
    // Vault server state
    val vaultServerStatus: VaultServerStatus = VaultServerStatus.UNKNOWN,
    val vaultInstanceId: String? = null,
    val vaultInstanceIp: String? = null,
    val natsEndpoint: String? = null,
    val vaultActionInProgress: Boolean = false,
    val vaultErrorMessage: String? = null,
    // Offline mode
    val isOfflineMode: Boolean = false,
    // PCR attestation info
    val pcrVersion: String? = null,
    val pcr0Hash: String? = null,
    val enrollmentPcrVersion: String? = null,
    // Handlers
    val installedHandlers: List<InstalledHandler> = emptyList(),
    val availableHandlers: List<HandlerSummary> = emptyList(),
    val handlersLoading: Boolean = false,
    val handlersError: String? = null
)

/**
 * Effects emitted by the vault preferences view model.
 */
sealed class VaultPreferencesEffect {
    data class ShowSuccess(val message: String) : VaultPreferencesEffect()
    data class ShowError(val message: String) : VaultPreferencesEffect()
    object NavigateToHandlers : VaultPreferencesEffect()
    object NavigateToChangePassword : VaultPreferencesEffect()
}

@HiltViewModel
class VaultPreferencesViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager
) : ViewModel() {

    private val _state = MutableStateFlow(VaultPreferencesState())
    val state: StateFlow<VaultPreferencesState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VaultPreferencesEffect>()
    val effects: SharedFlow<VaultPreferencesEffect> = _effects.asSharedFlow()

    init {
        loadPreferences()
        // With Nitro Enclave, vault is always ready - no need to poll status
        setEnclaveReady()
        // Load handlers (will auto-retry when NATS connects)
        loadHandlers()
        // Observe connection state to auto-reload handlers when NATS connects
        observeConnectionState()
    }

    /**
     * Observe NATS connection state and auto-reload handlers when connected.
     */
    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                if (state is NatsConnectionState.Connected) {
                    // Only reload if handlers haven't been loaded yet or had an error
                    val currentState = _state.value
                    if (currentState.installedHandlers.isEmpty() || currentState.handlersError != null) {
                        Log.d(TAG, "NATS connected - auto-loading handlers")
                        loadHandlers()
                    }
                }
            }
        }
    }

    /**
     * Categorize a handler based on its ID prefix.
     */
    private fun categorizeHandler(handlerId: String): String {
        return when {
            handlerId.startsWith("profile.") -> "profile"
            handlerId.startsWith("secrets.") -> "storage"
            handlerId.startsWith("connections.") -> "connections"
            handlerId.startsWith("messaging.") -> "messaging"
            handlerId.startsWith("feed.") -> "feed"
            handlerId.startsWith("credential.") -> "security"
            handlerId.startsWith("app.") -> "authentication"
            else -> "other"
        }
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            try {
                // Load offline mode preference
                val isOffline = credentialStore.getOfflineMode()
                // Load PCR attestation info
                val pcrVersion = credentialStore.getCurrentPcrVersion()
                val pcr0Hash = credentialStore.getEnrollmentPcr0Hash()
                val enrollmentPcrVersion = credentialStore.getEnrollmentPcrVersion()

                _state.value = VaultPreferencesState(
                    sessionTtlMinutes = 15,
                    archiveAfterDays = 7,
                    deleteAfterDays = 30,
                    isOfflineMode = isOffline,
                    pcrVersion = pcrVersion,
                    pcr0Hash = pcr0Hash,
                    enrollmentPcrVersion = enrollmentPcrVersion
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load preferences", e)
            }
        }
    }

    /**
     * Load installed handlers from the enclave via NATS.
     * Note: Handler info is only available when connected to the vault.
     */
    fun loadHandlers() {
        viewModelScope.launch {
            // Check if we're in offline mode
            if (_state.value.isOfflineMode) {
                _state.value = _state.value.copy(
                    handlersLoading = false,
                    handlersError = "Handler info requires vault connection"
                )
                return@launch
            }

            // Check if NATS is connected
            if (!connectionManager.isConnected()) {
                Log.d(TAG, "NATS not connected yet, will auto-retry when connected")
                _state.value = _state.value.copy(
                    handlersLoading = false,
                    handlersError = "Waiting for vault connection..."
                )
                return@launch
            }

            _state.value = _state.value.copy(handlersLoading = true, handlersError = null)

            // Request event types (handlers) from enclave via NATS
            Log.d(TAG, "Requesting event types from enclave via NATS")

            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "getEventTypes",
                    payload = JsonObject(),
                    timeoutMs = 10000L
                )

                when (response) {
                    is VaultResponse.EventTypesResponse -> {
                        Log.d(TAG, "Received ${response.eventTypes.size} event types from enclave")
                        val handlers = response.eventTypes.map { eventType ->
                            InstalledHandler(
                                id = eventType.id,
                                name = eventType.name,
                                version = "1.0",
                                category = categorizeHandler(eventType.id),
                                iconUrl = null,
                                installedAt = "",
                                lastExecutedAt = null,
                                executionCount = 0,
                                enabled = true,
                                hasUpdate = false,
                                latestVersion = null
                            )
                        }
                        _state.value = _state.value.copy(
                            installedHandlers = handlers,
                            installedHandlerCount = handlers.size,
                            handlersLoading = false,
                            handlersError = null
                        )
                    }
                    is VaultResponse.Error -> {
                        Log.e(TAG, "Event types request failed: ${response.message}")
                        _state.value = _state.value.copy(
                            handlersLoading = false,
                            handlersError = response.message
                        )
                    }
                    null -> {
                        Log.w(TAG, "Event types request timed out")
                        _state.value = _state.value.copy(
                            handlersLoading = false,
                            handlersError = "Request timed out - check vault connection"
                        )
                    }
                    else -> {
                        Log.w(TAG, "Unexpected response type: ${response::class.simpleName}")
                        _state.value = _state.value.copy(
                            handlersLoading = false,
                            handlersError = "Unexpected response from vault"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request event types: ${e.message}", e)
                _state.value = _state.value.copy(
                    handlersLoading = false,
                    handlersError = e.message ?: "Failed to load handlers"
                )
            }
        }
    }

    fun updateSessionTtl(minutes: Int) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(sessionTtlMinutes = minutes)
                // In production, persist to vault storage
                _effects.emit(VaultPreferencesEffect.ShowSuccess("Session TTL updated"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update TTL", e)
                _effects.emit(VaultPreferencesEffect.ShowError("Failed to update TTL"))
            }
        }
    }

    fun updateArchiveAfterDays(days: Int) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(archiveAfterDays = days)
                _effects.emit(VaultPreferencesEffect.ShowSuccess("Archive setting updated"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update archive setting", e)
                _effects.emit(VaultPreferencesEffect.ShowError("Failed to update setting"))
            }
        }
    }

    fun updateDeleteAfterDays(days: Int) {
        viewModelScope.launch {
            try {
                // Ensure delete is always >= archive
                val archiveAfter = _state.value.archiveAfterDays
                if (days < archiveAfter) {
                    _effects.emit(VaultPreferencesEffect.ShowError(
                        "Delete time must be greater than archive time"
                    ))
                    return@launch
                }
                _state.value = _state.value.copy(deleteAfterDays = days)
                _effects.emit(VaultPreferencesEffect.ShowSuccess("Delete setting updated"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update delete setting", e)
                _effects.emit(VaultPreferencesEffect.ShowError("Failed to update setting"))
            }
        }
    }

    fun onManageHandlersClick() {
        viewModelScope.launch {
            _effects.emit(VaultPreferencesEffect.NavigateToHandlers)
        }
    }

    fun onChangePasswordClick() {
        viewModelScope.launch {
            _effects.emit(VaultPreferencesEffect.NavigateToChangePassword)
        }
    }

    // MARK: - Vault Status (Nitro Enclave)

    /**
     * Set vault status to ENCLAVE_READY.
     * With Nitro architecture, the enclave is always available.
     */
    private fun setEnclaveReady() {
        _state.value = _state.value.copy(
            vaultServerStatus = VaultServerStatus.ENCLAVE_READY,
            vaultErrorMessage = null
        )
    }

    /**
     * Refresh vault status.
     * With Nitro architecture, this just confirms the enclave is ready.
     */
    fun refreshVaultStatus() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                vaultServerStatus = VaultServerStatus.LOADING,
                vaultErrorMessage = null
            )

            // With Nitro Enclave, vault is always ready
            kotlinx.coroutines.delay(500) // Brief delay for UX
            setEnclaveReady()
        }
    }

    /**
     * Start vault - deprecated with Nitro architecture.
     * The enclave is always running and doesn't need to be started.
     */
    @Deprecated("Nitro Enclave is always running. This method has no effect.")
    fun startVault() {
        viewModelScope.launch {
            _effects.emit(VaultPreferencesEffect.ShowSuccess("Vault is always available with Nitro Enclave"))
        }
    }

    /**
     * Stop vault - deprecated with Nitro architecture.
     * Cannot stop the shared enclave.
     */
    @Deprecated("Nitro Enclave cannot be stopped. This method has no effect.")
    fun stopVault() {
        viewModelScope.launch {
            _effects.emit(VaultPreferencesEffect.ShowError("Cannot stop shared Nitro Enclave"))
        }
    }
}
