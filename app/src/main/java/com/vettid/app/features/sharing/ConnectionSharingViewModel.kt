package com.vettid.app.features.sharing

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ConnSharingVM"

/**
 * Phase 1 of the sharing surface (see
 * vettid-dev/docs/SHARING-AND-CONTRACTS-PLAN.md §6).
 *
 * Backs ConnectionSharingScreen. Builds "Shared with me" rows by
 * combining the peer's published catalogs (data, secrets, wallets —
 * already cached on the ConnectionRecord) with the local
 * capability-request log so each row knows whether we've already
 * asked for it.
 *
 * Phase 1 only handles the read-side. The write-side
 * (Shared-with-connection editor) is Phase 2.
 */
@HiltViewModel
class ConnectionSharingViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val feedRepository: FeedRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val connectionId: String = savedStateHandle["connectionId"] ?: ""

    private val _state = MutableStateFlow<SharingState>(SharingState.Loading)
    val state: StateFlow<SharingState> = _state.asStateFlow()

    init { load() }

    fun onEvent(event: SharingEvent) {
        when (event) {
            is SharingEvent.RequestItem -> requestItem(event.key)
            is SharingEvent.CancelRequest -> {
                // Capability cancel is Phase 2 — for now the request
                // sits as pending until peer acts or it expires.
            }
            SharingEvent.Refresh -> load()
        }
    }

    private fun load() {
        if (connectionId.isEmpty()) {
            _state.value = SharingState.Error("Missing connection_id")
            return
        }
        viewModelScope.launch {
            _state.value = SharingState.Loading
            try {
                val conn = feedRepository.getConnections().getOrNull()
                    ?.firstOrNull { it.connectionId == connectionId }
                if (conn == null) {
                    _state.value = SharingState.Error("Connection not found")
                    return@launch
                }
                val peerName = listOfNotNull(
                    conn.peerProfile?.firstName, conn.peerProfile?.lastName
                ).joinToString(" ").trim().ifEmpty { conn.label.ifEmpty { "Peer" } }

                val outstanding = loadOutstandingCapabilityRequests()
                val items = buildSharedWithMe(conn, outstanding)

                _state.value = SharingState.Loaded(
                    peerName = peerName,
                    connectionType = conn.connectionType,
                    sharedWithMe = items,
                )
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
                _state.value = SharingState.Error(e.message ?: "Load failed")
            }
        }
    }

    /**
     * Fold the peer's catalogs into a single ordered list of shared
     * items. Wallets come last because they're already visible on the
     * profile preview — they're listed here for completeness but
     * shouldn't dominate the screen.
     */
    private fun buildSharedWithMe(
        conn: com.vettid.app.core.nats.ConnectionRecord,
        outstanding: Map<String, OutstandingRequest>,
    ): List<SharedItem> {
        val out = mutableListOf<SharedItem>()
        conn.peerProfile?.dataCatalog?.forEach { entry ->
            val key = "data:${entry.name}"
            out += SharedItem(
                key = key,
                displayName = entry.displayName.ifEmpty { entry.name },
                category = entry.category.ifEmpty { entry.fieldType },
                kind = SharedItem.Kind.DATA,
                status = outstanding[key]?.status ?: RequestStatus.AVAILABLE,
                requestId = outstanding[key]?.requestId,
            )
        }
        conn.peerProfile?.secretCatalog?.forEach { entry ->
            val key = "secret:${entry.name}"
            out += SharedItem(
                key = key,
                displayName = entry.name,
                category = entry.category.ifEmpty { entry.type },
                kind = SharedItem.Kind.SECRET,
                status = outstanding[key]?.status ?: RequestStatus.AVAILABLE,
                requestId = outstanding[key]?.requestId,
            )
        }
        // peerProfile.wallets aren't in ConnectionRecord directly;
        // they ride on the published profile and surface via
        // ConnectionDetail's wallet section. Not duplicating here
        // until Phase 2's request-by-wallet is wired.
        return out
    }

    private suspend fun loadOutstandingCapabilityRequests(): Map<String, OutstandingRequest> {
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
                        out[credId] = OutstandingRequest(
                            requestId = rid,
                            status = parseStatus(statusStr),
                        )
                    } catch (_: Exception) { /* skip */ }
                }
                out
            } else emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "loadOutstandingCapabilityRequests failed: ${e.message}")
            emptyMap()
        }
    }

    private fun parseStatus(status: String): RequestStatus = when (status) {
        "approved" -> RequestStatus.APPROVED
        "denied" -> RequestStatus.DENIED
        "expired" -> RequestStatus.EXPIRED
        else -> RequestStatus.PENDING
    }

    /**
     * Issue a capability request keyed by the catalog item. The vault
     * persists the pending request and notifies the peer; they
     * approve/deny via their feed (Phase 3 wires the value-delivery).
     */
    private fun requestItem(key: String) {
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("connection_id", connectionId)
                    addProperty("capability_type", "credential")
                    addProperty("credential_id", key)
                    addProperty("reason", "Catalog request via Sharing screen")
                }
                val resp = ownerSpaceClient.sendAndAwaitResponse("capability.request", payload, 10_000L)
                if (resp is VaultResponse.HandlerResult && resp.success && resp.result != null) {
                    val rid = resp.result.get("request_id")?.asString
                    val current = _state.value
                    if (current is SharingState.Loaded) {
                        _state.value = current.copy(
                            sharedWithMe = current.sharedWithMe.map {
                                if (it.key == key) it.copy(
                                    status = RequestStatus.PENDING,
                                    requestId = rid,
                                ) else it
                            }
                        )
                    }
                } else {
                    Log.w(TAG, "capability.request failed: ${(resp as? VaultResponse.Error)?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "requestItem", e)
            }
        }
    }

    private data class OutstandingRequest(
        val requestId: String,
        val status: RequestStatus,
    )
}
