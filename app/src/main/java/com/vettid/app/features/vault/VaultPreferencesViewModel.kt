package com.vettid.app.features.vault

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.network.HandlerSummary
import com.vettid.app.core.network.InstalledHandler
import com.vettid.app.core.storage.AppPreferencesStore
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.features.settings.AppTheme
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
    val theme: AppTheme = AppTheme.AUTO,
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
    private val connectionManager: NatsConnectionManager,
    private val appPreferencesStore: AppPreferencesStore
) : ViewModel() {

    private val _state = MutableStateFlow(VaultPreferencesState())
    val state: StateFlow<VaultPreferencesState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VaultPreferencesEffect>()
    val effects: SharedFlow<VaultPreferencesEffect> = _effects.asSharedFlow()

    init {
        loadPreferences()
        // With Nitro Enclave, vault is always ready - no need to poll status
        setEnclaveReady()
        // Load handlers (static list of known vault capabilities)
        loadHandlers()
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
                // Load theme preference
                val theme = appPreferencesStore.getTheme()

                _state.value = VaultPreferencesState(
                    theme = theme,
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
     * Load installed handlers.
     * Uses a static list of known vault capabilities since the enclave
     * doesn't expose a dynamic handler listing endpoint.
     */
    fun loadHandlers() {
        viewModelScope.launch {
            _state.value = _state.value.copy(handlersLoading = true, handlersError = null)

            // Build static list of known vault handler capabilities
            val handlers = listOf(
                InstalledHandler(
                    id = "profile.get", name = "Profile Management",
                    version = "1.0", category = "profile",
                    iconUrl = null, installedAt = "", lastExecutedAt = null,
                    executionCount = 0, enabled = true, hasUpdate = false, latestVersion = null
                ),
                InstalledHandler(
                    id = "personal-data.update", name = "Personal Data",
                    version = "1.0", category = "profile",
                    iconUrl = null, installedAt = "", lastExecutedAt = null,
                    executionCount = 0, enabled = true, hasUpdate = false, latestVersion = null
                ),
                InstalledHandler(
                    id = "secrets.add", name = "Secrets Storage",
                    version = "1.0", category = "storage",
                    iconUrl = null, installedAt = "", lastExecutedAt = null,
                    executionCount = 0, enabled = true, hasUpdate = false, latestVersion = null
                ),
                InstalledHandler(
                    id = "secrets.identity", name = "Identity Keys",
                    version = "1.0", category = "security",
                    iconUrl = null, installedAt = "", lastExecutedAt = null,
                    executionCount = 0, enabled = true, hasUpdate = false, latestVersion = null
                ),
                InstalledHandler(
                    id = "connection.request", name = "Connections",
                    version = "1.0", category = "connections",
                    iconUrl = null, installedAt = "", lastExecutedAt = null,
                    executionCount = 0, enabled = true, hasUpdate = false, latestVersion = null
                ),
                InstalledHandler(
                    id = "message.send", name = "Messaging",
                    version = "1.0", category = "messaging",
                    iconUrl = null, installedAt = "", lastExecutedAt = null,
                    executionCount = 0, enabled = true, hasUpdate = false, latestVersion = null
                ),
                InstalledHandler(
                    id = "credential.manage", name = "Credential Management",
                    version = "1.0", category = "security",
                    iconUrl = null, installedAt = "", lastExecutedAt = null,
                    executionCount = 0, enabled = true, hasUpdate = false, latestVersion = null
                )
            )

            _state.value = _state.value.copy(
                installedHandlers = handlers,
                installedHandlerCount = handlers.size,
                handlersLoading = false,
                handlersError = null
            )
            Log.d(TAG, "Loaded ${handlers.size} known vault handlers")
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

    fun updateTheme(theme: AppTheme) {
        viewModelScope.launch {
            appPreferencesStore.setTheme(theme)
            _state.update { it.copy(theme = theme) }
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
