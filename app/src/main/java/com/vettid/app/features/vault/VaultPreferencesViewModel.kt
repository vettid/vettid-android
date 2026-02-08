package com.vettid.app.features.vault

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.network.BackupApiClient
import com.vettid.app.core.network.BackupSettings
import com.vettid.app.core.storage.AppPreferencesStore
import com.vettid.app.core.storage.CredentialStore
import android.content.Context
import com.vettid.app.features.location.DisplacementThreshold
import com.vettid.app.features.location.LocationCollectionWorker
import com.vettid.app.features.location.LocationPrecision
import com.vettid.app.features.location.LocationRetention
import com.vettid.app.features.location.LocationUpdateFrequency
import com.vettid.app.features.settings.AppTheme
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    // Backup
    val backupEnabled: Boolean = true,
    // Location tracking
    val locationTrackingEnabled: Boolean = false,
    val locationPrecision: LocationPrecision = LocationPrecision.EXACT,
    val locationFrequency: LocationUpdateFrequency = LocationUpdateFrequency.THIRTY_MINUTES,
    val locationDisplacementThreshold: DisplacementThreshold = DisplacementThreshold.ONE_HUNDRED,
    val locationRetention: LocationRetention = LocationRetention.THIRTY_DAYS,
    val hasLocationPermission: Boolean = false
)

/**
 * Effects emitted by the vault preferences view model.
 */
sealed class VaultPreferencesEffect {
    data class ShowSuccess(val message: String) : VaultPreferencesEffect()
    data class ShowError(val message: String) : VaultPreferencesEffect()
    object NavigateToChangePassword : VaultPreferencesEffect()
    object NavigateToLocationHistory : VaultPreferencesEffect()
    object NavigateToSharedLocations : VaultPreferencesEffect()
    object NavigateToLocationSettings : VaultPreferencesEffect()
    data class RequestLocationPermission(val precision: LocationPrecision) : VaultPreferencesEffect()
}

@HiltViewModel
class VaultPreferencesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val credentialStore: CredentialStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager,
    private val appPreferencesStore: AppPreferencesStore,
    private val cryptoManager: CryptoManager,
    private val backupApiClient: BackupApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(VaultPreferencesState())
    val state: StateFlow<VaultPreferencesState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VaultPreferencesEffect>()
    val effects: SharedFlow<VaultPreferencesEffect> = _effects.asSharedFlow()

    init {
        loadPreferences()
        // With Nitro Enclave, vault is always ready - no need to poll status
        setEnclaveReady()
        // Load backup settings
        loadBackupSettings()
        // Load location preferences
        loadLocationPreferences()
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
                    sessionTtlMinutes = appPreferencesStore.getSessionTtlMinutes(),
                    archiveAfterDays = appPreferencesStore.getArchiveAfterDays(),
                    deleteAfterDays = appPreferencesStore.getDeleteAfterDays(),
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

    fun updateSessionTtl(minutes: Int) {
        viewModelScope.launch {
            try {
                appPreferencesStore.setSessionTtlMinutes(minutes)
                _state.value = _state.value.copy(sessionTtlMinutes = minutes)
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
                appPreferencesStore.setArchiveAfterDays(days)
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
                appPreferencesStore.setDeleteAfterDays(days)
                _state.value = _state.value.copy(deleteAfterDays = days)
                _effects.emit(VaultPreferencesEffect.ShowSuccess("Delete setting updated"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update delete setting", e)
                _effects.emit(VaultPreferencesEffect.ShowError("Failed to update setting"))
            }
        }
    }

    // MARK: - Backup Settings

    private fun loadBackupSettings() {
        viewModelScope.launch {
            try {
                backupApiClient.getBackupSettings()
                    .onSuccess { settings ->
                        _state.update { it.copy(backupEnabled = settings.autoBackupEnabled) }
                    }
                    .onFailure {
                        // Default to enabled if we can't reach the server
                        Log.w(TAG, "Failed to load backup settings, defaulting to enabled", it)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading backup settings", e)
            }
        }
    }

    fun toggleBackup(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val currentSettings = backupApiClient.getBackupSettings().getOrNull()
                val updatedSettings = currentSettings?.copy(autoBackupEnabled = enabled)
                    ?: BackupSettings(
                        autoBackupEnabled = enabled,
                        backupFrequency = com.vettid.app.core.network.BackupFrequency.DAILY,
                        backupTimeUtc = "03:00",
                        retentionDays = 30,
                        includeMessages = true,
                        wifiOnly = false
                    )

                backupApiClient.updateBackupSettings(updatedSettings)
                    .onSuccess {
                        _state.update { it.copy(backupEnabled = enabled) }
                        _effects.emit(VaultPreferencesEffect.ShowSuccess(
                            if (enabled) "Backup enabled" else "Backup disabled"
                        ))
                    }
                    .onFailure { error ->
                        _effects.emit(VaultPreferencesEffect.ShowError(
                            "Failed to update backup setting: ${error.message}"
                        ))
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling backup", e)
                _effects.emit(VaultPreferencesEffect.ShowError("Failed to update backup setting"))
            }
        }
    }

    fun updateTheme(theme: AppTheme) {
        viewModelScope.launch {
            appPreferencesStore.setTheme(theme)
            _state.update { it.copy(theme = theme) }
        }
    }

    fun onChangePasswordClick() {
        viewModelScope.launch {
            _effects.emit(VaultPreferencesEffect.NavigateToChangePassword)
        }
    }

    // MARK: - PIN Change

    /**
     * Change the vault unlock PIN.
     * Delegates to OwnerSpaceClient for encrypted communication with enclave.
     */
    fun changePIN(currentPin: String, newPin: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                val result = ownerSpaceClient.changePIN(currentPin, newPin, cryptoManager)
                result.fold(
                    onSuccess = { pinChangeResult ->
                        if (pinChangeResult.success) {
                            _effects.emit(VaultPreferencesEffect.ShowSuccess("PIN changed successfully"))
                            onResult(Result.success(Unit))
                        } else {
                            val error = pinChangeResult.error ?: "PIN change failed"
                            onResult(Result.failure(Exception(error)))
                        }
                    },
                    onFailure = { error ->
                        onResult(Result.failure(error))
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "changePIN error", e)
                onResult(Result.failure(e))
            }
        }
    }

    // MARK: - Password Change

    /**
     * Change the credential password stored in the Protean Credential.
     * Delegates to OwnerSpaceClient for encrypted communication with enclave.
     */
    fun changePassword(currentPassword: String, newPassword: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                val result = ownerSpaceClient.changePassword(currentPassword, newPassword, cryptoManager)
                result.fold(
                    onSuccess = { passwordChangeResult ->
                        if (passwordChangeResult.success) {
                            _effects.emit(VaultPreferencesEffect.ShowSuccess("Password changed successfully"))
                            onResult(Result.success(Unit))
                        } else {
                            val error = passwordChangeResult.error ?: "Password change failed"
                            onResult(Result.failure(Exception(error)))
                        }
                    },
                    onFailure = { error ->
                        onResult(Result.failure(error))
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "changePassword error", e)
                onResult(Result.failure(e))
            }
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

    // MARK: - Location Tracking

    private fun loadLocationPreferences() {
        _state.update {
            it.copy(
                locationTrackingEnabled = appPreferencesStore.isLocationTrackingEnabled(),
                locationPrecision = appPreferencesStore.getLocationPrecision(),
                locationFrequency = appPreferencesStore.getLocationFrequency(),
                locationDisplacementThreshold = appPreferencesStore.getDisplacementThreshold(),
                locationRetention = appPreferencesStore.getLocationRetention()
            )
        }
    }

    /**
     * Toggle location tracking on/off.
     * When enabling, emits RequestLocationPermission effect so the UI can request permission.
     * When disabling, saves immediately and cancels the worker.
     */
    fun toggleLocationTracking(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                // Request permission first - UI will call onLocationPermissionResult
                _effects.emit(
                    VaultPreferencesEffect.RequestLocationPermission(_state.value.locationPrecision)
                )
            } else {
                appPreferencesStore.setLocationTrackingEnabled(false)
                _state.update { it.copy(locationTrackingEnabled = false) }
                LocationCollectionWorker.cancel(appContext)
                syncLocationSettingsToVault(false)
                _effects.emit(VaultPreferencesEffect.ShowSuccess("Location tracking disabled"))
            }
        }
    }

    /**
     * Called by the UI after the permission result is received.
     */
    fun onLocationPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            if (granted) {
                appPreferencesStore.setLocationTrackingEnabled(true)
                _state.update {
                    it.copy(locationTrackingEnabled = true, hasLocationPermission = true)
                }
                val frequency = _state.value.locationFrequency
                LocationCollectionWorker.schedule(appContext, frequency.minutes)
                syncLocationSettingsToVault(true)
                _effects.emit(VaultPreferencesEffect.ShowSuccess("Location tracking enabled"))
            } else {
                _state.update {
                    it.copy(locationTrackingEnabled = false, hasLocationPermission = false)
                }
                _effects.emit(VaultPreferencesEffect.ShowError("Location permission required"))
            }
        }
    }

    fun updateLocationPrecision(precision: LocationPrecision) {
        appPreferencesStore.setLocationPrecision(precision)
        _state.update { it.copy(locationPrecision = precision) }
    }

    fun updateLocationFrequency(frequency: LocationUpdateFrequency) {
        appPreferencesStore.setLocationFrequency(frequency)
        _state.update { it.copy(locationFrequency = frequency) }
        // Reschedule worker with new frequency if tracking is enabled
        if (_state.value.locationTrackingEnabled) {
            LocationCollectionWorker.schedule(appContext, frequency.minutes)
        }
    }

    fun updateDisplacementThreshold(threshold: DisplacementThreshold) {
        appPreferencesStore.setDisplacementThreshold(threshold)
        _state.update { it.copy(locationDisplacementThreshold = threshold) }
    }

    fun updateLocationRetention(retention: LocationRetention) {
        viewModelScope.launch {
            appPreferencesStore.setLocationRetention(retention)
            _state.update { it.copy(locationRetention = retention) }
            // Sync retention to vault so compaction/purge uses the correct value
            syncLocationRetentionToVault(retention)
        }
    }

    fun onViewLocationHistoryClick() {
        viewModelScope.launch {
            _effects.emit(VaultPreferencesEffect.NavigateToLocationHistory)
        }
    }

    fun onViewSharedLocationsClick() {
        viewModelScope.launch {
            _effects.emit(VaultPreferencesEffect.NavigateToSharedLocations)
        }
    }

    fun onLocationSettingsClick() {
        viewModelScope.launch {
            _effects.emit(VaultPreferencesEffect.NavigateToLocationSettings)
        }
    }

    private fun syncLocationSettingsToVault(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val retention = _state.value.locationRetention
                val payload = JsonObject().apply {
                    addProperty("enabled", enabled)
                    addProperty("retention_days", retention.days)
                    addProperty("compaction_threshold_days", 7)
                }
                ownerSpaceClient.sendAndAwaitResponse(
                    "location.settings.update", payload, 10000L
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync location settings to vault", e)
            }
        }
    }

    private fun syncLocationRetentionToVault(retention: LocationRetention) {
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("enabled", _state.value.locationTrackingEnabled)
                    addProperty("retention_days", retention.days)
                    addProperty("compaction_threshold_days", 7)
                }
                ownerSpaceClient.sendAndAwaitResponse(
                    "location.settings.update", payload, 10000L
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync location retention to vault", e)
            }
        }
    }
}
