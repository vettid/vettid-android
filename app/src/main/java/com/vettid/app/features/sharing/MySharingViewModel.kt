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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject

private const val TAG = "MySharingVM"

/**
 * Backs MySharingScreen — the outbound view. Owns presence override,
 * location sharing toggle, and the per-item SharePolicy editor for
 * what THIS user is willing to share with this peer.
 */
@HiltViewModel
class MySharingViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val feedRepository: FeedRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val connectionId: String = savedStateHandle["connectionId"] ?: ""

    private val _state = MutableStateFlow<MySharingState>(MySharingState.Loading)
    val state: StateFlow<MySharingState> = _state.asStateFlow()

    init { load() }

    fun onEvent(event: MySharingEvent) {
        when (event) {
            is MySharingEvent.SetPresenceOverride -> setPresenceOverride(event.value)
            is MySharingEvent.SetLocationSharing -> setLocationSharing(event.enabled)
            is MySharingEvent.UpdatePolicy -> updatePolicy(event.row)
            MySharingEvent.Refresh -> load()
        }
    }

    private fun load() {
        if (connectionId.isEmpty()) {
            _state.value = MySharingState.Error("Missing connection_id")
            return
        }
        viewModelScope.launch {
            _state.value = MySharingState.Loading
            try {
                val conn = feedRepository.getConnections().getOrNull()
                    ?.firstOrNull { it.connectionId == connectionId }
                if (conn == null) {
                    _state.value = MySharingState.Error("Connection not found")
                    return@launch
                }
                val peerName = listOfNotNull(
                    conn.peerProfile?.firstName, conn.peerProfile?.lastName
                ).joinToString(" ").trim().ifEmpty { conn.label.ifEmpty { "Peer" } }

                _state.value = MySharingState.Loaded(
                    peerName = peerName,
                    presenceOverride = conn.presenceShareOverride,
                    isLocationSharingEnabled = false,
                    isTogglingLocation = false,
                    isPresenceUpdating = false,
                    rows = emptyList(),
                )

                // supervisorScope (not coroutineScope) — if one fetch
                // throws (e.g. a timeout surfaces as CancellationException
                // through the JS helper), we don't want it taking the
                // siblings down. With coroutineScope a single failure
                // cancelled the rows update, leaving the editor empty
                // until the user did something else (e.g. toggled
                // location) that triggered another state update.
                supervisorScope {
                    val locationDeferred = async { runCatching { fetchLocationSharing() }.getOrDefault(false) }
                    val storedPolicyDeferred = async { runCatching { fetchStoredPolicy() }.getOrDefault(emptyList()) }
                    val ownDataDeferred = async { runCatching { fetchOwnDataRows() }.getOrDefault(emptyList()) }
                    val ownSecretsDeferred = async { runCatching { fetchOwnSecretRows() }.getOrDefault(emptyList()) }
                    val handlerRowsDeferred = async { runCatching { fetchShareableHandlerRows() }.getOrDefault(emptyList()) }

                    val locationEnabled = locationDeferred.await()
                    _state.update { s ->
                        (s as? MySharingState.Loaded)?.copy(isLocationSharingEnabled = locationEnabled) ?: s
                    }

                    val seeded = ownDataDeferred.await() +
                        ownSecretsDeferred.await() +
                        handlerRowsDeferred.await()
                    val stored = storedPolicyDeferred.await()
                    val merged = LinkedHashMap<String, SharePolicyRow>()
                    seeded.forEach { merged[it.key] = it }
                    stored.forEach { merged[it.key] = it }
                    val rows = merged.values.sortedWith(
                        compareBy({ it.category }, { it.displayName })
                    )
                    Log.d(TAG, "load: seeded=${seeded.size} stored=${stored.size} merged=${rows.size}")
                    _state.update { s ->
                        (s as? MySharingState.Loaded)?.copy(rows = rows) ?: s
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
                _state.value = MySharingState.Error(e.message ?: "Load failed")
            }
        }
    }

    private suspend fun fetchLocationSharing(): Boolean {
        return try {
            val resp = ownerSpaceClient.sendAndAwaitResponse(
                "location.sharing.list", JsonObject(), 10_000L
            )
            if (resp is VaultResponse.HandlerResult && resp.success && resp.result != null) {
                resp.result.getAsJsonArray("shared_with")
                    ?.any { it.asString == connectionId } ?: false
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "fetchLocationSharing failed: ${e.message}")
            false
        }
    }

    private suspend fun fetchStoredPolicy(): List<SharePolicyRow> {
        return try {
            val payload = JsonObject().apply { addProperty("connection_id", connectionId) }
            val resp = ownerSpaceClient.sendAndAwaitResponse("connection.share-policy.get", payload, 10_000L)
            if (resp !is VaultResponse.HandlerResult || !resp.success || resp.result == null) return emptyList()
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
                } catch (_: Exception) { /* skip */ }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "fetchStoredPolicy failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchOwnDataRows(): List<SharePolicyRow> {
        return try {
            val resp = ownerSpaceClient.sendAndAwaitResponse("personal-data.get", JsonObject(), 10_000L)
            if (resp !is VaultResponse.HandlerResult || !resp.success || resp.result == null) return emptyList()
            val fields = resp.result.getAsJsonObject("fields") ?: return emptyList()
            fields.entrySet().mapNotNull { (namespace, valueJson) ->
                if (namespace == "_system_stored_at" || namespace == "_system_email_verified") return@mapNotNull null
                val alias = valueJson?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("alias")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val baseName = humanizeNamespace(namespace)
                val displayName = if (alias.isNotBlank()) "$baseName — $alias" else baseName
                SharePolicyRow(
                    key = "data:$namespace",
                    displayName = displayName,
                    category = "Personal data",
                    allowed = false,
                    tier = "on_demand",
                    retention = "until_revoked",
                    rateLimitPerHour = 0,
                    expiresAt = 0L,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchOwnDataRows failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchOwnSecretRows(): List<SharePolicyRow> {
        return try {
            val resp = ownerSpaceClient.sendAndAwaitResponse("credential.secret.list", JsonObject(), 10_000L)
            if (resp !is VaultResponse.HandlerResult || !resp.success || resp.result == null) return emptyList()
            val secrets = resp.result.getAsJsonArray("secrets") ?: return emptyList()
            secrets.mapNotNull { el ->
                try {
                    val o = el.asJsonObject
                    val name = o.get("name")?.asString ?: return@mapNotNull null
                    val category = o.get("category")?.asString?.takeIf { it.isNotEmpty() } ?: "Secret"
                    SharePolicyRow(
                        key = "secret:$name",
                        displayName = name,
                        category = category,
                        allowed = false,
                        tier = "on_demand",
                        retention = "until_revoked",
                        rateLimitPerHour = 0,
                        expiresAt = 0L,
                    )
                } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchOwnSecretRows failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchShareableHandlerRows(): List<SharePolicyRow> {
        return try {
            // Handlers default to ALLOWED for newly-activated peer
            // connections — that matches the gate's fallback (which
            // honours share_globally when the policy has no explicit
            // entry) and the user expectation that a freshly-connected
            // peer can use the user's published capabilities. The user
            // can flip individual rows to deny via the editor.
            ownerSpaceClient.listHandlers()
                .filter { it.shareable && it.enabled && it.shareGlobally }
                .map { h ->
                    SharePolicyRow(
                        key = "handler:${h.id}",
                        displayName = h.name.ifEmpty { h.id },
                        category = "Capability",
                        allowed = true,
                        tier = "on_demand",
                        retention = "until_revoked",
                        rateLimitPerHour = 0,
                        expiresAt = 0L,
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "fetchShareableHandlerRows failed: ${e.message}")
            emptyList()
        }
    }

    private fun setPresenceOverride(value: Boolean?) {
        val current = _state.value as? MySharingState.Loaded ?: return
        val previous = current.presenceOverride
        _state.update { s ->
            (s as? MySharingState.Loaded)?.copy(presenceOverride = value, isPresenceUpdating = true) ?: s
        }
        viewModelScope.launch {
            ownerSpaceClient.setPresenceOverride(connectionId, value).fold(
                onSuccess = {
                    _state.update { s ->
                        (s as? MySharingState.Loaded)?.copy(isPresenceUpdating = false) ?: s
                    }
                },
                onFailure = { err ->
                    Log.w(TAG, "setPresenceOverride failed: ${err.message}")
                    _state.update { s ->
                        (s as? MySharingState.Loaded)?.copy(presenceOverride = previous, isPresenceUpdating = false) ?: s
                    }
                },
            )
        }
    }

    private fun setLocationSharing(enabled: Boolean) {
        _state.update { s ->
            (s as? MySharingState.Loaded)?.copy(isTogglingLocation = true) ?: s
        }
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("connection_id", connectionId)
                    addProperty("enabled", enabled)
                }
                val resp = ownerSpaceClient.sendAndAwaitResponse("location.sharing.toggle", payload, 10_000L)
                val ok = resp is VaultResponse.HandlerResult && resp.success
                _state.update { s ->
                    val loaded = s as? MySharingState.Loaded ?: return@update s
                    loaded.copy(
                        isLocationSharingEnabled = if (ok) enabled else loaded.isLocationSharingEnabled,
                        isTogglingLocation = false,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "setLocationSharing", e)
                _state.update { s ->
                    (s as? MySharingState.Loaded)?.copy(isTogglingLocation = false) ?: s
                }
            }
        }
    }

    private fun updatePolicy(row: SharePolicyRow) {
        viewModelScope.launch {
            try {
                val itemJson = JsonObject().apply {
                    addProperty("allowed", row.allowed)
                    if (row.tier.isNotEmpty()) addProperty("tier", row.tier)
                    if (row.retention.isNotEmpty()) addProperty("retention", row.retention)
                    if (row.rateLimitPerHour > 0) addProperty("rate_limit_per_hour", row.rateLimitPerHour)
                    if (row.expiresAt > 0) addProperty("expires_at", row.expiresAt)
                }
                val items = JsonObject().apply { add(row.key, itemJson) }
                val payload = JsonObject().apply {
                    addProperty("connection_id", connectionId)
                    add("items", items)
                }
                val resp = ownerSpaceClient.sendAndAwaitResponse("connection.share-policy.set", payload, 10_000L)
                if (resp is VaultResponse.HandlerResult && resp.success) {
                    val current = _state.value as? MySharingState.Loaded ?: return@launch
                    _state.value = current.copy(
                        rows = current.rows.map { if (it.key == row.key) row else it }
                    )
                } else {
                    Log.w(TAG, "share-policy.set failed: ${(resp as? VaultResponse.Error)?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "updatePolicy", e)
            }
        }
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "data" -> "Personal data"
        "secret" -> "Secret"
        "wallet" -> "Wallet"
        "handler" -> "Capability"
        "action" -> "Action"
        "setting" -> "Setting"
        else -> kind.replaceFirstChar { it.uppercase() }
    }

    private fun displayNameFor(kind: String, id: String): String =
        humanizeNamespace(id)

    private fun humanizeNamespace(ns: String): String =
        ns.split(".", "_", "-").joinToString(" ") { seg ->
            seg.replaceFirstChar { c -> c.uppercase() }
        }
}

sealed class MySharingState {
    object Loading : MySharingState()
    data class Loaded(
        val peerName: String,
        val presenceOverride: Boolean?,
        val isLocationSharingEnabled: Boolean,
        val isTogglingLocation: Boolean,
        val isPresenceUpdating: Boolean,
        val rows: List<SharePolicyRow>,
    ) : MySharingState()
    data class Error(val message: String) : MySharingState()
}

sealed class MySharingEvent {
    data class SetPresenceOverride(val value: Boolean?) : MySharingEvent()
    data class SetLocationSharing(val enabled: Boolean) : MySharingEvent()
    data class UpdatePolicy(val row: SharePolicyRow) : MySharingEvent()
    object Refresh : MySharingEvent()
}
