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
                // Capability cancel is Phase 3 — for now the request
                // sits as pending until peer acts or it expires.
            }
            is SharingEvent.UpdatePolicy -> updatePolicy(event.items)
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
                val sharePolicy = loadSharePolicy()

                _state.value = SharingState.Loaded(
                    peerName = peerName,
                    connectionType = conn.connectionType,
                    sharedWithMe = items,
                    sharedWithConnection = sharePolicy,
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

    /**
     * Pull the per-connection share policy from the vault and project
     * it into the row shape the editor renders. Today this surfaces
     * handler entries (the only kind Phase 2 wires); the rest of the
     * Items map is forwarded as-is so future kinds light up without
     * a VM change.
     */
    private suspend fun loadSharePolicy(): List<SharePolicyRow> {
        // Two sources combine here:
        //   1. The vault's stored policy (explicit user decisions)
        //   2. The user's shareable inventory (handlers today; data /
        //      secrets / actions in Phase 3) — these surface as
        //      default-deny rows so the editor has something to toggle
        //      even before the user has changed anything.
        val stored = fetchStoredPolicy()
        val seeded = fetchShareableInventory()
        // stored decisions win on collision (key match).
        val merged = LinkedHashMap<String, SharePolicyRow>()
        seeded.forEach { merged[it.key] = it }
        stored.forEach { merged[it.key] = it }
        return merged.values.sortedWith(compareBy({ it.category }, { it.displayName }))
    }

    private suspend fun fetchStoredPolicy(): List<SharePolicyRow> {
        return try {
            val payload = JsonObject().apply { addProperty("connection_id", connectionId) }
            val resp = ownerSpaceClient.sendAndAwaitResponse("connection.share-policy.get", payload, 10_000L)
            if (resp is VaultResponse.HandlerResult && resp.success && resp.result != null) {
                val policyObj = resp.result.getAsJsonObject("policy") ?: return emptyList()
                val itemsObj = policyObj.getAsJsonObject("items") ?: return emptyList()
                val out = mutableListOf<SharePolicyRow>()
                itemsObj.entrySet().forEach { (key, valueJson) ->
                    try {
                        val o = valueJson.asJsonObject
                        val (kind, id) = key.split(":", limit = 2).let {
                            if (it.size == 2) it[0] to it[1] else "" to key
                        }
                        out += SharePolicyRow(
                            key = key,
                            displayName = displayNameFor(kind, id),
                            category = kindLabel(kind),
                            allowed = o.get("allowed")?.asBoolean ?: false,
                            tier = o.get("tier")?.asString ?: "consent",
                            retention = o.get("retention")?.asString ?: "session",
                            rateLimitPerHour = o.get("rate_limit_per_hour")?.asInt ?: 0,
                            expiresAt = o.get("expires_at")?.asLong ?: 0L,
                        )
                    } catch (_: Exception) { /* skip malformed */ }
                }
                out
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchStoredPolicy failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Surface the owner's shareable handler catalog as default-deny
     * rows. This gives the user something to toggle even when the
     * vault's policy items map is empty. Toggling sends the row
     * through connection.share-policy.set; the same key lands in the
     * stored policy on the next reload and overrides this default.
     */
    private suspend fun fetchShareableInventory(): List<SharePolicyRow> {
        return try {
            val handlers = ownerSpaceClient.listHandlers()
            handlers
                .filter { it.shareable && it.enabled && it.shareGlobally }
                .map { h ->
                    SharePolicyRow(
                        key = "handler:${h.id}",
                        displayName = h.name.ifEmpty { h.id },
                        category = "Handler",
                        allowed = false,         // default-deny until user toggles
                        tier = "on_demand",
                        retention = "until_revoked",
                        rateLimitPerHour = 0,
                        expiresAt = 0L,
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "fetchShareableInventory failed: ${e.message}")
            emptyList()
        }
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "data" -> "Personal data"
        "secret" -> "Secret"
        "wallet" -> "Wallet"
        "handler" -> "Handler"
        "action" -> "Action"
        "setting" -> "Setting"
        else -> kind.replaceFirstChar { it.uppercase() }
    }

    /**
     * "handler:wallet" → "Wallet" — not pretty for arbitrary IDs but
     * good enough for v1; the catalog provides nicer names that we
     * can wire in via a HandlerCatalog lookup in Phase 3.
     */
    private fun displayNameFor(kind: String, id: String): String =
        id.split(".", "_", "-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /**
     * Persist a partial set of policy changes via
     * connection.share-policy.set. The vault merges by key; we only
     * send the rows the user actually changed.
     */
    private fun updatePolicy(rows: Map<String, SharePolicyRow>) {
        viewModelScope.launch {
            try {
                val itemsJson = JsonObject()
                rows.forEach { (key, row) ->
                    val item = JsonObject().apply {
                        addProperty("allowed", row.allowed)
                        if (row.tier.isNotEmpty()) addProperty("tier", row.tier)
                        if (row.retention.isNotEmpty()) addProperty("retention", row.retention)
                        if (row.rateLimitPerHour > 0) addProperty("rate_limit_per_hour", row.rateLimitPerHour)
                        if (row.expiresAt > 0) addProperty("expires_at", row.expiresAt)
                    }
                    itemsJson.add(key, item)
                }
                val payload = JsonObject().apply {
                    addProperty("connection_id", connectionId)
                    add("items", itemsJson)
                }
                val resp = ownerSpaceClient.sendAndAwaitResponse("connection.share-policy.set", payload, 10_000L)
                if (resp is VaultResponse.HandlerResult && resp.success) {
                    // Re-load so the UI reflects the post-merge state
                    // (handler grants might trigger downstream flips).
                    load()
                } else {
                    Log.w(TAG, "share-policy.set failed: ${(resp as? VaultResponse.Error)?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "updatePolicy", e)
            }
        }
    }

    private data class OutstandingRequest(
        val requestId: String,
        val status: RequestStatus,
    )
}
