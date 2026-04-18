package com.vettid.app.features.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lists and revokes paired desktop devices. Session extension is not app-initiated
 * under the new flow — the desktop asks via device.request-session and the user
 * re-authorizes via QR scan. This screen is for oversight and logout.
 */
@HiltViewModel
class DeviceManagementViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) : ViewModel() {

    private val _state = MutableStateFlow<DeviceManagementState>(DeviceManagementState.Loading)
    val state: StateFlow<DeviceManagementState> = _state.asStateFlow()

    private val _isRevoking = MutableStateFlow(false)
    val isRevoking: StateFlow<Boolean> = _isRevoking.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var revocationListenerJob: Job? = null

    init {
        revocationListenerJob = viewModelScope.launch {
            ownerSpaceClient.deviceSessionRevoked.collect {
                // Another revoke happened (either from another client or from the
                // desktop logging out) — refresh the list.
                loadDevices()
            }
        }
    }

    fun loadDevices() {
        viewModelScope.launch {
            _state.value = DeviceManagementState.Loading
            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "device.list",
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
                                    deviceName = obj.get("device_name")?.asString ?: "Desktop",
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
                            _state.value = if (devices.isEmpty()) DeviceManagementState.Empty
                                           else DeviceManagementState.Loaded(devices)
                        } else {
                            _state.value = DeviceManagementState.Error(response.error ?: "Failed to load devices")
                        }
                    }
                    is VaultResponse.Error -> _state.value = DeviceManagementState.Error(response.message)
                    null -> _state.value = DeviceManagementState.Error("Request timed out")
                    else -> _state.value = DeviceManagementState.Error("Unexpected response")
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
                    addProperty("reason", "admin")
                }
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "device.revoke",
                    payload = payload,
                    timeoutMs = 15000L
                )
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) loadDevices()
                        else _errorMessage.value = response.error ?: "Failed to revoke device"
                    }
                    is VaultResponse.Error -> _errorMessage.value = response.message
                    else -> _errorMessage.value = "Failed to revoke device"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to revoke device"
            } finally {
                _isRevoking.value = false
            }
        }
    }

    fun clearError() { _errorMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        revocationListenerJob?.cancel()
    }
}
