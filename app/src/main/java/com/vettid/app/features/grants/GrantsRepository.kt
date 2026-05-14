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

    /** Receiver-side: ask the owner to perform an operation using a critical secret. */
    suspend fun requestCriticalUse(
        connectionId: String,
        itemRef: String,
        itemLabel: String,
        operation: String,
        payloadBase64: String,
        context: String,
    ): Result<String> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            addProperty("item_ref", itemRef)
            addProperty("item_label", itemLabel)
            addProperty("operation", operation)
            addProperty("payload", payloadBase64)
            if (context.isNotBlank()) addProperty("context", context)
        }
        return when (val resp = ownerSpaceClient.sendAndAwaitResponse("critical-secret-use.request-use", payload, 10_000L)) {
            is VaultResponse.HandlerResult ->
                if (resp.success) Result.success(resp.result?.get("request_id")?.asString.orEmpty())
                else Result.failure(Exception("critical-secret-use.request failed"))
            is VaultResponse.Error -> Result.failure(Exception(resp.message))
            else -> Result.failure(Exception("unexpected response"))
        }
    }

    /**
     * Owner-side: approve a pending critical-secret use with password
     * authorization. Caller supplies the password-derived fields
     * (encrypted under a fresh UTK) — mirrors credential.secret.get.
     */
    suspend fun approveCriticalUse(
        requestId: String,
        encryptedCredential: String,
        encryptedPasswordHash: String,
        ephemeralPublicKey: String,
        nonce: String,
        keyId: String,
    ): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("encrypted_credential", encryptedCredential)
            addProperty("encrypted_password_hash", encryptedPasswordHash)
            addProperty("ephemeral_public_key", ephemeralPublicKey)
            addProperty("nonce", nonce)
            addProperty("key_id", keyId)
        }
        return runSimple("critical-secret-use.approve", payload)
    }

    suspend fun denyCriticalUse(requestId: String, reason: String = ""): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("request_id", requestId)
            if (reason.isNotEmpty()) addProperty("reason", reason)
        }
        return runSimple("critical-secret-use.deny", payload)
    }

    /**
     * Receiver-side: approve a pending identity-verify challenge with
     * password authorization. The vault decrypts the credential blob,
     * signs the challenge nonce with the Ed25519 identity key, and
     * publishes the result back to the challenger. No TTL caching —
     * every approve re-prompts for the password.
     */
    suspend fun approveVerify(
        requestId: String,
        encryptedCredential: String,
        encryptedPasswordHash: String,
        ephemeralPublicKey: String,
        nonce: String,
        keyId: String,
    ): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("encrypted_credential", encryptedCredential)
            addProperty("encrypted_password_hash", encryptedPasswordHash)
            addProperty("ephemeral_public_key", ephemeralPublicKey)
            addProperty("nonce", nonce)
            addProperty("key_id", keyId)
        }
        return runSimple("connection-authenticate.approve", payload)
    }

    suspend fun denyVerify(requestId: String, reason: String = ""): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("request_id", requestId)
            if (reason.isNotEmpty()) addProperty("reason", reason)
        }
        return runSimple("connection-authenticate.deny", payload)
    }

    /**
     * Read cached verify-identity state for a single connection. The
     * vault persists last outcome + timestamp on both inbound and
     * outbound sides; the detail screen primarily renders the
     * outbound fields ("when did I last verify them?").
     */
    suspend fun getVerifyState(connectionId: String): Result<VerifyStatePayload?> {
        val payload = JsonObject().apply { addProperty("connection_id", connectionId) }
        return when (val resp = ownerSpaceClient.sendAndAwaitResponse("connection-authenticate.get", payload, 10_000L)) {
            is VaultResponse.HandlerResult -> {
                val state = resp.result?.getAsJsonObject("state") ?: return Result.success(null)
                Result.success(
                    VerifyStatePayload(
                        lastOutboundAt = state.get("last_outbound_at")?.asString.orEmpty(),
                        lastOutboundOk = state.get("last_outbound_ok")?.asBoolean ?: false,
                        lastOutboundReason = state.get("last_outbound_reason")?.asString.orEmpty(),
                        lastInboundAt = state.get("last_inbound_at")?.asString.orEmpty(),
                        lastInboundOk = state.get("last_inbound_ok")?.asBoolean ?: false,
                        lastInboundReason = state.get("last_inbound_reason")?.asString.orEmpty(),
                    )
                )
            }
            is VaultResponse.Error -> Result.failure(Exception(resp.message))
            else -> Result.failure(Exception("unexpected response"))
        }
    }

    /** Issue an authentication challenge to a connection. Result arrives via GrantEvent.AuthenticateResult. */
    suspend fun requestAuthenticate(connectionId: String, context: String): Result<String> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            if (context.isNotBlank()) addProperty("context", context)
        }
        return when (val resp = ownerSpaceClient.sendAndAwaitResponse("connection-authenticate.request", payload, 10_000L)) {
            is VaultResponse.HandlerResult ->
                if (resp.success) Result.success(resp.result?.get("request_id")?.asString.orEmpty())
                else Result.failure(Exception("connection-authenticate.request failed"))
            is VaultResponse.Error -> Result.failure(Exception(resp.message))
            else -> Result.failure(Exception("unexpected response"))
        }
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

    /**
     * List requests this vault has SENT to peers (outgoing), with their
     * current status. Backs the Them-tab "Requested" sub-tab and the
     * peer-catalog "already requested" badge. Scoped to a connection
     * when connectionId is non-empty.
     */
    suspend fun listMyRequests(connectionId: String?): Result<List<OutgoingRequestSummary>> {
        val payload = JsonObject().apply {
            if (!connectionId.isNullOrEmpty()) addProperty("connection_id", connectionId)
        }
        val resp = ownerSpaceClient.sendAndAwaitResponse("grant.list-my-requests", payload, 10_000L)
        if (resp !is VaultResponse.HandlerResult || !resp.success || resp.result == null) {
            return Result.failure(Exception("list-my-requests failed"))
        }
        val arr = resp.result.getAsJsonArray("my_requests") ?: return Result.success(emptyList())
        val out = mutableListOf<OutgoingRequestSummary>()
        arr.forEach { el ->
            try {
                val o = el.asJsonObject
                out += OutgoingRequestSummary(
                    requestId = o.get("request_id").asString,
                    connectionId = o.get("connection_id")?.asString.orEmpty(),
                    itemKind = o.get("item_kind")?.asString.orEmpty(),
                    itemRef = o.get("item_ref")?.asString.orEmpty(),
                    itemLabel = o.get("item_label")?.asString.orEmpty(),
                    mode = o.get("mode")?.asString.orEmpty(),
                    reason = o.get("reason")?.asString.orEmpty(),
                    status = o.get("status")?.asString.orEmpty(),
                    grantId = o.get("grant_id")?.asString.orEmpty(),
                    denialReason = o.get("denial_reason")?.asString.orEmpty(),
                    createdAt = o.get("created_at")?.asLong ?: 0L,
                    respondedAt = o.get("responded_at")?.asLong ?: 0L,
                )
            } catch (e: Exception) {
                Log.w(TAG, "skip malformed my-request: ${e.message}")
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

/**
 * Persistent verify-identity state cached by the vault per connection.
 * Outbound = we challenged them. Inbound = they challenged us. The
 * Detail screen primarily renders the outbound fields (when did I last
 * verify them?) but both are exposed so the same payload powers
 * per-side audit displays.
 */
data class VerifyStatePayload(
    val lastOutboundAt: String,
    val lastOutboundOk: Boolean,
    val lastOutboundReason: String,
    val lastInboundAt: String,
    val lastInboundOk: Boolean,
    val lastInboundReason: String,
) {
    /** True if we have ever issued or received a verify challenge. */
    val hasAnyHistory: Boolean
        get() = lastOutboundAt.isNotBlank() || lastInboundAt.isNotBlank()
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

/**
 * A request this vault has SENT to a peer, with its current status.
 * `status` is "pending" until the peer answers, then "approved" (with
 * `grantId` set) or "denied" (with `denialReason`).
 */
data class OutgoingRequestSummary(
    val requestId: String,
    val connectionId: String,
    val itemKind: String,
    val itemRef: String,
    val itemLabel: String,
    val mode: String,
    val reason: String,
    val status: String,
    val grantId: String,
    val denialReason: String,
    val createdAt: Long,
    val respondedAt: Long,
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
