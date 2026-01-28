package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for unified feed/event system operations via NATS.
 *
 * Supports:
 * - Feed operations: list, get, action, read, archive, delete, sync
 * - Settings: get/update feed settings
 * - Audit: query, export
 */
@Singleton
class FeedClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager
) {
    private val gson = Gson()
    private var jetStreamClient: JetStreamNatsClient? = null

    companion object {
        private const val TAG = "FeedClient"
        private const val REQUEST_TIMEOUT_MS = 30_000L
    }

    /**
     * Initialize JetStream client for reliable message delivery.
     */
    private fun ensureJetStreamInitialized(): Result<JetStreamNatsClient> {
        jetStreamClient?.let { client ->
            if (client.isConnected) return Result.success(client)
        }

        val natsClient = connectionManager.getNatsClient()
        if (!natsClient.isConnected) {
            return Result.failure(NatsException("NATS not connected"))
        }

        val androidClient = natsClient.getAndroidClient()
        val jsClient = JetStreamNatsClient()
        jsClient.initialize(androidClient)
        jetStreamClient = jsClient

        return Result.success(jsClient)
    }

    // MARK: - Feed Operations

    /**
     * List feed items with optional filtering.
     *
     * @param status Filter by status: "active", "read", "archived", or null for all
     * @param limit Maximum number of items to return (default 50)
     * @param offset Pagination offset
     * @return List of feed events
     */
    suspend fun listFeed(
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Result<FeedListResponse> {
        val payload = JsonObject().apply {
            status?.let { addProperty("status", it) }
            addProperty("limit", limit)
            addProperty("offset", offset)
        }

        return sendRequest("feed.list", payload) { result ->
            val eventsArray = result.getAsJsonArray("events")
            val events = eventsArray?.map { gson.fromJson(it, FeedEvent::class.java) } ?: emptyList()
            val total = result.get("total")?.asInt ?: events.size
            FeedListResponse(events = events, total = total)
        }
    }

    /**
     * Get a single feed event by ID.
     */
    suspend fun getEvent(eventId: String): Result<FeedEvent> {
        val payload = JsonObject().apply {
            addProperty("event_id", eventId)
        }

        return sendRequest("feed.get", payload) { result ->
            gson.fromJson(result.getAsJsonObject("event"), FeedEvent::class.java)
        }
    }

    /**
     * Execute an action on a feed event (accept/decline).
     *
     * @param eventId The event ID
     * @param action The action to take: "accept", "decline", "acknowledge", etc.
     * @param data Optional action-specific data
     */
    suspend fun executeAction(
        eventId: String,
        action: String,
        data: Map<String, String>? = null
    ): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("event_id", eventId)
            addProperty("action", action)
            data?.let {
                val dataObj = JsonObject()
                it.forEach { (k, v) -> dataObj.addProperty(k, v) }
                add("data", dataObj)
            }
        }

        return sendRequest("feed.action", payload) { Unit }
    }

    /**
     * Mark an event as read.
     */
    suspend fun markRead(eventId: String): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("event_id", eventId)
        }

        return sendRequest("feed.read", payload) { Unit }
    }

    /**
     * Mark multiple events as read.
     */
    suspend fun markMultipleRead(eventIds: List<String>): Result<Unit> {
        val payload = JsonObject().apply {
            val idsArray = gson.toJsonTree(eventIds)
            add("event_ids", idsArray)
        }

        return sendRequest("feed.read", payload) { Unit }
    }

    /**
     * Archive an event.
     */
    suspend fun archiveEvent(eventId: String): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("event_id", eventId)
        }

        return sendRequest("feed.archive", payload) { Unit }
    }

    /**
     * Delete (soft delete) an event.
     */
    suspend fun deleteEvent(eventId: String): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("event_id", eventId)
        }

        return sendRequest("feed.delete", payload) { Unit }
    }

    /**
     * Sync events since a given sequence number.
     * Used for incremental updates.
     *
     * @param sinceSequence Get events with sequence > this value (0 for all)
     * @param limit Maximum events to return
     * @return Sync response with events and latest sequence
     */
    suspend fun sync(sinceSequence: Long = 0, limit: Int = 100): Result<FeedSyncResponse> {
        val payload = JsonObject().apply {
            addProperty("since_sequence", sinceSequence)
            addProperty("limit", limit)
        }

        return sendRequest("feed.sync", payload) { result ->
            val eventsArray = result.getAsJsonArray("events")
            val events = eventsArray?.map { gson.fromJson(it, FeedEvent::class.java) } ?: emptyList()
            val latestSequence = result.get("latest_sequence")?.asLong ?: 0
            val hasMore = result.get("has_more")?.asBoolean ?: false
            FeedSyncResponse(events = events, latestSequence = latestSequence, hasMore = hasMore)
        }
    }

    // MARK: - Settings

    /**
     * Get feed settings.
     */
    suspend fun getSettings(): Result<FeedSettings> {
        return sendRequest("feed.settings.get", JsonObject()) { result ->
            gson.fromJson(result.getAsJsonObject("settings") ?: result, FeedSettings::class.java)
        }
    }

    /**
     * Update feed settings.
     */
    suspend fun updateSettings(settings: FeedSettings): Result<FeedSettings> {
        val payload = JsonObject().apply {
            add("settings", gson.toJsonTree(settings))
        }

        return sendRequest("feed.settings.update", payload) { result ->
            gson.fromJson(result.getAsJsonObject("settings") ?: result, FeedSettings::class.java)
        }
    }

    // MARK: - Audit Operations

    /**
     * Query audit log with filters.
     *
     * @param eventTypes Filter by event types (e.g., ["call.incoming", "message.received"])
     * @param startDate Start date as epoch millis
     * @param endDate End date as epoch millis
     * @param limit Max results
     */
    suspend fun queryAudit(
        eventTypes: List<String>? = null,
        startDate: Long? = null,
        endDate: Long? = null,
        limit: Int = 100
    ): Result<AuditQueryResponse> {
        val payload = JsonObject().apply {
            eventTypes?.let { add("event_types", gson.toJsonTree(it)) }
            startDate?.let { addProperty("start_date", it) }
            endDate?.let { addProperty("end_date", it) }
            addProperty("limit", limit)
        }

        return sendRequest("audit.query", payload) { result ->
            val eventsArray = result.getAsJsonArray("events")
            val events = eventsArray?.map { gson.fromJson(it, FeedEvent::class.java) } ?: emptyList()
            val total = result.get("total")?.asInt ?: events.size
            AuditQueryResponse(events = events, total = total)
        }
    }

    /**
     * Export audit events (max 1000).
     */
    suspend fun exportAudit(
        eventTypes: List<String>? = null,
        startDate: Long? = null,
        endDate: Long? = null
    ): Result<AuditExportResponse> {
        val payload = JsonObject().apply {
            eventTypes?.let { add("event_types", gson.toJsonTree(it)) }
            startDate?.let { addProperty("start_date", it) }
            endDate?.let { addProperty("end_date", it) }
        }

        return sendRequest("audit.export", payload) { result ->
            val eventsArray = result.getAsJsonArray("events")
            val events = eventsArray?.map { gson.fromJson(it, FeedEvent::class.java) } ?: emptyList()
            val exportedAt = result.get("exported_at")?.asLong ?: System.currentTimeMillis()
            AuditExportResponse(events = events, exportedAt = exportedAt)
        }
    }

    // MARK: - Private Helpers

    private suspend fun <T> sendRequest(
        messageType: String,
        payload: JsonObject,
        parser: (JsonObject) -> T
    ): Result<T> {
        Log.d(TAG, "Sending $messageType request")

        val ownerSpace = ownerSpaceClient.getOwnerSpace()
        if (ownerSpace == null) {
            Log.e(TAG, "OwnerSpace not set")
            return Result.failure(NatsException("OwnerSpace not set"))
        }

        // Use JetStream for reliable delivery (enclave publishes to JetStream)
        val jsResult = ensureJetStreamInitialized()
        if (jsResult.isSuccess) {
            val jsClient = jsResult.getOrThrow()
            Log.d(TAG, "Using JetStream for $messageType")

            val requestId = UUID.randomUUID().toString()
            val request = JsonObject().apply {
                addProperty("id", requestId)
                addProperty("type", messageType)
                addProperty("timestamp", java.time.Instant.now().toString())
                // Merge the payload fields
                payload.entrySet().forEach { (key, value) ->
                    add(key, value)
                }
            }

            val topic = "$ownerSpace.forVault.$messageType"
            val responseTopic = "$ownerSpace.forApp.$messageType.response"

            return try {
                val response = jsClient.requestWithJetStream(
                    requestSubject = topic,
                    responseSubject = responseTopic,
                    payload = gson.toJson(request).toByteArray(Charsets.UTF_8),
                    timeoutMs = REQUEST_TIMEOUT_MS
                )

                response.fold(
                    onSuccess = { msg ->
                        Log.d(TAG, "$messageType response received: ${msg.dataString.take(200)}")
                        val jsonResponse = gson.fromJson(msg.dataString, JsonObject::class.java)

                        // Check for error response
                        if (jsonResponse.has("error") && !jsonResponse.get("error").isJsonNull) {
                            val error = jsonResponse.get("error").asString
                            Log.w(TAG, "$messageType failed: $error")
                            Result.failure(NatsException(error))
                        } else if (jsonResponse.has("success") && jsonResponse.get("success").asBoolean == false) {
                            val error = jsonResponse.get("error")?.asString ?: "Request failed"
                            Log.w(TAG, "$messageType failed: $error")
                            Result.failure(NatsException(error))
                        } else {
                            // Extract result field or use whole response
                            val resultObj = if (jsonResponse.has("result")) {
                                jsonResponse.getAsJsonObject("result")
                            } else {
                                jsonResponse
                            }
                            try {
                                val parsed = parser(resultObj)
                                Result.success(parsed)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse $messageType response", e)
                                Result.failure(e)
                            }
                        }
                    },
                    onFailure = { e ->
                        Log.e(TAG, "$messageType request failed: ${e.message}", e)
                        Result.failure(e)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "JetStream $messageType failed: ${e.message}", e)
                Result.failure(e)
            }
        }

        // Fallback to regular approach if JetStream not available
        Log.w(TAG, "JetStream not available, using regular subscription")
        val requestIdResult = ownerSpaceClient.sendToVault(messageType, payload)
        if (requestIdResult.isFailure) {
            Log.e(TAG, "Failed to send $messageType request", requestIdResult.exceptionOrNull())
            return Result.failure(requestIdResult.exceptionOrNull()
                ?: NatsException("Failed to send $messageType"))
        }

        val requestId = requestIdResult.getOrThrow()

        // Wait for response
        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            ownerSpaceClient.vaultResponses.first { it.requestId == requestId }
        }

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    try {
                        val parsed = parser(response.result)
                        Result.success(parsed)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse $messageType response", e)
                        Result.failure(e)
                    }
                } else {
                    Log.w(TAG, "$messageType failed: ${response.error}")
                    Result.failure(NatsException(response.error ?: "Request failed"))
                }
            }
            is VaultResponse.Error -> {
                Log.e(TAG, "$messageType error: ${response.code} - ${response.message}")
                Result.failure(NatsException("${response.code}: ${response.message}"))
            }
            null -> {
                Log.w(TAG, "$messageType request timed out")
                Result.failure(NatsException("Request timed out"))
            }
            else -> {
                Log.w(TAG, "Unexpected response type for $messageType")
                Result.failure(NatsException("Unexpected response"))
            }
        }
    }
}

// MARK: - Data Classes

/**
 * Unified feed event from the backend.
 * Maps to vault-manager Event type.
 */
data class FeedEvent(
    @SerializedName("event_id") val eventId: String,
    @SerializedName("event_type") val eventType: String,
    @SerializedName("source_type") val sourceType: String?,
    @SerializedName("source_id") val sourceId: String?,
    val title: String,
    val message: String?,
    val metadata: Map<String, String>?,
    @SerializedName("feed_status") val feedStatus: String,
    @SerializedName("action_type") val actionType: String?,
    val priority: Int,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("read_at") val readAt: Long?,
    @SerializedName("actioned_at") val actionedAt: Long?,
    @SerializedName("archived_at") val archivedAt: Long?,
    @SerializedName("expires_at") val expiresAt: Long?,
    @SerializedName("sync_sequence") val syncSequence: Long,
    @SerializedName("retention_class") val retentionClass: String
) {
    /**
     * Check if this event is unread.
     */
    val isUnread: Boolean get() = feedStatus == "active" && readAt == null

    /**
     * Check if this event requires user action.
     */
    val requiresAction: Boolean get() = actionType != null && actionType.isNotEmpty() && actionedAt == null

    /**
     * Get priority level.
     */
    val priorityLevel: EventPriority get() = when (priority) {
        -1 -> EventPriority.LOW
        0 -> EventPriority.NORMAL
        1 -> EventPriority.HIGH
        2 -> EventPriority.URGENT
        else -> EventPriority.NORMAL
    }
}

enum class EventPriority {
    LOW, NORMAL, HIGH, URGENT
}

/**
 * Feed settings.
 */
data class FeedSettings(
    @SerializedName("feed_retention_days") val feedRetentionDays: Int = 30,
    @SerializedName("audit_retention_days") val auditRetentionDays: Int = 90,
    @SerializedName("archive_behavior") val archiveBehavior: String = "archive",
    @SerializedName("auto_archive_enabled") val autoArchiveEnabled: Boolean = true,
    @SerializedName("updated_at") val updatedAt: Long = 0
)

/**
 * Response from feed.list
 */
data class FeedListResponse(
    val events: List<FeedEvent>,
    val total: Int
)

/**
 * Response from feed.sync
 */
data class FeedSyncResponse(
    val events: List<FeedEvent>,
    val latestSequence: Long,
    val hasMore: Boolean
)

/**
 * Response from audit.query
 */
data class AuditQueryResponse(
    val events: List<FeedEvent>,
    val total: Int
)

/**
 * Response from audit.export
 */
data class AuditExportResponse(
    val events: List<FeedEvent>,
    val exportedAt: Long
)
