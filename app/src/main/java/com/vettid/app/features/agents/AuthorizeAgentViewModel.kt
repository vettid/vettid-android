package com.vettid.app.features.agents

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vettid.app.core.nats.AgentPendingAuthNotification
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Stage-2 agent authorization (vettid-agent/docs/AGENT-PAIRING-FLOW.md).
 *
 * The agent has resolved its invite code, generated an ephemeral X25519
 * keypair, and posted `agent.request-session`. The vault stores
 * AgentPendingAuth and pushes `agent.pending-authorization` to the owner's
 * phone — OwnerSpaceClient deserializes it into a [AgentPendingAuthNotification]
 * on the [OwnerSpaceClient.agentPendingAuth] flow.
 *
 * This ViewModel:
 *   1. Listens to `agentPendingAuth`, filtered to the route's connectionId.
 *   2. Seeds [AuthorizeAgentState.Ready] with default-on toggles for every
 *      `requested_scope` token and a duration picked from the agent's hint
 *      (capped at the vault's max).
 *   3. On Approve, publishes `agent.authorize-session` with the owner's
 *      final scope / approval_mode / rate_limit / duration. The vault does
 *      the X25519+HKDF, persists the ConnectionContract, and publishes
 *      `agent.session.activated` to the agent's MessageSpace channel.
 *
 * Unlike the desktop authorize flow, scope is the load-bearing input. The
 * agent's [AgentPendingAuthNotification.requestedScope] is a hint — every
 * token here defaults-on but is freely toggleable; tokens outside the
 * requested set can't be added (vault would refuse them anyway, since the
 * agent didn't acknowledge them in its request).
 */
@HiltViewModel
class AuthorizeAgentViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
) : ViewModel() {

    private val _state = MutableStateFlow<AuthorizeAgentState>(AuthorizeAgentState.Waiting)
    val state: StateFlow<AuthorizeAgentState> = _state.asStateFlow()

    private var listenerJob: Job? = null

    /**
     * Called from the screen on first composition. Begins collecting the
     * pending-auth flow filtered to this connectionId; once the notification
     * arrives, seeds the Ready form.
     */
    fun bindConnection(connectionId: String) {
        listenerJob?.cancel()
        listenerJob = viewModelScope.launch {
            ownerSpaceClient.agentPendingAuth.collect { notif ->
                if (notif.connectionId == connectionId) seedReady(notif)
            }
        }
    }

    private fun seedReady(notif: AgentPendingAuthNotification) {
        // If the user has already toggled things on this screen and the
        // vault re-emits (e.g. they backgrounded and came back, hitting
        // replay=1), don't clobber their picks.
        if (_state.value is AuthorizeAgentState.Ready) return

        // Render the FULL canonical capability vocabulary, not just what
        // the agent listed in requested_scope. The owner gets the same
        // menu every time and decides what to grant — the agent's hint
        // only controls the default-on state of each toggle.
        //
        // Tokens the agent requested that aren't in the canonical
        // catalog (e.g. parameterized agent.action.<tool>) are appended
        // so the owner still sees them — scopeMeta() falls back
        // gracefully for unknown tokens.
        val requested = notif.requestedScope.toSet()
        val canonicalToggles = AgentCapabilityCatalog.map { (token, meta) ->
            val granted = if (requested.isEmpty()) {
                meta.defaultGranted
            } else {
                requested.contains(token)
            }
            ScopeToggle(token = token, granted = granted)
        }
        val customToggles = notif.requestedScope
            .filter { it !in AgentCapabilityCatalog.map { (t, _) -> t } }
            .map { ScopeToggle(token = it, granted = true) }
        val toggles = canonicalToggles + customToggles

        // Duration picker: prefer the agent's hint if it's within
        // [60s, max]. Otherwise fall back to the vault default. The
        // vault re-clamps anyway.
        val hint = notif.requestedDurationSeconds
        val seed = when {
            hint in 60..notif.maxDurationSeconds -> hint
            notif.defaultDurationSeconds > 0L -> notif.defaultDurationSeconds
            else -> 60L * 60L
        }

        _state.value = AuthorizeAgentState.Ready(
            notification = notif,
            agentName = agentDisplayName(notif),
            scopes = toggles,
            // Only enable auto-approve when the agent asked for it.
            // Owner can still flip back to always-ask but not vice-versa.
            approvalMode = if (notif.requestedApprovalMode == "auto_within_contract") {
                "auto_within_contract"
            } else {
                "always_ask"
            },
            durationSeconds = seed,
            // Conservative defaults that match the AGENT-PAIRING-FLOW
            // doc's spirit ("rate-limit is owner-chosen"). The vault has
            // no globally-required minimum, but an effectively-disabled
            // limit (Max=0) is more dangerous than a defaultable one.
            rateLimitMax = DEFAULT_RATE_LIMIT_MAX,
            rateLimitPer = DEFAULT_RATE_LIMIT_PER,
        )
    }

    fun toggleScope(token: String, granted: Boolean) {
        val ready = _state.value as? AuthorizeAgentState.Ready ?: return
        _state.value = ready.copy(
            scopes = ready.scopes.map { if (it.token == token) it.copy(granted = granted) else it }
        )
    }

    fun setApprovalMode(mode: String) {
        val ready = _state.value as? AuthorizeAgentState.Ready ?: return
        // Don't let the user pick auto_within_contract if the agent
        // didn't ask for it — the agent might not handle the consequences
        // (silently-approved ops it expected to prompt for).
        if (mode == "auto_within_contract" && ready.notification.requestedApprovalMode != "auto_within_contract") {
            return
        }
        _state.value = ready.copy(approvalMode = mode)
    }

    fun setDuration(seconds: Long) {
        val ready = _state.value as? AuthorizeAgentState.Ready ?: return
        val capped = seconds.coerceIn(60L, ready.notification.maxDurationSeconds)
        _state.value = ready.copy(durationSeconds = capped)
    }

    fun setRateLimit(max: Int, per: String) {
        val ready = _state.value as? AuthorizeAgentState.Ready ?: return
        _state.value = ready.copy(
            rateLimitMax = max.coerceAtLeast(0),
            rateLimitPer = if (per == "minute") "minute" else "hour",
        )
    }

    fun setAgentName(name: String) {
        val ready = _state.value as? AuthorizeAgentState.Ready ?: return
        _state.value = ready.copy(agentName = name)
    }

    fun approve() {
        val ready = _state.value as? AuthorizeAgentState.Ready ?: return
        val notif = ready.notification
        val grantedTokens = ready.scopes.filter { it.granted }.map { it.token }

        _state.value = AuthorizeAgentState.Submitting
        viewModelScope.launch {
            // agent.authorize-session needs the matching AgentPendingAuth
            // to be live in the vault. The agent's request-session can be
            // delayed (initial NATS connect + bootstrap creds) so a "no
            // pending authorization" error means "agent not ready yet" —
            // retry rather than fail the pairing. Same window as device.
            val deadline = System.currentTimeMillis() + AUTHORIZE_RETRY_WINDOW_MS
            while (true) {
                try {
                    val resolvedName = ready.agentName.trim()
                        .ifBlank { agentDisplayName(notif) }
                    val payload = JsonObject().apply {
                        addProperty("connection_id", notif.connectionId)
                        addProperty("approval_token", notif.approvalToken)
                        addProperty("agent_name", resolvedName)
                        add("granted_scope", JsonArray().apply {
                            grantedTokens.forEach { add(it) }
                        })
                        addProperty("approval_mode", ready.approvalMode)
                        addProperty("duration_seconds", ready.durationSeconds)
                        add("rate_limit", JsonObject().apply {
                            addProperty("max", ready.rateLimitMax)
                            addProperty("per", ready.rateLimitPer)
                        })
                    }
                    val response = ownerSpaceClient.sendAndAwaitResponse(
                        messageType = "agent.authorize-session",
                        payload = payload,
                        timeoutMs = 20_000L,
                    )

                    val notReadyMsg = when (response) {
                        is VaultResponse.HandlerResult -> if (!response.success) response.error else null
                        is VaultResponse.Error -> response.message
                        else -> null
                    }
                    if (notReadyMsg != null &&
                        notReadyMsg.contains("pending authorization", ignoreCase = true) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        Log.i(TAG, "authorize-session: agent not ready yet — retrying")
                        delay(AUTHORIZE_RETRY_INTERVAL_MS)
                        continue
                    }

                    when (response) {
                        is VaultResponse.HandlerResult -> {
                            if (response.success) _state.value = AuthorizeAgentState.Done
                            else _state.value = AuthorizeAgentState.Error(
                                response.error ?: "Authorization failed"
                            )
                        }
                        is VaultResponse.Error -> _state.value =
                            AuthorizeAgentState.Error(response.message)
                        null -> _state.value = AuthorizeAgentState.Error("Request timed out")
                        else -> _state.value = AuthorizeAgentState.Error("Unexpected response")
                    }
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Authorization failed", e)
                    _state.value = AuthorizeAgentState.Error(e.message ?: "Failed")
                    return@launch
                }
            }
        }
    }

    /** Owner tapped Deny. Publishes agent.revoke; vault tears down the connection. */
    fun deny() {
        val ready = _state.value as? AuthorizeAgentState.Ready ?: return
        val connId = ready.notification.connectionId

        _state.value = AuthorizeAgentState.Submitting
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("connection_id", connId)
                    addProperty("reason", "owner_denied_authorization")
                }
                ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "agent.revoke",
                    payload = payload,
                    timeoutMs = 10_000L,
                )
                // Revoke is fire-and-forget from the owner's POV — we
                // mark Done either way so the screen pops back.
                _state.value = AuthorizeAgentState.Done
            } catch (e: Exception) {
                Log.e(TAG, "Deny failed", e)
                _state.value = AuthorizeAgentState.Done
            }
        }
    }

    /** Name the vault writes into ConnectionContract.AgentName. */
    private fun agentDisplayName(notif: AgentPendingAuthNotification): String {
        val meta = notif.agentMetadata
        if (meta != null && meta.agentType.isNotBlank()) {
            return if (meta.hostname.isNotBlank()) "${meta.agentType} @ ${meta.hostname}" else meta.agentType
        }
        return "Agent"
    }

    override fun onCleared() {
        super.onCleared()
        listenerJob?.cancel()
    }

    companion object {
        private const val TAG = "AuthorizeAgentVM"
        // Mirror device-side retry window — see AuthorizeDeviceViewModel.
        private const val AUTHORIZE_RETRY_WINDOW_MS = 45_000L
        private const val AUTHORIZE_RETRY_INTERVAL_MS = 2_000L
        // Sensible defaults for the rate-limit picker. Owner is free
        // to crank these higher; agents will get HTTP 429-equivalent
        // back when they exceed Max-per-Per requests against the vault.
        private const val DEFAULT_RATE_LIMIT_MAX = 60
        private const val DEFAULT_RATE_LIMIT_PER = "hour"
    }
}
