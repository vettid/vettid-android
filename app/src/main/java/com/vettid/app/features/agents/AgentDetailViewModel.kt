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
        val mint: MintDialogState? = null,
    ) : AgentDetailState()
    data class Error(val message: String) : AgentDetailState()
}

sealed class AgentDetailEvent {
    data class ToggleVisibility(val secretId: String, val makeVisible: Boolean) : AgentDetailEvent()
    object ShowAll : AgentDetailEvent()
    object HideAll : AgentDetailEvent()
    object Refresh : AgentDetailEvent()
    // LEASH mint flow
    object OpenMint : AgentDetailEvent()
    object CloseMint : AgentDetailEvent()
    data class MintSetAgentPubkey(val pubkey: String) : AgentDetailEvent()
    data class MintToggleScope(val token: String, val granted: Boolean) : AgentDetailEvent()
    data class MintSetCustomScope(val token: String) : AgentDetailEvent()
    data class MintSetDuration(val seconds: Long) : AgentDetailEvent()
    object MintConfirm : AgentDetailEvent()
    object MintDismissResult : AgentDetailEvent()
}

sealed class AgentDetailEffect {
    data class ShowError(val message: String) : AgentDetailEffect()
    data class ShowMessage(val message: String) : AgentDetailEffect()
}

/**
 * LEASH mint dialog state. Lives next to the visibility list state on
 * AgentDetailState.Loaded — null when no dialog open, populated while
 * the form is up, replaced with Result once the vault returns.
 */
data class MintDialogState(
    val agentPubkeyB64: String,
    val scopes: List<MintScopeToggle>,
    val customScope: String,
    val durationSeconds: Long,
    val submitting: Boolean = false,
    val result: MintResult? = null,
    val error: String? = null,
)

data class MintScopeToggle(
    val token: String,
    val label: String,
    val description: String,
    val granted: Boolean,
)

data class MintResult(
    val jwt: String,
    val jti: String,
    val expiresAt: Long,
)

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
            AgentDetailEvent.OpenMint -> openMint()
            AgentDetailEvent.CloseMint -> closeMint()
            is AgentDetailEvent.MintSetAgentPubkey -> updateMint { m -> m.copy(agentPubkeyB64 = e.pubkey.trim()) }
            is AgentDetailEvent.MintToggleScope -> updateMint { m ->
                m.copy(
                    scopes = m.scopes.map {
                        if (it.token == e.token) it.copy(granted = e.granted) else it
                    },
                )
            }
            is AgentDetailEvent.MintSetCustomScope -> updateMint { m -> m.copy(customScope = e.token) }
            is AgentDetailEvent.MintSetDuration -> updateMint { m -> m.copy(durationSeconds = e.seconds) }
            AgentDetailEvent.MintConfirm -> mint()
            AgentDetailEvent.MintDismissResult -> closeMint()
        }
    }

    private fun openMint() {
        val current = (_state.value as? AgentDetailState.Loaded) ?: return
        val toggles = LeashCommonScopes.map {
            MintScopeToggle(
                token = it.token,
                label = it.label,
                description = it.description,
                granted = it.defaultGranted,
            )
        }
        _state.value = current.copy(
            mint = MintDialogState(
                agentPubkeyB64 = "",
                scopes = toggles,
                customScope = "",
                durationSeconds = DEFAULT_MINT_DURATION_SECONDS,
            ),
        )
    }

    private fun closeMint() {
        val current = (_state.value as? AgentDetailState.Loaded) ?: return
        _state.value = current.copy(mint = null)
    }

    private inline fun updateMint(transform: (MintDialogState) -> MintDialogState) {
        val current = (_state.value as? AgentDetailState.Loaded) ?: return
        val mint = current.mint ?: return
        _state.value = current.copy(mint = transform(mint))
    }

    private fun mint() {
        val current = (_state.value as? AgentDetailState.Loaded) ?: return
        val mint = current.mint ?: return
        val pubkey = mint.agentPubkeyB64.trim()
        if (pubkey.isEmpty()) {
            _state.value = current.copy(
                mint = mint.copy(error = "Agent pubkey required — run `vettid-agent leash pubkey` and paste the output."),
            )
            return
        }
        // Ed25519 pubkey is 32 bytes → 43 base64url chars (no padding) or
        // 44 with a single '=' pad. Server re-validates; this is just
        // fast-fail UX.
        if (pubkey.length !in 43..44 || pubkey.any { !(it.isLetterOrDigit() || it == '-' || it == '_' || it == '=') }) {
            _state.value = current.copy(
                mint = mint.copy(error = "Agent pubkey must be a 32-byte base64url Ed25519 key."),
            )
            return
        }
        val granted = mint.scopes.filter { it.granted }.map { it.token }.toMutableList()
        val custom = mint.customScope.trim()
        if (custom.isNotEmpty()) {
            if (!isValidLeashScope(custom)) {
                _state.value = current.copy(
                    mint = mint.copy(error = "Custom scope token does not match the LEASH grammar."),
                )
                return
            }
            if (custom !in granted) granted += custom
        }
        if (granted.isEmpty()) {
            _state.value = current.copy(
                mint = mint.copy(error = "Grant at least one scope before minting."),
            )
            return
        }

        _state.value = current.copy(mint = mint.copy(submitting = true, error = null))
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("connection_id", current.agent.connectionId)
                    add("scope", com.google.gson.JsonArray().apply { granted.forEach { add(it) } })
                    addProperty("duration_secs", mint.durationSeconds)
                    addProperty("agent_pubkey", pubkey)
                }
                val resp = ownerSpaceClient.sendAndAwaitResponse("leash.attest", payload, 30_000L)
                val state = _state.value as? AgentDetailState.Loaded ?: return@launch
                val current2 = state.mint ?: return@launch
                if (resp is VaultResponse.HandlerResult && resp.success && resp.result != null) {
                    val jwt = resp.result.safeString("leash").orEmpty()
                    val jti = resp.result.safeString("jti").orEmpty()
                    val expiresAt = resp.result.get("expires_at")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    _state.value = state.copy(
                        mint = current2.copy(
                            submitting = false,
                            result = MintResult(jwt = jwt, jti = jti, expiresAt = expiresAt),
                        ),
                    )
                } else {
                    val msg = when (resp) {
                        is VaultResponse.HandlerResult -> resp.error ?: "Mint failed"
                        is VaultResponse.Error -> resp.message
                        else -> "Unexpected response"
                    }
                    _state.value = state.copy(mint = current2.copy(submitting = false, error = msg))
                }
            } catch (e: Exception) {
                Log.e(TAG, "mint failed", e)
                val s = _state.value as? AgentDetailState.Loaded ?: return@launch
                val cm = s.mint ?: return@launch
                _state.value = s.copy(mint = cm.copy(submitting = false, error = e.message ?: "Mint failed"))
            }
        }
    }

    private companion object {
        const val DEFAULT_MINT_DURATION_SECONDS = 30L * 60L  // 30 minutes — matches LEASH demo session
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
            if (obj.safeString("connection_id") != connectionId) continue
            return AgentConnection(
                connectionId = connectionId,
                agentName = obj.safeString("agent_name") ?: "Unknown",
                agentType = obj.safeString("agent_type") ?: "",
                status = obj.safeString("status") ?: "unknown",
                approvalMode = obj.safeString("approval_mode") ?: "always_ask",
                scope = obj.getAsJsonArray("scope")
                    ?.mapNotNull { if (it.isJsonNull) null else it.asString }
                    ?: emptyList(),
                connectedAt = obj.safeString("paired_at")
                    ?: obj.safeString("connected_at") ?: "",
                lastActiveAt = obj.safeString("last_active_at"),
                hostname = obj.safeString("hostname"),
                platform = obj.safeString("platform"),
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
            val id = obj.safeString("id") ?: continue
            out += VisibilityItem(
                secretId = id,
                name = obj.safeString("name") ?: id,
                category = obj.safeString("category").orEmpty(),
                discoverability = obj.safeString("discoverability").orEmpty(),
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
