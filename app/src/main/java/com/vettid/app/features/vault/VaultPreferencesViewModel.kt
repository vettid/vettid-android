package com.vettid.app.features.vault

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.network.VaultLifecycleClient
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "VaultPreferencesViewModel"

/**
 * Vault server status.
 */
enum class VaultServerStatus {
    UNKNOWN,
    LOADING,
    RUNNING,
    STOPPED,
    STARTING,
    STOPPING,
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
    private val credentialStore: CredentialStore,
    private val vaultLifecycleClient: VaultLifecycleClient
) : ViewModel() {

    private val _state = MutableStateFlow(VaultPreferencesState())
    val state: StateFlow<VaultPreferencesState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VaultPreferencesEffect>()
    val effects: SharedFlow<VaultPreferencesEffect> = _effects.asSharedFlow()

    init {
        loadPreferences()
        refreshVaultStatus()
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

    // MARK: - Vault Lifecycle

    fun refreshVaultStatus() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                vaultServerStatus = VaultServerStatus.LOADING,
                vaultErrorMessage = null
            )

            vaultLifecycleClient.getVaultStatus()
                .onSuccess { response ->
                    val status = when {
                        response.isVaultRunning -> VaultServerStatus.RUNNING
                        response.isVaultStopped -> VaultServerStatus.STOPPED
                        response.isVaultPending -> VaultServerStatus.PENDING
                        else -> VaultServerStatus.UNKNOWN
                    }
                    _state.value = _state.value.copy(
                        vaultServerStatus = status,
                        vaultInstanceId = response.instanceId,
                        vaultInstanceIp = response.instanceIp,
                        natsEndpoint = response.natsEndpoint,
                        vaultErrorMessage = null
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to get vault status", error)
                    _state.value = _state.value.copy(
                        vaultServerStatus = VaultServerStatus.ERROR,
                        vaultErrorMessage = error.message
                    )
                }
        }
    }

    fun startVault() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                vaultActionInProgress = true,
                vaultServerStatus = VaultServerStatus.STARTING,
                vaultErrorMessage = null
            )

            vaultLifecycleClient.startVault()
                .onSuccess { response ->
                    Log.i(TAG, "Vault start response: ${response.status}")
                    _effects.emit(VaultPreferencesEffect.ShowSuccess(
                        if (response.isAlreadyRunning) "Vault is already running"
                        else "Vault is starting..."
                    ))
                    // Refresh status after a short delay to get updated info
                    kotlinx.coroutines.delay(2000)
                    refreshVaultStatus()
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to start vault", error)
                    _state.value = _state.value.copy(
                        vaultServerStatus = VaultServerStatus.ERROR,
                        vaultErrorMessage = error.message
                    )
                    _effects.emit(VaultPreferencesEffect.ShowError(
                        "Failed to start vault: ${error.message}"
                    ))
                }

            _state.value = _state.value.copy(vaultActionInProgress = false)
        }
    }

    fun stopVault() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                vaultActionInProgress = true,
                vaultServerStatus = VaultServerStatus.STOPPING,
                vaultErrorMessage = null
            )

            vaultLifecycleClient.stopVault()
                .onSuccess { response ->
                    Log.i(TAG, "Vault stop response: ${response.status}")
                    _effects.emit(VaultPreferencesEffect.ShowSuccess(
                        if (response.isAlreadyStopped) "Vault is already stopped"
                        else "Vault is stopping..."
                    ))
                    // Refresh status after a short delay
                    kotlinx.coroutines.delay(2000)
                    refreshVaultStatus()
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to stop vault", error)
                    _state.value = _state.value.copy(
                        vaultServerStatus = VaultServerStatus.ERROR,
                        vaultErrorMessage = error.message
                    )
                    _effects.emit(VaultPreferencesEffect.ShowError(
                        "Failed to stop vault: ${error.message}"
                    ))
                }

            _state.value = _state.value.copy(vaultActionInProgress = false)
        }
    }
}
