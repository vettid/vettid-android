package com.vettid.app.features.actions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vettid.app.core.actions.*
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ActionsViewModel"

/**
 * Single ViewModel for the shared-action layer's UI surfaces:
 *
 *  - Settings list of my own actions (catalog + per-action mode/allowlist)
 *  - Available-actions submenu on a connection card (peer's published actions)
 *  - Param-form invocation flow with inline schema validation
 *  - Pending-approval inbox + Approve / Deny
 *  - Async result delivery (forApp.action.result subscription)
 */
@HiltViewModel
class ActionsViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
) : ViewModel() {
    private val gson = Gson()

    private val _myActions = MutableStateFlow<List<MyActionEntry>>(emptyList())
    val myActions: StateFlow<List<MyActionEntry>> = _myActions.asStateFlow()

    private val _peerActions = MutableStateFlow<List<PublishedAction>>(emptyList())
    val peerActions: StateFlow<List<PublishedAction>> = _peerActions.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingActionApproval>>(emptyList())
    val pending: StateFlow<List<PendingActionApproval>> = _pending.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _results = MutableSharedFlow<ActionInvocationResult>(replay = 0, extraBufferCapacity = 8)
    val results: SharedFlow<ActionInvocationResult> = _results.asSharedFlow()

    fun loadMyActions() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val resp = ownerSpaceClient.sendAndAwaitResponse("action.list-mine", JsonObject(), 10000L)
                if (resp is VaultResponse.HandlerResult && resp.success && resp.result != null) {
                    val parsed = gson.fromJson(resp.result, MyActionsResponse::class.java)
                    _myActions.value = parsed.actions
                } else {
                    _error.value = "list-mine failed: ${(resp as? VaultResponse.Error)?.message ?: "unknown"}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMyActions", e); _error.value = e.message
            } finally { _busy.value = false }
        }
    }

    fun loadPeerActions(connectionId: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val resp = ownerSpaceClient.sendAndAwaitResponse(
                    "action.list-on-peer", ActionRequests.listOnPeer(connectionId), 10000L
                )
                if (resp is VaultResponse.HandlerResult && resp.success && resp.result != null) {
                    val arr = resp.result.getAsJsonArray("actions")
                    val list = arr?.map { gson.fromJson(it, PublishedAction::class.java) } ?: emptyList()
                    _peerActions.value = list
                } else {
                    _peerActions.value = emptyList()
                    _error.value = "list-on-peer failed: ${(resp as? VaultResponse.Error)?.message ?: "unknown"}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadPeerActions", e); _error.value = e.message
            } finally { _busy.value = false }
        }
    }

    fun setEnabled(
        actionId: String,
        mode: ActionAuthMode,
        allowlist: List<String> = emptyList(),
        ownerParams: Map<String, Any?>? = null,
    ) {
        viewModelScope.launch {
            try {
                ownerSpaceClient.sendAndAwaitResponse(
                    "action.set-enabled",
                    ActionRequests.setEnabled(actionId, mode, allowlist, ownerParams),
                    10000L
                )
                loadMyActions()
            } catch (e: Exception) {
                Log.e(TAG, "setEnabled", e); _error.value = e.message
            }
        }
    }

    /**
     * Invoke an action on the peer behind connectionId. Validates params
     * against the action's schema BEFORE sending — bad params surface as
     * an inline error before the wire call.
     *
     * Returns the invocation_id so the caller can correlate the async
     * result on the [results] flow.
     */
    fun invokeOnPeer(
        connectionId: String,
        action: PublishedAction,
        params: JsonElement,
        onSent: (invocationId: String) -> Unit = {},
    ) {
        val schemaErr = ActionSchemaValidator.validate(action.paramSchema, params)
        if (schemaErr != null) {
            _error.value = schemaErr
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                val resp = ownerSpaceClient.sendAndAwaitResponse(
                    "action.invoke-on-peer",
                    ActionRequests.invokeOnPeer(connectionId, action.id, action.version, params),
                    15000L
                )
                if (resp is VaultResponse.HandlerResult && resp.success && resp.result != null) {
                    val invocationId = resp.result.get("invocation_id")?.asString ?: ""
                    onSent(invocationId)
                } else {
                    _error.value = "invoke failed: ${(resp as? VaultResponse.Error)?.message ?: "unknown"}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "invokeOnPeer", e); _error.value = e.message
            } finally { _busy.value = false }
        }
    }

    fun loadPending() {
        viewModelScope.launch {
            try {
                val resp = ownerSpaceClient.sendAndAwaitResponse("action.list-pending", JsonObject(), 10000L)
                if (resp is VaultResponse.HandlerResult && resp.success && resp.result != null) {
                    val arr = resp.result.getAsJsonArray("pending")
                    _pending.value = arr?.map { gson.fromJson(it, PendingActionApproval::class.java) } ?: emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadPending", e); _error.value = e.message
            }
        }
    }

    fun approve(invocationId: String, ownerOverrides: Map<String, Any?>? = null) {
        viewModelScope.launch {
            try {
                ownerSpaceClient.sendAndAwaitResponse(
                    "action.approve",
                    ActionRequests.approve(invocationId, ownerOverrides = ownerOverrides),
                    20000L
                )
                loadPending()
            } catch (e: Exception) { Log.e(TAG, "approve", e); _error.value = e.message }
        }
    }

    fun deny(invocationId: String, note: String? = null) {
        viewModelScope.launch {
            try {
                ownerSpaceClient.sendAndAwaitResponse(
                    "action.deny", ActionRequests.deny(invocationId, note), 10000L
                )
                loadPending()
            } catch (e: Exception) { Log.e(TAG, "deny", e); _error.value = e.message }
        }
    }

    /**
     * Hook called from a forApp NATS subscriber (action.result). Forwards
     * the parsed envelope to the [results] flow so the invoker's UI can
     * render success / failure / pending modals.
     */
    fun onIncomingResult(rawJson: String) {
        try {
            val parsed = gson.fromJson(JsonParser.parseString(rawJson), ActionInvocationResult::class.java)
            viewModelScope.launch { _results.emit(parsed) }
        } catch (e: Exception) {
            Log.w(TAG, "failed to parse incoming action result: ${e.message}")
        }
    }

    fun clearError() { _error.value = null }
}
