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
                if (rec.connectionType != "device" && rec.connectionType != "agent") {
                    _state.value = DesktopDetailState.Error("Not a desktop or agent connection")
                    return@launch
                }
                // For agents, hydrate the metadata + contract from
                // agent.list. ConnectionRecord.deviceMetadata is null
                // for non-device types per the vault schema. agent.list
                // is the authoritative source for the contract surface
                // (scope / approval_mode / rate_limit) and the
                // auto-derived agent_name.
                val agentData = if (rec.connectionType == "agent") {
                    fetchAgentDetails(connectionId)
                } else {
                    null
                }
                val metadata = agentData?.metadata ?: rec.deviceMetadata
                val session = if (rec.connectionType == "agent") null else rec.deviceSession
                val deviceName = when {
                    rec.connectionType == "agent" -> agentData?.agentName
                        ?: metadata?.hostname
                        ?: "Agent"
                    rec.label.isNotBlank() -> rec.label
                    metadata?.hostname != null -> metadata.hostname!!
                    else -> "Desktop"
                }
                // Status maps to the agent paired/revoked vocabulary
                // when this is an agent (vault now returns "paired" or
                // "revoked" directly per Phase A) — for devices it stays
                // whatever the connection record says.
                val status = if (rec.connectionType == "agent")
                    agentData?.status ?: rec.status
                else rec.status
                _state.value = DesktopDetailState.Loaded(
                    connectionId = rec.connectionId,
                    deviceName = deviceName,
                    status = status,
                    createdAt = agentData?.pairedAt ?: rec.createdAt,
                    metadata = metadata,
                    session = session,
                    connectionType = rec.connectionType,
                    contract = agentData?.contract,
                )
                loadActivity(connectionId)
            } catch (e: Exception) {
                _state.value = DesktopDetailState.Error(e.message ?: "Failed to load desktop")
            }
        }
    }

    /**
     * Fetch the agent record from `agent.list` and map its fields onto
     * the device-shaped state types so the existing detail UI works
     * without a per-type branch. Returns null on any failure — the
     * screen renders empty sections rather than an error, since the
     * connection record itself was found in the connection list.
     */
    private suspend fun fetchAgentDetails(connectionId: String): AgentDetailsLoad? {
        return try {
            val response = ownerSpaceClient.sendAndAwaitResponse(
                messageType = "agent.list",
                payload = JsonObject(),
            )
            val resp = response as? VaultResponse.HandlerResult ?: return null
            if (!resp.success) return null
            val agentsArray = resp.result?.getAsJsonArray("agents") ?: return null
            val obj = agentsArray.map { it.asJsonObject }
                .firstOrNull { it.get("connection_id")?.asString == connectionId }
                ?: return null

            val hostname = obj.get("hostname")?.asString
            val platform = obj.get("platform")?.asString
            val agentName = obj.get("agent_name")?.asString
            val agentType = obj.get("agent_type")?.asString
            val pairedAtStr = obj.get("paired_at")?.asString
                ?: obj.get("connected_at")?.asString
            val lastActiveStr = obj.get("last_active_at")?.asString
            val status = obj.get("status")?.asString ?: "paired"

            val metadata = DeviceConnectionMetadata(
                deviceName = agentName,
                hostname = hostname,
                platform = platform,
                appVersion = agentType,
                firstSeenAt = parseEpochSecondsLoose(pairedAtStr),
            )

            // Contract — the new user-visible truth of "what is this
            // agent allowed to do?". Defaults are conservative so the
            // UI degrades gracefully on missing fields.
            val scope = obj.getAsJsonArray("scope")?.mapNotNull { it.asString } ?: emptyList()
            val approvalMode = obj.get("approval_mode")?.asString ?: "always_ask"
            val rateLimitObj = obj.getAsJsonObject("rate_limit")
            val contract = AgentContract(
                scope = scope,
                approvalMode = approvalMode,
                rateLimitMax = rateLimitObj?.get("max")?.asInt ?: 0,
                rateLimitPer = rateLimitObj?.get("per")?.asString ?: "hour",
            )

            AgentDetailsLoad(
                metadata = metadata,
                contract = contract,
                status = status,
                agentName = agentName ?: "",
                pairedAt = pairedAtStr ?: "",
                lastActiveAt = lastActiveStr ?: "",
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Bundled payload from fetchAgentDetails — pulls all the agent-
     *  shaped fields together so the load() flow can build Loaded
     *  state in one go without juggling tuples. */
    private data class AgentDetailsLoad(
        val metadata: DeviceConnectionMetadata,
        val contract: AgentContract,
        val status: String,
        val agentName: String,
        val pairedAt: String,
        val lastActiveAt: String,
    )

    private fun parseEpochSecondsLoose(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            java.time.Instant.parse(iso).epochSecond
        } catch (_: Exception) {
            0L
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
        val connectionType: String = "device",
        /** Agent's Contract — scope/approval/rate. Null for devices. */
        val contract: AgentContract? = null,
    ) : DesktopDetailState()
    data class Error(val message: String) : DesktopDetailState()
}

/**
 * Contract that defines what a paired agent is allowed to do. The
 * vault stores the authoritative copy; the owner edits via
 * agent.update-contract. See docs/AGENT-PAIRED-CONTRACT-MODEL.md.
 */
data class AgentContract(
    val scope: List<String>,
    val approvalMode: String,
    val rateLimitMax: Int,
    val rateLimitPer: String,
)

sealed class ActivityState {
    object Loading : ActivityState()
    data class Loaded(val entries: List<AuditEntry>) : ActivityState()
    data class Error(val message: String) : ActivityState()
}
