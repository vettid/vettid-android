package com.vettid.app.features.agents

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AgentManagementVM"

/**
 * ViewModel for agent management screen.
 *
 * Lists connected agents, supports revoking connections.
 */
@HiltViewModel
class AgentManagementViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) : ViewModel() {

    private val _state = MutableStateFlow<AgentManagementState>(AgentManagementState.Loading)
    val state: StateFlow<AgentManagementState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<AgentManagementEffect>()
    val effects: SharedFlow<AgentManagementEffect> = _effects.asSharedFlow()

    init {
        loadAgents()
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
                            val agentsArray = response.result?.getAsJsonArray("agents")
                            if (agentsArray == null || agentsArray.size() == 0) {
                                _state.value = AgentManagementState.Empty
                            } else {
                                val agents = agentsArray.map { element ->
                                    val obj = element.asJsonObject
                                    AgentConnection(
                                        connectionId = obj.get("connection_id")?.asString ?: "",
                                        agentName = obj.get("agent_name")?.asString ?: "Unknown",
                                        agentType = obj.get("agent_type")?.asString ?: "",
                                        status = obj.get("status")?.asString ?: "unknown",
                                        approvalMode = obj.get("approval_mode")?.asString ?: "always_ask",
                                        scope = obj.getAsJsonArray("scope")?.map { it.asString } ?: emptyList(),
                                        connectedAt = obj.get("connected_at")?.asString ?: "",
                                        lastActiveAt = obj.get("last_active_at")?.asString,
                                        hostname = obj.get("hostname")?.asString,
                                        platform = obj.get("platform")?.asString
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
