package com.vettid.app.features.vault

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val installedHandlerCount: Int = 4,
    val availableHandlerCount: Int = 2,
    val archiveAfterDays: Int = 7,
    val deleteAfterDays: Int = 30,
    val isLoading: Boolean = false,
    // Vault server state
    val vaultServerStatus: VaultServerStatus = VaultServerStatus.UNKNOWN,
    val vaultInstanceId: String? = null,
    val vaultInstanceIp: String? = null,
    val natsEndpoint: String? = null,
    val vaultActionInProgress: Boolean = false,
    val vaultErrorMessage: String? = null
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
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _state = MutableStateFlow(VaultPreferencesState())
    val state: StateFlow<VaultPreferencesState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VaultPreferencesEffect>()
    val effects: SharedFlow<VaultPreferencesEffect> = _effects.asSharedFlow()

    init {
        loadPreferences()
        // With Nitro Enclave, vault is always ready - no need to poll status
        setEnclaveReady()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            try {
                // In production, load from vault storage
                // For now, use defaults
                _state.value = VaultPreferencesState(
                    sessionTtlMinutes = 15,
                    installedHandlerCount = 4,  // Core handlers
                    availableHandlerCount = 3,   // File sharing, voice, video
                    archiveAfterDays = 7,
                    deleteAfterDays = 30
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load preferences", e)
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
