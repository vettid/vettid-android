package com.vettid.app.features.agents

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vettid.app.core.nats.AgentLeashMintPendingNotification
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
 * Per-scope toggle state for the LeashApprovalScreen's scope picker.
 * Same shape as AuthorizeAgentScreen's ScopeToggle but specialized to
 * LEASH grammar tokens (resource:action[:qualifier]) rather than agent
 * capabilities (verb-shaped).
 */
data class LeashScopeToggle(
    val token: String,
    val granted: Boolean,
)

sealed class LeashApprovalState {
    /** Waiting for the leash-mint-pending push from the vault. */
    object Waiting : LeashApprovalState()

    data class Ready(
        val notification: AgentLeashMintPendingNotification,
        val scopes: List<LeashScopeToggle>,
        val durationSeconds: Long,
    ) : LeashApprovalState()

    object Submitting : LeashApprovalState()
    object Done : LeashApprovalState()
    data class Error(val message: String) : LeashApprovalState()
}

/**
 * Stage-2-style authorization for agent-initiated LEASH mints. The agent
 * already paired and now requested a LEASH via vettid-agent leash mint
 * (or the equivalent local-API call). Vault stored a PendingLeashRequest
 * and pushed agent.leash-mint-pending — this VM listens, shows the
 * scope picker to the owner, and publishes agent.leash-approve back
 * with the final scope/duration.
 *
 * Approve / Deny outcomes:
 *   - vault HandleAgentLeashApprove mints (or denies) and publishes
 *     AgentMsgLeashGranted/Denied to the agent's response subject.
 *     The agent's CLI receives the JWT (or denial) without the owner
 *     having to copy anything back manually.
 */
@HiltViewModel
class LeashApprovalViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
) : ViewModel() {

    private val _state = MutableStateFlow<LeashApprovalState>(LeashApprovalState.Waiting)
    val state: StateFlow<LeashApprovalState> = _state.asStateFlow()

    private var listenerJob: Job? = null

    /** Called from the screen on first composition with the request_id. */
    fun bindRequest(requestId: String) {
        listenerJob?.cancel()
        listenerJob = viewModelScope.launch {
            ownerSpaceClient.agentLeashMintPending.collect { notif ->
                if (notif.requestId == requestId) seedReady(notif)
            }
        }
    }

    private fun seedReady(notif: AgentLeashMintPendingNotification) {
        if (_state.value is LeashApprovalState.Ready) return  // don't clobber edits on replay

        val toggles = notif.requestedScope
            .map { LeashScopeToggle(token = it, granted = true) }

        _state.value = LeashApprovalState.Ready(
            notification = notif,
            scopes = toggles,
            durationSeconds = notif.durationSeconds,
        )
    }

    fun toggleScope(token: String, granted: Boolean) {
        val ready = _state.value as? LeashApprovalState.Ready ?: return
        _state.value = ready.copy(
            scopes = ready.scopes.map { if (it.token == token) it.copy(granted = granted) else it },
        )
    }

    fun setDuration(seconds: Long) {
        val ready = _state.value as? LeashApprovalState.Ready ?: return
        _state.value = ready.copy(durationSeconds = seconds.coerceIn(60L, 24L * 3600L))
    }

    fun approve() {
        val ready = _state.value as? LeashApprovalState.Ready ?: return
        val granted = ready.scopes.filter { it.granted }.map { it.token }
        if (granted.isEmpty()) {
            _state.value = LeashApprovalState.Error("Grant at least one scope before approving.")
            return
        }

        _state.value = LeashApprovalState.Submitting
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("request_id", ready.notification.requestId)
                    addProperty("approved", true)
                    add("granted_scope", JsonArray().apply { granted.forEach { add(it) } })
                    addProperty("duration_secs", ready.durationSeconds)
                }
                val resp = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "agent.leash-approve",
                    payload = payload,
                    timeoutMs = 30_000L,
                )
                when (resp) {
                    is VaultResponse.HandlerResult -> if (resp.success) {
                        _state.value = LeashApprovalState.Done
                    } else {
                        _state.value = LeashApprovalState.Error(resp.error ?: "Vault returned failure")
                    }
                    is VaultResponse.Error -> _state.value = LeashApprovalState.Error(resp.message)
                    null -> _state.value = LeashApprovalState.Error("Request timed out")
                    else -> _state.value = LeashApprovalState.Error("Unexpected response")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Approve failed", e)
                _state.value = LeashApprovalState.Error(e.message ?: "Failed")
            }
        }
    }

    fun deny() {
        val ready = _state.value as? LeashApprovalState.Ready ?: return
        _state.value = LeashApprovalState.Submitting
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("request_id", ready.notification.requestId)
                    addProperty("approved", false)
                    addProperty("deny_reason", "owner_denied")
                }
                ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "agent.leash-approve",
                    payload = payload,
                    timeoutMs = 10_000L,
                )
                // Treat the deny op as fire-and-forget from the owner's POV
                // — vault will publish AgentMsgLeashDenied to the agent
                // either way. Pop the screen so the owner gets back to
                // wherever they were.
                _state.value = LeashApprovalState.Done
            } catch (e: Exception) {
                Log.e(TAG, "Deny failed", e)
                _state.value = LeashApprovalState.Done
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        listenerJob?.cancel()
    }

    private companion object {
        const val TAG = "LeashApprovalVM"
    }
}
