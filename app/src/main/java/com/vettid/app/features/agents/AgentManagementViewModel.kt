package com.vettid.app.features.agents

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AgentManagementVM"

/**
 * Read a string field that may be absent OR JSON null. Gson's JsonNull
 * is a non-null JsonElement that throws on .asString — Kotlin's
 * `?.asString` only protects against absent keys, not null values.
 * This helper handles both cases.
 */
internal fun JsonObject.safeString(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return el.asString
}

/**
 * Read an array field that may be absent OR JSON null. Same hazard as
 * safeString: getAsJsonArray() does an unchecked cast that throws
 * ClassCastException when the field is JsonNull (e.g. `"scope": null`
 * on the wire), and `?.` doesn't catch it because JsonNull is a real
 * JsonElement.
 */
internal fun JsonObject.safeStringList(key: String): List<String> {
    val el = get(key) ?: return emptyList()
    if (el.isJsonNull || !el.isJsonArray) return emptyList()
    return el.asJsonArray.mapNotNull { if (it.isJsonNull) null else it.asString }
}

/**
 * ViewModel for agent management screen.
 *
 * Lists connected agents, supports revoking connections.
 * Waits for NATS connection before loading to avoid "failed to publish" errors.
 */
@HiltViewModel
class AgentManagementViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector,
    private val connectionsClient: ConnectionsClient,
) : ViewModel() {

    private val _state = MutableStateFlow<AgentManagementState>(AgentManagementState.Loading)
    val state: StateFlow<AgentManagementState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<AgentManagementEffect>()
    val effects: SharedFlow<AgentManagementEffect> = _effects.asSharedFlow()

    private var hasLoadedInitially = false

    init {
        observeConnectionAndLoad()
        observeConnectionStatusUpdates()
    }

    private fun observeConnectionAndLoad() {
        viewModelScope.launch {
            natsAutoConnector.connectionState.collect { state ->
                if (state is NatsAutoConnector.AutoConnectState.Connected && !hasLoadedInitially) {
                    hasLoadedInitially = true
                    Log.d(TAG, "NATS connected, loading agents")
                    loadAgents()
                }
            }
        }
    }

    /**
     * Refresh the agents list on connection.activated / connection.revoked.
     * Without this, ConnectionsClient.listCacheTtlMs (30s) gates how soon a
     * freshly-paired or freshly-revoked agent shows up — observed as a
     * ~30s lag between "Approved on phone" and the new agent appearing in
     * the list. ConnectionsViewModel listens for the same events for the
     * peers screen; this mirrors that for agents.
     */
    private fun observeConnectionStatusUpdates() {
        viewModelScope.launch {
            ownerSpaceClient.connectionStatusUpdates.collect { update ->
                Log.d(TAG, "Connection status update: ${update.type} — refreshing agents list")
                connectionsClient.invalidateListCache()
                loadAgents()
            }
        }
    }

    fun onEvent(event: AgentManagementEvent) {
        when (event) {
            is AgentManagementEvent.LoadAgents -> loadAgents()
            is AgentManagementEvent.RevokeAgent -> revokeAgent(event.connectionId)
            is AgentManagementEvent.CreateInvitation -> {
                viewModelScope.launch {
                    _effects.emit(AgentManagementEffect.NavigateToCreateInvitation)
                }
            }
        }
    }

    private fun loadAgents() {
        viewModelScope.launch {
            _state.value = AgentManagementState.Loading

            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "agent.list",
                    payload = com.google.gson.JsonObject()
                )

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            val agentsArray = response.result?.let {
                                val el = it.get("agents")
                                if (el == null || el.isJsonNull || !el.isJsonArray) null
                                else el.asJsonArray
                            }
                            if (agentsArray == null || agentsArray.size() == 0) {
                                _state.value = AgentManagementState.Empty
                            } else {
                                // Gson's JsonNull is a non-null JsonElement that throws on
                                // .asString — so Kotlin's `?.asString` doesn't protect against
                                // it. Use safeString() which returns null on both missing keys
                                // AND JsonNull values.
                                val agents = agentsArray.map { element ->
                                    val obj = element.asJsonObject
                                    AgentConnection(
                                        connectionId = obj.safeString("connection_id") ?: "",
                                        agentName = obj.safeString("agent_name") ?: "Unknown",
                                        agentType = obj.safeString("agent_type") ?: "",
                                        status = obj.safeString("status") ?: "unknown",
                                        approvalMode = obj.safeString("approval_mode") ?: "always_ask",
                                        scope = obj.safeStringList("scope"),
                                        // Phase A renamed connected_at → paired_at on
                                        // the wire (docs/AGENT-PAIRED-CONTRACT-MODEL.md).
                                        // Read both for backwards compatibility with
                                        // unmigrated enclaves.
                                        connectedAt = obj.safeString("paired_at")
                                            ?: obj.safeString("connected_at") ?: "",
                                        lastActiveAt = obj.safeString("last_active_at"),
                                        hostname = obj.safeString("hostname"),
                                        platform = obj.safeString("platform"),
                                    )
                                }
                                _state.value = AgentManagementState.Loaded(agents)
                            }
                        } else {
                            _state.value = AgentManagementState.Error(
                                response.error ?: "Failed to load agents"
                            )
                        }
                    }
                    is VaultResponse.Error -> {
                        _state.value = AgentManagementState.Error(response.message)
                    }
                    else -> {
                        _state.value = AgentManagementState.Error("Unexpected response")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load agents", e)
                _state.value = AgentManagementState.Error(e.message ?: "Failed to load agents")
            }
        }
    }

    private fun revokeAgent(connectionId: String) {
        viewModelScope.launch {
            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "agent.revoke",
                    payload = com.google.gson.JsonObject().apply {
                        addProperty("connection_id", connectionId)
                    }
                )

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            // Drop the cached connection list so the
                            // Feed / Connections screens reflect the
                            // revoke immediately instead of lagging
                            // up to ConnectionsClient.listCacheTtlMs
                            // (30s). loadAgents() below ALSO needs a
                            // fresh fetch.
                            connectionsClient.invalidateListCache()
                            _effects.emit(AgentManagementEffect.ShowSuccess("Agent revoked"))
                            loadAgents() // Refresh list
                        } else {
                            _effects.emit(AgentManagementEffect.ShowError(
                                response.error ?: "Failed to revoke agent"
                            ))
                        }
                    }
                    is VaultResponse.Error -> {
                        _effects.emit(AgentManagementEffect.ShowError(response.message))
                    }
                    else -> {
                        _effects.emit(AgentManagementEffect.ShowError("Unexpected response"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to revoke agent", e)
                _effects.emit(AgentManagementEffect.ShowError(e.message ?: "Failed to revoke agent"))
            }
        }
    }
}
