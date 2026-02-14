package com.vettid.app.features.devices

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
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
    private val gson = Gson()

    fun loadDevices() {
        viewModelScope.launch {
            _state.value = DeviceManagementState.Loading
            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "connection.device.list",
                    payload = JsonObject(),
                    timeoutMs = 15000L
                )
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success && response.result != null) {
                            val devicesArray = response.result.getAsJsonArray("devices")
                            val devices = devicesArray?.map { element ->
                                val obj = element.asJsonObject
                                ConnectedDevice(
                                    connectionId = obj.get("connection_id")?.asString ?: "",
                                    deviceName = obj.get("device_name")?.asString ?: "Unknown",
                                    hostname = obj.get("hostname")?.asString,
                                    platform = obj.get("platform")?.asString,
                                    status = obj.get("status")?.asString ?: "unknown",
                                    sessionId = obj.get("session_id")?.asString,
                                    sessionStatus = obj.get("session_status")?.asString,
                                    sessionExpires = obj.get("session_expires")?.asLong,
                                    connectedAt = obj.get("connected_at")?.asString ?: "",
                                    lastActiveAt = obj.get("last_active_at")?.asString
                                )
                            } ?: emptyList()

                            if (devices.isEmpty()) {
                                _state.value = DeviceManagementState.Empty
                            } else {
                                _state.value = DeviceManagementState.Loaded(devices)
                            }

                            // Start heartbeat if any device has an active session
                            val hasActive = devices.any { it.isSessionActive }
                            if (hasActive) startHeartbeat() else stopHeartbeat()
                        } else {
                            _state.value = DeviceManagementState.Error(response.error ?: "Failed to load devices")
                        }
                    }
                    is VaultResponse.Error -> {
                        _state.value = DeviceManagementState.Error(response.message)
                    }
                    null -> {
                        _state.value = DeviceManagementState.Error("Request timed out")
                    }
                    else -> {
                        _state.value = DeviceManagementState.Error("Unexpected response")
                    }
                }
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
                val payload = JsonObject().apply {
                    addProperty("connection_id", connectionId)
                }
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "connection.device.revoke",
                    payload = payload,
                    timeoutMs = 15000L
                )
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            loadDevices()
                        } else {
                            _errorMessage.value = "Failed to revoke device: ${response.error}"
                        }
                    }
                    is VaultResponse.Error -> {
                        _errorMessage.value = "Failed to revoke device: ${response.message}"
                    }
                    else -> {
                        _errorMessage.value = "Failed to revoke device: unexpected response"
                    }
                }
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
                val payload = JsonObject().apply {
                    addProperty("connection_id", connectionId)
                }
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "connection.device.extend-session",
                    payload = payload,
                    timeoutMs = 15000L
                )
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            loadDevices()
                        } else {
                            _errorMessage.value = "Failed to extend session: ${response.error}"
                        }
                    }
                    is VaultResponse.Error -> {
                        _errorMessage.value = "Failed to extend session: ${response.message}"
                    }
                    else -> {
                        _errorMessage.value = "Failed to extend session: unexpected response"
                    }
                }
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
                        messageType = "connection.device.heartbeat",
                        payload = JsonObject()
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

    companion object {
        private const val TAG = "DeviceManagementVM"
    }
}
