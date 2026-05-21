package com.vettid.app.features.grants

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.GrantEvent
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.features.feed.ApprovalNotificationKind
import com.vettid.app.features.feed.FeedNotificationService
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "DataGrantApprovalVM"

/**
 * Backs the full-screen approval prompt for an incoming data/secret
 * access request. Unlike critical-secret use and identity-verify
 * (both password-gated), regular grants only need explicit approve /
 * deny with the requested expiry + max-uses. The vault enforces those
 * server-side at fetch time.
 *
 * Alias-group aware: when a peer used "Request all" on an alias group,
 * each member arrives as its own `grant.request` / RequestReceived
 * event. The route carries the first (anchor) request; this VM resolves
 * the anchor item's alias from the user's OWN catalog, pulls every
 * sibling pending request that shares it, and approves / denies the
 * whole group as a unit. An ungrouped item is just a group of one.
 *
 * Approval sends `grant.approve` per item; denial sends `grant.deny`.
 */
@HiltViewModel
class DataGrantApprovalViewModel @Inject constructor(
    private val grants: GrantsRepository,
    private val feedRepository: FeedRepository,
    private val notificationService: FeedNotificationService,
    private val ownerSpaceClient: OwnerSpaceClient,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Anchor request from the route — the RequestReceived event that
    // triggered the screen.
    private val anchorRequestId: String = savedStateHandle["requestId"] ?: ""
    val connectionId: String = savedStateHandle["connectionId"] ?: ""
    private val anchorItemKind: String = savedStateHandle["itemKind"] ?: ""
    private val anchorItemRef: String = savedStateHandle["itemRef"] ?: ""
    private val anchorItemLabel: String = savedStateHandle["itemLabel"] ?: ""
    val requestedMode: String = savedStateHandle["requestedMode"] ?: GrantModes.ONE_SHOT
    val requestedExpiresAt: Long = savedStateHandle["requestedExpiresAt"] ?: 0L
    val requestedMaxUses: Int = savedStateHandle["requestedMaxUses"] ?: 1
    val reason: String = savedStateHandle["reason"] ?: ""

    val peerName: String = resolvePeerName(connectionId)

    /** One request inside the group this screen approves/denies. */
    data class RequestItem(
        val requestId: String,
        val itemKind: String,
        val itemRef: String,
        val itemLabel: String,
        val mode: String,
        val expiresAt: Long,
        val maxUses: Int,
    )

    // The group this screen acts on. Seeded with the anchor so the
    // screen renders instantly; loadGroup() expands it to siblings.
    private val _items = MutableStateFlow(
        listOf(
            RequestItem(
                requestId = anchorRequestId,
                itemKind = anchorItemKind,
                itemRef = anchorItemRef,
                itemLabel = anchorItemLabel,
                mode = requestedMode,
                expiresAt = requestedExpiresAt,
                maxUses = requestedMaxUses,
            )
        )
    )
    val items: StateFlow<List<RequestItem>> = _items.asStateFlow()

    // Alias the group shares; blank when the anchor item is ungrouped.
    private val _alias = MutableStateFlow("")
    val alias: StateFlow<String> = _alias.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // Own-catalog alias maps, fetched once per screen.
    private var aliasesLoaded = false
    private var dataAliasMap: Map<String, String> = emptyMap()
    private var secretAliasMap: Map<String, String> = emptyMap()

    init {
        loadGroup()
        // The requester fans out N requests near-simultaneously, so a
        // sibling can land just after this screen opened — re-resolve
        // the group when another RequestReceived for this connection
        // fires, until the user acts.
        ownerSpaceClient.grantEvents
            .onEach { ev ->
                if (ev is GrantEvent.RequestReceived &&
                    ev.connectionId == connectionId &&
                    _state.value is State.Idle
                ) {
                    loadGroup()
                }
            }
            .launchIn(viewModelScope)
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
     * Resolve the anchor item's alias from the user's own catalog and
     * pull every sibling pending request that shares it. When the
     * anchor item carries no alias the group stays just the anchor.
     */
    private fun loadGroup() {
        viewModelScope.launch {
            if (!aliasesLoaded) {
                dataAliasMap = runCatching { grants.ownDataAliases() }.getOrDefault(emptyMap())
                secretAliasMap = runCatching { grants.ownSecretAliases() }.getOrDefault(emptyMap())
                aliasesLoaded = true
            }
            val anchorAlias = aliasOf(anchorItemKind, anchorItemRef)
            _alias.value = anchorAlias
            if (anchorAlias.isBlank()) return@launch  // ungrouped — anchor only

            val pending = grants.listPending().getOrDefault(emptyList())
                .filter { it.connectionId == connectionId }
            // Anchor first, always present even if list-pending lags
            // behind the fan-out.
            val merged = LinkedHashMap<String, RequestItem>()
            _items.value.firstOrNull { it.requestId == anchorRequestId }?.let {
                merged[anchorRequestId] = it
            }
            pending.forEach { p ->
                if (aliasOf(p.itemKind, p.itemRef) == anchorAlias) {
                    merged[p.requestId] = RequestItem(
                        requestId = p.requestId,
                        itemKind = p.itemKind,
                        itemRef = p.itemRef,
                        itemLabel = p.itemLabel,
                        mode = p.requestedMode,
                        expiresAt = p.requestedExpiresAt,
                        maxUses = p.requestedMaxUses,
                    )
                }
            }
            if (merged.isNotEmpty()) _items.value = merged.values.toList()
        }
    }

    private fun aliasOf(itemKind: String, itemRef: String): String =
        (if (itemKind == "secret") secretAliasMap[itemRef] else dataAliasMap[itemRef]).orEmpty()

    fun approve() {
        val targets = _items.value.filter { it.requestId.isNotEmpty() }
        if (targets.isEmpty()) {
            _state.value = State.Error("Missing request_id")
            return
        }
        viewModelScope.launch {
            _state.value = State.Submitting
            var firstError: String? = null
            targets.forEach { item ->
                grants.approve(item.requestId, item.expiresAt, item.maxUses, item.mode)
                    .onSuccess {
                        notificationService.clearApprovalNotification(
                            ApprovalNotificationKind.DataRequest, item.requestId
                        )
                        // Drop the IncomingGrantRequest row from the feed
                        // card immediately rather than waiting on the
                        // vault's data-grant-created echo.
                        ownerSpaceClient.emitGrantCreatedLocally(connectionId, item.requestId)
                    }
                    .onFailure {
                        Log.e(TAG, "approve ${item.requestId}", it)
                        if (firstError == null) firstError = it.message
                    }
            }
            _state.value = if (firstError == null) State.Approved
                else State.Error(firstError ?: "Approve failed")
        }
    }

    fun deny() {
        val targets = _items.value.filter { it.requestId.isNotEmpty() }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _state.value = State.Submitting
            var firstError: String? = null
            targets.forEach { item ->
                grants.deny(item.requestId, "")
                    .onSuccess {
                        notificationService.clearApprovalNotification(
                            ApprovalNotificationKind.DataRequest, item.requestId
                        )
                        ownerSpaceClient.emitGrantDeniedLocally(connectionId, item.requestId)
                    }
                    .onFailure {
                        Log.e(TAG, "deny ${item.requestId}", it)
                        if (firstError == null) firstError = it.message
                    }
            }
            _state.value = if (firstError == null) State.Denied
                else State.Error(firstError ?: "Deny failed")
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
