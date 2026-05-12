package com.vettid.app.features.sharing

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.features.feed.FeedRepository
import com.vettid.app.core.nats.GrantEvent
import com.vettid.app.features.grants.GrantItemKinds
import com.vettid.app.features.grants.GrantsRepository
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "PeerCatalogVM"

/**
 * Backs PeerCatalogScreen — the "what does this connection share with
 * me" view. Shows the peer's published catalog and lets the user
 * request specific items via capability.request.
 */
@HiltViewModel
class PeerCatalogViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val feedRepository: FeedRepository,
    private val grantsRepository: GrantsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val connectionId: String = savedStateHandle["connectionId"] ?: ""

    private val _state = MutableStateFlow<PeerCatalogState>(PeerCatalogState.Loading)
    val state: StateFlow<PeerCatalogState> = _state.asStateFlow()

    private val _lastCriticalResult = MutableStateFlow<CriticalUseResult?>(null)
    /** Latest critical-secret use response — surfaces as a result dialog. */
    val lastCriticalResult: StateFlow<CriticalUseResult?> = _lastCriticalResult.asStateFlow()

    init {
        load()
        // Subscribe to critical-secret use responses so the result
        // dialog appears as soon as the owner's vault returns.
        ownerSpaceClient.grantEvents
            .onEach { ev ->
                if (ev is GrantEvent.CriticalUseResponse && ev.connectionId == connectionId) {
                    _lastCriticalResult.value = CriticalUseResult(
                        requestId = ev.requestId,
                        status = ev.status,
                        result = ev.result,
                        error = ev.error,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun dismissCriticalResult() { _lastCriticalResult.value = null }

    fun onEvent(event: PeerCatalogEvent) {
        when (event) {
            is PeerCatalogEvent.Request -> request(event.key)
            is PeerCatalogEvent.RequestGrant -> requestGrant(event)
            is PeerCatalogEvent.RequestCriticalUse -> requestCriticalUse(event)
            PeerCatalogEvent.Refresh -> load()
        }
    }

    private fun requestCriticalUse(event: PeerCatalogEvent.RequestCriticalUse) {
        viewModelScope.launch {
            val ref = event.key.removePrefix("secret:")
            val item = (_state.value as? PeerCatalogState.Loaded)?.items
                ?.firstOrNull { it.key == event.key } ?: return@launch
            grantsRepository.requestCriticalUse(
                connectionId = connectionId,
                itemRef = ref,
                itemLabel = item.displayName,
                operation = event.operation,
                payloadBase64 = event.payloadBase64,
                context = event.context,
            ).onFailure { Log.w(TAG, "critical-use request: ${it.message}") }
        }
    }

    private fun requestGrant(event: PeerCatalogEvent.RequestGrant) {
        viewModelScope.launch {
            // Catalog key shape: "data:<name>" or "secret:<name>".
            val (kindPrefix, ref) = event.key.split(':', limit = 2).let {
                if (it.size == 2) it[0] to it[1] else "data" to event.key
            }
            val kind = if (kindPrefix == "secret") GrantItemKinds.SECRET else GrantItemKinds.DATA
            val current = _state.value
            val label = (current as? PeerCatalogState.Loaded)?.items
                ?.firstOrNull { it.key == event.key }?.displayName.orEmpty()
            val result = grantsRepository.sendRequest(
                connectionId = connectionId,
                itemKind = kind,
                itemRef = ref,
                itemLabel = label,
                mode = event.mode,
                deliverTo = "self",
                requestedExpiresAt = event.expiresAt,
                requestedMaxUses = event.maxUses,
                reason = event.reason,
            )
            result.onSuccess { rid ->
                if (current is PeerCatalogState.Loaded) {
                    _state.value = current.copy(
                        items = current.items.map {
                            if (it.key == event.key) it.copy(status = RequestStatus.PENDING, requestId = rid) else it
                        }
                    )
                }
            }.onFailure { Log.w(TAG, "grant.request: ${it.message}") }
        }
    }

    private fun load() {
        if (connectionId.isEmpty()) {
            _state.value = PeerCatalogState.Error("Missing connection_id")
            return
        }
        viewModelScope.launch {
            _state.value = PeerCatalogState.Loading
            try {
                val conn = feedRepository.getConnections().getOrNull()
                    ?.firstOrNull { it.connectionId == connectionId }
                if (conn == null) {
                    _state.value = PeerCatalogState.Error("Connection not found")
                    return@launch
                }
                val peerName = listOfNotNull(
                    conn.peerProfile?.firstName, conn.peerProfile?.lastName
                ).joinToString(" ").trim().ifEmpty { conn.label.ifEmpty { "Peer" } }

                // Render the peer's cached catalog immediately so the user
                // sees something before the request-status round-trip lands.
                val itemsNoStatus = buildItems(conn, emptyMap())
                _state.value = PeerCatalogState.Loaded(peerName, itemsNoStatus)

                val outstanding = loadOutstandingRequests()
                val items = buildItems(conn, outstanding)
                _state.value = PeerCatalogState.Loaded(peerName, items)
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
                _state.value = PeerCatalogState.Error(e.message ?: "Load failed")
            }
        }
    }

    private fun buildItems(
        conn: com.vettid.app.core.nats.ConnectionRecord,
        outstanding: Map<String, OutstandingRequest>,
    ): List<SharedItem> {
        val out = mutableListOf<SharedItem>()
        conn.peerProfile?.dataCatalog?.forEach { entry ->
            val key = "data:${entry.name}"
            val baseName = entry.displayName.ifEmpty { entry.name }
            // Alias surfaces in the catalog so peers can tell similar
            // entries apart ("Family · Phone — Wife" vs. "Family ·
            // Phone — Daughter") without seeing the value.
            val displayName = if (entry.alias.isNotBlank()) "$baseName — ${entry.alias}" else baseName
            out += SharedItem(
                key = key,
                displayName = displayName,
                category = entry.category.ifEmpty { entry.fieldType },
                kind = SharedItem.Kind.DATA,
                status = outstanding[key]?.status ?: RequestStatus.AVAILABLE,
                requestId = outstanding[key]?.requestId,
            )
        }
        conn.peerProfile?.secretCatalog?.forEach { entry ->
            val key = "secret:${entry.name}"
            val cat = entry.category.ifEmpty { entry.type }
            out += SharedItem(
                key = key,
                displayName = entry.name,
                category = cat,
                kind = SharedItem.Kind.SECRET,
                status = outstanding[key]?.status ?: RequestStatus.AVAILABLE,
                requestId = outstanding[key]?.requestId,
                // "Critical Secret · Use-only" is how the vault tags
                // cataloged-for-use rows in buildSecretCatalog.
                useOnly = cat.contains("Use-only", ignoreCase = true),
            )
        }
        return out
    }

    private suspend fun loadOutstandingRequests(): Map<String, OutstandingRequest> {
        return try {
            val payload = JsonObject().apply { addProperty("connection_id", connectionId) }
            val resp = ownerSpaceClient.sendAndAwaitResponse("capability.request.list", payload, 10_000L)
            if (resp is VaultResponse.HandlerResult && resp.success && resp.result != null) {
                val out = mutableMapOf<String, OutstandingRequest>()
                resp.result.getAsJsonArray("requests")?.forEach { el ->
                    try {
                        val o = el.asJsonObject
                        val credId = o.get("credential_id")?.asString ?: return@forEach
                        val statusStr = o.get("status")?.asString ?: "pending"
                        val rid = o.get("request_id")?.asString ?: return@forEach
                        out[credId] = OutstandingRequest(rid, parseStatus(statusStr))
                    } catch (_: Exception) { /* skip */ }
                }
                out
            } else emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "loadOutstandingRequests failed: ${e.message}")
            emptyMap()
        }
    }

    private fun parseStatus(s: String): RequestStatus = when (s) {
        "approved" -> RequestStatus.APPROVED
        "denied" -> RequestStatus.DENIED
        "expired" -> RequestStatus.EXPIRED
        else -> RequestStatus.PENDING
    }

    private fun request(key: String) {
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("connection_id", connectionId)
                    addProperty("capability_type", "credential")
                    addProperty("credential_id", key)
                    addProperty("reason", "Catalog request")
                }
                val resp = ownerSpaceClient.sendAndAwaitResponse("capability.request", payload, 10_000L)
                if (resp is VaultResponse.HandlerResult && resp.success && resp.result != null) {
                    val rid = resp.result.get("request_id")?.asString
                    val current = _state.value
                    if (current is PeerCatalogState.Loaded) {
                        _state.value = current.copy(
                            items = current.items.map {
                                if (it.key == key) it.copy(status = RequestStatus.PENDING, requestId = rid) else it
                            }
                        )
                    }
                } else {
                    Log.w(TAG, "capability.request failed: ${(resp as? VaultResponse.Error)?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "request", e)
            }
        }
    }

    private data class OutstandingRequest(
        val requestId: String,
        val status: RequestStatus,
    )

    data class CriticalUseResult(
        val requestId: String,
        val status: String,
        val result: String,
        val error: String,
    )
}

sealed class PeerCatalogState {
    object Loading : PeerCatalogState()
    data class Loaded(
        val peerName: String,
        val items: List<SharedItem>,
    ) : PeerCatalogState()
    data class Error(val message: String) : PeerCatalogState()
}

sealed class PeerCatalogEvent {
    data class Request(val key: String) : PeerCatalogEvent()
    data class RequestGrant(
        val key: String,
        val mode: String,
        val expiresAt: Long,
        val maxUses: Int,
        val reason: String,
    ) : PeerCatalogEvent()
    data class RequestCriticalUse(
        val key: String,
        val operation: String,
        val payloadBase64: String,
        val context: String,
    ) : PeerCatalogEvent()
    object Refresh : PeerCatalogEvent()
}
