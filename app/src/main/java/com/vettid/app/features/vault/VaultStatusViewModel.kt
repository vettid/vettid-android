package com.vettid.app.features.vault

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.network.*
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for vault status and lifecycle management
 *
 * Handles vault status display, provisioning, start/stop, and health monitoring
 */
private const val TAG = "VaultStatusViewModel"

@HiltViewModel
class VaultStatusViewModel @Inject constructor(
    private val vaultServiceClient: VaultServiceClient,
    private val credentialStore: CredentialStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector
) : ViewModel() {

    private val _state = MutableStateFlow<VaultStatusState>(VaultStatusState.Loading)
    val state: StateFlow<VaultStatusState> = _state.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _effects = MutableSharedFlow<VaultStatusEffect>()
    val effects: SharedFlow<VaultStatusEffect> = _effects.asSharedFlow()

    // Current action token for authenticated operations
    private var currentActionToken: String? = null

    init {
        loadVaultStatus()
    }

    fun onEvent(event: VaultStatusEvent) {
        when (event) {
            is VaultStatusEvent.Refresh -> loadVaultStatus()
            is VaultStatusEvent.StartEnrollment -> startEnrollment()
            is VaultStatusEvent.ProvisionVault -> provisionVault()
            is VaultStatusEvent.StartVault -> startVault()
            is VaultStatusEvent.StopVault -> stopVault()
            is VaultStatusEvent.SyncVault -> syncVault()
            is VaultStatusEvent.TriggerBackup -> triggerBackup()
            is VaultStatusEvent.Retry -> loadVaultStatus()
            is VaultStatusEvent.ViewSettings -> viewSettings()
        }
    }

    /**
     * Set action token after authentication
     */
    fun setActionToken(token: String) {
        currentActionToken = token
    }

    private fun loadVaultStatus() {
        viewModelScope.launch {
            _state.value = VaultStatusState.Loading

            // Check if user is enrolled
            if (!credentialStore.hasStoredCredential()) {
                _state.value = VaultStatusState.NotEnrolled
                return@launch
            }

            // Try to get vault status from server
            val actionToken = currentActionToken
            if (actionToken == null) {
                // No action token - check NATS connection state to determine vault status
                val vaultId = credentialStore.getUserGuid() ?: "unknown"
                if (natsAutoConnector.connectionState.value is NatsAutoConnector.AutoConnectState.Connected) {
                    // NATS connected means vault is running - show Running state with handlers
                    _state.value = VaultStatusState.Running(
                        vaultId = vaultId,
                        instanceId = null,
                        region = null,
                        health = VaultHealth(
                            status = HealthLevel.HEALTHY,
                            memoryUsagePercent = null,
                            diskUsagePercent = null,
                            cpuUsagePercent = null,
                            natsConnected = true,
                            lastChecked = null
                        ),
                        lastBackup = null,
                        lastSync = null
                    )
                    loadHandlers()
                } else {
                    _state.value = VaultStatusState.Enrolled(
                        vaultId = vaultId,
                        enrolledAt = ""
                    )
                }
                return@launch
            }

            val result = vaultServiceClient.getVaultStatus(actionToken)

            result.fold(
                onSuccess = { response ->
                    _state.value = mapResponseToState(response)
                    // If vault is running, fetch handler list from enclave
                    if (_state.value is VaultStatusState.Running) {
                        loadHandlers()
                    }
                    _effects.emit(VaultStatusEffect.StatusUpdated(_state.value))
                },
                onFailure = { error ->
                    val errorCode = when {
                        error is VaultServiceException && error.code == 404 ->
                            VaultErrorCode.NOT_FOUND
                        error is VaultServiceException && error.code == 401 ->
                            VaultErrorCode.NOT_AUTHENTICATED
                        else -> VaultErrorCode.NETWORK_ERROR
                    }

                    // If 404, user might not have a vault yet
                    if (errorCode == VaultErrorCode.NOT_FOUND) {
                        _state.value = VaultStatusState.Enrolled(
                            vaultId = credentialStore.getUserGuid() ?: "unknown",
                            enrolledAt = ""
                        )
                    } else {
                        _state.value = VaultStatusState.Error(
                            message = error.message ?: "Failed to load vault status",
                            code = errorCode,
                            retryable = errorCode != VaultErrorCode.NOT_AUTHENTICATED
                        )
                    }
                }
            )
        }
    }

    private fun mapResponseToState(response: VaultStatusResponse): VaultStatusState {
        return when (response.status) {
            "pending_enrollment" -> VaultStatusState.NotEnrolled

            "enrolled" -> VaultStatusState.Enrolled(
                vaultId = response.vaultId,
                enrolledAt = response.enrolledAt ?: ""
            )

            "provisioning" -> VaultStatusState.Provisioning(
                vaultId = response.vaultId,
                estimatedReadyTime = null,
                progress = 0.5f // Indeterminate
            )

            "running" -> VaultStatusState.Running(
                vaultId = response.vaultId,
                instanceId = response.instanceId,
                region = response.region,
                health = mapHealthResponse(response.health),
                lastBackup = response.lastBackup,
                lastSync = null
            )

            "stopped" -> VaultStatusState.Stopped(
                vaultId = response.vaultId,
                instanceId = response.instanceId,
                lastBackup = response.lastBackup
            )

            "terminated" -> VaultStatusState.Terminated(
                vaultId = response.vaultId,
                terminatedAt = null
            )

            else -> VaultStatusState.Error(
                message = "Unknown vault status: ${response.status}",
                code = VaultErrorCode.UNKNOWN
            )
        }
    }

    private fun mapHealthResponse(health: VaultHealthResponse?): VaultHealth {
        if (health == null) {
            return VaultHealth(
                status = HealthLevel.UNKNOWN,
                memoryUsagePercent = null,
                diskUsagePercent = null,
                cpuUsagePercent = null,
                natsConnected = null,
                lastChecked = null
            )
        }

        return VaultHealth(
            status = when (health.status) {
                "healthy" -> HealthLevel.HEALTHY
                "degraded" -> HealthLevel.DEGRADED
                "unhealthy" -> HealthLevel.UNHEALTHY
                else -> HealthLevel.UNKNOWN
            },
            memoryUsagePercent = health.memoryUsagePercent,
            diskUsagePercent = health.diskUsagePercent,
            cpuUsagePercent = health.cpuUsagePercent,
            natsConnected = health.natsConnected,
            lastChecked = health.lastChecked
        )
    }

    private fun startEnrollment() {
        viewModelScope.launch {
            _effects.emit(VaultStatusEffect.NavigateToEnrollment)
        }
    }

    private fun provisionVault() {
        viewModelScope.launch {
            val actionToken = currentActionToken
            if (actionToken == null) {
                _effects.emit(VaultStatusEffect.RequireAuth("vault_provision"))
                return@launch
            }

            val currentState = _state.value
            if (currentState !is VaultStatusState.Enrolled) return@launch

            _state.value = VaultStatusState.Provisioning(
                vaultId = currentState.vaultId,
                progress = 0.1f
            )

            val result = vaultServiceClient.provisionVault(actionToken)

            result.fold(
                onSuccess = { response ->
                    _state.value = VaultStatusState.Provisioning(
                        vaultId = response.vaultId,
                        estimatedReadyTime = response.estimatedReadyTime,
                        progress = 0.3f
                    )
                    _effects.emit(VaultStatusEffect.ShowSuccess("Vault provisioning started"))
                },
                onFailure = { error ->
                    _state.value = VaultStatusState.Error(
                        message = error.message ?: "Failed to provision vault",
                        code = VaultErrorCode.PROVISION_FAILED,
                        retryable = true
                    )
                }
            )
        }
    }

    private fun startVault() {
        viewModelScope.launch {
            val actionToken = currentActionToken
            if (actionToken == null) {
                _effects.emit(VaultStatusEffect.RequireAuth("vault_start"))
                return@launch
            }

            val currentState = _state.value
            if (currentState !is VaultStatusState.Stopped) return@launch

            // Optimistically show provisioning state
            _state.value = VaultStatusState.Provisioning(
                vaultId = currentState.vaultId,
                progress = 0.1f
            )

            val result = vaultServiceClient.startVault(actionToken)

            result.fold(
                onSuccess = {
                    _effects.emit(VaultStatusEffect.ShowSuccess("Vault starting..."))
                    // Refresh to get actual status
                    loadVaultStatus()
                },
                onFailure = { error ->
                    _state.value = currentState // Restore previous state
                    _effects.emit(VaultStatusEffect.ShowError(
                        error.message ?: "Failed to start vault"
                    ))
                }
            )
        }
    }

    private fun stopVault() {
        viewModelScope.launch {
            val actionToken = currentActionToken
            if (actionToken == null) {
                _effects.emit(VaultStatusEffect.RequireAuth("vault_stop"))
                return@launch
            }

            val currentState = _state.value
            if (currentState !is VaultStatusState.Running) return@launch

            val result = vaultServiceClient.stopVault(actionToken)

            result.fold(
                onSuccess = {
                    _state.value = VaultStatusState.Stopped(
                        vaultId = currentState.vaultId,
                        instanceId = currentState.instanceId,
                        lastBackup = currentState.lastBackup
                    )
                    _effects.emit(VaultStatusEffect.ShowSuccess("Vault stopped"))
                },
                onFailure = { error ->
                    _effects.emit(VaultStatusEffect.ShowError(
                        error.message ?: "Failed to stop vault"
                    ))
                }
            )
        }
    }

    private fun syncVault() {
        viewModelScope.launch {
            val actionToken = currentActionToken
            if (actionToken == null) {
                _effects.emit(VaultStatusEffect.RequireAuth("vault_sync"))
                return@launch
            }

            _syncStatus.value = SyncStatus.Syncing

            // Refresh vault status and health
            val result = vaultServiceClient.getVaultStatus(actionToken)

            result.fold(
                onSuccess = { response ->
                    _state.value = mapResponseToState(response)
                    _syncStatus.value = SyncStatus.Success(
                        timestamp = java.time.Instant.now().toString()
                    )
                    _effects.emit(VaultStatusEffect.StatusUpdated(_state.value))
                },
                onFailure = { error ->
                    _syncStatus.value = SyncStatus.Failed(
                        error = error.message ?: "Sync failed"
                    )
                }
            )
        }
    }

    private fun triggerBackup() {
        viewModelScope.launch {
            val actionToken = currentActionToken
            if (actionToken == null) {
                _effects.emit(VaultStatusEffect.RequireAuth("vault_backup"))
                return@launch
            }

            val result = vaultServiceClient.triggerBackup(actionToken)

            result.fold(
                onSuccess = { response ->
                    _effects.emit(VaultStatusEffect.ShowSuccess(
                        "Backup started: ${response.backupId}"
                    ))
                },
                onFailure = { error ->
                    _effects.emit(VaultStatusEffect.ShowError(
                        error.message ?: "Failed to start backup"
                    ))
                }
            )
        }
    }

    private fun viewSettings() {
        viewModelScope.launch {
            _effects.emit(VaultStatusEffect.NavigateToSettings)
        }
    }

    fun saveVaultState() {
        viewModelScope.launch {
            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "vault.save",
                    payload = com.google.gson.JsonObject()
                )
                when (response) {
                    is com.vettid.app.core.nats.VaultResponse.HandlerResult -> {
                        if (response.success) {
                            _effects.emit(VaultStatusEffect.ShowSuccess("Vault state saved to S3"))
                        } else {
                            _effects.emit(VaultStatusEffect.ShowError(response.error ?: "Failed to save"))
                        }
                    }
                    is com.vettid.app.core.nats.VaultResponse.Error -> {
                        _effects.emit(VaultStatusEffect.ShowError(response.message))
                    }
                    else -> {
                        _effects.emit(VaultStatusEffect.ShowError("Unexpected response"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save vault state", e)
                _effects.emit(VaultStatusEffect.ShowError(e.message ?: "Failed to save"))
            }
        }
    }

    private fun loadHandlers() {
        val currentState = _state.value
        if (currentState !is VaultStatusState.Running) return

        _state.value = currentState.copy(handlersLoading = true)

        viewModelScope.launch {
            try {
                val handlers = ownerSpaceClient.listHandlers()
                val latest = _state.value
                if (latest is VaultStatusState.Running) {
                    _state.value = latest.copy(
                        handlers = handlers,
                        handlersLoading = false
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load handlers from enclave", e)
                val latest = _state.value
                if (latest is VaultStatusState.Running) {
                    _state.value = latest.copy(handlersLoading = false)
                }
            }
        }
    }

    /**
     * Check if vault needs attention (for home screen indicator)
     */
    fun needsAttention(): Boolean {
        return when (val state = _state.value) {
            is VaultStatusState.Running -> state.health.status != HealthLevel.HEALTHY
            is VaultStatusState.Error -> true
            else -> false
        }
    }

    /**
     * Get a summary for the home screen
     */
    fun getStatusSummary(): VaultStatusSummary {
        return when (val state = _state.value) {
            is VaultStatusState.Loading -> VaultStatusSummary(
                title = "Loading...",
                subtitle = null,
                icon = VaultStatusIcon.LOADING,
                actionLabel = null
            )
            is VaultStatusState.NotEnrolled -> VaultStatusSummary(
                title = "Not Set Up",
                subtitle = "Set up your vault to get started",
                icon = VaultStatusIcon.NOT_ENROLLED,
                actionLabel = "Set Up Vault"
            )
            is VaultStatusState.Enrolled -> VaultStatusSummary(
                title = "Enrolled",
                subtitle = "Ready to provision",
                icon = VaultStatusIcon.ENROLLED,
                actionLabel = "Provision Vault"
            )
            is VaultStatusState.Provisioning -> VaultStatusSummary(
                title = "Provisioning",
                subtitle = "Starting your vault...",
                icon = VaultStatusIcon.PROVISIONING,
                actionLabel = null
            )
            is VaultStatusState.Running -> VaultStatusSummary(
                title = "Running",
                subtitle = getHealthSubtitle(state.health),
                icon = getHealthIcon(state.health),
                actionLabel = "View Details"
            )
            is VaultStatusState.Stopped -> VaultStatusSummary(
                title = "Stopped",
                subtitle = "Tap to start",
                icon = VaultStatusIcon.STOPPED,
                actionLabel = "Start Vault"
            )
            is VaultStatusState.Terminated -> VaultStatusSummary(
                title = "Terminated",
                subtitle = "Vault has been terminated",
                icon = VaultStatusIcon.TERMINATED,
                actionLabel = null
            )
            is VaultStatusState.Error -> VaultStatusSummary(
                title = "Error",
                subtitle = state.message,
                icon = VaultStatusIcon.ERROR,
                actionLabel = if (state.retryable) "Retry" else null
            )
        }
    }

    private fun getHealthSubtitle(health: VaultHealth): String {
        return when (health.status) {
            HealthLevel.HEALTHY -> "All systems operational"
            HealthLevel.DEGRADED -> "Performance degraded"
            HealthLevel.UNHEALTHY -> "Needs attention"
            HealthLevel.UNKNOWN -> "Status unknown"
        }
    }

    private fun getHealthIcon(health: VaultHealth): VaultStatusIcon {
        return when (health.status) {
            HealthLevel.HEALTHY -> VaultStatusIcon.HEALTHY
            HealthLevel.DEGRADED -> VaultStatusIcon.DEGRADED
            HealthLevel.UNHEALTHY -> VaultStatusIcon.UNHEALTHY
            HealthLevel.UNKNOWN -> VaultStatusIcon.UNKNOWN
        }
    }
}

/**
 * Summary for home screen display
 */
data class VaultStatusSummary(
    val title: String,
    val subtitle: String?,
    val icon: VaultStatusIcon,
    val actionLabel: String?
)

/**
 * Icons for vault status display
 */
enum class VaultStatusIcon {
    LOADING,
    NOT_ENROLLED,
    ENROLLED,
    PROVISIONING,
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    UNKNOWN,
    STOPPED,
    TERMINATED,
    ERROR
}
