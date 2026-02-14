package com.vettid.app.features.devices

import android.util.Log
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
class DevicePairingViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) : ViewModel() {

    private val _state = MutableStateFlow<DevicePairingState>(DevicePairingState.Idle)
    val state: StateFlow<DevicePairingState> = _state.asStateFlow()

    private var pairingJob: Job? = null
    private var countdownJob: Job? = null
    private val pairingTimeoutSeconds = 300 // 5 minutes

    fun startPairing() {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            _state.value = DevicePairingState.Creating

            try {
                // Step 1: Create device invitation via vault
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "connection.device.create-invite",
                    payload = JsonObject(),
                    timeoutMs = 15000L
                )

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success && response.result != null) {
                            val code = response.result.get("code")?.asString
                                ?: response.result.get("invitation_code")?.asString
                                ?: ""

                            if (code.isEmpty()) {
                                _state.value = DevicePairingState.Error("No pairing code received")
                                return@launch
                            }

                            // Step 2: Show code with countdown
                            _state.value = DevicePairingState.ShowingCode(code, pairingTimeoutSeconds)
                            startCountdown()

                            // Step 3: Wait for device connection event
                            // The vault will send a push notification on forApp.device.connection.request
                            // when a device connects. For now we wait for the timeout.
                            // TODO: Listen for device connection event via vaultEvents flow
                        } else {
                            _state.value = DevicePairingState.Error(response.error ?: "Failed to create invitation")
                        }
                    }
                    is VaultResponse.Error -> {
                        _state.value = DevicePairingState.Error(response.message)
                    }
                    null -> {
                        _state.value = DevicePairingState.Error("Request timed out")
                    }
                    else -> {
                        _state.value = DevicePairingState.Error("Unexpected response")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed", e)
                _state.value = DevicePairingState.Error(e.message ?: "Pairing failed")
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = pairingTimeoutSeconds
            while (remaining > 0 && isActive) {
                val current = _state.value
                if (current is DevicePairingState.ShowingCode) {
                    _state.value = current.copy(remainingSeconds = remaining)
                }
                delay(1000)
                remaining--
            }
            val current = _state.value
            if (current is DevicePairingState.ShowingCode) {
                _state.value = DevicePairingState.Timeout
            }
        }
    }

    fun cancel() {
        pairingJob?.cancel()
        countdownJob?.cancel()
        _state.value = DevicePairingState.Idle
    }

    fun dismiss() {
        pairingJob?.cancel()
        countdownJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        pairingJob?.cancel()
        countdownJob?.cancel()
    }

    companion object {
        private const val TAG = "DevicePairingVM"
    }
}

// Response types (used when device pairing backend is implemented)
data class CreateInviteResponse(
    val connectionId: String,
    val invitationId: String,
    val inviteToken: String,
    val vaultPublicKey: String,
    val messagespaceUri: String,
    val ownerGuid: String
)

data class ShortlinkResponse(
    val code: String,
    val expiresAt: String
)

data class DeviceConnectionEvent(
    val connectionId: String,
    val deviceName: String?,
    val platform: String?
)
