package com.vettid.app.features.grants

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.features.feed.ApprovalNotificationKind
import com.vettid.app.features.feed.FeedNotificationService
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "DataGrantApprovalVM"

/**
 * Backs the full-screen approval prompt for an incoming data/secret
 * access request. A request covering an alias group is ONE request in
 * the vault (one pending record, one event) — this screen loads its
 * fields and approves / denies the whole request as a unit.
 *
 * Regular grants need no password — the vault has already authorized
 * the peer to issue requests against this connection; approval here
 * just consents to the requested items + expiry + max-uses. The vault
 * enforces those server-side at fetch time.
 */
@HiltViewModel
class DataGrantApprovalViewModel @Inject constructor(
    private val grants: GrantsRepository,
    private val feedRepository: FeedRepository,
    private val notificationService: FeedNotificationService,
    private val ownerSpaceClient: OwnerSpaceClient,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val requestId: String = savedStateHandle["requestId"] ?: ""
    val connectionId: String = savedStateHandle["connectionId"] ?: ""
    private val anchorItemKind: String = savedStateHandle["itemKind"] ?: ""
    private val anchorItemRef: String = savedStateHandle["itemRef"] ?: ""
    private val anchorItemLabel: String = savedStateHandle["itemLabel"] ?: ""
    val requestedMode: String = savedStateHandle["requestedMode"] ?: GrantModes.ONE_SHOT
    val requestedExpiresAt: Long = savedStateHandle["requestedExpiresAt"] ?: 0L
    val requestedMaxUses: Int = savedStateHandle["requestedMaxUses"] ?: 1
    val reason: String = savedStateHandle["reason"] ?: ""

    val peerName: String = resolvePeerName(connectionId)

    /** One field the request covers. */
    data class RequestItem(
        val itemKind: String,
        val itemRef: String,
        val itemLabel: String,
    )

    // The fields this request covers. Seeded from the route's single
    // item so the screen renders instantly; loadGroup() replaces it
    // with the full set once the pending request is loaded.
    private val _items = MutableStateFlow(
        listOf(RequestItem(anchorItemKind, anchorItemRef, anchorItemLabel))
    )
    val items: StateFlow<List<RequestItem>> = _items.asStateFlow()

    // The alias / group label when the request covers multiple fields;
    // blank for a single-field request.
    private val _alias = MutableStateFlow("")
    val alias: StateFlow<String> = _alias.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        loadGroup()
    }

    private fun resolvePeerName(connID: String): String {
        if (connID.isBlank()) return "A connection"
        val connection = feedRepository.getCachedConnections()
            .firstOrNull { it.connectionId == connID } ?: return "A connection"
        val first = connection.peerProfile?.firstName.orEmpty().trim()
        val last = connection.peerProfile?.lastName.orEmpty().trim()
        val full = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
        if (full.isNotEmpty()) return full
        return connection.label.trim().takeIf { it.isNotEmpty() } ?: "A connection"
    }

    /**
     * Loads the pending request's full field list from the vault. A
     * request for an alias group carries every field in `items`; the
     * request's item_label is the group label (alias).
     */
    private fun loadGroup() {
        if (requestId.isEmpty()) return
        viewModelScope.launch {
            val pending = grants.listPending().getOrNull()
                ?.firstOrNull { it.requestId == requestId } ?: return@launch
            val fields = pending.items.takeIf { it.isNotEmpty() } ?: return@launch
            _items.value = fields.map {
                RequestItem(itemKind = it.itemKind, itemRef = it.itemRef, itemLabel = it.itemLabel)
            }
            // A multi-field request names the alias in item_label.
            _alias.value = if (fields.size > 1) pending.itemLabel else ""
        }
    }

    fun approve() {
        if (requestId.isEmpty()) {
            _state.value = State.Error("Missing request_id")
            return
        }
        viewModelScope.launch {
            _state.value = State.Submitting
            // One approve resolves the whole request — the vault creates
            // a grant per field.
            grants.approve(requestId, requestedExpiresAt, requestedMaxUses, requestedMode)
                .onSuccess {
                    notificationService.clearApprovalNotification(
                        ApprovalNotificationKind.DataRequest, requestId
                    )
                    // Drop the IncomingGrantRequest feed row immediately
                    // rather than waiting on the vault's echo.
                    ownerSpaceClient.emitGrantCreatedLocally(connectionId, requestId)
                    _state.value = State.Approved
                }
                .onFailure {
                    Log.e(TAG, "approve", it)
                    _state.value = State.Error(it.message ?: "Approve failed")
                }
        }
    }

    fun deny() {
        if (requestId.isEmpty()) return
        viewModelScope.launch {
            _state.value = State.Submitting
            grants.deny(requestId, "")
                .onSuccess {
                    notificationService.clearApprovalNotification(
                        ApprovalNotificationKind.DataRequest, requestId
                    )
                    ownerSpaceClient.emitGrantDeniedLocally(connectionId, requestId)
                    _state.value = State.Denied
                }
                .onFailure {
                    Log.e(TAG, "deny", it)
                    _state.value = State.Error(it.message ?: "Deny failed")
                }
        }
    }

    sealed class State {
        object Idle : State()
        object Submitting : State()
        object Approved : State()
        object Denied : State()
        data class Error(val message: String) : State()
    }
}
