package com.vettid.app.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.network.BackupApiClient
import com.vettid.app.core.network.BackupFrequency
import com.vettid.app.core.network.BackupSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the backup settings screen.
 */
sealed class BackupSettingsState {
    data object Loading : BackupSettingsState()
    data class Loaded(val settings: BackupSettings) : BackupSettingsState()
    data class Error(val message: String) : BackupSettingsState()
}

/**
 * ViewModel for backup settings management.
 */
@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val backupApiClient: BackupApiClient
) : ViewModel() {

    private val _state = MutableStateFlow<BackupSettingsState>(BackupSettingsState.Loading)
    val state: StateFlow<BackupSettingsState> = _state.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    init {
        loadSettings()
    }

    /**
     * Load backup settings.
     */
    fun loadSettings() {
        viewModelScope.launch {
            _state.value = BackupSettingsState.Loading

            backupApiClient.getBackupSettings().fold(
                onSuccess = { settings ->
                    _state.value = BackupSettingsState.Loaded(settings)
                },
                onFailure = { error ->
                    _state.value = BackupSettingsState.Error(error.message ?: "Failed to load settings")
                }
            )
        }
    }

    /**
     * Update auto-backup enabled setting.
     */
    fun setAutoBackupEnabled(enabled: Boolean) {
        updateSettings { it.copy(autoBackupEnabled = enabled) }
    }

    /**
     * Update backup frequency.
     */
    fun setBackupFrequency(frequency: BackupFrequency) {
        updateSettings { it.copy(backupFrequency = frequency) }
    }

    /**
     * Update backup time (HH:mm format).
     */
    fun setBackupTime(time: String) {
        updateSettings { it.copy(backupTimeUtc = time) }
    }

    /**
     * Update retention days.
     */
    fun setRetentionDays(days: Int) {
        updateSettings { it.copy(retentionDays = days) }
    }

    /**
     * Update include messages setting.
     */
    fun setIncludeMessages(include: Boolean) {
        updateSettings { it.copy(includeMessages = include) }
    }

    /**
     * Update WiFi only setting.
     */
    fun setWifiOnly(wifiOnly: Boolean) {
        updateSettings { it.copy(wifiOnly = wifiOnly) }
    }

    /**
     * Update settings with a transform function.
     */
    private fun updateSettings(transform: (BackupSettings) -> BackupSettings) {
        val currentState = _state.value
        if (currentState is BackupSettingsState.Loaded) {
            val newSettings = transform(currentState.settings)
            _state.value = BackupSettingsState.Loaded(newSettings)
            saveSettings(newSettings)
        }
    }

    /**
     * Save settings to the server.
     */
    private fun saveSettings(settings: BackupSettings) {
        viewModelScope.launch {
            _isSaving.value = true

            backupApiClient.updateBackupSettings(settings).fold(
                onSuccess = { updatedSettings ->
                    _state.value = BackupSettingsState.Loaded(updatedSettings)
                },
                onFailure = { error ->
                    // Revert to server state
                    loadSettings()
                }
            )

            _isSaving.value = false
        }
    }

    /**
     * Trigger a manual backup now.
     */
    fun backupNow() {
        viewModelScope.launch {
            _isBackingUp.value = true

            val currentState = _state.value
            val includeMessages = if (currentState is BackupSettingsState.Loaded) {
                currentState.settings.includeMessages
            } else {
                true
            }

            backupApiClient.triggerBackup(includeMessages).fold(
                onSuccess = {
                    // Reload settings to update last backup time
                    loadSettings()
                },
                onFailure = { error ->
                    _state.value = BackupSettingsState.Error(error.message ?: "Failed to create backup")
                }
            )

            _isBackingUp.value = false
        }
    }
}
