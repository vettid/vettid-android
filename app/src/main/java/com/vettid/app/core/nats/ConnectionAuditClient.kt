package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the per-connection audit trail.
 *
 * Backs the Connection History screen. The vault owns a
 * `connection.audit.*` namespace that records one entry per
 * user-visible interaction (messages, calls, transfers, lifecycle) and
 * exposes paginated list + FTS5-backed search.
 *
 * See `vettid-dev/docs/CONNECTION-AUDIT-TRAIL-PLAN.md`.
 */
@Singleton
class ConnectionAuditClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "ConnectionAuditClient"
        private const val DEFAULT_TIMEOUT_MS = 15_000L
    }

    suspend fun list(
        connectionId: String,
        limit: Int = 100,
        cursor: AuditCursor? = null,
        sinceEpoch: Long? = null,
        untilEpoch: Long? = null,
        eventTypePrefixes: List<String>? = null,
    ): Result<AuditListResult> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            addProperty("limit", limit)
            cursor?.let {
                addProperty("cursor_created_at", it.createdAt)
                addProperty("cursor_entry_id", it.entryId)
            }
            sinceEpoch?.takeIf { it > 0 }?.let { addProperty("since_epoch", it) }
            untilEpoch?.takeIf { it > 0 }?.let { addProperty("until_epoch", it) }
            eventTypePrefixes?.takeIf { it.isNotEmpty() }?.let {
                val arr = com.google.gson.JsonArray()
                it.forEach(arr::add)
                add("event_types", arr)
            }
        }
        return send("connection.audit.list", payload)
    }

    suspend fun search(
        connectionId: String,
        query: String,
        limit: Int = 100,
        cursor: AuditCursor? = null,
        eventTypePrefixes: List<String>? = null,
    ): Result<AuditListResult> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            addProperty("query", query)
            addProperty("limit", limit)
            cursor?.let {
                addProperty("cursor_created_at", it.createdAt)
                addProperty("cursor_entry_id", it.entryId)
            }
            eventTypePrefixes?.takeIf { it.isNotEmpty() }?.let {
                val arr = com.google.gson.JsonArray()
                it.forEach(arr::add)
                add("event_types", arr)
            }
        }
        return send("connection.audit.search", payload)
    }

    private suspend fun send(type: String, payload: JsonObject): Result<AuditListResult> {
        val response = ownerSpaceClient.sendAndAwaitResponse(type, payload, DEFAULT_TIMEOUT_MS)
        return when (response) {
            null -> Result.failure(Exception("Request timed out"))
            is VaultResponse.HandlerResult -> {
                if (!response.success || response.result == null) {
                    Result.failure(Exception(response.error ?: "audit request failed"))
                } else {
                    try {
                        Result.success(parseResponse(response.result))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse audit response", e)
                        Result.failure(e)
                    }
                }
            }
            is VaultResponse.Error -> Result.failure(Exception("${response.code}: ${response.message}"))
            else -> Result.failure(Exception("Unexpected response type"))
        }
    }

    private fun parseResponse(json: JsonObject): AuditListResult {
        val entriesArr = json.getAsJsonArray("entries") ?: com.google.gson.JsonArray()
        val entries = entriesArr.mapNotNull { el ->
            try {
                gson.fromJson(el, AuditEntry::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse audit entry: $el", e)
                null
            }
        }
        val nextCursor = json.getAsJsonObject("next_cursor")?.let {
            AuditCursor(
                createdAt = it.get("created_at")?.asLong ?: 0L,
                entryId = it.get("entry_id")?.asString ?: "",
            )
        }
        val total = json.get("total_estimate")?.asInt ?: entries.size
        // Audit-chain anchor (#125): forwarded from the vault when
        // available. Empty / absent means the vault hasn't been
        // unlocked this session — caller renders rows as Unsigned.
        return AuditListResult(
            entries = entries,
            nextCursor = nextCursor,
            totalEstimate = total,
            auditPubB64 = json.get("audit_pub")?.asString?.takeIf { it.isNotBlank() },
            bindingSigB64 = json.get("binding_sig")?.asString?.takeIf { it.isNotBlank() },
            identityPubB64 = json.get("identity_pub")?.asString?.takeIf { it.isNotBlank() },
        )
    }
}

// --- Data models ---

/**
 * One row in the per-connection audit trail. Event type strings match
 * the vault-side taxonomy (`message.sent`, `call.voice.completed`, …).
 *
 * Chain fields (entry_hash / previous_hash / entry_sig) ship with
 * each row when the vault has an audit-key anchor available, and feed
 * AuditChainVerifier for client-side verification. `verification` is
 * stamped by the client after the verifier runs over the page; rows
 * fetched but not yet verified report Unsigned. See #125.
 */
data class AuditEntry(
    val entry_id: String,
    val connection_id: String,
    val peer_guid: String? = null,
    val event_type: String,
    val direction: String? = null,
    val title: String,
    val body: String? = null,
    val created_at: Long,
    val refs: Map<String, String>? = null,
    val metadata: Map<String, String>? = null,
    val entry_hash: String? = null,
    val previous_hash: String? = null,
    val entry_sig: String? = null,
    val verification: com.vettid.app.core.audit.AuditChainVerifier.RowState =
        com.vettid.app.core.audit.AuditChainVerifier.RowState.Unsigned,
)

data class AuditCursor(
    val createdAt: Long,
    val entryId: String,
)

/**
 * Server response for connection.audit.{list,search}. The anchor
 * fields (auditPubB64 / bindingSigB64 / identityPubB64) carry the
 * audit-chain anchor for client-side verification — empty when the
 * vault was queried before the user unlocked this session. See #125.
 */
data class AuditListResult(
    val entries: List<AuditEntry>,
    val nextCursor: AuditCursor?,
    val totalEstimate: Int,
    val auditPubB64: String? = null,
    val bindingSigB64: String? = null,
    val identityPubB64: String? = null,
)
