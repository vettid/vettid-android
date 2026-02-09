package com.vettid.app.features.agents

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.AgentApprovalRequest
import com.vettid.app.core.nats.OwnerSpaceClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AgentApprovalVM"

/**
 * ViewModel for agent approval screen.
 *
 * Subscribes to agent approval requests from OwnerSpaceClient.
 * On approve/deny, sends the decision to the vault via NATS.
 * The vault then forwards the response to the agent.
 */
@HiltViewModel
class AgentApprovalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ownerSpaceClient: OwnerSpaceClient
) : ViewModel() {

    private val requestIdArg: String? = savedStateHandle.get<String>("requestId")

    private val _state = MutableStateFlow<AgentApprovalState>(AgentApprovalState.Loading)
    val state: StateFlow<AgentApprovalState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<AgentApprovalEffect>()
    val effects: SharedFlow<AgentApprovalEffect> = _effects.asSharedFlow()

    private var currentRequest: AgentApprovalRequest? = null

    init {
        subscribeToApprovalRequests()
        requestIdArg?.let { onEvent(AgentApprovalEvent.Load(it)) }
    }

    fun onEvent(event: AgentApprovalEvent) {
        when (event) {
            is AgentApprovalEvent.Load -> loadRequest(event.requestId)
            is AgentApprovalEvent.Approve -> approveRequest()
            is AgentApprovalEvent.Deny -> denyRequest()
            is AgentApprovalEvent.Dismiss -> dismiss()
        }
    }

    private fun subscribeToApprovalRequests() {
        viewModelScope.launch {
            ownerSpaceClient.agentApprovalRequests.collect { request ->
                Log.d(TAG, "Received approval request: ${request.requestId}")
                currentRequest = request
                _state.value = AgentApprovalState.Ready(request)
            }
        }
    }

    private fun loadRequest(requestId: String) {
        // If we already have the request (from the flow), use it
        currentRequest?.let { req ->
            if (req.requestId == requestId) {
                _state.value = AgentApprovalState.Ready(req)
                return
            }
        }
        // Otherwise wait for it from the flow — state stays Loading
        Log.d(TAG, "Waiting for approval request: $requestId")
    }

    private fun approveRequest() {
        val request = currentRequest ?: return
        viewModelScope.launch {
            _state.value = AgentApprovalState.ProcessingApproval

            try {
                val result = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "agent.approval",
                    payload = com.google.gson.JsonObject().apply {
                        addProperty("request_id", request.requestId)
                        addProperty("response", "approve")
                    }
                )

                if (result != null) {
                    _state.value = AgentApprovalState.Approved()
                    _effects.emit(AgentApprovalEffect.ShowSuccess("Request approved"))
                } else {
                    _state.value = AgentApprovalState.Error("No response from vault")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to approve request", e)
                _state.value = AgentApprovalState.Error(e.message ?: "Failed to approve")
                _effects.emit(AgentApprovalEffect.ShowError(e.message ?: "Failed to approve"))
            }
        }
    }

    private fun denyRequest() {
        val request = currentRequest ?: return
        viewModelScope.launch {
            _state.value = AgentApprovalState.ProcessingDenial

            try {
                val result = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "agent.approval",
                    payload = com.google.gson.JsonObject().apply {
                        addProperty("request_id", request.requestId)
                        addProperty("response", "deny")
                        addProperty("reason", "Owner denied the request")
                    }
                )

                if (result != null) {
                    _state.value = AgentApprovalState.Denied()
                    _effects.emit(AgentApprovalEffect.ShowSuccess("Request denied"))
                } else {
                    _state.value = AgentApprovalState.Error("No response from vault")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deny request", e)
                _state.value = AgentApprovalState.Error(e.message ?: "Failed to deny")
                _effects.emit(AgentApprovalEffect.ShowError(e.message ?: "Failed to deny"))
            }
        }
    }

    private fun dismiss() {
        viewModelScope.launch {
            _effects.emit(AgentApprovalEffect.NavigateBack)
        }
    }
}
