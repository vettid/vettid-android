package com.vettid.app.features.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
class DeviceApprovalViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) : ViewModel() {

    private val _state = MutableStateFlow<DeviceApprovalState>(DeviceApprovalState.Idle)
    val state: StateFlow<DeviceApprovalState> = _state.asStateFlow()

    private var elapsedJob: Job? = null
    private var timeoutJob: Job? = null
    private val approvalTimeoutSeconds = 120 // 2 minutes

    /**
     * Load a pending approval request by ID and start tracking elapsed time.
     */
    fun loadRequest(request: DeviceApprovalRequest) {
        _state.value = DeviceApprovalState.Ready(request = request, elapsedSeconds = 0)
        startElapsedTimer(request)
        startTimeout()
    }

    fun approve() {
        val current = _state.value
        if (current !is DeviceApprovalState.Ready) return

        _state.value = DeviceApprovalState.ProcessingApproval
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("request_id", current.request.requestId)
                    addProperty("approved", true)
                }
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "connection.device.approval",
                    payload = payload,
                    timeoutMs = 15000L
                )
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            _state.value = DeviceApprovalState.Approved()
                        } else {
                            _state.value = DeviceApprovalState.Error(response.error ?: "Approval failed")
                        }
                    }
                    is VaultResponse.Error -> {
                        _state.value = DeviceApprovalState.Error(response.message)
                    }
                    else -> {
                        _state.value = DeviceApprovalState.Error("Unexpected response")
                    }
                }
            } catch (e: Exception) {
                _state.value = DeviceApprovalState.Error(e.message ?: "Failed to approve")
            } finally {
                stopTimers()
            }
        }
    }

    fun deny() {
        val current = _state.value
        if (current !is DeviceApprovalState.Ready) return

        _state.value = DeviceApprovalState.ProcessingDenial
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("request_id", current.request.requestId)
                    addProperty("approved", false)
                }
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "connection.device.approval",
                    payload = payload,
                    timeoutMs = 15000L
                )
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            _state.value = DeviceApprovalState.Denied()
                        } else {
                            _state.value = DeviceApprovalState.Error(response.error ?: "Denial failed")
                        }
                    }
                    is VaultResponse.Error -> {
                        _state.value = DeviceApprovalState.Error(response.message)
                    }
                    else -> {
                        _state.value = DeviceApprovalState.Error("Unexpected response")
                    }
                }
            } catch (e: Exception) {
                _state.value = DeviceApprovalState.Error(e.message ?: "Failed to deny")
            } finally {
                stopTimers()
            }
        }
    }

    fun dismiss() {
        stopTimers()
        _state.value = DeviceApprovalState.Idle
    }

    private fun startElapsedTimer(request: DeviceApprovalRequest) {
        elapsedJob?.cancel()
        elapsedJob = viewModelScope.launch {
            var elapsed = 0
            while (isActive) {
                delay(1000)
                elapsed++
                val current = _state.value
                if (current is DeviceApprovalState.Ready) {
                    _state.value = current.copy(elapsedSeconds = elapsed)
                }
            }
        }
    }

    private fun startTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(approvalTimeoutSeconds * 1000L)
            val current = _state.value
            if (current is DeviceApprovalState.Ready) {
                _state.value = DeviceApprovalState.Timeout
            }
            stopTimers()
        }
    }

    private fun stopTimers() {
        elapsedJob?.cancel()
        timeoutJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        stopTimers()
    }
}
