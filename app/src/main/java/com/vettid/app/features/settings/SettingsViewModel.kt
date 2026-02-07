package com.vettid.app.features.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.storage.AppPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferencesStore: AppPreferencesStore
) : ViewModel() {

    private val _state = MutableStateFlow(AppSettingsState())
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>()
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _state.value = AppSettingsState(
                theme = appPreferencesStore.getTheme(),
                appLockEnabled = false,
                lockMethod = LockMethod.BIOMETRIC,
                autoLockTimeout = AutoLockTimeout.FIVE_MINUTES,
                screenshotsEnabled = false,
                screenLockEnabled = true
            )
        }
    }

    fun updateTheme(theme: AppTheme) {
        viewModelScope.launch {
            try {
                appPreferencesStore.setTheme(theme)
                _state.value = _state.value.copy(theme = theme)
                _effects.emit(SettingsEffect.ShowSuccess("Theme updated"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update theme", e)
                _effects.emit(SettingsEffect.ShowError("Failed to update theme"))
            }
        }
    }

    fun toggleAppLock(enabled: Boolean) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(appLockEnabled = enabled)
                if (enabled && _state.value.lockMethod == LockMethod.PIN) {
                    _effects.emit(SettingsEffect.NavigateToSetupPin)
                }
                _effects.emit(SettingsEffect.ShowSuccess(
                    if (enabled) "App lock enabled" else "App lock disabled"
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle app lock", e)
                _effects.emit(SettingsEffect.ShowError("Failed to update setting"))
            }
        }
    }

    fun updateLockMethod(method: LockMethod) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(lockMethod = method)
                if (method == LockMethod.PIN || method == LockMethod.BOTH) {
                    _effects.emit(SettingsEffect.NavigateToSetupPin)
                }
                _effects.emit(SettingsEffect.ShowSuccess("Lock method updated"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update lock method", e)
                _effects.emit(SettingsEffect.ShowError("Failed to update setting"))
            }
        }
    }

    fun updateAutoLockTimeout(timeout: AutoLockTimeout) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(autoLockTimeout = timeout)
                _effects.emit(SettingsEffect.ShowSuccess("Auto-lock updated"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update auto-lock", e)
                _effects.emit(SettingsEffect.ShowError("Failed to update setting"))
            }
        }
    }

    fun toggleScreenshots(enabled: Boolean) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(screenshotsEnabled = enabled)
                _effects.emit(SettingsEffect.ShowSuccess(
                    if (enabled) "Screenshots enabled" else "Screenshots disabled"
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle screenshots", e)
                _effects.emit(SettingsEffect.ShowError("Failed to update setting"))
            }
        }
    }

    fun toggleScreenLock(enabled: Boolean) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(screenLockEnabled = enabled)
                _effects.emit(SettingsEffect.ShowSuccess(
                    if (enabled) "Screen lock enabled" else "Screen lock disabled"
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle screen lock", e)
                _effects.emit(SettingsEffect.ShowError("Failed to update setting"))
            }
        }
    }

    fun onChangePasswordClick() {
        viewModelScope.launch {
            _effects.emit(SettingsEffect.NavigateToChangePassword)
        }
    }

    fun onViewRecoveryPhraseClick() {
        viewModelScope.launch {
            _effects.emit(SettingsEffect.NavigateToRecoveryPhrase)
        }
    }
}
