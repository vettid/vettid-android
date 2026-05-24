package com.vettid.app.features.agents

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "CreateAgentInvVM"

/**
 * ViewModel for creating agent invitations.
 *
 * Calls the vault to create an agent invitation and returns the
 * invite token which can be used with `vettid-agent init`.
 */
@HiltViewModel
class CreateAgentInvitationViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector
) : ViewModel() {

    private val _state = MutableStateFlow<CreateInvitationState>(CreateInvitationState.Ready)
    val state: StateFlow<CreateInvitationState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CreateInvitationEffect>()
    val effects: SharedFlow<CreateInvitationEffect> = _effects.asSharedFlow()

    fun onEvent(event: CreateInvitationEvent) {
        when (event) {
            is CreateInvitationEvent.Create -> createInvitation(event.name)
            is CreateInvitationEvent.Dismiss -> {
                viewModelScope.launch { _effects.emit(CreateInvitationEffect.NavigateBack) }
            }
        }
    }

    private fun createInvitation(name: String) {
        viewModelScope.launch {
            // Check connection state before sending
            if (natsAutoConnector.connectionState.value !is NatsAutoConnector.AutoConnectState.Connected) {
                _state.value = CreateInvitationState.Error("Not connected to vault. Please wait for connection.")
                return@launch
            }

            _state.value = CreateInvitationState.Creating

            try {
                // Stage 1 of the agent pairing flow — see
                // vettid-agent/docs/AGENT-PAIRING-FLOW.md. The vault mints
                // a 12-char invite code + scoped NATS creds, publishes the
                // payload to invite.<code> on JetStream, and returns the
                // code for the user to paste into `vettid-agent init`.
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "agent.create-invite",
                    payload = com.google.gson.JsonObject().apply {
                        // Placeholder label for the brief window between
                        // Stage 1 (this call) and Stage 2 (authorize-
                        // session, which overwrites PeerAlias with the
                        // owner-entered name from AuthorizeAgentScreen).
                        // Using "Pending agent…" instead of generic
                        // "Agent" so the transient state is self-
                        // explanatory if the connection list paints
                        // before the activation event lands.
                        addProperty("label", name.ifBlank { "Pending agent…" })
                    }
                )

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            val result = response.result
                            val connectionId = result?.get("connection_id")?.asString ?: ""
                            val inviteCode = result?.get("invite_code")?.asString ?: ""
                            val expiresAt = result?.get("expires_at")?.asString ?: ""

                            if (inviteCode.isBlank()) {
                                _state.value = CreateInvitationState.Error(
                                    "Vault returned an empty invite code"
                                )
                            } else {
                                _state.value = CreateInvitationState.Created(
                                    connectionId = connectionId,
                                    inviteCode = inviteCode,
                                    expiresAt = expiresAt
                                )
                            }
                        } else {
                            _state.value = CreateInvitationState.Error(
                                response.error ?: "Failed to create invitation"
                            )
                        }
                    }
                    is VaultResponse.Error -> {
                        _state.value = CreateInvitationState.Error(response.message)
                    }
                    else -> {
                        _state.value = CreateInvitationState.Error("Unexpected response")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create invitation", e)
                _state.value = CreateInvitationState.Error(e.message ?: "Failed to create invitation")
            }
        }
    }
}
