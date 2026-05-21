package com.vettid.app.features.grants

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.GrantEvent
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.features.feed.ApprovalNotificationKind
import com.vettid.app.features.feed.FeedNotificationService
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs grant-related UI surfaces:
 *  - Held-in-trust panel (received grants for a connection)
 *  - Granted-to-peer panel (outbound grants for a connection)
 *  - Pending requests (incoming, awaiting decision)
 *  - Fetch-value sheet (transient plaintext, never persisted)
 *
 * The plaintext value lives in `revealedValue` as a StateFlow<String?>;
 * UI clears it on dismiss to honor the "no plaintext at rest" rule.
 */
@HiltViewModel
class GrantsViewModel @Inject constructor(
    private val repo: GrantsRepository,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val notificationService: FeedNotificationService,
    private val feedRepository: FeedRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val connectionId: String = savedStateHandle["connectionId"] ?: ""

    /**
     * Which side of the sharing relationship this screen renders, from
     * the route's `direction` arg. The ConnectionDetail Them/You tabs
     * each open this screen scoped to one direction so a grant only
     * ever appears under the side that owns it:
     *   "inbound"  = data they've shared with you (Current / Expired)
     *   "outbound" = data you share with them (Allowed / Expired / Pending)
     */
    val direction: String = savedStateHandle.get<String>("direction") ?: "inbound"
    val isInbound: Boolean get() = direction != "outbound"

    private val _outbound = MutableStateFlow<List<GrantSummary>>(emptyList())
    val outbound: StateFlow<List<GrantSummary>> = _outbound.asStateFlow()

    private val _inbound = MutableStateFlow<List<GrantSummary>>(emptyList())
    val inbound: StateFlow<List<GrantSummary>> = _inbound.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingRequestSummary>>(emptyList())
    val pending: StateFlow<List<PendingRequestSummary>> = _pending.asStateFlow()

    // Requests this vault has sent to the peer, awaiting (or already
    // given) their decision. Drives the Them-tab "Requested" sub-tab.
    private val _myRequests = MutableStateFlow<List<OutgoingRequestSummary>>(emptyList())
    val myRequests: StateFlow<List<OutgoingRequestSummary>> = _myRequests.asStateFlow()

    // grantId → alias, derived from the peer's published catalog so the
    // inbound "Data they've shared" list groups items the peer filed
    // together (a credit card's number/expiry/CVV) into one alias card.
    private val _inboundAliases = MutableStateFlow<Map<String, String>>(emptyMap())
    val inboundAliases: StateFlow<Map<String, String>> = _inboundAliases.asStateFlow()

    // grantId → alias for outbound grants, derived from the user's OWN
    // catalog (personal-data fields + secrets) so the "Data sharing"
    // list groups items the user filed together into one alias card.
    private val _outboundAliases = MutableStateFlow<Map<String, String>>(emptyMap())
    val outboundAliases: StateFlow<Map<String, String>> = _outboundAliases.asStateFlow()

    // requestId → alias for incoming pending requests, derived the same
    // way (the requested items are the user's own), so the Pending tab
    // groups a peer's "Request all" fan-out back into one alias card.
    private val _pendingAliases = MutableStateFlow<Map<String, String>>(emptyMap())
    val pendingAliases: StateFlow<Map<String, String>> = _pendingAliases.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _revealedValue = MutableStateFlow<String?>(null)
    val revealedValue: StateFlow<String?> = _revealedValue.asStateFlow()

    private val _events = MutableSharedFlow<GrantsEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GrantsEvent> = _events.asSharedFlow()

    init {
        refresh()
        // Subscribe to vault → app grant events. The fetch-response path
        // is the load-bearing one: the plaintext value arrives via this
        // flow and is dropped into _revealedValue for foreground render.
        // Other events trigger a refresh so the list views catch up.
        ownerSpaceClient.grantEvents
            .onEach { ev ->
                when (ev) {
                    is GrantEvent.FetchResponse ->
                        onFetchResponse(ev.grantId, ev.status, ev.value, ev.error)
                    is GrantEvent.RequestReceived,
                    is GrantEvent.GrantCreated,
                    is GrantEvent.GrantDenied,
                    is GrantEvent.GrantRevoked,
                    is GrantEvent.CriticalUseRequested,
                    is GrantEvent.CriticalUseResponse,
                    is GrantEvent.AuthenticateResult,
                    is GrantEvent.IdentityVerifyChallenged -> refresh()
                }
            }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        viewModelScope.launch {
            repo.listOutbound(connectionId.ifEmpty { null }).onSuccess { _outbound.value = it }
            repo.listInbound(connectionId.ifEmpty { null }).onSuccess {
                _inbound.value = it
                if (isInbound) updateInboundAliases(it)
            }
            repo.listPending().onSuccess { list ->
                _pending.value = if (connectionId.isEmpty()) list else list.filter { it.connectionId == connectionId }
            }
            repo.listMyRequests(connectionId.ifEmpty { null }).onSuccess { _myRequests.value = it }
            // Outbound grants + incoming pending requests both group by
            // the user's OWN catalog — resolve it once and map both.
            if (!isInbound) updateOwnAliasMaps()
        }
    }

    /**
     * Builds the grantId → alias map for inbound grants by matching each
     * grant's item against the peer's published catalog. Grants whose
     * item carries no alias — or isn't in the cached catalog — are left
     * out and render as their own single card.
     */
    private suspend fun updateInboundAliases(grants: List<GrantSummary>) {
        if (connectionId.isEmpty()) {
            _inboundAliases.value = emptyMap()
            return
        }
        val conn = runCatching { feedRepository.getConnections().getOrNull() }
            .getOrNull()?.firstOrNull { it.connectionId == connectionId }
        if (conn == null) {
            _inboundAliases.value = emptyMap()
            return
        }
        val dataAlias = conn.peerProfile?.dataCatalog?.associate { it.name to it.alias }.orEmpty()
        val secretAlias = conn.peerProfile?.secretCatalog?.associate { it.name to it.alias }.orEmpty()
        _inboundAliases.value = grants.mapNotNull { g ->
            val alias = if (g.itemKind == "secret") secretAlias[g.itemRef] else dataAlias[g.itemRef]
            if (!alias.isNullOrBlank()) g.grantId to alias else null
        }.toMap()
    }

    /**
     * Resolves the outbound grantId → alias and pending requestId →
     * alias maps from the user's OWN catalog (the shared/requested
     * items belong to the user). One catalog fetch covers both —
     * personal-data fields keyed by namespace, secrets keyed by name.
     * Items with no alias are left out and render as a single card.
     */
    private suspend fun updateOwnAliasMaps() {
        val dataAlias = runCatching { repo.ownDataAliases() }.getOrDefault(emptyMap())
        val secretAlias = runCatching { repo.ownSecretAliases() }.getOrDefault(emptyMap())
        fun aliasOf(kind: String, ref: String): String =
            (if (kind == "secret") secretAlias[ref] else dataAlias[ref]).orEmpty()
        _outboundAliases.value = _outbound.value.mapNotNull { g ->
            aliasOf(g.itemKind, g.itemRef).takeIf { it.isNotBlank() }?.let { g.grantId to it }
        }.toMap()
        _pendingAliases.value = _pending.value.mapNotNull { p ->
            aliasOf(p.itemKind, p.itemRef).takeIf { it.isNotBlank() }?.let { p.requestId to it }
        }.toMap()
    }

    fun sendRequest(
        itemKind: String,
        itemRef: String,
        itemLabel: String,
        mode: String,
        deliverTo: String,
        expiresAt: Long,
        maxUses: Int,
        reason: String,
    ) {
        viewModelScope.launch {
            _busy.value = true
            repo.sendRequest(connectionId, itemKind, itemRef, itemLabel, mode, deliverTo, expiresAt, maxUses, reason)
                .onSuccess { _events.tryEmit(GrantsEvent.RequestSent(it)) }
                .onFailure { _events.tryEmit(GrantsEvent.Error(it.message ?: "Request failed")) }
            _busy.value = false
            refresh()
        }
    }

    fun approve(requestId: String, expiresAt: Long, maxUses: Int, mode: String) {
        viewModelScope.launch {
            _busy.value = true
            repo.approve(requestId, expiresAt, maxUses, mode)
                .onSuccess {
                    // Approving from the Pending tab must clear the same
                    // surfaces the full-screen prompt does: the OS shade
                    // entry and the feed card's IncomingGrantRequest row.
                    notificationService.clearApprovalNotification(
                        ApprovalNotificationKind.DataRequest, requestId
                    )
                    ownerSpaceClient.emitGrantCreatedLocally(connectionId, requestId)
                    _events.tryEmit(GrantsEvent.Approved(it))
                }
                .onFailure { _events.tryEmit(GrantsEvent.Error(it.message ?: "Approve failed")) }
            _busy.value = false
            refresh()
        }
    }

    fun deny(requestId: String, reason: String) {
        viewModelScope.launch {
            _busy.value = true
            repo.deny(requestId, reason)
                .onSuccess {
                    notificationService.clearApprovalNotification(
                        ApprovalNotificationKind.DataRequest, requestId
                    )
                    ownerSpaceClient.emitGrantDeniedLocally(connectionId, requestId)
                    _events.tryEmit(GrantsEvent.Denied(requestId))
                }
                .onFailure { _events.tryEmit(GrantsEvent.Error(it.message ?: "Deny failed")) }
            _busy.value = false
            refresh()
        }
    }

    fun revoke(grantId: String) {
        viewModelScope.launch {
            _busy.value = true
            repo.revoke(grantId)
                .onSuccess { _events.tryEmit(GrantsEvent.Revoked(grantId)) }
                .onFailure { _events.tryEmit(GrantsEvent.Error(it.message ?: "Revoke failed")) }
            _busy.value = false
            refresh()
        }
    }

    fun reveal(grantId: String) {
        viewModelScope.launch {
            _busy.value = true
            repo.fetchRemote(grantId)
                .onFailure { _events.tryEmit(GrantsEvent.Error(it.message ?: "Fetch failed")) }
            _busy.value = false
        }
    }

    /** Called by VettIDApp when a forApp.connection.data-grant-fetch-response event arrives. */
    fun onFetchResponse(grantId: String, status: String, value: String?, error: String?) {
        if (status == "ok" && !value.isNullOrEmpty()) {
            _revealedValue.value = value
        } else {
            _events.tryEmit(GrantsEvent.Error("Fetch denied: ${error ?: "unknown"}"))
        }
    }

    fun dismissReveal() { _revealedValue.value = null }
}

sealed class GrantsEvent {
    data class RequestSent(val requestId: String) : GrantsEvent()
    data class Approved(val grantId: String) : GrantsEvent()
    data class Denied(val requestId: String) : GrantsEvent()
    data class Revoked(val grantId: String) : GrantsEvent()
    data class Error(val message: String) : GrantsEvent()
}
