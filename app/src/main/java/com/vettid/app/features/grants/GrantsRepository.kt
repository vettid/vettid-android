package com.vettid.app.features.grants

import android.util.Log
import com.google.gson.JsonObject
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GrantsRepo"

@Singleton
class GrantsRepository @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
) {

    suspend fun sendRequest(
        connectionId: String,
        itemKind: String,
        itemRef: String,
        itemLabel: String,
        mode: String,
        deliverTo: String,
        requestedExpiresAt: Long,
        requestedMaxUses: Int,
        reason: String,
    ): Result<String> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            addProperty("item_kind", itemKind)
            addProperty("item_ref", itemRef)
            addProperty("item_label", itemLabel)
            addProperty("mode", mode)
            addProperty("deliver_to", deliverTo)
            addProperty("requested_expires_at", requestedExpiresAt)
            addProperty("requested_max_uses", requestedMaxUses)
            addProperty("reason", reason)
        }
        return when (val resp = ownerSpaceClient.sendAndAwaitResponse("grant.request", payload, 10_000L)) {
            is VaultResponse.HandlerResult ->
                if (resp.success) Result.success(resp.result?.get("request_id")?.asString.orEmpty())
                else Result.failure(Exception(resp.result?.get("error")?.asString ?: "request failed"))
            is VaultResponse.Error -> Result.failure(Exception(resp.message))
            else -> Result.failure(Exception("unexpected response"))
        }
    }

    suspend fun approve(requestId: String, expiresAt: Long, maxUses: Int, mode: String): Result<String> {
        val payload = JsonObject().apply {
            addProperty("request_id", requestId)
            if (expiresAt > 0) addProperty("expires_at", expiresAt)
            if (maxUses > 0) addProperty("max_uses", maxUses)
            if (mode.isNotEmpty()) addProperty("mode", mode)
        }
        return when (val resp = ownerSpaceClient.sendAndAwaitResponse("grant.approve", payload, 10_000L)) {
            is VaultResponse.HandlerResult ->
                if (resp.success) Result.success(resp.result?.get("grant_id")?.asString.orEmpty())
                else Result.failure(Exception("approve failed"))
            is VaultResponse.Error -> Result.failure(Exception(resp.message))
            else -> Result.failure(Exception("unexpected response"))
        }
    }

    suspend fun deny(requestId: String, reason: String = ""): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("request_id", requestId)
            if (reason.isNotEmpty()) addProperty("reason", reason)
        }
        return runSimple("grant.deny", payload)
    }

    suspend fun revoke(grantId: String, reason: String = ""): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("grant_id", grantId)
            if (reason.isNotEmpty()) addProperty("reason", reason)
        }
        return runSimple("grant.revoke", payload)
    }

    suspend fun fetchRemote(grantId: String): Result<String> {
        val payload = JsonObject().apply { addProperty("grant_id", grantId) }
        return when (val resp = ownerSpaceClient.sendAndAwaitResponse("grant.fetch-remote", payload, 10_000L)) {
            is VaultResponse.HandlerResult ->
                if (resp.success) Result.success(resp.result?.get("request_id")?.asString.orEmpty())
                else Result.failure(Exception("fetch failed"))
            is VaultResponse.Error -> Result.failure(Exception(resp.message))
            else -> Result.failure(Exception("unexpected response"))
        }
    }

    suspend fun listOutbound(connectionId: String?): Result<List<GrantSummary>> {
        val payload = JsonObject().apply { if (!connectionId.isNullOrEmpty()) addProperty("connection_id", connectionId) }
        val resp = ownerSpaceClient.sendAndAwaitResponse("grant.list-outbound", payload, 10_000L)
        return parseGrants(resp, "grants")
    }

    suspend fun listInbound(connectionId: String?): Result<List<GrantSummary>> {
        val payload = JsonObject().apply { if (!connectionId.isNullOrEmpty()) addProperty("connection_id", connectionId) }
        val resp = ownerSpaceClient.sendAndAwaitResponse("grant.list-inbound", payload, 10_000L)
        return parseGrants(resp, "received_grants")
    }

    suspend fun listPending(): Result<List<PendingRequestSummary>> {
        val resp = ownerSpaceClient.sendAndAwaitResponse("grant.list-pending", JsonObject(), 10_000L)
        if (resp !is VaultResponse.HandlerResult || !resp.success || resp.result == null) {
            return Result.failure(Exception("list-pending failed"))
        }
        val arr = resp.result.getAsJsonArray("pending") ?: return Result.success(emptyList())
        val out = mutableListOf<PendingRequestSummary>()
        arr.forEach { el ->
            try {
                val o = el.asJsonObject
                out += PendingRequestSummary(
                    requestId = o.get("request_id").asString,
                    requesterGuid = o.get("requester_guid")?.asString.orEmpty(),
                    connectionId = o.get("connection_id")?.asString.orEmpty(),
                    itemKind = o.get("item_kind")?.asString.orEmpty(),
                    itemRef = o.get("item_ref")?.asString.orEmpty(),
                    itemLabel = o.get("item_label")?.asString.orEmpty(),
                    requestedMode = o.get("requested_mode")?.asString.orEmpty(),
                    requestedExpiresAt = o.get("requested_expires_at")?.asLong ?: 0L,
                    requestedMaxUses = o.get("requested_max_uses")?.asInt ?: 0,
                    deliverTo = o.get("deliver_to")?.asString.orEmpty(),
                    reason = o.get("reason")?.asString.orEmpty(),
                    receivedAt = o.get("received_at")?.asLong ?: 0L,
                )
            } catch (e: Exception) {
                Log.w(TAG, "skip malformed pending: ${e.message}")
            }
        }
        return Result.success(out)
    }

    private fun parseGrants(resp: VaultResponse?, arrayKey: String): Result<List<GrantSummary>> {
        if (resp !is VaultResponse.HandlerResult || !resp.success || resp.result == null) {
            return Result.failure(Exception("$arrayKey list failed"))
        }
        val arr = resp.result.getAsJsonArray(arrayKey) ?: return Result.success(emptyList())
        val out = mutableListOf<GrantSummary>()
        arr.forEach { el ->
            try {
                val o = el.asJsonObject
                out += GrantSummary(
                    grantId = o.get("grant_id").asString,
                    ownerGuid = o.get("owner_guid")?.asString.orEmpty(),
                    requesterGuid = o.get("requester_guid")?.asString.orEmpty(),
                    granterGuid = o.get("granter_guid")?.asString.orEmpty(),
                    connectionId = o.get("connection_id")?.asString.orEmpty(),
                    itemKind = o.get("item_kind")?.asString.orEmpty(),
                    itemRef = o.get("item_ref")?.asString.orEmpty(),
                    itemLabel = o.get("item_label")?.asString.orEmpty(),
                    mode = o.get("mode")?.asString.orEmpty(),
                    expiresAt = o.get("expires_at")?.asLong ?: 0L,
                    maxUses = o.get("max_uses")?.asInt ?: 0,
                    usesSoFar = o.get("uses_so_far")?.asInt ?: 0,
                    status = o.get("status")?.asString.orEmpty(),
                    createdAt = o.get("created_at")?.asLong ?: 0L,
                    grantedAt = o.get("granted_at")?.asLong ?: 0L,
                    lastFetched = (o.get("last_fetched")?.asLong ?: o.get("last_fetched_at")?.asLong) ?: 0L,
                )
            } catch (e: Exception) {
                Log.w(TAG, "skip malformed grant: ${e.message}")
            }
        }
        return Result.success(out)
    }

    private suspend fun runSimple(op: String, payload: JsonObject): Result<Unit> {
        return when (val resp = ownerSpaceClient.sendAndAwaitResponse(op, payload, 10_000L)) {
            is VaultResponse.HandlerResult ->
                if (resp.success) Result.success(Unit) else Result.failure(Exception("$op failed"))
            is VaultResponse.Error -> Result.failure(Exception(resp.message))
            else -> Result.failure(Exception("unexpected response"))
        }
    }
}

data class GrantSummary(
    val grantId: String,
    val ownerGuid: String,
    val requesterGuid: String,
    val granterGuid: String,
    val connectionId: String,
    val itemKind: String,
    val itemRef: String,
    val itemLabel: String,
    val mode: String,
    val expiresAt: Long,
    val maxUses: Int,
    val usesSoFar: Int,
    val status: String,
    val createdAt: Long,
    val grantedAt: Long,
    val lastFetched: Long,
)

data class PendingRequestSummary(
    val requestId: String,
    val requesterGuid: String,
    val connectionId: String,
    val itemKind: String,
    val itemRef: String,
    val itemLabel: String,
    val requestedMode: String,
    val requestedExpiresAt: Long,
    val requestedMaxUses: Int,
    val deliverTo: String,
    val reason: String,
    val receivedAt: Long,
)

object GrantModes {
    const val ONE_SHOT = "one-shot"
    const val RENEWABLE = "renewable"
    const val AGENT_RENEWABLE = "agent-renewable"
}

object GrantItemKinds {
    const val DATA = "data"
    const val SECRET = "secret"
}
