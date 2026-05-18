package com.vettid.app.features.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.AuditEntry
import com.vettid.app.core.nats.ConnectionAuditClient
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.DeviceConnectionMetadata
import com.vettid.app.core.nats.DeviceConnectionSession
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Loads + actions a single paired desktop. Data comes straight from the
 * fresh `connection.list` result (the vault inlines `device_metadata` +
 * `device_session` so we get hostname/fingerprint/session-state in one
 * round-trip). Remove is wired to `device.revoke`.
 */
@HiltViewModel
class DesktopConnectionDetailViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionsClient: ConnectionsClient,
    private val auditClient: ConnectionAuditClient,
) : ViewModel() {

    private val _state = MutableStateFlow<DesktopDetailState>(DesktopDetailState.Loading)
    val state: StateFlow<DesktopDetailState> = _state.asStateFlow()

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // Recent activity for this desktop — the latest entries from
    // connection.audit.list filtered to this connection_id. Loads in
    // the background after the device info renders so the screen
    // doesn't stall on the audit fetch.
    private val _activity = MutableStateFlow<ActivityState>(ActivityState.Loading)
    val activity: StateFlow<ActivityState> = _activity.asStateFlow()

    fun load(connectionId: String) {
        viewModelScope.launch {
            _state.value = DesktopDetailState.Loading
            try {
                // Skip the unfiltered cache so we always see the latest
                // session state (extends + key rotations happen
                // server-side without re-poll signaling).
                connectionsClient.invalidateListCache()
                val list = connectionsClient.list()
                val rec = list.getOrNull()?.items?.firstOrNull { it.connectionId == connectionId }
                if (rec == null) {
                    _state.value = DesktopDetailState.Error("Desktop not found")
                    return@launch
                }
                if (rec.connectionType != "device") {
                    _state.value = DesktopDetailState.Error("Not a desktop connection")
                    return@launch
                }
                _state.value = DesktopDetailState.Loaded(
                    connectionId = rec.connectionId,
                    deviceName = rec.label.ifBlank {
                        rec.deviceMetadata?.hostname ?: "Desktop"
                    },
                    status = rec.status,
                    createdAt = rec.createdAt,
                    metadata = rec.deviceMetadata,
                    session = rec.deviceSession,
                )
                loadActivity(connectionId)
            } catch (e: Exception) {
                _state.value = DesktopDetailState.Error(e.message ?: "Failed to load desktop")
            }
        }
    }

    /**
     * Fetch the most recent N audit entries for this connection so the
     * detail screen can show "what has this desktop done?". Runs in
     * the background — failures degrade silently to an empty list
     * rather than failing the whole detail load.
     */
    private fun loadActivity(connectionId: String) {
        viewModelScope.launch {
            _activity.value = ActivityState.Loading
            val result = auditClient.list(connectionId = connectionId, limit = 20)
            _activity.value = result.fold(
                onSuccess = { ActivityState.Loaded(it.entries) },
                onFailure = { ActivityState.Error(it.message ?: "Failed to load activity") },
            )
        }
    }

    /**
     * Force-ends the current session vault-side without revoking the
     * pairing. Wipes the server-side session key + flips DeviceSession
     * to expired. The desktop will see is_active=false on its next poll
     * (or via the forApp.device.{conn}.ended notification) and land on
     * its Start-New-Session view — the user can start a fresh session
     * without re-pairing. Use [remove] when the goal is to retire the
     * desktop entirely.
     */
    fun endSession(connectionId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _isWorking.value = true
            try {
                val payload = JsonObject().apply {
                    addProperty("connection_id", connectionId)
                    addProperty("reason", "phone_locked")
                }
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "device.end-session",
                    payload = payload,
                    timeoutMs = 15_000L,
                )
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            connectionsClient.invalidateListCache()
                            // Refresh the screen so the Session card
                            // reflects the now-expired state.
                            load(connectionId)
                            _toast.value = "Session ended."
                            onDone()
                        } else {
                            _toast.value = response.error ?: "Failed to end session"
                        }
                    }
                    is VaultResponse.Error -> _toast.value = response.message
                    null -> _toast.value = "Request timed out"
                    else -> _toast.value = "Failed to end session"
                }
            } catch (e: Exception) {
                _toast.value = e.message ?: "Failed to end session"
            } finally {
                _isWorking.value = false
            }
        }
    }

    /**
     * Tears down the desktop session vault-side. Wipes the per-session key,
     * marks the connection revoked, and notifies the desktop so it can clear
     * its local credentials. Used for "Remove desktop" from the detail screen.
     */
    fun remove(connectionId: String, onRemoved: () -> Unit) {
        viewModelScope.launch {
            _isWorking.value = true
            try {
                val payload = JsonObject().apply {
                    addProperty("connection_id", connectionId)
                    addProperty("reason", "user_removed")
                }
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "device.revoke",
                    payload = payload,
                    timeoutMs = 15_000L,
                )
                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            // Refresh the in-memory cache so the feed
                            // immediately reflects the revoke without
                            // waiting for the next poll cycle.
                            connectionsClient.invalidateListCache()
                            onRemoved()
                        } else {
                            _toast.value = response.error ?: "Failed to remove desktop"
                        }
                    }
                    is VaultResponse.Error -> _toast.value = response.message
                    null -> _toast.value = "Request timed out"
                    else -> _toast.value = "Failed to remove desktop"
                }
            } catch (e: Exception) {
                _toast.value = e.message ?: "Failed to remove desktop"
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun clearToast() {
        _toast.value = null
    }
}

sealed class DesktopDetailState {
    object Loading : DesktopDetailState()
    data class Loaded(
        val connectionId: String,
        val deviceName: String,
        val status: String,
        val createdAt: String,
        val metadata: DeviceConnectionMetadata?,
        val session: DeviceConnectionSession?,
    ) : DesktopDetailState()
    data class Error(val message: String) : DesktopDetailState()
}

sealed class ActivityState {
    object Loading : ActivityState()
    data class Loaded(val entries: List<AuditEntry>) : ActivityState()
    data class Error(val message: String) : ActivityState()
}
