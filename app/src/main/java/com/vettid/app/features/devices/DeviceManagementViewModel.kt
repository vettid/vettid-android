package com.vettid.app.features.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.OwnerSpaceClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class DeviceManagementViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) : ViewModel() {

    private val _state = MutableStateFlow<DeviceManagementState>(DeviceManagementState.Loading)
    val state: StateFlow<DeviceManagementState> = _state.asStateFlow()

    private val _isRevoking = MutableStateFlow(false)
    val isRevoking: StateFlow<Boolean> = _isRevoking.asStateFlow()

    private val _isExtending = MutableStateFlow(false)
    val isExtending: StateFlow<Boolean> = _isExtending.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var heartbeatJob: Job? = null
    private val heartbeatIntervalMs = 120_000L // 2 minutes

    fun loadDevices() {
        viewModelScope.launch {
            _state.value = DeviceManagementState.Loading
            try {
                val response = ownerSpaceClient.requestFromVault<DeviceListResponse>(
                    topic = "connection.device.list",
                    payload = "{}"
                )
                if (response.devices.isEmpty()) {
                    _state.value = DeviceManagementState.Empty
                } else {
                    _state.value = DeviceManagementState.Loaded(response.devices)
                }

                // Start heartbeat if any device has an active session
                val hasActive = response.devices.any { it.isSessionActive }
                if (hasActive) startHeartbeat() else stopHeartbeat()
            } catch (e: Exception) {
                _state.value = DeviceManagementState.Error(e.message ?: "Failed to load devices")
            }
        }
    }

    fun revokeDevice(connectionId: String) {
        viewModelScope.launch {
            _isRevoking.value = true
            _errorMessage.value = null
            try {
                ownerSpaceClient.sendToVault(
                    topic = "connection.device.revoke",
                    payload = """{"connection_id":"$connectionId"}"""
                )
                loadDevices()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to revoke device: ${e.message}"
            } finally {
                _isRevoking.value = false
            }
        }
    }

    fun extendSession(connectionId: String) {
        viewModelScope.launch {
            _isExtending.value = true
            _errorMessage.value = null
            try {
                ownerSpaceClient.sendToVault(
                    topic = "connection.device.extend-session",
                    payload = """{"connection_id":"$connectionId"}"""
                )
                loadDevices()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to extend session: ${e.message}"
            } finally {
                _isExtending.value = false
            }
        }
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                try {
                    ownerSpaceClient.sendToVault(
                        topic = "connection.device.heartbeat",
                        payload = "{}"
                    )
                } catch (_: Exception) { }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
    }
}
