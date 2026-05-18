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
 * Stage-1 pairing: ask the vault for a 12-char invite code, show it to the user,
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

    // Connection ID returned by device.create-invite. Kept here (rather than
    // on the state class) so cancel/timeout paths can fire
    // device.cancel-invite without exposing it to the UI. Cleared when the
    // invite is no longer cancellable (DevicePending onwards) or after a
    // successful cancel call.
    //
    // Also surfaced to the screen via `pendingConnectionId()` so the
    // "Scan Desktop QR" manual-escape button can navigate to the
    // authorize-device scanner even when the vault's pending-auth
    // notification hasn't (yet) reached the phone. Without that, a
    // missed/delayed notification leaves the user stranded on the
    // code-display screen with no way to proceed.
    private var pendingConnectionId: String? = null

    /** Connection ID for the in-flight pairing invite, if one is active. */
    fun pendingConnectionId(): String? = pendingConnectionId

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
                            binaryFingerprint = meta?.binaryFingerprint ?: "",
                            machineFingerprint = meta?.machineFingerprint ?: "",
                            defaultDurationSeconds = notif.defaultDurationSeconds,
                            maxDurationSeconds = notif.maxDurationSeconds,
                            existingAlias = null,
                        )
                    )
                    countdownJob?.cancel()
                    // Desktop has claimed the invite — the pairing flow takes
                    // over from here, so the invite is no longer cancellable
                    // via device.cancel-invite (revoke is the right teardown
                    // once activated).
                    pendingConnectionId = null
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
                        val connId = response.result?.get("connection_id")?.asString
                        if (response.success && !code.isNullOrEmpty()) {
                            // Remember the connection_id so cancel/timeout
                            // can tear down the in-flight invite vault-side.
                            pendingConnectionId = connId
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
                // Code aged out without being claimed. Tear down the vault-side
                // pending connection so the feed doesn't keep showing it as a
                // standalone card.
                cancelOnVault()
                _state.value = DevicePairingState.Timeout
            }
        }
    }

    fun cancel() {
        createJob?.cancel()
        countdownJob?.cancel()
        // Fire-and-forget the vault-side teardown; UI flips to Idle right
        // away rather than blocking on the round trip. If the publish fails
        // the connection record is still pending_pairing but will roll into
        // expired/cancelled state on the next sweep.
        cancelOnVault()
        _state.value = DevicePairingState.Idle
    }

    private fun cancelOnVault() {
        val connId = pendingConnectionId ?: return
        pendingConnectionId = null
        viewModelScope.launch(NonCancellable) {
            try {
                val payload = JsonObject().apply { addProperty("connection_id", connId) }
                ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "device.cancel-invite",
                    payload = payload,
                    timeoutMs = 10_000L,
                )
            } catch (e: Exception) {
                Log.w(TAG, "device.cancel-invite failed (non-fatal)", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        createJob?.cancel()
        countdownJob?.cancel()
        pendingListenerJob?.cancel()
        // Fire teardown if the user navigated away without explicitly
        // cancelling (e.g. system back, process tear-down).
        cancelOnVault()
    }

    companion object {
        private const val TAG = "DevicePairingVM"
    }
}
