package com.vettid.app.features.agents

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AgentDetailVM"

/**
 * One minor secret in the visibility list, with its current
 * Discoverability state. The screen toggles between "private" and
 * "cataloged" — peers/agents see the item iff Discoverability is not
 * private.
 */
data class VisibilityItem(
    val secretId: String,
    val name: String,
    val category: String,
    val discoverability: String,  // "public" | "cataloged" | "private" | "cataloged-for-use"
) {
    val visible: Boolean get() = discoverability != "private" && discoverability != ""
}

sealed class AgentDetailState {
    object Loading : AgentDetailState()
    data class Loaded(
        val agent: AgentConnection,
        val items: List<VisibilityItem>,
    ) : AgentDetailState()
    data class Error(val message: String) : AgentDetailState()
}

sealed class AgentDetailEvent {
    data class ToggleVisibility(val secretId: String, val makeVisible: Boolean) : AgentDetailEvent()
    object ShowAll : AgentDetailEvent()
    object HideAll : AgentDetailEvent()
    object Refresh : AgentDetailEvent()
}

sealed class AgentDetailEffect {
    data class ShowError(val message: String) : AgentDetailEffect()
    data class ShowMessage(val message: String) : AgentDetailEffect()
}

/**
 * ViewModel for the per-agent details screen. Loads the agent's
 * Contract (capabilities) and the user's minor-secret inventory with
 * Discoverability state, lets the owner flip each secret's visibility
 * in bulk. Visibility is GLOBAL — a secret toggled cataloged here is
 * visible to every connection (peers AND every agent), not only this
 * one. That matches the existing peer-profile model; per-connection
 * visibility would require a separate index that doesn't exist yet.
 */
@HiltViewModel
class AgentDetailViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector,
) : ViewModel() {

    private val _state = MutableStateFlow<AgentDetailState>(AgentDetailState.Loading)
    val state: StateFlow<AgentDetailState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<AgentDetailEffect>()
    val effects: SharedFlow<AgentDetailEffect> = _effects.asSharedFlow()

    private var connectionId: String = ""

    fun bind(connectionId: String) {
        if (this.connectionId == connectionId) return
        this.connectionId = connectionId
        viewModelScope.launch {
            natsAutoConnector.connectionState.collect { conn ->
                if (conn is NatsAutoConnector.AutoConnectState.Connected) {
                    load()
                    return@collect
                }
            }
        }
    }

    fun onEvent(e: AgentDetailEvent) {
        when (e) {
            is AgentDetailEvent.ToggleVisibility -> toggle(e.secretId, e.makeVisible)
            AgentDetailEvent.ShowAll -> bulkSet(makeVisible = true)
            AgentDetailEvent.HideAll -> bulkSet(makeVisible = false)
            AgentDetailEvent.Refresh -> viewModelScope.launch { load() }
        }
    }

    private suspend fun load() {
        _state.value = AgentDetailState.Loading
        try {
            val agent = fetchAgent(connectionId)
            if (agent == null) {
                _state.value = AgentDetailState.Error("Agent not found")
                return
            }
            val items = fetchMinorSecrets()
            _state.value = AgentDetailState.Loaded(agent, items)
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            _state.value = AgentDetailState.Error(e.message ?: "Load failed")
        }
    }

    private suspend fun fetchAgent(connectionId: String): AgentConnection? {
        val resp = ownerSpaceClient.sendAndAwaitResponse("agent.list", JsonObject())
        if (resp !is VaultResponse.HandlerResult || !resp.success) return null
        val arr = resp.result?.getAsJsonArray("agents") ?: return null
        for (el in arr) {
            val obj = el.asJsonObject
            if (obj.get("connection_id")?.asString != connectionId) continue
            return AgentConnection(
                connectionId = connectionId,
                agentName = obj.get("agent_name")?.asString ?: "Unknown",
                agentType = obj.get("agent_type")?.asString ?: "",
                status = obj.get("status")?.asString ?: "unknown",
                approvalMode = obj.get("approval_mode")?.asString ?: "always_ask",
                scope = obj.getAsJsonArray("scope")?.map { it.asString } ?: emptyList(),
                connectedAt = obj.get("paired_at")?.asString
                    ?: obj.get("connected_at")?.asString ?: "",
                lastActiveAt = obj.get("last_active_at")?.asString,
                hostname = obj.get("hostname")?.asString,
                platform = obj.get("platform")?.asString,
            )
        }
        return null
    }

    private suspend fun fetchMinorSecrets(): List<VisibilityItem> {
        val resp = ownerSpaceClient.sendAndAwaitResponse("secret.list", JsonObject())
        if (resp !is VaultResponse.HandlerResult || !resp.success) return emptyList()
        val arr = resp.result?.getAsJsonArray("secrets") ?: return emptyList()
        val out = mutableListOf<VisibilityItem>()
        for (el in arr) {
            val obj = el.asJsonObject
            val id = obj.get("id")?.asString ?: continue
            out += VisibilityItem(
                secretId = id,
                name = obj.get("name")?.asString ?: id,
                category = obj.get("category")?.asString.orEmpty(),
                discoverability = obj.get("discoverability")?.asString.orEmpty(),
            )
        }
        return out
    }

    private fun toggle(secretId: String, makeVisible: Boolean) {
        viewModelScope.launch {
            // Optimistic update so the toggle responds instantly. Reverted
            // on op failure.
            val prev = (_state.value as? AgentDetailState.Loaded) ?: return@launch
            val target = if (makeVisible) "cataloged" else "private"
            _state.value = prev.copy(items = prev.items.map {
                if (it.secretId == secretId) it.copy(discoverability = target) else it
            })

            val payload = JsonObject().apply {
                addProperty("id", secretId)
                addProperty("discoverability", target)
            }
            val resp = ownerSpaceClient.sendAndAwaitResponse(
                "secret.set-discoverability", payload, 10_000L
            )
            if (resp !is VaultResponse.HandlerResult || !resp.success) {
                _effects.emit(AgentDetailEffect.ShowError("Could not update visibility"))
                _state.value = prev  // revert
            }
        }
    }

    private fun bulkSet(makeVisible: Boolean) {
        viewModelScope.launch {
            val current = (_state.value as? AgentDetailState.Loaded) ?: return@launch
            val target = if (makeVisible) "cataloged" else "private"
            val toChange = current.items.filter { (it.discoverability != target) }
            if (toChange.isEmpty()) return@launch

            var ok = 0
            var failed = 0
            for (item in toChange) {
                val payload = JsonObject().apply {
                    addProperty("id", item.secretId)
                    addProperty("discoverability", target)
                }
                val resp = ownerSpaceClient.sendAndAwaitResponse(
                    "secret.set-discoverability", payload, 10_000L
                )
                if (resp is VaultResponse.HandlerResult && resp.success) ok++ else failed++
            }
            val verb = if (makeVisible) "shared" else "hidden"
            if (failed == 0) {
                _effects.emit(AgentDetailEffect.ShowMessage("$ok secret(s) $verb"))
            } else {
                _effects.emit(AgentDetailEffect.ShowError("$ok updated, $failed failed"))
            }
            load()  // refresh from source of truth
        }
    }
}
