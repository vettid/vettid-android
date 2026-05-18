package com.vettid.app.features.devices

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vettid.app.core.nats.ConnectionsClient
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
 * Stage-2 authorization: the user has a pending device and scans its QR
 * (which carries the approval_token + connection_id). They then choose a
 * session duration and device name, and approve — which submits
 * device.authorize-session to the vault.
 */
@HiltViewModel
class AuthorizeDeviceViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionsClient: ConnectionsClient,
) : ViewModel() {

    private val _state = MutableStateFlow<AuthorizeDeviceState>(AuthorizeDeviceState.Scanning)
    val state: StateFlow<AuthorizeDeviceState> = _state.asStateFlow()

    private var pending: PendingDeviceInfo? = null
    private var listenerJob: Job? = null

    fun setPendingFromNotification(connectionId: String) {
        // If we've already paired this desktop, the user picked a name
        // at first-pair time and shouldn't have to retype it on every
        // session refresh. Pull it from the cached connections list so
        // the AuthorizeForm can render it read-only.
        val existingAlias = connectionsClient.cachedListSnapshot()
            ?.items
            ?.firstOrNull { it.connectionId == connectionId }
            ?.label
            ?.takeIf { it.isNotBlank() }

        // Cache the info from any already-emitted pending notifications for this
        // connection_id. If none is cached yet, listen briefly.
        listenerJob?.cancel()
        listenerJob = viewModelScope.launch {
            ownerSpaceClient.devicePendingAuth.collect { notif ->
                if (notif.connectionId == connectionId) {
                    val meta = notif.deviceMetadata
                    pending = PendingDeviceInfo(
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
                        existingAlias = existingAlias,
                    )
                }
            }
        }
    }

    /**
     * Called when the user scans a QR. Expected payload: {"t":"...","c":"..."}.
     * Validates the connection_id matches our pending device, then transitions
     * to Ready with the user input form.
     *
     * Falls back to synthesized defaults when no pending-authorization
     * notification has been received yet — covers the case where the user
     * tapped the manual "Scan Desktop QR" button before (or instead of) the
     * vault's notification arrived. The approval_token in the QR is the
     * security boundary: the vault rejects on token mismatch.
     */
    fun onQrScanned(qrText: String) {
        val parsed = parseQr(qrText)
        if (parsed == null) {
            _state.value = AuthorizeDeviceState.Error("Invalid QR code — try again")
            return
        }
        val p = pending
        if (p != null && p.connectionId != parsed.connectionId) {
            _state.value = AuthorizeDeviceState.Error(
                "QR doesn't match the device waiting for authorization"
            )
            return
        }
        val info = p ?: PendingDeviceInfo(
            connectionId = parsed.connectionId,
            hostname = "",
            platform = "",
            osName = "",
            osVersion = "",
            appVersion = "",
            clientIp = "",
            binaryFingerprint = "",
            machineFingerprint = "",
            defaultDurationSeconds = DEFAULT_FALLBACK_DURATION_S,
            maxDurationSeconds = MAX_FALLBACK_DURATION_S,
            existingAlias = null,
        )
        val initialName = info.existingAlias
            ?: info.hostname.takeIf { it.isNotBlank() }
            ?: "Desktop"
        _state.value = AuthorizeDeviceState.Ready(
            scanned = parsed,
            info = info,
            deviceName = initialName,
            durationSeconds = info.defaultDurationSeconds,
        )
    }

    fun updateDeviceName(name: String) {
        val current = _state.value as? AuthorizeDeviceState.Ready ?: return
        _state.value = current.copy(deviceName = name)
    }

    fun updateDuration(seconds: Long) {
        val current = _state.value as? AuthorizeDeviceState.Ready ?: return
        val capped = seconds.coerceIn(60L, current.info.maxDurationSeconds)
        _state.value = current.copy(durationSeconds = capped)
    }

    fun approve() {
        val ready = _state.value as? AuthorizeDeviceState.Ready ?: return
        submitAuthorize(
            scanned = ready.scanned,
            info = ready.info,
            deviceName = ready.deviceName,
            durationSeconds = ready.durationSeconds,
            forceReplace = false,
        )
    }

    /**
     * User confirmed the "end existing session and continue" prompt.
     * Re-fires authorize-session with force_replace=true.
     */
    fun confirmReplace() {
        val pending = _state.value as? AuthorizeDeviceState.ConfirmReplace ?: return
        submitAuthorize(
            scanned = pending.scanned,
            info = pending.info,
            deviceName = pending.deviceName,
            durationSeconds = pending.durationSeconds,
            forceReplace = true,
        )
    }

    /** User dismissed the ConfirmReplace prompt — return to the Ready form. */
    fun cancelReplace() {
        val pending = _state.value as? AuthorizeDeviceState.ConfirmReplace ?: return
        _state.value = AuthorizeDeviceState.Ready(
            scanned = pending.scanned,
            info = pending.info,
            deviceName = pending.deviceName,
            durationSeconds = pending.durationSeconds,
        )
    }

    private fun submitAuthorize(
        scanned: ScannedDeviceAuthQr,
        info: PendingDeviceInfo,
        deviceName: String,
        durationSeconds: Long,
        forceReplace: Boolean,
    ) {
        _state.value = AuthorizeDeviceState.Submitting
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("connection_id", scanned.connectionId)
                    addProperty("approval_token", scanned.approvalToken)
                    addProperty("device_name", deviceName.ifBlank { "Desktop" })
                    addProperty("duration_seconds", durationSeconds)
                    if (forceReplace) addProperty("force_replace", true)
                }
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "device.authorize-session",
                    payload = payload,
                    timeoutMs = 20000L
                )
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) _state.value = AuthorizeDeviceState.Done
                        else _state.value = AuthorizeDeviceState.Error(response.error ?: "Authorization failed")
                    }
                    is VaultResponse.Error -> {
                        if (response.code == "existing_session_active") {
                            val existing = parseExistingDevices(response.extra)
                            _state.value = AuthorizeDeviceState.ConfirmReplace(
                                scanned = scanned,
                                info = info,
                                deviceName = deviceName,
                                durationSeconds = durationSeconds,
                                existingDevices = existing,
                            )
                        } else {
                            _state.value = AuthorizeDeviceState.Error(response.message)
                        }
                    }
                    null -> _state.value = AuthorizeDeviceState.Error("Request timed out")
                    else -> _state.value = AuthorizeDeviceState.Error("Unexpected response")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Authorization failed", e)
                _state.value = AuthorizeDeviceState.Error(e.message ?: "Failed")
            }
        }
    }

    private fun parseExistingDevices(extra: JsonObject?): List<ExistingDeviceSummary> {
        if (extra == null) return emptyList()
        val arr = extra.getAsJsonArray("existing_devices") ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.asJsonObject ?: return@mapNotNull null
            ExistingDeviceSummary(
                connectionId = obj.get("connection_id")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                deviceName = obj.get("device_name")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                lastActiveAt = obj.get("last_active_at")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                expiresAt = obj.get("expires_at")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
            )
        }
    }

    fun reset() {
        _state.value = AuthorizeDeviceState.Scanning
    }

    private fun parseQr(text: String): ScannedDeviceAuthQr? {
        return try {
            val obj = JsonParser.parseString(text).asJsonObject
            val token = obj.get("t")?.asString ?: return null
            val connId = obj.get("c")?.asString ?: return null
            if (token.length != 64 || connId.isEmpty()) return null
            ScannedDeviceAuthQr(approvalToken = token, connectionId = connId)
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        listenerJob?.cancel()
    }

    companion object {
        private const val TAG = "AuthorizeDeviceVM"

        // Conservative defaults when the user scans the QR before the
        // vault's pending-authorization notification arrives. The vault
        // separately enforces its own session-duration policy, so these
        // are display-time suggestions, not the source of truth.
        private const val DEFAULT_FALLBACK_DURATION_S = 24L * 60L * 60L
        private const val MAX_FALLBACK_DURATION_S = 30L * 24L * 60L * 60L
    }
}
