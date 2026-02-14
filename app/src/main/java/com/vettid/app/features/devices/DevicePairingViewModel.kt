package com.vettid.app.features.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.network.ApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class DevicePairingViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val apiClient: ApiClient
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
                // Step 1: Create device invitation via NATS
                val inviteResponse = ownerSpaceClient.requestFromVault<CreateInviteResponse>(
                    topic = "connection.device.create-invite",
                    payload = "{}"
                )

                // Step 2: Create shortlink via API
                val shortlinkResponse = apiClient.createDeviceShortlink(
                    invitationId = inviteResponse.invitationId,
                    inviteToken = inviteResponse.inviteToken,
                    vaultPublicKey = inviteResponse.vaultPublicKey,
                    messagespaceUri = inviteResponse.messagespaceUri,
                    ownerGuid = inviteResponse.ownerGuid
                )

                val code = shortlinkResponse.code

                // Step 3: Show code with countdown
                _state.value = DevicePairingState.ShowingCode(code, pairingTimeoutSeconds)
                startCountdown()

                // Step 4: Wait for device connection event from vault
                val connectionEvent = ownerSpaceClient.waitForEvent<DeviceConnectionEvent>(
                    topic = "device.connection.request",
                    timeoutSeconds = pairingTimeoutSeconds
                )

                if (connectionEvent != null) {
                    countdownJob?.cancel()
                    _state.value = DevicePairingState.WaitingApproval

                    // Auto-approve for now (in production, show approval UI)
                    _state.value = DevicePairingState.Approved(
                        deviceName = connectionEvent.hostname ?: "Desktop"
                    )
                } else {
                    _state.value = DevicePairingState.Timeout
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
}

// Response types
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
    val hostname: String?,
    val platform: String?,
    val deviceName: String?
)
