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

/**
 * Stage-1 pairing: ask the vault for an 8-char invite code, show it to the user,
 * then wait for the desktop to complete stage 1 and post device.request-session.
 * When that happens, OwnerSpaceClient emits on its devicePendingAuth flow and we
 * transition to DevicePending — at which point the screen navigates to
 * AuthorizeDeviceScreen to scan the desktop's QR and set duration.
 */
@HiltViewModel
class DevicePairingViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) : ViewModel() {

    private val _state = MutableStateFlow<DevicePairingState>(DevicePairingState.Idle)
    val state: StateFlow<DevicePairingState> = _state.asStateFlow()

    private var createJob: Job? = null
    private var countdownJob: Job? = null
    private var pendingListenerJob: Job? = null
    private val inviteTtlSeconds = 120 // 2 min, matches backend

    init {
        pendingListenerJob = viewModelScope.launch {
            ownerSpaceClient.devicePendingAuth.collect { notif ->
                // Only transition if we're currently showing a code — otherwise a
                // stale notification would pop us into pending unexpectedly.
                val current = _state.value
                if (current is DevicePairingState.ShowingCode) {
                    val meta = notif.deviceMetadata
                    _state.value = DevicePairingState.DevicePending(
                        PendingDeviceInfo(
                            connectionId = notif.connectionId,
                            hostname = meta?.hostname ?: "",
                            platform = meta?.platform ?: "",
                            osName = meta?.osName ?: "",
                            osVersion = meta?.osVersion ?: "",
                            appVersion = meta?.appVersion ?: "",
                            clientIp = meta?.clientIp ?: "",
                            binaryFpPrefix = notif.binaryFpPrefix,
                            defaultDurationSeconds = notif.defaultDurationSeconds,
                            maxDurationSeconds = notif.maxDurationSeconds
                        )
                    )
                    countdownJob?.cancel()
                }
            }
        }
    }

    fun startPairing() {
        createJob?.cancel()
        createJob = viewModelScope.launch {
            _state.value = DevicePairingState.Creating
            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "device.create-invite",
                    payload = JsonObject(),
                    timeoutMs = 15000L
                )
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        val code = response.result?.get("invite_code")?.asString
                        if (response.success && !code.isNullOrEmpty()) {
                            _state.value = DevicePairingState.ShowingCode(code, inviteTtlSeconds)
                            startCountdown()
                        } else {
                            _state.value = DevicePairingState.Error(response.error ?: "No invite code received")
                        }
                    }
                    is VaultResponse.Error -> _state.value = DevicePairingState.Error(response.message)
                    null -> _state.value = DevicePairingState.Error("Request timed out")
                    else -> _state.value = DevicePairingState.Error("Unexpected response")
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
            var remaining = inviteTtlSeconds
            while (remaining > 0 && isActive) {
                val current = _state.value
                if (current is DevicePairingState.ShowingCode) {
                    _state.value = current.copy(remainingSeconds = remaining)
                } else {
                    // State changed (likely DevicePending) — stop countdown
                    return@launch
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
        createJob?.cancel()
        countdownJob?.cancel()
        _state.value = DevicePairingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        createJob?.cancel()
        countdownJob?.cancel()
        pendingListenerJob?.cancel()
    }

    companion object {
        private const val TAG = "DevicePairingVM"
    }
}
