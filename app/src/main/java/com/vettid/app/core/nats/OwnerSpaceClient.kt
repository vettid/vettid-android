package com.vettid.app.core.nats

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.EncryptedSessionMessage
import com.vettid.app.core.security.wipe
import com.vettid.app.core.crypto.SessionCrypto
import com.vettid.app.core.crypto.SessionInfo
import com.vettid.app.core.crypto.SessionKeyPair
import com.vettid.app.core.network.TransactionKeyInfo
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for OwnerSpace pub/sub communication.
 *
 * OwnerSpace is the private namespace for owner-vault communication.
 * Mobile app topics:
 * - Publish: OwnerSpace.{guid}.forVault.> (send commands to vault)
 * - Subscribe: OwnerSpace.{guid}.forApp.> (receive responses from vault)
 * - Subscribe: OwnerSpace.{guid}.eventTypes (read handler definitions)
 *
 * Supports E2E encryption via session key exchange with vault.
 */
@Singleton
class OwnerSpaceClient @Inject constructor(
    private val connectionManager: NatsConnectionManager,
    private val credentialStore: CredentialStore,
    private val unlockRateLimiter: com.vettid.app.core.security.UnlockRateLimiter,
) {
    private val gson = Gson()

    // E2E session crypto (established via bootstrap)
    private var sessionCrypto: SessionCrypto? = null
    private var pendingKeyPair: SessionKeyPair? = null
    private val natsClient: NatsClient
        get() = connectionManager.getNatsClient()

    private val _vaultResponses = MutableSharedFlow<VaultResponse>(extraBufferCapacity = 64)
    val vaultResponses: SharedFlow<VaultResponse> = _vaultResponses.asSharedFlow()

    private val _vaultEvents = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 64)
    val vaultEvents: SharedFlow<VaultEvent> = _vaultEvents.asSharedFlow()

    private val _credentialRotation = MutableSharedFlow<CredentialRotationMessage>(extraBufferCapacity = 8)
    /** Flow of credential rotation events from vault. Subscribe to handle proactive credential rotation. */
    val credentialRotation: SharedFlow<CredentialRotationMessage> = _credentialRotation.asSharedFlow()

    // Messaging events
    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    /** Flow of new messages from peers. */
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages.asSharedFlow()

    private val _readReceipts = MutableSharedFlow<ReadReceipt>(extraBufferCapacity = 64)
    /** Flow of read receipts from peers. */
    val readReceipts: SharedFlow<ReadReceipt> = _readReceipts.asSharedFlow()

    private val _profileUpdates = MutableSharedFlow<ProfileUpdate>(extraBufferCapacity = 16)
    /** Flow of profile updates from peers. */
    val profileUpdates: SharedFlow<ProfileUpdate> = _profileUpdates.asSharedFlow()

    private val _connectionRevocations = MutableSharedFlow<ConnectionRevoked>(extraBufferCapacity = 16)
    /** Flow of connection revocation notices from peers. */
    val connectionRevocations: SharedFlow<ConnectionRevoked> = _connectionRevocations.asSharedFlow()

    private val _connectionAcceptances = MutableSharedFlow<ConnectionPeerAccepted>(extraBufferCapacity = 16)
    /** Flow of connection acceptance notifications (peer accepted our invitation). */
    val connectionAcceptances: SharedFlow<ConnectionPeerAccepted> = _connectionAcceptances.asSharedFlow()

    private val _connectionStatusUpdates = MutableSharedFlow<ConnectionStatusUpdate>(extraBufferCapacity = 16)
    /** Flow of connection status updates (activated, key-exchanged, rejected). */
    val connectionStatusUpdates: SharedFlow<ConnectionStatusUpdate> = _connectionStatusUpdates.asSharedFlow()

    private val _presenceHeartbeats = MutableSharedFlow<PresenceHeartbeat>(extraBufferCapacity = 32)
    /**
     * Flow of peer presence heartbeats re-emitted by our vault. Each
     * event means "this peer is online as of <at>." The observer side
     * infers offline from heartbeat absence (no event for 90s).
     * Opt-in is handled by the peer vault — if we receive nothing for
     * a given connection, the peer hasn't opted in (or the user
     * hasn't overridden their default).
     */
    val presenceHeartbeats: SharedFlow<PresenceHeartbeat> = _presenceHeartbeats.asSharedFlow()

    private val _devicePendingAuth = MutableSharedFlow<DevicePendingAuthNotification>(extraBufferCapacity = 8)
    /**
     * Flow of desktop devices awaiting session authorization (stage 2 of pairing).
     * Emitted when a desktop has resolved a device invite code and posted
     * device.request-session — the user now needs to scan the desktop's QR and approve.
     */
    val devicePendingAuth: SharedFlow<DevicePendingAuthNotification> = _devicePendingAuth.asSharedFlow()

    private val _deviceSessionRevoked = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Flow of device connection_ids that were revoked (so UI can refresh). */
    val deviceSessionRevoked: SharedFlow<String> = _deviceSessionRevoked.asSharedFlow()

    private val _callEvents = MutableSharedFlow<CallSignalEvent>(extraBufferCapacity = 64)
    /** Flow of call signaling events (vault-routed). */
    val callEvents: SharedFlow<CallSignalEvent> = _callEvents.asSharedFlow()

    private val _grantEvents = MutableSharedFlow<GrantEvent>(extraBufferCapacity = 64)
    /**
     * Flow of grant-flow events emitted by the vault — incoming data
     * requests, grant created/denied/revoked, fetch responses with
     * (transient) plaintext values, critical-secret use prompts +
     * responses, and connection.authenticate verdicts. Subscribers
     * are typically connection-scoped ViewModels that filter by
     * connectionId. The fetch-response carries a plaintext value
     * intended for foreground rendering only — viewmodels must NOT
     * persist it.
     */
    val grantEvents: SharedFlow<GrantEvent> = _grantEvents.asSharedFlow()

    // Feed notification events (Issue #15)
    private val _feedNotifications = MutableSharedFlow<FeedNotification>(extraBufferCapacity = 64)
    /** Flow of feed notifications (new events, status updates). */
    val feedNotifications: SharedFlow<FeedNotification> = _feedNotifications.asSharedFlow()

    /**
     * Tick fired whenever the vault republishes the owner's public
     * profile snapshot (`forApp.profile.public`). Cache holders such
     * as PersonalDataStore observe this and re-hydrate so other
     * devices' edits propagate without a full app restart.
     */
    private val _ownProfileSnapshotTick = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val ownProfileSnapshotTick: SharedFlow<Long> = _ownProfileSnapshotTick.asSharedFlow()

    // Location sharing events
    private val _locationUpdates = MutableSharedFlow<SharedLocationUpdate>(extraBufferCapacity = 16)
    /** Flow of location updates from connections sharing their location. */
    val locationUpdates: SharedFlow<SharedLocationUpdate> = _locationUpdates.asSharedFlow()

    // Transition notifications fired by the vault when a peer flips
    // their sharing toggle (V3/V5) or pings for a one-shot (V6).
    // ConnectionDetailViewModel observes these to drive UI badge
    // refreshes without poll. Activity-feed system cards observe
    // them too (A4) so the timeline reflects the lifecycle.
    private val _peerLocationTransitions = MutableSharedFlow<PeerLocationShareTransition>(extraBufferCapacity = 16)
    /** Flow of peer location-share start/stop/request transitions. */
    val peerLocationTransitions: SharedFlow<PeerLocationShareTransition> = _peerLocationTransitions.asSharedFlow()

    // Agent approval request events
    private val _agentApprovalRequests = MutableSharedFlow<AgentApprovalRequest>(extraBufferCapacity = 16)
    /** Flow of agent approval requests (secret/action requests needing owner approval). */
    val agentApprovalRequests: SharedFlow<AgentApprovalRequest> = _agentApprovalRequests.asSharedFlow()

    // Vault locked events — emitted when the vault returns "vault_locked" error
    // (e.g., after enclave instance refresh where DEK is lost). Observers should
    // trigger PIN re-entry to re-derive the DEK.
    private val _vaultLocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Flow of vault-locked events. Subscribe to trigger PIN re-entry. */
    val vaultLocked: SharedFlow<Unit> = _vaultLocked.asSharedFlow()

    private var appSubscription: NatsSubscription? = null
    private var eventTypesSubscription: NatsSubscription? = null

    /**
     * In-flight request map for the inbox-based request-response path.
     *
     * sendAndAwaitResponse registers a deferred here keyed by requestId
     * before publishing, then awaits. handleVaultResponse routes any
     * `*.{requestId}.response` message to its matching deferred. This
     * replaces the old per-request JetStream consumer create/delete
     * dance — saves two NATS round-trips per vault call (typically
     * ~200-500ms each on a phone over cellular).
     *
     * If the connection drops between publish and response, the deferred
     * times out via the withTimeout wrapper; callers see the same
     * TIMEOUT VaultResponse.Error they'd see from the old path.
     */
    private val inflightRequests = ConcurrentHashMap<String, CompletableDeferred<NatsMessage>>()

    /**
     * Get the current OwnerSpace ID for constructing topic names.
     */
    fun getOwnerSpace(): String? = connectionManager.getOwnerSpaceId()

    /**
     * Subscribe to vault responses and events.
     * Call this after connecting to NATS.
     */
    fun subscribeToVault(): Result<Unit> {
        // Clean up any existing subscriptions first to prevent duplicates
        unsubscribeFromVault()

        val ownerSpaceId = connectionManager.getOwnerSpaceId()
            ?: return Result.failure(NatsException("No OwnerSpace ID available"))

        // Subscribe to responses from vault
        val forAppSubject = "$ownerSpaceId.forApp.>"
        val forAppResult = natsClient.subscribe(forAppSubject) { message ->
            handleVaultResponse(message)
        }

        forAppResult.onSuccess {
            appSubscription = it
            android.util.Log.d(TAG, "Subscribed to $forAppSubject")
        }.onFailure {
            android.util.Log.e(TAG, "Failed to subscribe to $forAppSubject", it)
            return Result.failure(it)
        }

        // Subscribe to event types
        val eventTypesSubject = "$ownerSpaceId.eventTypes"
        val eventTypesResult = natsClient.subscribe(eventTypesSubject) { message ->
            handleEventTypes(message)
        }

        eventTypesResult.onSuccess {
            eventTypesSubscription = it
            android.util.Log.d(TAG, "Subscribed to $eventTypesSubject")
        }.onFailure {
            android.util.Log.e(TAG, "Failed to subscribe to $eventTypesSubject", it)
        }

        return Result.success(Unit)
    }

    /**
     * Unsubscribe from vault topics.
     */
    fun unsubscribeFromVault() {
        appSubscription?.unsubscribe()
        appSubscription = null
        eventTypesSubscription?.unsubscribe()
        eventTypesSubscription = null
        // Don't drain inflightRequests here. unsubscribeFromVault is
        // most often called as the first half of a subscribe-resubscribe
        // sequence (PIN unlock issues new creds, reconnect handlers,
        // etc.) and the new subscription comes up within milliseconds.
        // Any in-flight request will see its response on the new sub
        // and complete normally. If the resubscribe never happens, the
        // per-request withTimeout(timeoutMs) wrapper handles it.
    }

    /**
     * Send a message to the vault.
     *
     * If E2E encryption is enabled, the payload is encrypted before sending.
     * The message envelope (id, type, timestamp) remains in plaintext for routing.
     *
     * @param messageType The message type/action (e.g., "profile.get", "secrets.datastore.add")
     * @param payload The message payload
     * @return Request ID for correlating response
     */
    suspend fun sendToVault(
        messageType: String,
        payload: JsonObject = JsonObject()
    ): Result<String> {
        val ownerSpaceId = connectionManager.getOwnerSpaceId()
            ?: return Result.failure(NatsException("No OwnerSpace ID available"))

        val requestId = UUID.randomUUID().toString()
        // Subject includes the message type for routing
        val subject = "$ownerSpaceId.forVault.$messageType"

        // Encrypt payload if E2E session is established (skip for bootstrap)
        val effectivePayload = if (isE2EEnabled && messageType != "app.bootstrap") {
            android.util.Log.d(TAG, "sendToVault: encrypting payload for $messageType")
            android.util.Log.d(TAG, "sendToVault: original payload keys: ${payload.keySet()}")
            encryptPayload(payload)
        } else {
            android.util.Log.d(TAG, "sendToVault: NOT encrypting (E2E=${isE2EEnabled}, type=$messageType)")
            payload
        }

        val message = VaultMessage.create(messageType, effectivePayload).copy(
            id = requestId,
            payload = effectivePayload
        )

        val json = gson.toJson(message)
        return natsClient.publish(subject, json).map { requestId }
    }

    /**
     * Send a message to the vault and await the response via JetStream.
     *
     * Uses JetStreamRequestHelper to create an ephemeral consumer for reliable
     * response delivery, solving the issue where regular NATS subscriptions miss
     * responses after reconnection.
     *
     * @param messageType The message type/action (e.g., "profile.get", "feed.sync")
     * @param payload The message payload
     * @param timeoutMs Timeout in milliseconds (default 30 seconds)
     * @return The vault response, or null if timeout
     */
    suspend fun sendAndAwaitResponse(
        messageType: String,
        payload: JsonObject = JsonObject(),
        timeoutMs: Long = 30000L
    ): VaultResponse? {
        val ownerSpaceId = connectionManager.getOwnerSpaceId()
            ?: return VaultResponse.Error(
                requestId = "",
                code = "NO_OWNER_SPACE",
                message = "No OwnerSpace ID available"
            )

        // Wait briefly for reconnection if NATS is temporarily disconnected
        var androidClient = connectionManager.getAndroidClient()
        if (androidClient == null) {
            android.util.Log.w(TAG, "NATS not connected for $messageType, waiting for reconnect...")
            for (i in 1..10) {
                kotlinx.coroutines.delay(500L)
                androidClient = connectionManager.getAndroidClient()
                if (androidClient != null) {
                    android.util.Log.i(TAG, "NATS reconnected after ${i * 500}ms")
                    break
                }
            }
            if (androidClient == null) {
                return VaultResponse.Error(
                    requestId = "",
                    code = "NOT_CONNECTED",
                    message = "NATS not connected"
                )
            }
        }

        // Wait for the forApp.> Core subscription to be established before
        // publishing. The inbox dispatcher (handleVaultResponse) only runs
        // for messages that land on this sub; publishing before sub is
        // ready means the response gets discarded by the server (no
        // matching subscriber), the deferred sits for 30s, and the call
        // times out. Common path that hits this: notification tap →
        // ConversationViewModel.init fires multiple vault calls during
        // the post-PIN-unlock window where the sub is still being
        // established. Wait up to 5s for the sub to come up.
        if (appSubscription == null) {
            android.util.Log.w(TAG, "forApp.> sub not yet established for $messageType, waiting...")
            var waited = 0L
            while (appSubscription == null && waited < 5000L) {
                kotlinx.coroutines.delay(100L)
                waited += 100L
            }
            if (appSubscription == null) {
                android.util.Log.e(TAG, "forApp.> sub still not established after ${waited}ms — request would hang")
                return VaultResponse.Error(
                    requestId = "",
                    code = "NOT_SUBSCRIBED",
                    message = "Vault subscription not ready"
                )
            }
            android.util.Log.i(TAG, "forApp.> sub ready after ${waited}ms")
        }

        val requestId = UUID.randomUUID().toString()
        val requestSubject = "$ownerSpaceId.forVault.$messageType"

        // Register a deferred BEFORE publishing so a response that comes
        // back faster than the publish coroutine returns still finds its
        // slot. handleVaultResponse will complete it when the matching
        // .response message lands on the existing forApp.> subscription.
        val deferred = CompletableDeferred<NatsMessage>()
        inflightRequests[requestId] = deferred

        try {
            // Encrypt payload if E2E session is established (skip for bootstrap)
            val effectivePayload = if (isE2EEnabled && messageType != "app.bootstrap") {
                encryptPayload(payload)
            } else {
                payload
            }

            val message = VaultMessage.create(messageType, effectivePayload).copy(
                id = requestId,
                payload = effectivePayload
            )

            val json = gson.toJson(message)

            val pubResult = androidClient.publish(requestSubject, json.toByteArray(Charsets.UTF_8))
            if (pubResult.isFailure) {
                android.util.Log.w(TAG, "Publish failed for $messageType: ${pubResult.exceptionOrNull()?.message}")
                return VaultResponse.Error(
                    requestId = requestId,
                    code = "PUBLISH_FAILED",
                    message = pubResult.exceptionOrNull()?.message ?: "Publish failed"
                )
            }
            androidClient.flush()

            val responseMessage = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                android.util.Log.w(TAG, "Request $messageType timed out after ${timeoutMs}ms")
                return VaultResponse.Error(
                    requestId = requestId,
                    code = "TIMEOUT",
                    message = "Request timed out"
                )
            }

            return parseJetStreamResponse(requestId, responseMessage)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "sendAndAwaitResponse($messageType) exception", e)
            return VaultResponse.Error(
                requestId = requestId,
                code = "EXCEPTION",
                message = e.message ?: "Unknown error"
            )
        } finally {
            // Drop the slot whether we succeeded, timed out, or errored.
            // A late response after a timeout simply has no deferred to
            // complete and is dropped by handleVaultResponse.
            inflightRequests.remove(requestId)
        }
    }

    /**
     * Parse a JetStream response message into a VaultResponse.
     * Handles decryption, JSON parsing, UTK extraction, and response type mapping.
     */
    private fun parseJetStreamResponse(requestId: String, message: NatsMessage): VaultResponse {
        try {
            // Decrypt if E2E is enabled
            val data = decryptPayload(message.data)
            val responseString = String(data, Charsets.UTF_8)

            android.util.Log.d(TAG, "parseJetStreamResponse raw=${responseString.take(500)}")

            // Guard: skip garbled/non-JSON responses (NATS framing issue)
            val trimmed = responseString.trimStart()
            if (!trimmed.startsWith("{")) {
                android.util.Log.w(TAG, "Skipping non-JSON response (garbled NATS data): ${trimmed.take(80)}")
                return VaultResponse.Error(
                    requestId = requestId,
                    code = "GARBLED",
                    message = "Received garbled response — retrying may help"
                )
            }

            val response = gson.fromJson(responseString, VaultResponseJson::class.java)

            // Detect vault_locked error — vault has no DEK (e.g., after enclave refresh).
            // Emit event so the app can prompt PIN re-entry to re-derive the DEK.
            if (response.error?.startsWith("vault_locked") == true) {
                android.util.Log.w(TAG, "Vault locked detected — emitting vaultLocked event")
                _vaultLocked.tryEmit(Unit)
                return VaultResponse.Error(
                    requestId = response.getCorrelationId().ifEmpty { requestId },
                    code = "VAULT_LOCKED",
                    message = response.error ?: "Vault locked — PIN unlock required"
                )
            }

            // Extract and store any new UTKs
            extractAndStoreUtks(responseString)

            // Handle standard vault-manager response format
            if (response.success != null) {
                return if (response.success) {
                    val resultData = response.result ?: run {
                        try {
                            gson.fromJson(responseString, JsonObject::class.java)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    VaultResponse.HandlerResult(
                        requestId = response.getCorrelationId().ifEmpty { requestId },
                        handlerId = response.handlerId,
                        success = true,
                        result = resultData,
                        error = null
                    )
                } else {
                    VaultResponse.Error(
                        requestId = response.getCorrelationId().ifEmpty { requestId },
                        code = response.errorCode ?: "HANDLER_ERROR",
                        message = response.error ?: "Unknown error"
                    )
                }
            }

            // Handle typed responses
            return when (response.type) {
                "handlerResult" -> VaultResponse.HandlerResult(
                    requestId = response.getCorrelationId().ifEmpty { requestId },
                    handlerId = response.handlerId,
                    success = response.success ?: false,
                    result = response.result,
                    error = response.error
                )
                "status" -> VaultResponse.StatusResponse(
                    requestId = response.getCorrelationId().ifEmpty { requestId },
                    status = parseVaultStatus(response.result)
                )
                "eventTypes" -> VaultResponse.EventTypesResponse(
                    requestId = response.getCorrelationId().ifEmpty { requestId },
                    eventTypes = parseEventTypes(response.result)
                )
                "pong" -> VaultResponse.Pong(
                    requestId = response.getCorrelationId().ifEmpty { requestId },
                    timestamp = parseTimestamp(response.timestamp)
                )
                "error" -> VaultResponse.Error(
                    requestId = response.getCorrelationId().ifEmpty { requestId },
                    code = response.errorCode ?: "UNKNOWN",
                    message = response.error ?: "Unknown error"
                )
                else -> {
                    val resultData = response.result ?: run {
                        try {
                            gson.fromJson(responseString, JsonObject::class.java)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    VaultResponse.HandlerResult(
                        requestId = response.getCorrelationId().ifEmpty { requestId },
                        handlerId = null,
                        success = response.error == null,
                        result = resultData,
                        error = response.error
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse JetStream response", e)
            return VaultResponse.Error(
                requestId = requestId,
                code = "PARSE_ERROR",
                message = "Failed to parse response: ${e.message}"
            )
        }
    }

    /**
     * Execute a vault handler.
     *
     * @param handlerId The handler to execute
     * @param payload Handler parameters
     * @return Request ID
     */
    suspend fun executeHandler(
        handlerId: String,
        payload: JsonObject = JsonObject()
    ): Result<String> {
        val requestPayload = JsonObject().apply {
            addProperty("handlerId", handlerId)
            add("params", payload)
        }
        return sendToVault("execute", requestPayload)
    }

    /**
     * Get profile data from vault.
     *
     * Fetches all profile fields including system fields (_system_* prefix)
     * and user-editable fields.
     *
     * @return Request ID for correlating response
     */
    suspend fun getProfileFromVault(): Result<String> {
        return sendToVault("profile.get", JsonObject())
    }

    /**
     * Get personal data from the vault.
     * Personal data is private data stored in the vault (separate from public profile).
     * @return Request ID for correlating response
     */
    suspend fun getPersonalDataFromVault(): Result<String> {
        return sendToVault("personal-data.get", JsonObject())
    }

    /**
     * Publish the public profile to NATS.
     *
     * The vault will:
     * 1. Update the list of fields to include in public profile (if provided)
     * 2. Build the public profile JSON from system fields + selected fields
     * 3. Publish to {ownerSpace}.profile.public topic
     *
     * @param selectedFields List of field namespaces to include (e.g., "contact.phone.mobile")
     * @param publicSecrets Metadata (name/type/category — never value)
     *        for secrets the user has opted to publish. Pass an empty
     *        list to explicitly clear previously-published secrets;
     *        pass null to leave the vault's stored metadata untouched.
     * @return Request ID for correlating response
     */
    suspend fun publishProfile(
        selectedFields: List<String>? = null,
        publicSecrets: List<Map<String, String>>? = null,
    ): Result<String> {
        val payload = JsonObject()
        if (selectedFields != null) {
            val fieldsArray = com.google.gson.JsonArray()
            selectedFields.forEach { fieldsArray.add(it) }
            payload.add("fields", fieldsArray)
        }
        if (publicSecrets != null) {
            val secretsArray = com.google.gson.JsonArray()
            publicSecrets.forEach { entry ->
                val obj = JsonObject()
                entry.forEach { (k, v) -> obj.addProperty(k, v) }
                secretsArray.add(obj)
            }
            payload.add("public_secrets", secretsArray)
        }
        return sendToVault("profile.publish", payload)
    }

    /**
     * Update public profile settings without publishing.
     *
     * @param selectedFields List of field namespaces to include in public profile
     * @return Request ID for correlating response
     */
    suspend fun updatePublicProfileSettings(selectedFields: List<String>): Result<String> {
        val payload = JsonObject()
        val fieldsArray = com.google.gson.JsonArray()
        selectedFields.forEach { fieldsArray.add(it) }
        payload.add("fields", fieldsArray)
        return sendToVault("profile.public.update", payload)
    }

    /**
     * Get public profile settings (which fields are shared).
     *
     * @return Request ID for correlating response
     */
    suspend fun getPublicProfileSettings(): Result<String> {
        return sendToVault("profile.public.get", JsonObject())
    }

    /**
     * Update field sort order in the vault.
     * Persists the sort order of personal data fields for consistent display.
     *
     * @param sortOrder Map of field namespace to sort order (0-based index)
     * @return Result indicating success
     */
    suspend fun updateFieldSortOrder(sortOrder: Map<String, Int>): Result<Unit> {
        return try {
            val payload = JsonObject().apply {
                val sortOrderObj = JsonObject()
                sortOrder.forEach { (namespace, order) ->
                    sortOrderObj.addProperty(namespace, order)
                }
                add("sort_order", sortOrderObj)
            }

            Log.d(TAG, "Updating field sort order: ${sortOrder.size} fields")
            val response = sendAndAwaitResponse("personal-data.update-sort-order", payload, 10000L)

            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success) {
                        Log.i(TAG, "Field sort order updated successfully")
                        Result.success(Unit)
                    } else {
                        Log.e(TAG, "updateFieldSortOrder failed: ${response.error}")
                        Result.failure(Exception(response.error ?: "Update failed"))
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "updateFieldSortOrder error: ${response.code} - ${response.message}")
                    Result.failure(Exception(response.message))
                }
                null -> {
                    Log.w(TAG, "updateFieldSortOrder timed out")
                    Result.failure(Exception("Request timed out"))
                }
                else -> {
                    Result.failure(Exception("Unexpected response"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateFieldSortOrder exception", e)
            Result.failure(e)
        }
    }

    /**
     * Get all categories (predefined + custom).
     *
     * @return Request ID for correlating response
     */
    suspend fun getCategories(): Result<String> {
        return sendToVault("profile.categories.get", JsonObject())
    }

    /**
     * Update custom categories in the vault.
     * Custom categories are user-created categories for organizing personal data.
     *
     * @param categories List of custom categories to store
     * @return Request ID for correlating response
     */
    suspend fun updateCategories(categories: List<CustomCategoryDto>): Result<String> {
        val payload = JsonObject().apply {
            val categoriesArray = com.google.gson.JsonArray()
            categories.forEach { category ->
                val categoryObj = JsonObject().apply {
                    addProperty("id", category.id)
                    addProperty("name", category.name)
                    if (category.icon != null) {
                        addProperty("icon", category.icon)
                    }
                    addProperty("created_at", category.createdAt)
                }
                categoriesArray.add(categoryObj)
            }
            add("categories", categoriesArray)
        }
        return sendToVault("profile.categories.update", payload)
    }

    // MARK: - Profile Photo Operations

    /**
     * Get profile photo from vault.
     *
     * @return Result with Base64-encoded JPEG or null if no photo set
     */
    suspend fun getProfilePhoto(): Result<String?> {
        return try {
            val response = sendAndAwaitResponse("profile.photo.get", JsonObject(), 10000L)
            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        val photo = response.result.get("photo")?.takeIf { !it.isJsonNull }?.asString
                        Result.success(photo?.takeIf { it.isNotEmpty() })
                    } else {
                        Result.success(null)
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "getProfilePhoto error: ${response.code} - ${response.message}")
                    Result.failure(Exception(response.message))
                }
                null -> {
                    Log.w(TAG, "getProfilePhoto timed out")
                    Result.failure(Exception("Request timed out"))
                }
                else -> {
                    Result.success(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getProfilePhoto exception", e)
            Result.failure(e)
        }
    }

    /**
     * Update profile photo in vault.
     *
     * @param base64Photo Base64-encoded JPEG photo (max 200KB)
     * @return Result indicating success
     */
    suspend fun updateProfilePhoto(base64Photo: String): Result<Unit> {
        return try {
            val payload = JsonObject().apply {
                addProperty("photo", base64Photo)
            }
            val response = sendAndAwaitResponse("profile.photo.update", payload, 15000L)
            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success) {
                        Log.i(TAG, "Profile photo updated successfully")
                        Result.success(Unit)
                    } else {
                        Log.e(TAG, "updateProfilePhoto failed: ${response.error}")
                        Result.failure(Exception(response.error ?: "Update failed"))
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "updateProfilePhoto error: ${response.code} - ${response.message}")
                    Result.failure(Exception(response.message))
                }
                null -> {
                    Log.w(TAG, "updateProfilePhoto timed out")
                    Result.failure(Exception("Request timed out"))
                }
                else -> {
                    Result.failure(Exception("Unexpected response"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateProfilePhoto exception", e)
            Result.failure(e)
        }
    }

    /**
     * Delete profile photo from vault.
     *
     * @return Result indicating success
     */
    suspend fun deleteProfilePhoto(): Result<Unit> {
        return try {
            val response = sendAndAwaitResponse("profile.photo.delete", JsonObject(), 10000L)
            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success) {
                        Log.i(TAG, "Profile photo deleted successfully")
                        Result.success(Unit)
                    } else {
                        Log.e(TAG, "deleteProfilePhoto failed: ${response.error}")
                        Result.failure(Exception(response.error ?: "Delete failed"))
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "deleteProfilePhoto error: ${response.code} - ${response.message}")
                    Result.failure(Exception(response.message))
                }
                null -> {
                    Log.w(TAG, "deleteProfilePhoto timed out")
                    Result.failure(Exception("Request timed out"))
                }
                else -> {
                    Result.failure(Exception("Unexpected response"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteProfilePhoto exception", e)
            Result.failure(e)
        }
    }

    // MARK: - PIN Change

    /**
     * Change the vault unlock PIN.
     *
     * Encrypts old and new PIN to the enclave's public key, sends via pin.change
     * topic, and handles UTK lifecycle and new UTK storage on success.
     *
     * @param oldPin Current PIN (4-8 digits)
     * @param newPin New PIN (4-8 digits)
     * @param cryptoManager CryptoManager for public key encryption
     * @return Result with PINChangeResult
     */
    suspend fun changePIN(
        oldPin: String,
        newPin: String,
        cryptoManager: com.vettid.app.core.crypto.CryptoManager
    ): Result<PINChangeResult> {
        val oldPw = com.vettid.app.core.security.SecurePassword.fromString(oldPin)
        val newPw = com.vettid.app.core.security.SecurePassword.fromString(newPin)
        return try {
            changePIN(oldPw, newPw, cryptoManager)
        } finally {
            oldPw.wipe()
            newPw.wipe()
        }
    }

    /**
     * Wipeable variant — takes ownership of both PINs.
     *
     * The JSON payload sent to the enclave does briefly contain the
     * PINs as a JSON String (unavoidable; that's the wire format).
     * That String only lives for the microseconds it takes to
     * `toByteArray()` and encrypt; the long-lived parameter slots
     * that previously held the PIN through the suspending NATS
     * round-trip are now SecurePassword-backed and wiped by the
     * String overload above (or by the caller if they passed a
     * SecurePassword directly).
     */
    suspend fun changePIN(
        oldPin: com.vettid.app.core.security.SecurePassword,
        newPin: com.vettid.app.core.security.SecurePassword,
        cryptoManager: com.vettid.app.core.crypto.CryptoManager
    ): Result<PINChangeResult> {
        return try {
            // Get enclave public key
            val enclavePublicKey = credentialStore.getEnclavePublicKey()
                ?: return Result.failure(Exception("Enclave public key missing. Please re-enroll."))

            // Get an available UTK (single-use)
            val utkPool = credentialStore.getUtkPool()
            val availableUtk = utkPool.firstOrNull()
                ?: return Result.failure(Exception("No transaction keys available. Please unlock your vault first."))

            Log.d(TAG, "changePIN: Using UTK ${availableUtk.keyId}")

            // Build the JSON payload byte-by-byte from the
            // SecurePassword chars. The intermediate String at the
            // JsonObject boundary still exists briefly — but we
            // construct it inside this scope and immediately drop
            // the reference. The String is unreachable as soon as
            // `pinPayload.toString().toByteArray()` returns.
            val pinPayloadBytes = run {
                val oldChars = oldPin.copyChars()
                val newChars = newPin.copyChars()
                try {
                    val pinPayload = JsonObject().apply {
                        addProperty("old_pin", String(oldChars))
                        addProperty("new_pin", String(newChars))
                    }
                    pinPayload.toString().toByteArray(Charsets.UTF_8)
                } finally {
                    oldChars.wipe()
                    newChars.wipe()
                }
            }

            // Encrypt with X25519 + ChaCha20-Poly1305 to enclave's public key
            val encryptedResult = try {
                cryptoManager.encryptToPublicKey(
                    plaintext = pinPayloadBytes,
                    publicKeyBase64 = android.util.Base64.encodeToString(enclavePublicKey, android.util.Base64.NO_WRAP)
                )
            } finally {
                pinPayloadBytes.wipe()
            }

            // Combine ephemeral public key + nonce + ciphertext
            val ephemeralPubKeyBytes = android.util.Base64.decode(encryptedResult.ephemeralPublicKey, android.util.Base64.NO_WRAP)
            val nonceBytes = android.util.Base64.decode(encryptedResult.nonce, android.util.Base64.NO_WRAP)
            val ciphertextBytes = android.util.Base64.decode(encryptedResult.ciphertext, android.util.Base64.NO_WRAP)

            val combined = ephemeralPubKeyBytes + nonceBytes + ciphertextBytes
            val encryptedPayloadBase64 = android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)

            // Build request payload. Phase D: include the encrypted
            // credential blob so the vault decrypts in-flight rather
            // than reading vaultState.credential.
            val requestPayload = JsonObject().apply {
                addProperty("utk_id", availableUtk.keyId)
                addProperty("encrypted_payload", encryptedPayloadBase64)
                credentialStore.getEncryptedBlob()?.let {
                    addProperty("encrypted_credential", it)
                }
            }

            // Ensure we're subscribed to vault responses
            subscribeToVault()

            // Send via pin.change topic (dot notation matching enclave handler registration)
            val response = sendAndAwaitResponse(
                messageType = "pin.change",
                payload = requestPayload,
                timeoutMs = 30000L
            )

            // SECURITY: Remove used UTK immediately regardless of outcome - UTKs are single-use
            credentialStore.removeUtk(availableUtk.keyId)
            Log.d(TAG, "changePIN: Removed used UTK ${availableUtk.keyId}")

            if (response == null) {
                return Result.failure(Exception("Request timed out"))
            }

            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        val status = response.result.get("status")?.asString
                        if (status == "pin_changed") {
                            // Store new UTKs from response
                            val newUtksArray = response.result.getAsJsonArray("new_utks")
                            if (newUtksArray != null && newUtksArray.size() > 0) {
                                val newKeys = mutableListOf<TransactionKeyInfo>()
                                for (i in 0 until newUtksArray.size()) {
                                    val utkString = newUtksArray.get(i).asString
                                    val parts = utkString.split(":", limit = 2)
                                    if (parts.size == 2) {
                                        newKeys.add(TransactionKeyInfo(
                                            keyId = parts[0],
                                            publicKey = parts[1],
                                            algorithm = "X25519"
                                        ))
                                    }
                                }
                                if (newKeys.isNotEmpty()) {
                                    credentialStore.addUtks(newKeys)
                                    Log.i(TAG, "changePIN: Added ${newKeys.size} new UTKs. Total: ${credentialStore.getUtkCount()}")
                                }
                            }
                            // Persist the re-encrypted credential blob the
                            // vault just minted with the new AuthHash/Salt
                            // so subsequent password-verify ops compare
                            // against the up-to-date hash.
                            response.result.get("encrypted_credential")?.asString?.takeIf { it.isNotBlank() }
                                ?.let { credentialStore.setEncryptedBlob(it) }
                            Result.success(PINChangeResult(success = true))
                        } else {
                            val error = response.result.get("error")?.asString ?: "PIN change failed"
                            Result.success(PINChangeResult(success = false, error = error))
                        }
                    } else {
                        val error = response.error ?: "PIN change failed"
                        // Check for known error messages from enclave
                        Result.success(PINChangeResult(success = false, error = error))
                    }
                }
                is VaultResponse.Error -> {
                    Result.success(PINChangeResult(success = false, error = response.message))
                }
                else -> {
                    Result.success(PINChangeResult(success = false, error = "Unexpected response"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "changePIN error", e)
            Result.failure(e)
        }
    }

    /**
     * Phase E: re-populate the in-memory identity-key carve-out after
     * the sliding TTL has lapsed (or to extend the window proactively).
     *
     * Flow:
     * 1. Hash password with Argon2id PHC, encrypt to UTK
     * 2. Send credential blob + encrypted password material via
     *    credential.identity-unlock
     * 3. Enclave decrypts blob, verifies password, copies identity
     *    keypair into vaultState carve-outs with a fresh TTL window
     *
     * @return IdentityUnlockResult with the unix timestamp when the
     *         window will lapse if not extended by other signed ops
     */
    suspend fun unlockIdentity(
        password: String,
        cryptoManager: com.vettid.app.core.crypto.CryptoManager
    ): Result<IdentityUnlockResult> {
        // Wrap the String into a SecurePassword and delegate. Callers
        // that already hold a SecurePassword should call the overload
        // below directly so the String constructor never runs.
        val pw = com.vettid.app.core.security.SecurePassword.fromString(password)
        return try {
            unlockIdentity(pw, cryptoManager)
        } finally {
            pw.wipe()
        }
    }

    /**
     * Wipeable variant — takes ownership of [password] and wipes it
     * before returning. Caller MUST NOT use [password] after this
     * call.
     */
    suspend fun unlockIdentity(
        password: com.vettid.app.core.security.SecurePassword,
        cryptoManager: com.vettid.app.core.crypto.CryptoManager
    ): Result<IdentityUnlockResult> {
        // SECURITY (android-auth-H2): drain UTKs aren't free — rate-
        // limit consecutive failures so a stolen-but-locked phone
        // can't burn the whole pool guessing.
        if (!unlockRateLimiter.beforeAttempt()) {
            return Result.failure(Exception("Too many failed attempts; restart the app to try again"))
        }
        return try {
            val salt = credentialStore.getPasswordSaltBytes()
                ?: return Result.failure(Exception("Password salt not found"))
            val utkPool = credentialStore.getUtkPool()
            val utk = utkPool.firstOrNull()
                ?: return Result.failure(Exception("No transaction keys available"))
            val encryptedBlob = credentialStore.getEncryptedBlob()
                ?: return Result.failure(Exception("No credential blob available"))

            val enc = cryptoManager.encryptPasswordForServer(password, salt, utk.publicKey)

            val payload = JsonObject().apply {
                addProperty("encrypted_credential", encryptedBlob)
                addProperty("encrypted_password_hash", enc.encryptedPasswordHash)
                addProperty("ephemeral_public_key", enc.ephemeralPublicKey)
                addProperty("nonce", enc.nonce)
                addProperty("key_id", utk.keyId)
            }

            subscribeToVault()
            val response = sendAndAwaitResponse(
                messageType = "credential.identity-unlock",
                payload = payload,
                timeoutMs = 15000L
            )

            // UTKs are single-use regardless of outcome.
            credentialStore.removeUtk(utk.keyId)

            val outcome: Result<IdentityUnlockResult> = when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        val expiresAt = response.result.get("expires_at")?.asLong ?: 0L
                        val ttl = response.result.get("ttl_seconds")?.asLong ?: 0L
                        Result.success(IdentityUnlockResult(expiresAt, ttl))
                    } else {
                        Result.failure(Exception(response.error ?: "identity unlock failed"))
                    }
                }
                is VaultResponse.Error -> Result.failure(Exception(response.message))
                null -> Result.failure(Exception("Request timed out"))
                else -> Result.failure(Exception("Unexpected response from vault"))
            }
            if (outcome.isSuccess) {
                unlockRateLimiter.recordSuccess()
            } else {
                unlockRateLimiter.recordFailure()
            }
            outcome
        } catch (e: Exception) {
            Log.e(TAG, "unlockIdentity failed", e)
            unlockRateLimiter.recordFailure()
            Result.failure(e)
        }
    }

    /**
     * Change the credential password stored in the Protean Credential.
     *
     * Flow:
     * 1. Hash old and new passwords with Argon2id to PHC format
     * 2. Encrypt both hashes with UTK for transport security
     * 3. Send to enclave via credential.password-change
     * 4. Enclave verifies old hash, updates credential, returns re-encrypted credential
     * 5. Store new UTKs from response
     *
     * @param oldPassword The current credential password (plaintext)
     * @param newPassword The new credential password (plaintext)
     * @param cryptoManager For Argon2id hashing and UTK encryption
     */
    suspend fun changePassword(
        oldPassword: String,
        newPassword: String,
        cryptoManager: com.vettid.app.core.crypto.CryptoManager
    ): Result<PasswordChangeResult> {
        // Wrap + delegate. Strings are dropped after fromString().
        val oldPw = com.vettid.app.core.security.SecurePassword.fromString(oldPassword)
        val newPw = com.vettid.app.core.security.SecurePassword.fromString(newPassword)
        return try {
            changePassword(oldPw, newPw, cryptoManager)
        } finally {
            oldPw.wipe()
            newPw.wipe()
        }
    }

    /**
     * Wipeable variant — takes ownership of both passwords and wipes
     * them before returning.
     */
    suspend fun changePassword(
        oldPassword: com.vettid.app.core.security.SecurePassword,
        newPassword: com.vettid.app.core.security.SecurePassword,
        cryptoManager: com.vettid.app.core.crypto.CryptoManager
    ): Result<PasswordChangeResult> {
        return try {
            // Get an available UTK (single-use)
            val utkPool = credentialStore.getUtkPool()
            val availableUtk = utkPool.firstOrNull()
                ?: return Result.failure(Exception("No transaction keys available. Please unlock your vault first."))

            Log.d(TAG, "changePassword: Using UTK ${availableUtk.keyId}")

            // Hash both passwords with Argon2id to PHC format
            val oldSaltBytes = credentialStore.getPasswordSaltBytes()
                ?: return Result.failure(Exception("Password salt not found. Please re-enroll."))
            val oldPasswordHash = cryptoManager.hashPasswordPHC(oldPassword, oldSaltBytes)

            val newSaltBytes = cryptoManager.generateSalt()
            val newPasswordHash = cryptoManager.hashPasswordPHC(newPassword, newSaltBytes)

            // Build payload matching enclave PasswordChangePayload struct
            val passwordPayload = JsonObject().apply {
                addProperty("old_password_hash", oldPasswordHash)
                addProperty("new_password_hash", newPasswordHash)
            }

            // Encrypt with X25519 + XChaCha20-Poly1305 using UTK (matches enrollment)
            val encryptedBlob = cryptoManager.encryptToUTK(
                plaintext = passwordPayload.toString().toByteArray(Charsets.UTF_8),
                utkPublicKeyBase64 = availableUtk.publicKey
            )
            val encryptedPayloadBase64 = android.util.Base64.encodeToString(encryptedBlob, android.util.Base64.NO_WRAP)

            // Build request payload
            val requestPayload = JsonObject().apply {
                addProperty("utk_id", availableUtk.keyId)
                addProperty("encrypted_payload", encryptedPayloadBase64)
                // Phase D: vault decrypts the credential per-op rather
                // than holding it in memory; supply the blob here.
                credentialStore.getEncryptedBlob()?.let {
                    addProperty("encrypted_credential", it)
                }
            }

            // Ensure we're subscribed to vault responses
            subscribeToVault()

            // Send via credential.password-change topic
            val response = sendAndAwaitResponse(
                messageType = "credential.password-change",
                payload = requestPayload,
                timeoutMs = 30000L
            )

            // SECURITY: Remove used UTK immediately regardless of outcome - UTKs are single-use
            credentialStore.removeUtk(availableUtk.keyId)
            Log.d(TAG, "changePassword: Removed used UTK ${availableUtk.keyId}")

            if (response == null) {
                return Result.failure(Exception("Request timed out"))
            }

            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        val status = response.result.get("status")?.asString
                        if (status == "password_changed") {
                            // Store new UTKs from response
                            val newUtksArray = response.result.getAsJsonArray("new_utks")
                            if (newUtksArray != null && newUtksArray.size() > 0) {
                                val newKeys = mutableListOf<TransactionKeyInfo>()
                                for (i in 0 until newUtksArray.size()) {
                                    val utkObj = newUtksArray.get(i).asJsonObject
                                    val id = utkObj.get("id")?.asString ?: continue
                                    val publicKey = utkObj.get("public_key")?.asString ?: continue
                                    newKeys.add(TransactionKeyInfo(
                                        keyId = id,
                                        publicKey = publicKey,
                                        algorithm = "X25519"
                                    ))
                                }
                                if (newKeys.isNotEmpty()) {
                                    credentialStore.addUtks(newKeys)
                                    Log.i(TAG, "changePassword: Added ${newKeys.size} new UTKs. Total: ${credentialStore.getUtkCount()}")
                                }
                            }

                            // Persist the re-encrypted credential blob the
                            // vault just minted. Without this we keep the
                            // pre-change blob locally — and because we ALSO
                            // store the new salt below, every subsequent
                            // password verify hashes the typed password
                            // with the new salt, compares against the
                            // old-salt PHC inside the stale blob, and
                            // fails with "incorrect password".
                            response.result.get("encrypted_credential")?.asString?.takeIf { it.isNotBlank() }
                                ?.let { credentialStore.setEncryptedBlob(it) }

                            // Update stored password salt for future operations
                            credentialStore.setPasswordSalt(
                                android.util.Base64.encodeToString(newSaltBytes, android.util.Base64.NO_WRAP)
                            )

                            Result.success(PasswordChangeResult(success = true))
                        } else {
                            val error = response.result.get("error")?.asString ?: "Password change failed"
                            Result.success(PasswordChangeResult(success = false, error = error))
                        }
                    } else {
                        val error = response.error ?: "Password change failed"
                        Result.success(PasswordChangeResult(success = false, error = error))
                    }
                }
                is VaultResponse.Error -> {
                    Result.success(PasswordChangeResult(success = false, error = response.message))
                }
                else -> {
                    Result.success(PasswordChangeResult(success = false, error = "Unexpected response"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "changePassword error", e)
            Result.failure(e)
        }
    }

    /**
     * Send a message to another user's vault.
     *
     * Used for vault-routed signaling where messages go to the TARGET user's vault
     * instead of our own vault. The target vault will verify, log, and relay the message.
     *
     * @param targetUserGuid The target user's GUID
     * @param messageType The message type/action (e.g., "call.initiate")
     * @param payload The message payload
     * @return Request ID for correlating response
     */
    suspend fun sendToTargetVault(
        targetUserGuid: String,
        messageType: String,
        payload: JsonObject = JsonObject()
    ): Result<String> {
        val requestId = UUID.randomUUID().toString()
        val subject = "OwnerSpace.$targetUserGuid.forVault.$messageType"

        // Add our user GUID as caller_id for identification
        val enrichedPayload = payload.deepCopy().apply {
            val ownGuid = credentialStore.getUserGuid()
            if (ownGuid != null && !has("caller_id")) {
                addProperty("caller_id", ownGuid)
            }
        }

        val message = VaultMessage.create(messageType, enrichedPayload).copy(
            id = requestId,
            payload = enrichedPayload
        )

        val json = gson.toJson(message)
        android.util.Log.d(TAG, "Sending to target vault: $subject")
        return natsClient.publish(subject, json).map { requestId }
    }

    /**
     * Request vault status.
     *
     * @return Request ID
     */
    suspend fun requestVaultStatus(): Result<String> {
        return sendToVault("status")
    }

    /**
     * Request event types/handlers from vault.
     *
     * @return Request ID
     */
    suspend fun requestEventTypes(): Result<String> {
        return sendToVault("getEventTypes")
    }

    /**
     * Send a ping to vault to check connectivity.
     *
     * @return Request ID
     */
    suspend fun ping(): Result<String> {
        return sendToVault("ping")
    }

    /**
     * List available vault handler categories and their operations.
     * Queries the enclave's handlers.list endpoint for a dynamic list
     * that matches the running enclave version.
     *
     * @return List of handler categories, or empty list on error/timeout
     */
    suspend fun listHandlers(): List<VaultHandler> {
        return try {
            val response = sendAndAwaitResponse("handlers.list", JsonObject(), 10000L)
            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        val handlersArray = response.result.getAsJsonArray("handlers")
                        handlersArray?.mapNotNull { element ->
                            try {
                                val obj = element.asJsonObject
                                VaultHandler(
                                    id = obj.get("id").asString,
                                    name = obj.get("name").asString,
                                    description = obj.get("description")?.asString ?: "",
                                    operations = obj.getAsJsonArray("operations")
                                        ?.map { it.asString } ?: emptyList(),
                                    category = obj.get("category")?.asString ?: "",
                                    required = obj.get("required")?.asBoolean ?: false,
                                    shareable = obj.get("shareable")?.asBoolean ?: false,
                                    enabled = obj.get("enabled")?.asBoolean ?: true,
                                    shareGlobally = obj.get("share_globally")?.asBoolean ?: false,
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse handler entry", e)
                                null
                            }
                        } ?: emptyList()
                    } else {
                        Log.w(TAG, "listHandlers: unsuccessful response: ${response.error}")
                        emptyList()
                    }
                }
                else -> {
                    Log.w(TAG, "listHandlers: unexpected response type: $response")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listHandlers failed", e)
            emptyList()
        }
    }

    // MARK: - Handler authorization (enable/share toggles + per-connection grants)

    /**
     * Toggle whether a non-required handler runs at all. Disabling a
     * handler also clears its share_globally flag in the vault. Required
     * handlers cannot be disabled — the vault returns handler_required.
     */
    suspend fun setHandlerEnabled(handlerId: String, enabled: Boolean): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("handler_id", handlerId)
            addProperty("enabled", enabled)
        }
        return when (val response = sendAndAwaitResponse("handlers.set-enabled", payload, 8000L)) {
            is VaultResponse.HandlerResult ->
                if (response.success) Result.success(Unit)
                else Result.failure(Exception(response.error ?: "Unknown vault error"))
            is VaultResponse.Error ->
                Result.failure(Exception("${response.code}: ${response.message}"))
            else ->
                Result.failure(Exception("Unexpected vault response"))
        }
    }

    /**
     * Toggle whether a shareable handler appears in the user's public
     * profile. Only meaningful for handlers where shareable=true. The
     * vault rejects with handler_disabled if the handler is currently
     * off; enable it first.
     */
    suspend fun setHandlerShareGlobally(handlerId: String, share: Boolean): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("handler_id", handlerId)
            addProperty("share_globally", share)
        }
        return when (val response = sendAndAwaitResponse("handlers.set-share-global", payload, 8000L)) {
            is VaultResponse.HandlerResult ->
                if (response.success) Result.success(Unit)
                else Result.failure(Exception(response.error ?: "Unknown vault error"))
            is VaultResponse.Error ->
                Result.failure(Exception("${response.code}: ${response.message}"))
            else ->
                Result.failure(Exception("Unexpected vault response"))
        }
    }

    /**
     * Read the per-connection share grant. Returns the map of
     * handler_id -> granted. Missing entries mean "not granted". Vault
     * lazy-seeds the blob from the user's globally-shared set on first
     * access, so a freshly-activated connection sees every handler the
     * owner publishes globally until the user narrows.
     */
    suspend fun getConnectionShareHandlers(connectionId: String): Result<Map<String, Boolean>> {
        val payload = JsonObject().apply { addProperty("connection_id", connectionId) }
        return when (val response = sendAndAwaitResponse("connection.share-handlers.get", payload, 8000L)) {
            is VaultResponse.HandlerResult -> {
                if (!response.success) {
                    Result.failure(Exception(response.error ?: "Unknown vault error"))
                } else {
                    val granted = response.result?.getAsJsonObject("granted")
                    val out = mutableMapOf<String, Boolean>()
                    granted?.entrySet()?.forEach { (k, v) ->
                        if (v.isJsonPrimitive) out[k] = v.asBoolean
                    }
                    Result.success(out)
                }
            }
            is VaultResponse.Error ->
                Result.failure(Exception("${response.code}: ${response.message}"))
            else ->
                Result.failure(Exception("Unexpected vault response"))
        }
    }

    /**
     * Replace the per-connection share grant. Each granted=true entry
     * must reference a handler currently in the user's globally-shared
     * set; the vault rejects otherwise.
     */
    suspend fun setConnectionShareHandlers(
        connectionId: String,
        granted: Map<String, Boolean>,
    ): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            val grants = JsonObject()
            granted.forEach { (k, v) -> grants.addProperty(k, v) }
            add("granted", grants)
        }
        return when (val response = sendAndAwaitResponse("connection.share-handlers.set", payload, 8000L)) {
            is VaultResponse.HandlerResult ->
                if (response.success) Result.success(Unit)
                else Result.failure(Exception(response.error ?: "Unknown vault error"))
            is VaultResponse.Error ->
                Result.failure(Exception("${response.code}: ${response.message}"))
            else ->
                Result.failure(Exception("Unexpected vault response"))
        }
    }

    // MARK: - Presence (opt-in online signal)

    /**
     * Read the user-wide presence share default. Returns false (the
     * opt-in default) on any error — presence is a privacy signal,
     * always fail closed.
     */
    suspend fun getPresenceShareDefault(): Boolean {
        return try {
            val response = sendAndAwaitResponse("presence.get", JsonObject(), 8000L)
            (response as? VaultResponse.HandlerResult)
                ?.result
                ?.get("share_default")
                ?.asBoolean
                ?: false
        } catch (e: Exception) {
            Log.w(TAG, "presence.get failed: ${e.message}")
            false
        }
    }

    /**
     * Tell the vault whether the app is currently active. The vault's
     * heartbeat broadcast loop is gated on this — without a recent
     * `active=true` signal, peers stop seeing us online. Call on app
     * resume (active=true) and graceful pause (active=false), plus a
     * periodic refresh while foregrounded.
     */
    suspend fun setAppActive(active: Boolean) {
        val payload = JsonObject().apply { addProperty("active", active) }
        // Fire-and-forget — no need to block UI on the response.
        sendToVault("presence.app-active", payload)
    }

    /** Update the user-wide presence share default. */
    suspend fun setPresenceShareDefault(enabled: Boolean): Result<Unit> {
        val payload = JsonObject().apply { addProperty("share_default", enabled) }
        return when (val response = sendAndAwaitResponse("presence.set-default", payload, 8000L)) {
            is VaultResponse.HandlerResult ->
                if (response.success) Result.success(Unit)
                else Result.failure(Exception(response.error ?: "Unknown vault error"))
            is VaultResponse.Error ->
                Result.failure(Exception("${response.code}: ${response.message}"))
            else ->
                Result.failure(Exception("Unexpected vault response"))
        }
    }

    /**
     * Update the per-connection presence override. Passing null
     * clears the override so the connection follows the user-wide
     * default.
     */
    suspend fun setPresenceOverride(connectionId: String, override: Boolean?): Result<Unit> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            if (override == null) add("override", com.google.gson.JsonNull.INSTANCE)
            else addProperty("override", override)
        }
        return when (val response = sendAndAwaitResponse("presence.set-override", payload, 8000L)) {
            is VaultResponse.HandlerResult ->
                if (response.success) Result.success(Unit)
                else Result.failure(Exception(response.error ?: "Unknown vault error"))
            is VaultResponse.Error ->
                Result.failure(Exception("${response.code}: ${response.message}"))
            else ->
                Result.failure(Exception("Unexpected vault response"))
        }
    }

    // MARK: - Credential Operations (KMS-Sealed Protean Credential)

    /**
     * Create a new Protean Credential inside the Nitro Enclave.
     *
     * The credential is sealed using KMS envelope encryption bound to PCR0,
     * meaning only the exact enclave code can unseal it.
     *
     * @param encryptedPin Base64-encoded encrypted PIN/password
     * @param authType Authentication type ("pin" or "password")
     * @return Request ID for correlating the response
     */
    suspend fun createCredential(
        encryptedPin: String,
        authType: String = "pin"
    ): Result<String> {
        val payload = JsonObject().apply {
            add("credential_request", JsonObject().apply {
                addProperty("encrypted_pin", encryptedPin)
                addProperty("auth_type", authType)
            })
        }
        return sendToVault("credential.create", payload)
    }

    /**
     * Unseal a previously created Protean Credential.
     *
     * Requires the sealed credential blob and correct PIN/password.
     * KMS validates PCR0 before decrypting the DEK.
     *
     * @param sealedCredential Base64-encoded sealed credential blob
     * @param challengeId Challenge identifier
     * @param challengeResponse PIN/password response
     * @return Request ID for correlating the response
     */
    suspend fun unsealCredential(
        sealedCredential: String,
        challengeId: String,
        challengeResponse: String
    ): Result<String> {
        val payload = JsonObject().apply {
            addProperty("sealed_credential", sealedCredential)
            add("challenge", JsonObject().apply {
                addProperty("challenge_id", challengeId)
                addProperty("response", challengeResponse)
            })
        }
        return sendToVault("credential.unseal", payload)
    }

    // MARK: - Block List Management

    /**
     * Block a user from contacting you.
     *
     * @param targetGuid The user GUID to block
     * @param reason Optional reason for blocking
     * @param durationSecs Duration in seconds (0 = permanent)
     * @return Request ID
     */
    suspend fun blockUser(
        targetGuid: String,
        reason: String? = null,
        durationSecs: Long = 0
    ): Result<String> {
        val payload = JsonObject().apply {
            addProperty("target_id", targetGuid)
            reason?.let { addProperty("reason", it) }
            addProperty("duration_secs", durationSecs)
        }
        return sendToVault("block.add", payload)
    }

    /**
     * Unblock a user.
     *
     * @param targetGuid The user GUID to unblock
     * @return Request ID
     */
    suspend fun unblockUser(targetGuid: String): Result<String> {
        val payload = JsonObject().apply {
            addProperty("target_id", targetGuid)
        }
        return sendToVault("block.remove", payload)
    }

    /**
     * Get list of blocked users.
     *
     * @return Request ID
     */
    suspend fun getBlockedUsers(): Result<String> {
        return sendToVault("block.list")
    }

    // MARK: - E2E Session Management

    /**
     * Bootstrap the vault connection with E2E session key exchange.
     *
     * This establishes an encrypted session for app-vault communication:
     * 1. Generate X25519 keypair
     * 2. Send public key to vault via app.bootstrap
     * 3. Receive vault's public key in response
     * 4. Derive shared session key via ECDH + HKDF
     *
     * @param deviceId Device identifier
     * @param appVersion App version string
     * @return Result with session info on success
     */
    suspend fun bootstrap(
        deviceId: String,
        appVersion: String = "1.0.0"
    ): Result<BootstrapResponse> {
        // Generate session keypair
        val keyPair = SessionCrypto.generateKeyPair()
        pendingKeyPair = keyPair

        // Build bootstrap request
        val payload = JsonObject().apply {
            addProperty("device_id", deviceId)
            addProperty("device_type", "android")
            addProperty("app_version", appVersion)
            addProperty("app_session_public_key", keyPair.publicKeyBase64())
        }

        android.util.Log.i(TAG, "Sending bootstrap with session public key")

        // Send bootstrap request
        val requestIdResult = sendToVault("app.bootstrap", payload)
        if (requestIdResult.isFailure) {
            pendingKeyPair?.clear()
            pendingKeyPair = null
            return Result.failure(requestIdResult.exceptionOrNull() ?: NatsException("Bootstrap failed"))
        }

        return Result.success(BootstrapResponse(
            requestId = requestIdResult.getOrThrow(),
            pendingKeyPair = keyPair
        ))
    }

    /**
     * Complete session establishment from bootstrap response.
     * Call this when you receive the bootstrap response with session_info.
     *
     * @param sessionInfo Session info from vault response
     * @return SessionCrypto instance for encrypting messages
     */
    fun establishSession(sessionInfo: SessionInfo): Result<SessionCrypto> {
        val keyPair = pendingKeyPair
            ?: return Result.failure(NatsException("No pending key exchange"))

        return try {
            val vaultPublicKey = Base64.decode(sessionInfo.vaultSessionPublicKey, Base64.NO_WRAP)
            val expiresAt = sessionInfo.expiresAtMillis()

            val session = SessionCrypto.fromKeyExchange(
                sessionId = sessionInfo.sessionId,
                appPrivateKey = keyPair.privateKey,
                appPublicKey = keyPair.publicKey,
                vaultPublicKey = vaultPublicKey,
                expiresAt = expiresAt
            )

            // Store session
            credentialStore.storeSession(
                sessionId = session.sessionId,
                sessionKey = session.getSessionKeyForStorage(),
                publicKey = session.publicKey,
                expiresAt = expiresAt
            )

            sessionCrypto = session
            pendingKeyPair?.clear()
            pendingKeyPair = null

            android.util.Log.i(TAG, "E2E session established: ${session.sessionId}")
            Result.success(session)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to establish session", e)
            pendingKeyPair?.clear()
            pendingKeyPair = null
            Result.failure(e)
        }
    }

    /**
     * Restore session from storage if available.
     *
     * @return true if a valid session was restored
     */
    fun restoreSession(): Boolean {
        if (!credentialStore.hasValidSession()) {
            return false
        }

        val sessionId = credentialStore.getSessionId() ?: return false
        val sessionKey = credentialStore.getSessionKey() ?: return false
        val publicKey = credentialStore.getSessionPublicKey() ?: return false
        val expiresAt = credentialStore.getSessionExpiresAt()

        sessionCrypto = SessionCrypto.fromStored(
            sessionId = sessionId,
            sessionKey = sessionKey,
            publicKey = publicKey,
            expiresAt = expiresAt
        )

        android.util.Log.i(TAG, "E2E session restored: $sessionId")
        return true
    }

    /**
     * Check if E2E encryption is enabled.
     */
    val isE2EEnabled: Boolean
        get() = sessionCrypto?.isValid == true

    /**
     * Get current session ID if E2E is enabled.
     */
    val currentSessionId: String?
        get() = sessionCrypto?.sessionId

    /**
     * Rotate the session key.
     * Call this periodically or after a certain message count.
     *
     * @param reason Reason for rotation (scheduled, message_count, explicit)
     * @return Request ID
     */
    suspend fun rotateSession(reason: String = "scheduled"): Result<String> {
        val keyPair = SessionCrypto.generateKeyPair()
        pendingKeyPair = keyPair

        val payload = JsonObject().apply {
            addProperty("device_id", credentialStore.getUserGuid() ?: "")
            addProperty("new_app_public_key", keyPair.publicKeyBase64())
            addProperty("reason", reason)
        }

        return sendToVault("session.rotate", payload)
    }

    /**
     * Clear the current session.
     */
    fun clearSession() {
        sessionCrypto?.clear()
        sessionCrypto = null
        pendingKeyPair?.clear()
        pendingKeyPair = null
        credentialStore.clearSession()
    }

    /**
     * Set the session directly (used during bootstrap credential rotation).
     * This enables E2E encryption for immediate credential refresh requests.
     */
    fun setSession(session: SessionCrypto) {
        sessionCrypto = session
        Log.d(TAG, "Session set directly: ${session.sessionId}")
    }

    /**
     * Encrypt a message payload if E2E is enabled.
     *
     * @param payload The message payload
     * @return Encrypted payload as JsonObject or original if E2E not enabled
     */
    fun encryptPayload(payload: JsonObject): JsonObject {
        val session = sessionCrypto
        if (session == null || !session.isValid) {
            return payload
        }

        val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
        val encrypted = session.encrypt(plaintext)

        // Convert to Gson JsonObject
        return JsonObject().apply {
            addProperty("session_id", encrypted.sessionId)
            addProperty("ciphertext", Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP))
            addProperty("nonce", Base64.encodeToString(encrypted.nonce, Base64.NO_WRAP))
        }
    }

    /**
     * Decrypt a message if it's encrypted.
     *
     * @param data Raw message data
     * @return Decrypted data or original if not encrypted
     */
    fun decryptPayload(data: ByteArray): ByteArray {
        val session = sessionCrypto ?: return data

        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            if (json.has("session_id") && json.has("ciphertext") && json.has("nonce")) {
                val encrypted = EncryptedSessionMessage.fromJson(json)
                session.decrypt(encrypted)
            } else {
                data
            }
        } catch (e: Exception) {
            // Not encrypted or decryption failed - return original
            data
        }
    }

    /**
     * Handle messages on the forApp.> subscription.
     *
     * This subscription is now used for push notifications only (real-time events
     * like calls, messages, feed notifications, etc.). Request-response patterns
     * are handled by JetStream via sendAndAwaitResponse().
     *
     * Any .response messages that arrive here are logged and discarded — they are
     * handled by JetStreamRequestHelper's ephemeral consumers instead.
     */
    /**
     * Route incoming NATS messages to the appropriate handler.
     *
     * The vault publishes ALL messages through JetStream, which may append
     * `.response` to subjects. Push notifications and request-responses
     * both arrive on the same `forApp.>` subscription.
     *
     * Routing order:
     * 1. Push notifications — matched by `contains()` (works with or without .response suffix)
     * 2. Request-responses — silently skipped (JetStream consumers handle these)
     * 3. Unknown — logged for debugging
     */
    private fun handleVaultResponse(message: NatsMessage) {
        try {
            val subject = message.subject

            // --- INBOX DISPATCH ---
            // Request-response messages have the requestId as the
            // second-to-last subject token, e.g.:
            //   OwnerSpace.{guid}.forApp.{messageType}.{requestId}.response
            // If sendAndAwaitResponse is currently awaiting that requestId,
            // hand the message to its deferred and stop. Avoids the cost
            // of going through every push-notification branch for what is
            // really a private reply.
            if (subject.endsWith(".response")) {
                val parts = subject.split('.')
                if (parts.size >= 2) {
                    val requestId = parts[parts.size - 2]
                    val deferred = inflightRequests.remove(requestId)
                    if (deferred != null) {
                        deferred.complete(message)
                        return
                    }
                }
            }

            // --- PUSH NOTIFICATIONS ---
            // Use contains() to match regardless of .response suffix from JetStream
            when {
                // Messaging
                subject.contains(".forApp.new-message") -> {
                    handleNewMessage(message); return
                }
                // Read receipt PUSH from peer (not our own request-response which is .message.read-receipt)
                subject.contains(".forApp.read-receipt") && !subject.contains(".forApp.message.read-receipt") -> {
                    handleReadReceipt(message); return
                }

                // Connection lifecycle. The parallel-review flow uses
                // `peer-reviewing` as the trigger event (replaces the
                // older `peer-accepted` semantic) so the inviter sees
                // the scanner's profile the moment the scanner pulls
                // theirs. We keep handling `peer-accepted` for backward
                // compatibility during the rollout.
                subject.contains(".forApp.connection.peer-reviewing") -> {
                    handleConnectionPeerAccepted(message); return
                }
                subject.contains(".forApp.connection.peer-accepted") -> {
                    handleConnectionPeerAccepted(message); return
                }
                subject.contains(".forApp.connection.activated") ||
                subject.contains(".forApp.connection.key-exchanged") ||
                subject.contains(".forApp.connection.rejected") ||
                subject.contains(".forApp.connection.expired") -> {
                    handleConnectionStatusUpdate(message); return
                }
                subject.contains(".forApp.connection-revoked") -> {
                    handleConnectionRevoked(message); return
                }

                // Presence heartbeat re-emitted by our vault — peer is online.
                subject.contains(".forApp.presence.heartbeat") -> {
                    handlePresenceHeartbeat(message); return
                }

                // Device pairing — desktop has requested session authorization
                subject.contains(".forApp.device.pending-authorization") -> {
                    handleDevicePendingAuth(message); return
                }
                // Device pairing — a session was revoked (so UI lists refresh)
                subject.contains(".forApp.device.") && subject.endsWith(".revoked") -> {
                    handleDeviceRevoked(message); return
                }

                // Profile
                subject.contains(".forApp.profile-update") -> {
                    handleProfileUpdate(message); return
                }
                // Own published-profile snapshot republished by the
                // vault (multi-device fan-out + after every catalog
                // mutation). Caches re-hydrate from this tick.
                subject.contains(".forApp.profile.public") -> {
                    _ownProfileSnapshotTick.tryEmit(System.currentTimeMillis())
                    return
                }

                // Credentials
                subject.contains(".forApp.credentials.rotate") -> {
                    handleCredentialRotation(message); return
                }

                // Calls
                subject.contains(".forApp.call.") -> {
                    handleCallEvent(message); return
                }

                // Security & recovery
                subject.contains(".forApp.recovery.") -> {
                    handleRecoveryEvent(message); return
                }
                subject.contains(".forApp.transfer.") -> {
                    handleTransferEvent(message); return
                }
                subject.contains(".forApp.security.") -> {
                    handleSecurityEvent(message); return
                }

                // Agent events (push notifications, not request-responses)
                subject.contains(".forApp.agent.") && !isRequestResponse(subject) -> {
                    handleAgentEvent(message); return
                }

                // Location
                subject.contains(".forApp.location-update") -> {
                    handleLocationUpdate(message); return
                }
                subject.contains(".forApp.connection.peer-location-share-started") -> {
                    handlePeerLocationTransition(message, PeerLocationShareTransition.Transition.STARTED); return
                }
                subject.contains(".forApp.connection.peer-location-share-stopped") -> {
                    handlePeerLocationTransition(message, PeerLocationShareTransition.Transition.STOPPED); return
                }
                subject.contains(".forApp.connection.peer-location-requested") -> {
                    handlePeerLocationTransition(message, PeerLocationShareTransition.Transition.REQUESTED); return
                }

                // Grants (plans/data-request-grants.md Phase 2). These
                // forward the vault's notifications to a SharedFlow that
                // GrantsViewModel collects from. Keep handling here narrow:
                // emit on the flow and return — UI threads do the rendering.
                subject.contains(".forApp.connection.data-request-received") ||
                subject.contains(".forApp.connection.data-grant-created") ||
                subject.contains(".forApp.connection.data-grant-denied") ||
                subject.contains(".forApp.connection.data-grant-revoked") ||
                subject.contains(".forApp.connection.data-grant-fetch-response") -> {
                    handleGrantEvent(message, subject); return
                }

                // Critical-secret use prompts (Phase 6).
                subject.contains(".forApp.connection.critical-secret-use-requested") ||
                subject.contains(".forApp.connection.critical-secret-use-response") -> {
                    handleGrantEvent(message, subject); return
                }

                // connection.authenticate verdict.
                subject.contains(".forApp.connection.authenticate-result") -> {
                    handleGrantEvent(message, subject); return
                }

                // Feed notifications
                subject.contains(".forApp.feed.new") ||
                subject.contains(".forApp.feed.updated") -> {
                    handleFeedNotification(message); return
                }
            }

            // --- REQUEST-RESPONSES ---
            // Everything else is a JetStream request-response message.
            // The JetStreamRequestHelper consumer handles these — silently skip.
            // (No log spam — this is normal for every request-response exchange)

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to handle vault message", e)
        }
    }

    /**
     * Check if a subject looks like a JetStream request-response
     * (contains a UUID event_id before .response suffix).
     */
    private fun isRequestResponse(subject: String): Boolean {
        // Request-responses have pattern: ...{type}.{uuid}.response
        // Push notifications have pattern: ...{type}.response (no UUID)
        val parts = subject.split(".")
        if (parts.size < 2) return false
        val beforeResponse = parts[parts.size - 2]
        // UUID pattern: 8-4-4-4-12 hex chars
        return beforeResponse.length == 36 && beforeResponse.count { it == '-' } == 4
    }

    private fun parseTimestamp(timestamp: String?): Long {
        return try {
            timestamp?.let { Instant.parse(it).toEpochMilli() } ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * Extract and store any new UTKs from vault response.
     * The vault automatically includes replacement UTKs when one is consumed.
     * Format: ["keyId:base64PublicKey", ...]
     */
    private fun extractAndStoreUtks(responseString: String) {
        try {
            val json = gson.fromJson(responseString, JsonObject::class.java)
            val newUtksArray = json.getAsJsonArray("new_utks") ?: return

            if (newUtksArray.size() == 0) return

            Log.d(TAG, "Received ${newUtksArray.size()} new UTKs from vault response")

            val newKeys = mutableListOf<TransactionKeyInfo>()
            for (i in 0 until newUtksArray.size()) {
                val utkString = newUtksArray.get(i).asString
                val parts = utkString.split(":", limit = 2)
                if (parts.size == 2) {
                    newKeys.add(TransactionKeyInfo(
                        keyId = parts[0],
                        publicKey = parts[1],
                        algorithm = "X25519"
                    ))
                }
            }

            if (newKeys.isNotEmpty()) {
                credentialStore.addUtks(newKeys)
                Log.i(TAG, "Added ${newKeys.size} new UTKs to pool. Total: ${credentialStore.getUtkCount()}")
            }
        } catch (e: Exception) {
            // Not all responses have new_utks, so failures are expected
            Log.v(TAG, "No new_utks in response (expected for most responses)")
        }
    }

    private fun handleEventTypes(message: NatsMessage) {
        try {
            val eventTypes = gson.fromJson(message.dataString, EventTypesJson::class.java)
            val event = VaultEvent.EventTypesUpdated(
                eventTypes = eventTypes.types.map { type ->
                    EventType(
                        id = type.id,
                        name = type.name,
                        description = type.description,
                        parameters = type.parameters
                    )
                }
            )
            _vaultEvents.tryEmit(event)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse event types", e)
        }
    }

    private fun parseVaultStatus(json: JsonObject?): VaultRuntimeStatus {
        return VaultRuntimeStatus(
            isOnline = json?.get("isOnline")?.asBoolean ?: false,
            connectedAt = json?.get("connectedAt")?.asString,
            handlers = json?.getAsJsonArray("handlers")?.map { it.asString } ?: emptyList()
        )
    }

    private fun parseEventTypes(json: JsonObject?): List<EventType> {
        val array = json?.getAsJsonArray("eventTypes") ?: return emptyList()
        return array.map { element ->
            val obj = element.asJsonObject
            EventType(
                id = obj.get("id")?.asString ?: "",
                name = obj.get("name")?.asString ?: "",
                description = obj.get("description")?.asString,
                parameters = obj.getAsJsonArray("parameters")?.map { it.asString }
            )
        }
    }

    /**
     * Handle credential rotation event from vault.
     * These are proactive push messages from the vault with new credentials.
     */
    private fun handleCredentialRotation(message: NatsMessage) {
        try {
            // Credential rotation messages are NOT encrypted (sent before session)
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) {
                json.getJSONObject("payload")
            } else {
                json
            }

            val rotationMessage = CredentialRotationMessage(
                credentials = payload.getString("credentials"),
                expiresAt = payload.optString("expires_at", ""),
                ttlSeconds = payload.optLong("ttl_seconds", 604800L),
                credentialId = payload.optString("credential_id", ""),
                reason = payload.optString("reason", "unknown"),
                oldCredentialId = if (payload.has("old_credential_id")) payload.optString("old_credential_id") else null
            )

            android.util.Log.i(TAG, "Received credential rotation: reason=${rotationMessage.reason}")
            _credentialRotation.tryEmit(rotationMessage)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse credential rotation event", e)
        }
    }

    /**
     * Handle new message event from peer vault.
     */
    private fun handleNewMessage(message: NatsMessage) {
        try {
            val rawString = String(message.data, Charsets.UTF_8)

            // Guard: skip if not valid JSON object
            val trimmed = rawString.trimStart()
            if (!trimmed.startsWith("{")) {
                android.util.Log.w(TAG, "Skipping non-JSON message data: ${trimmed.take(100)}")
                return
            }

            val json = JSONObject(rawString)
            val payload = if (json.has("payload")) {
                val payloadVal = json.get("payload")
                if (payloadVal is org.json.JSONObject) payloadVal else json
            } else json

            val incomingMessage = IncomingMessage(
                messageId = payload.getString("message_id"),
                connectionId = payload.getString("connection_id"),
                senderGuid = payload.getString("sender_guid"),
                content = payload.optString("content", null)
                    ?.takeIf { it != "null" && it.isNotEmpty() },
                encryptedContent = payload.optString("encrypted_content", ""),
                nonce = payload.optString("nonce", ""),
                contentType = payload.optString("content_type", "text"),
                sentAt = payload.optString("sent_at", "")
            )

            android.util.Log.d(TAG, "Received new message: ${incomingMessage.messageId}")
            _incomingMessages.tryEmit(incomingMessage)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse new-message event", e)
        }
    }

    /**
     * Handle read receipt from peer vault.
     */
    private fun handleReadReceipt(message: NatsMessage) {
        try {
            val rawString = String(message.data, Charsets.UTF_8).trim()
            if (rawString.isEmpty() || !rawString.startsWith("{")) {
                return // Skip empty or non-JSON data (JetStream duplicate)
            }

            val json = JSONObject(rawString)
            val payload = if (json.has("payload") && json.get("payload") is JSONObject) {
                json.getJSONObject("payload")
            } else json

            // message_id is required, other fields are optional
            val messageId = payload.optString("message_id", "")
            if (messageId.isEmpty()) return

            val readReceipt = ReadReceipt(
                messageId = messageId,
                connectionId = payload.optString("connection_id", ""),
                readerGuid = payload.optString("reader_guid", ""),
                readAt = payload.optString("read_at", "")
            )

            android.util.Log.i(TAG, "Received read receipt for message: ${readReceipt.messageId}")
            _readReceipts.tryEmit(readReceipt)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse read-receipt event", e)
        }
    }

    /**
     * Handle profile update from peer vault.
     *
     * Vault broadcast schema (BroadcastPublishedProfile in
     * notifications.go): {fields, updated_at, profile,
     * from_owner_space}. There is NO `connection_id` or
     * `peer_guid` at the top level — the sender's connection_id
     * wouldn't match ours anyway (each side assigns its own).
     *
     * Earlier versions of this parser called payload.getString(
     * "connection_id") which threw JSONException on every broadcast,
     * dropping the live UI refresh signal entirely. The cache update
     * still happened on the vault side (HandleIncomingProfileUpdate
     * in notifications.go writes connections/<id>/_peer_profile), but
     * subscribers waiting on _profileUpdates never fired so the UI
     * showed stale data until the next manual reload.
     *
     * Identified 2026-05-09 testing: peer profile fields + identity
     * key appeared missing on the receiving phone after a republish.
     */
    private fun handleProfileUpdate(message: NatsMessage) {
        try {
            val rawString = String(message.data, Charsets.UTF_8).trim()
            if (rawString.isEmpty() || !rawString.startsWith("{")) {
                // JetStream redeliveries / heartbeats / malformed pushes
                // can land here as empty bytes. Don't try to parse;
                // logging an exception every 30 seconds for these is just
                // noise. Same defensive pattern as handleNewMessage.
                return
            }
            val json = JSONObject(rawString)
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            // Use from_owner_space as the peer GUID. The connection_id
            // is unknown to us at this layer; downstream consumers
            // resolve it from peer_guid via their local connection
            // index. Pass empty string for now (was previously the
            // sender's id which we can't use).
            val peerGuid = payload.optString("from_owner_space", "").takeIf { it.isNotBlank() } ?: ""

            // Pull display_name + photo from the embedded profile
            // snapshot when present so live UI updates get the
            // freshest header rendering without waiting for a
            // connection.list reload.
            var displayName: String? = null
            var avatarUrl: String? = null
            payload.optJSONObject("profile")?.let { snapshot ->
                val first = snapshot.optString("first_name", "")
                val last = snapshot.optString("last_name", "")
                val combined = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ").trim()
                if (combined.isNotEmpty()) displayName = combined
                snapshot.optString("photo", "").takeIf { it.isNotBlank() }?.let { avatarUrl = it }
            }

            val profileUpdate = ProfileUpdate(
                // connection_id isn't in the broadcast (sender's id
                // wouldn't match ours). Empty string; consumers must
                // resolve via peerGuid.
                connectionId = "",
                peerGuid = peerGuid,
                displayName = displayName,
                avatarUrl = avatarUrl,
                status = null,
                updatedAt = payload.optString("updated_at", "")
            )

            android.util.Log.d(TAG, "Received profile update from: $peerGuid")
            _profileUpdates.tryEmit(profileUpdate)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse profile-update event", e)
        }
    }

    /**
     * Handle connection revocation notice from peer vault.
     */
    private fun handleConnectionRevoked(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val connectionRevoked = ConnectionRevoked(
                connectionId = payload.getString("connection_id"),
                peerGuid = payload.getString("peer_guid"),
                reason = if (payload.has("reason")) payload.getString("reason") else null,
                revokedAt = payload.optString("revoked_at", "")
            )

            android.util.Log.i(TAG, "Connection revoked: ${connectionRevoked.connectionId}")
            _connectionRevocations.tryEmit(connectionRevoked)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse connection-revoked event", e)
        }
    }

    /**
     * Handle connection acceptance notification from vault.
     * Emitted when a peer accepts our connection invitation.
     */
    private fun handleConnectionPeerAccepted(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            var peerProfile: Map<String, String>? = null
            var peerPhoto: String? = null
            var peerFields: Map<String, Map<String, String>>? = null

            if (payload.has("peer_profile") && !payload.isNull("peer_profile")) {
                val profileObj = payload.getJSONObject("peer_profile")
                val map = mutableMapOf<String, String>()
                profileObj.keys().forEach { key ->
                    if (key != "fields" && key != "photo") {
                        profileObj.optString(key)?.let { map[key] = it }
                    }
                }
                peerProfile = map.toMap()

                // Extract photo
                peerPhoto = profileObj.optString("photo", null)
                    ?.takeIf { it.isNotEmpty() && it != "null" }

                // Extract structured fields
                if (profileObj.has("fields") && !profileObj.isNull("fields")) {
                    val fieldsObj = profileObj.getJSONObject("fields")
                    val fieldsMap = mutableMapOf<String, Map<String, String>>()
                    fieldsObj.keys().forEach { fieldKey ->
                        val fieldObj = fieldsObj.optJSONObject(fieldKey)
                        if (fieldObj != null) {
                            val fieldData = mutableMapOf<String, String>()
                            fieldObj.keys().forEach { k ->
                                fieldObj.optString(k)?.let { fieldData[k] = it }
                            }
                            fieldsMap[fieldKey] = fieldData.toMap()
                        }
                    }
                    peerFields = fieldsMap.toMap()
                }
            }

            val accepted = ConnectionPeerAccepted(
                connectionId = payload.getString("connection_id"),
                peerGuid = payload.optString("peer_guid", ""),
                peerAlias = if (payload.has("peer_alias")) payload.getString("peer_alias") else null,
                peerProfile = peerProfile,
                peerPhoto = peerPhoto,
                peerFields = peerFields
            )

            android.util.Log.i(TAG, "Connection peer accepted: ${accepted.connectionId} by ${accepted.peerAlias}")
            _connectionAcceptances.tryEmit(accepted)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse connection.peer-accepted event", e)
        }
    }

    private fun handleDevicePendingAuth(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val meta = payload.optJSONObject("device_metadata")
            val metadata = if (meta != null) {
                DeviceMetadataSummary(
                    deviceName = meta.optString("device_name", ""),
                    hostname = meta.optString("hostname", ""),
                    platform = meta.optString("platform", ""),
                    osName = meta.optString("os_name", ""),
                    osVersion = meta.optString("os_version", ""),
                    appVersion = meta.optString("app_version", ""),
                    binaryFingerprint = meta.optString("binary_fingerprint", ""),
                    machineFingerprint = meta.optString("machine_fingerprint", ""),
                    clientIp = meta.optString("client_ip", "")
                )
            } else null

            val notif = DevicePendingAuthNotification(
                connectionId = payload.getString("connection_id"),
                deviceMetadata = metadata,
                binaryFpPrefix = payload.optString("binary_fp_prefix", ""),
                expiresAt = payload.optLong("expires_at", 0L),
                defaultDurationSeconds = payload.optLong("default_duration_s", 3600L),
                maxDurationSeconds = payload.optLong("max_duration_s", 24 * 3600L)
            )
            android.util.Log.i(TAG, "Device pending authorization: ${notif.connectionId}")
            _devicePendingAuth.tryEmit(notif)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse device.pending-authorization event", e)
        }
    }

    private fun handleDeviceRevoked(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json
            val connId = payload.optString("connection_id", "")
            if (connId.isNotEmpty()) _deviceSessionRevoked.tryEmit(connId)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse device.revoked event", e)
        }
    }

    /**
     * Parse a forApp.presence.heartbeat message and emit a domain
     * event. Observers subscribe to [presenceHeartbeats] and track
     * timestamps themselves — no retention logic in the client.
     */
    private fun handlePresenceHeartbeat(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json
            val connectionId = payload.optString("connection_id", "")
            if (connectionId.isEmpty()) return
            val status = payload.optString("status", "online")
            val at = payload.optLong("at", System.currentTimeMillis() / 1000)
            _presenceHeartbeats.tryEmit(
                PresenceHeartbeat(
                    connectionId = connectionId,
                    status = status,
                    at = at,
                )
            )
        } catch (e: Exception) {
            android.util.Log.d(TAG, "Ignoring malformed presence heartbeat: ${e.message}")
        }
    }

    /**
     * Locally broadcast a connection status-change event. Used by
     * ConnectionReviewViewModel after a successful accept/decline so
     * FeedViewModel's collector on [connectionStatusUpdates] re-fetches
     * the connection list without waiting for the vault's broadcast
     * to round-trip back. Avoids the "card still says Wants to
     * connect after I accepted" stale state.
     */
    fun notifyLocalConnectionStatusChanged(connectionId: String, type: String) {
        _connectionStatusUpdates.tryEmit(
            ConnectionStatusUpdate(
                type = type,
                connectionId = connectionId,
                peerGuid = null,
                peerAlias = null,
            )
        )
    }

    private fun handleConnectionStatusUpdate(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val update = ConnectionStatusUpdate(
                type = payload.optString("type", ""),
                connectionId = payload.optString("connection_id", ""),
                peerGuid = payload.optString("peer_guid", null),
                peerAlias = payload.optString("peer_alias", null)
            )

            android.util.Log.i(TAG, "Connection status update: ${update.type} for ${update.connectionId}")
            _connectionStatusUpdates.tryEmit(update)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse connection status update", e)
        }
    }

    /**
     * Handle location update from peer vault.
     * Ephemeral: not stored, only emitted to flow for display.
     */
    private fun handleLocationUpdate(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            // Sender's wire schema renamed `timestamp` → `captured_at`
            // to dodge the parent's replay-prevention layer (which
            // drops any message whose top-level `timestamp` is older
            // than 5 min). Read both for backwards-compat with any
            // sender still on the old wire.
            val capturedAt = when {
                payload.has("captured_at") -> payload.getLong("captured_at")
                payload.has("timestamp") -> payload.getLong("timestamp")
                else -> 0L
            }
            val update = SharedLocationUpdate(
                connectionId = payload.getString("connection_id"),
                latitude = payload.getDouble("latitude"),
                longitude = payload.getDouble("longitude"),
                accuracy = if (payload.has("accuracy") && !payload.isNull("accuracy"))
                    payload.getDouble("accuracy").toFloat() else null,
                timestamp = capturedAt,
                updatedAt = payload.optString("updated_at", "")
            )

            android.util.Log.d(TAG, "Received location update from: ${update.connectionId}")
            _locationUpdates.tryEmit(update)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse location-update event", e)
        }
    }

    private fun handlePeerLocationTransition(
        message: NatsMessage,
        transition: PeerLocationShareTransition.Transition
    ) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json
            val connectionId = payload.optString("connection_id", "")
            if (connectionId.isEmpty()) {
                android.util.Log.w(TAG, "peer-location transition missing connection_id; dropping")
                return
            }
            val fromOwnerSpace = payload.optString("from_owner_space", "")
            val at = when (transition) {
                PeerLocationShareTransition.Transition.STARTED -> payload.optString("started_at", "")
                PeerLocationShareTransition.Transition.STOPPED -> payload.optString("stopped_at", "")
                PeerLocationShareTransition.Transition.REQUESTED -> payload.optString("requested_at", "")
            }
            _peerLocationTransitions.tryEmit(
                PeerLocationShareTransition(
                    connectionId = connectionId,
                    fromOwnerSpace = fromOwnerSpace,
                    transition = transition,
                    at = at
                )
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse peer-location transition", e)
        }
    }

    /**
     * Fetch the cached peer location for one connection. Returns null
     * when the peer is not currently sharing (vault responds
     * `{shared:false}`). Wraps `location.peer.get`.
     */
    suspend fun getPeerLocation(connectionId: String): CachedPeerLocation? {
        val payload = JsonObject().apply { addProperty("connection_id", connectionId) }
        val response = sendAndAwaitResponse("location.peer.get", payload, 10_000L)
        if (response !is VaultResponse.HandlerResult || !response.success) return null
        val result = response.result ?: return null
        if (!result.has("shared") || !result.get("shared").asBoolean) return null
        val locElem = result.get("location") ?: return null
        if (!locElem.isJsonObject) return null
        val loc = locElem.asJsonObject
        return CachedPeerLocation(
            latitude = loc.get("latitude").asDouble,
            longitude = loc.get("longitude").asDouble,
            accuracy = if (loc.has("accuracy") && !loc.get("accuracy").isJsonNull)
                loc.get("accuracy").asFloat else null,
            timestamp = if (loc.has("timestamp")) loc.get("timestamp").asLong else 0L,
            updatedAt = if (loc.has("updated_at")) loc.get("updated_at").asString else "",
            firstReceivedAt = if (loc.has("first_received_at")) loc.get("first_received_at").asString else ""
        )
    }

    /**
     * Send a one-shot location-request ping to a peer (V6). The peer's
     * app will see a `connection.peer-location-requested` notification
     * and may respond via `location.send-once`.
     */
    suspend fun requestPeerLocation(connectionId: String): Result<Unit> {
        val payload = JsonObject().apply { addProperty("connection_id", connectionId) }
        val response = sendAndAwaitResponse("location.request", payload, 10_000L)
        return if (response is VaultResponse.HandlerResult && response.success) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to request peer location"))
        }
    }

    /**
     * Send the owner's latest cached location to a single peer once,
     * without modifying the sharing index (V6). Used to fulfill a
     * `peer-location-requested` notification.
     */
    suspend fun sendLocationOnce(connectionId: String): Result<Unit> {
        val payload = JsonObject().apply { addProperty("connection_id", connectionId) }
        val response = sendAndAwaitResponse("location.send-once", payload, 10_000L)
        return if (response is VaultResponse.HandlerResult && response.success) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to send one-shot location"))
        }
    }

    /**
     * Handle agent events from vault (approval requests).
     *
     * Events:
     * - agent.secret.request: Agent requesting a secret, needs owner approval
     * - agent.action.request: Agent requesting an action, needs owner approval
     */
    private fun handleAgentEvent(message: NatsMessage) {
        try {
            val eventType = message.subject.substringAfterLast(".forApp.")

            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val request = AgentApprovalRequest(
                requestId = payload.getString("request_id"),
                connectionId = payload.getString("connection_id"),
                agentName = payload.optString("agent_name", "Unknown Agent"),
                secretId = payload.optString("secret_id", ""),
                secretName = payload.optString("secret_name", "Unknown"),
                category = payload.optString("category", ""),
                purpose = payload.optString("purpose", ""),
                action = payload.optString("action", "retrieve"),
                eventType = eventType
            )

            android.util.Log.i(TAG, "Agent approval request: ${request.requestId} from ${request.agentName}")
            _agentApprovalRequests.tryEmit(request)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse agent event", e)
        }
    }

    /**
     * Handle call signaling events from vault (vault-routed calls).
     *
     * Events:
     * - call.incoming: Incoming call notification
     * - call.offer: WebRTC SDP offer
     * - call.answer: WebRTC SDP answer
     * - call.candidate: ICE candidate
     * - call.accepted: Call was accepted
     * - call.rejected: Call was rejected
     * - call.ended: Call ended
     * - call.missed: Call was not answered
     * - call.blocked: Caller is blocked
     * - call.busy: Callee is busy
     */
    private fun handleCallEvent(message: NatsMessage) {
        try {
            // Extract event type from subject (e.g., "OwnerSpace.guid.forApp.call.incoming" -> "incoming")
            val eventType = message.subject.substringAfterLast(".forApp.call.")

            // Skip request/response echoes — forApp.call.*.response is the vault's
            // ack for forVault.call.*, not a signaling event. Also skip empty bodies
            // (e.g., 0-byte `call.initiate.response` the parent emits for vault→vault
            // messages). Both are routed here by the ".forApp.call." prefix match.
            if (eventType.endsWith(".response") || message.data.isEmpty()) {
                return
            }

            // CallEvent fields (call_id, caller_id, sdp_offer, etc.) are top-level.
            // The optional nested "payload" object carries the X25519 key-exchange
            // blob (local_key_pub) for initiate; don't mistake it for an envelope.
            val json = JSONObject(String(message.data, Charsets.UTF_8))

            val callEvent = when (eventType) {
                "incoming" -> {
                    val callId = json.optString("call_id", "")
                    val callerGuid = json.optString("caller_id", "")
                    if (callId.isEmpty() && callerGuid.isEmpty()) {
                        android.util.Log.w(TAG, "Incoming call missing call_id and caller_id, raw: ${String(message.data, Charsets.UTF_8).take(200)}")
                        null
                    } else {
                        CallSignalEvent.Incoming(
                            callId = callId,
                            callerGuid = callerGuid,
                            callerDisplayName = json.optString("caller_display_name", "Unknown"),
                            callType = json.optString("call_type", "voice"),
                            sdpOffer = json.optString("sdp_offer", "").takeIf { it.isNotEmpty() },
                            timestamp = json.optLong("timestamp", System.currentTimeMillis())
                        )
                    }
                }
                "offer" -> {
                    // Backend forwards HandleSendSignaling payload as nested "payload" on CallEvent.
                    val inner = json.optJSONObject("payload")
                    CallSignalEvent.Offer(
                        callId = json.optString("call_id", ""),
                        callerGuid = json.optString("caller_id", ""),
                        sdpOffer = inner?.optString("sdp_offer", "") ?: json.optString("sdp_offer", "")
                    )
                }
                "answer" -> {
                    val inner = json.optJSONObject("payload")
                    CallSignalEvent.Answer(
                        callId = json.optString("call_id", ""),
                        sdpAnswer = inner?.optString("sdp_answer", "") ?: json.optString("sdp_answer", "")
                    )
                }
                "candidate" -> {
                    val inner = json.optJSONObject("payload")
                    CallSignalEvent.IceCandidate(
                        callId = json.optString("call_id", ""),
                        candidate = inner?.optString("candidate", "") ?: json.optString("candidate", ""),
                        sdpMid = (inner?.optString("sdp_mid", "") ?: json.optString("sdp_mid", "")).takeIf { it.isNotEmpty() },
                        sdpMLineIndex = inner?.takeIf { it.has("sdp_m_line_index") }?.getInt("sdp_m_line_index")
                            ?: json.takeIf { it.has("sdp_m_line_index") }?.getInt("sdp_m_line_index")
                    )
                }
                "accepted" -> CallSignalEvent.Accepted(
                    callId = json.optString("call_id", ""),
                    sdpAnswer = json.optString("sdp_answer", "").takeIf { it.isNotEmpty() },
                    sharedSecret = json.optString("shared_secret", "").takeIf { it.isNotEmpty() }
                )
                "rejected" -> CallSignalEvent.Rejected(
                    callId = json.optString("call_id", ""),
                    reason = json.optString("reason", "").takeIf { it.isNotEmpty() }
                )
                "ended" -> CallSignalEvent.Ended(
                    callId = json.optString("call_id", ""),
                    reason = json.optString("reason", "completed"),
                    duration = json.optLong("duration", 0)
                )
                "missed" -> CallSignalEvent.Missed(
                    callId = json.optString("call_id", ""),
                    callerGuid = json.optString("caller_id", ""),
                    callerDisplayName = json.optString("caller_display_name", "Unknown"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
                "blocked" -> CallSignalEvent.Blocked(
                    callId = json.optString("call_id", ""),
                    targetGuid = json.optString("target_id", "")
                )
                "busy" -> CallSignalEvent.Busy(
                    callId = json.optString("call_id", "")
                )
                else -> {
                    android.util.Log.w(TAG, "Unknown call event type: $eventType")
                    null
                }
            }

            callEvent?.let { event ->
                android.util.Log.d(TAG, "Received call event: $eventType (callId=${event.callId})")
                _callEvents.tryEmit(event)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse call event", e)
        }
    }

    /**
     * Handle recovery events (Issue #32, #33).
     *
     * Events:
     * - recovery.requested: Someone requested to recover credentials
     * - recovery.cancelled: Recovery was cancelled (manual or fraud detection)
     * - recovery.completed: Recovery completed on another device
     */
    private fun handleRecoveryEvent(message: NatsMessage) {
        try {
            val eventType = message.subject.substringAfterLast(".forApp.recovery.")
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val event = when (eventType) {
                "requested" -> VaultEvent.RecoveryRequested(
                    requestId = payload.getString("request_id"),
                    email = if (payload.has("email")) payload.getString("email") else null
                )
                "cancelled" -> VaultEvent.RecoveryCancelled(
                    requestId = payload.getString("request_id"),
                    reason = if (payload.has("reason")) payload.getString("reason") else null
                )
                "completed" -> VaultEvent.RecoveryCompleted(
                    requestId = payload.getString("request_id")
                )
                else -> {
                    android.util.Log.w(TAG, "Unknown recovery event type: $eventType")
                    null
                }
            }

            event?.let {
                android.util.Log.i(TAG, "Received recovery event: $eventType")
                _vaultEvents.tryEmit(it)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse recovery event", e)
        }
    }

    /**
     * Handle transfer events (Issue #31: Device-to-device transfer).
     *
     * Events:
     * - transfer.requested: New device requests credential transfer
     * - transfer.approved: Transfer was approved by old device
     * - transfer.denied: Transfer was denied by old device
     * - transfer.completed: Transfer finished successfully
     * - transfer.expired: Transfer request timed out (15 minutes)
     */
    private fun handleTransferEvent(message: NatsMessage) {
        try {
            val eventType = message.subject.substringAfterLast(".forApp.transfer.")
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val event = when (eventType) {
                "requested" -> {
                    val deviceInfoJson = payload.optJSONObject("target_device_info")
                        ?: payload.optJSONObject("device_info")
                        ?: JSONObject()

                    VaultEvent.TransferRequested(
                        transferId = payload.getString("transfer_id"),
                        sourceDeviceId = if (payload.has("source_device_id"))
                            payload.getString("source_device_id") else null,
                        targetDeviceInfo = DeviceInfo(
                            deviceId = deviceInfoJson.optString("device_id", ""),
                            model = deviceInfoJson.optString("model", "Unknown Device"),
                            osVersion = deviceInfoJson.optString("os_version", ""),
                            location = if (deviceInfoJson.has("location"))
                                deviceInfoJson.getString("location") else null
                        )
                    )
                }
                "approved" -> VaultEvent.TransferApproved(
                    transferId = payload.getString("transfer_id")
                )
                "denied" -> VaultEvent.TransferDenied(
                    transferId = payload.getString("transfer_id")
                )
                "completed" -> VaultEvent.TransferCompleted(
                    transferId = payload.getString("transfer_id")
                )
                "expired" -> VaultEvent.TransferExpired(
                    transferId = payload.getString("transfer_id")
                )
                else -> {
                    android.util.Log.w(TAG, "Unknown transfer event type: $eventType")
                    null
                }
            }

            event?.let {
                android.util.Log.i(TAG, "Received transfer event: $eventType")
                _vaultEvents.tryEmit(it)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse transfer event", e)
        }
    }

    /**
     * Handle security events (Issue #32: Fraud detection).
     *
     * Events:
     * - security.fraud_detected: Recovery auto-cancelled due to credential usage
     */
    private fun handleSecurityEvent(message: NatsMessage) {
        try {
            val eventType = message.subject.substringAfterLast(".forApp.security.")
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val event = when (eventType) {
                "fraud_detected" -> VaultEvent.RecoveryFraudDetected(
                    requestId = payload.getString("request_id"),
                    reason = payload.optString("reason", "credential_used_during_recovery"),
                    detectedAt = try {
                        Instant.parse(payload.getString("detected_at"))
                    } catch (e: Exception) {
                        Instant.now()
                    }
                )
                else -> {
                    android.util.Log.w(TAG, "Unknown security event type: $eventType")
                    null
                }
            }

            event?.let {
                android.util.Log.i(TAG, "Received security event: $eventType")
                _vaultEvents.tryEmit(it)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse security event", e)
        }
    }

    /**
     * Handle feed notification events (Issue #15: Push notifications).
     *
     * Events:
     * - feed.new: New actionable feed event created
     * - feed.updated: Feed event status changed
     */
    private fun handleFeedNotification(message: NatsMessage) {
        try {
            val eventType = message.subject.substringAfterLast(".forApp.feed.")
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val notification = when (eventType) {
                "new" -> FeedNotification.NewEvent(
                    eventId = payload.getString("event_id"),
                    eventType = payload.getString("event_type"),
                    sourceId = payload.optString("source_id").takeIf { it.isNotEmpty() },
                    sourceType = payload.optString("source_type").takeIf { it.isNotEmpty() },
                    title = payload.getString("title"),
                    message = payload.optString("message").takeIf { it.isNotEmpty() },
                    priority = payload.optInt("priority", 0),
                    actionType = payload.optString("action").takeIf { it.isNotEmpty() },
                    createdAt = payload.optLong("created_at", System.currentTimeMillis())
                )
                "updated" -> FeedNotification.EventUpdated(
                    eventId = payload.getString("event_id"),
                    newStatus = payload.getString("new_status"),
                    updatedAt = payload.optLong("updated_at", System.currentTimeMillis())
                )
                else -> {
                    android.util.Log.w(TAG, "Unknown feed event type: $eventType")
                    null
                }
            }

            notification?.let {
                android.util.Log.i(TAG, "Received feed notification: $eventType - ${it.eventId}")
                _feedNotifications.tryEmit(it)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse feed notification", e)
        }
    }

    /**
     * Parses + dispatches a grant-flow event from the vault. Subject
     * is what we got off the wire; the parser picks the right
     * GrantEvent subtype by suffix. Plaintext fetch-response values
     * pass through this method once and never get persisted.
     */
    private fun handleGrantEvent(message: NatsMessage, subject: String) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json
            val event: GrantEvent = when {
                subject.contains(".data-request-received") -> GrantEvent.RequestReceived(
                    connectionId = payload.optString("connection_id"),
                    requesterGuid = payload.optString("requester_guid"),
                    requestId = payload.optString("request_id"),
                    itemKind = payload.optString("item_kind"),
                    itemRef = payload.optString("item_ref"),
                    itemLabel = payload.optString("item_label"),
                    requestedMode = payload.optString("requested_mode"),
                    requestedExpiresAt = payload.optLong("requested_expires_at"),
                    requestedMaxUses = payload.optInt("requested_max_uses"),
                    deliverTo = payload.optString("deliver_to"),
                    reason = payload.optString("reason"),
                )
                subject.contains(".data-grant-created") -> GrantEvent.GrantCreated(
                    connectionId = payload.optString("connection_id"),
                    granterGuid = payload.optString("granter_guid"),
                    grantId = payload.optString("grant_id"),
                    requestId = payload.optString("request_id"),
                    itemKind = payload.optString("item_kind"),
                    itemLabel = payload.optString("item_label"),
                    mode = payload.optString("mode"),
                    expiresAt = payload.optLong("expires_at"),
                    maxUses = payload.optInt("max_uses"),
                )
                subject.contains(".data-grant-denied") -> GrantEvent.GrantDenied(
                    connectionId = payload.optString("connection_id"),
                    granterGuid = payload.optString("granter_guid"),
                    requestId = payload.optString("request_id"),
                    reason = payload.optString("reason"),
                )
                subject.contains(".data-grant-revoked") -> GrantEvent.GrantRevoked(
                    connectionId = payload.optString("connection_id"),
                    granterGuid = payload.optString("granter_guid"),
                    grantId = payload.optString("grant_id"),
                    reason = payload.optString("reason"),
                )
                subject.contains(".data-grant-fetch-response") -> GrantEvent.FetchResponse(
                    connectionId = payload.optString("connection_id"),
                    granterGuid = payload.optString("granter_guid"),
                    requestId = payload.optString("request_id"),
                    grantId = payload.optString("grant_id"),
                    status = payload.optString("status"),
                    value = payload.optString("value"),
                    error = payload.optString("error"),
                )
                subject.contains(".critical-secret-use-requested") -> GrantEvent.CriticalUseRequested(
                    connectionId = payload.optString("connection_id"),
                    requesterGuid = payload.optString("requester_guid"),
                    requestId = payload.optString("request_id"),
                    itemRef = payload.optString("item_ref"),
                    itemLabel = payload.optString("item_label"),
                    operation = payload.optString("operation"),
                    context = payload.optString("context"),
                )
                subject.contains(".critical-secret-use-response") -> GrantEvent.CriticalUseResponse(
                    connectionId = payload.optString("connection_id"),
                    granterGuid = payload.optString("granter_guid"),
                    requestId = payload.optString("request_id"),
                    status = payload.optString("status"),
                    result = payload.optString("result"),
                    error = payload.optString("error"),
                )
                subject.contains(".authenticate-result") -> GrantEvent.AuthenticateResult(
                    connectionId = payload.optString("connection_id"),
                    peerGuid = payload.optString("peer_guid"),
                    requestId = payload.optString("request_id"),
                    authenticated = payload.optBoolean("authenticated"),
                    failureReason = payload.optString("failure_reason"),
                )
                else -> return
            }
            _grantEvents.tryEmit(event)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse grant event: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "OwnerSpaceClient"

        // Handler-authorization error codes returned by the vault gate.
        // Stable strings — feature view-models can match these to route to
        // friendly UI ("Bitcoin is turned off — enable it in Settings").
        const val ERR_HANDLER_DISABLED = "handler_disabled"
        const val ERR_HANDLER_REQUIRED = "handler_required"
        const val ERR_HANDLER_NOT_SHAREABLE = "handler_not_shareable"
        const val ERR_HANDLER_NOT_SHARED_WITH_PEER = "handler_not_shared_with_peer"
        const val ERR_HANDLER_UNKNOWN = "handler_unknown"

        /**
         * Produce a user-facing message for handler-authorization errors,
         * or null if the error doesn't match any of the known codes
         * (caller should fall back to its default error handling).
         */
        fun friendlyHandlerError(message: String?): String? {
            if (message.isNullOrEmpty()) return null
            return when {
                message.contains(ERR_HANDLER_DISABLED) ->
                    "This capability is turned off in your vault. Enable it in Settings → Capabilities."
                message.contains(ERR_HANDLER_REQUIRED) ->
                    "This capability is required and can't be disabled."
                message.contains(ERR_HANDLER_NOT_SHARED_WITH_PEER) ->
                    "You haven't shared this capability with this connection."
                message.contains(ERR_HANDLER_NOT_SHAREABLE) ->
                    "This capability can't be shared."
                message.contains(ERR_HANDLER_UNKNOWN) ->
                    "Unknown capability."
                else -> null
            }
        }
    }
}

// MARK: - Message Types

private val replayNonceRandom = java.security.SecureRandom()

/**
 * Stamps `timestamp_ms` and a fresh random `nonce` onto a vault-bound request
 * envelope. The parent's replay-cache hashes the raw NATS bytes; a fresh
 * nonce keeps the hash unique even when the rest of the payload is stable
 * (periodic `connection.list`, `wallet.list`, `feed.sync` polls otherwise
 * collide and get dropped as replay attacks).
 */
fun JsonObject.addReplayHeaders() {
    addProperty("timestamp_ms", java.time.Instant.now().toEpochMilli())
    val bytes = ByteArray(8)
    replayNonceRandom.nextBytes(bytes)
    addProperty("nonce", bytes.joinToString("") { "%02x".format(it) })
}

/**
 * Message sent TO the vault.
 *
 * Field names must match vault-manager expectations:
 * - id: Unique request ID for correlation (not "requestId")
 * - type: Handler/event type (e.g., "profile.get", "secrets.datastore.add")
 * - timestamp: ISO 8601 string (not epoch milliseconds)
 * - payload: Handler-specific data
 * - timestamp_ms: epoch millis; read by parent for replay freshness gate
 * - nonce: random 16-char hex; guarantees outer JSON is byte-unique per call
 *   so the parent replay-cache never sees two identical-payload reads (e.g.
 *   periodic connection.list / wallet.list / feed.sync polls) as duplicates.
 */
data class VaultMessage(
    val id: String,
    val type: String,
    val payload: JsonObject,
    val timestamp: String,
    @com.google.gson.annotations.SerializedName("timestamp_ms") val timestampMs: Long,
    val nonce: String
) {
    companion object {
        private val secureRandom = java.security.SecureRandom()

        private fun freshNonce(): String {
            val bytes = ByteArray(8)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun create(type: String, payload: JsonObject = JsonObject()): VaultMessage {
            val now = Instant.now()
            return VaultMessage(
                id = UUID.randomUUID().toString(),
                type = type,
                payload = payload,
                timestamp = now.toString(),
                timestampMs = now.toEpochMilli(),
                nonce = freshNonce()
            )
        }
    }
}

/**
 * Response FROM the vault.
 */
sealed class VaultResponse {
    abstract val requestId: String

    data class HandlerResult(
        override val requestId: String,
        val handlerId: String?,
        val success: Boolean,
        val result: JsonObject?,
        val error: String?
    ) : VaultResponse()

    data class StatusResponse(
        override val requestId: String,
        val status: VaultRuntimeStatus
    ) : VaultResponse()

    data class EventTypesResponse(
        override val requestId: String,
        val eventTypes: List<EventType>
    ) : VaultResponse()

    data class Pong(
        override val requestId: String,
        val timestamp: Long
    ) : VaultResponse()

    data class Error(
        override val requestId: String,
        val code: String,
        val message: String
    ) : VaultResponse()
}

/**
 * Events from the vault (not request-response).
 */
sealed class VaultEvent {
    data class EventTypesUpdated(val eventTypes: List<EventType>) : VaultEvent()
    data class HandlerTriggered(val handlerId: String, val data: JsonObject) : VaultEvent()

    // Recovery events
    data class RecoveryRequested(val requestId: String, val email: String?) : VaultEvent()
    data class RecoveryCancelled(val requestId: String, val reason: String? = null) : VaultEvent()
    data class RecoveryCompleted(val requestId: String) : VaultEvent()

    // Transfer events (Issue #31: Device-to-device transfer)
    data class TransferRequested(
        val transferId: String,
        val sourceDeviceId: String?,
        val targetDeviceInfo: DeviceInfo
    ) : VaultEvent()
    data class TransferApproved(val transferId: String) : VaultEvent()
    data class TransferDenied(val transferId: String) : VaultEvent()
    data class TransferCompleted(val transferId: String) : VaultEvent()
    data class TransferExpired(val transferId: String) : VaultEvent()

    // Fraud detection events (Issue #32: Recovery fraud detection)
    data class RecoveryFraudDetected(
        val requestId: String,
        val reason: String,
        val detectedAt: Instant
    ) : VaultEvent()
}

/**
 * Device information for transfer requests.
 */
data class DeviceInfo(
    val deviceId: String,
    val model: String,
    val osVersion: String,
    val location: String? = null
)

/**
 * Vault runtime status.
 */
data class VaultRuntimeStatus(
    val isOnline: Boolean,
    val connectedAt: String?,
    val handlers: List<String>
)

/**
 * Event type/handler definition.
 */
data class EventType(
    val id: String,
    val name: String,
    val description: String?,
    val parameters: List<String>?
)

// MARK: - JSON Models

/**
 * JSON model for vault responses.
 *
 * Vault-manager returns:
 * - event_id: Matches the 'id' from the request
 * - success: Whether the operation succeeded
 * - timestamp: ISO 8601 when response was generated
 * - result: Handler result (if success=true)
 * - error: Error message (if success=false)
 */
private data class VaultResponseJson(
    val event_id: String? = null,      // Matches request 'id'
    val id: String? = null,            // Alternative field name
    val requestId: String? = null,     // Legacy fallback
    val type: String? = null,
    val request_type: String? = null,  // Added for response validation (e.g., "pin.unlock")
    val handlerId: String? = null,
    val success: Boolean? = null,
    val result: JsonObject? = null,
    val error: String? = null,
    val errorCode: String? = null,
    val timestamp: String? = null      // ISO 8601 format
) {
    /** Get the correlation ID, preferring event_id over id over legacy requestId */
    fun getCorrelationId(): String = event_id ?: id ?: requestId ?: ""
}

private data class EventTypesJson(
    val types: List<EventTypeJson>
)

private data class EventTypeJson(
    val id: String,
    val name: String,
    val description: String? = null,
    val parameters: List<String>? = null
)

// MARK: - Bootstrap Response

/**
 * Response from bootstrap request.
 * Contains the pending keypair for session establishment.
 */
data class BootstrapResponse(
    val requestId: String,
    val pendingKeyPair: SessionKeyPair
)

// MARK: - Credential Operations (KMS-Sealed)

/**
 * Response from credential.create operation.
 * Contains the sealed credential blob encrypted with KMS.
 */
data class CreateCredentialResponse(
    /** Base64-encoded sealed credential blob */
    val credential: String,
    /** Algorithm used for sealing */
    val algorithm: String = "nitro-kms-aes256-gcm"
) {
    companion object {
        fun fromJson(json: JsonObject): CreateCredentialResponse? {
            return try {
                CreateCredentialResponse(
                    credential = json.get("credential")?.asString ?: return null,
                    algorithm = json.get("algorithm")?.asString ?: "nitro-kms-aes256-gcm"
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Response from credential.unseal operation.
 * Contains session token for subsequent operations.
 */
data class UnsealCredentialResponse(
    /** Session token for authenticated operations */
    val sessionToken: String,
    /** Session expiration (epoch seconds) */
    val expiresAt: Long
) {
    companion object {
        fun fromJson(json: JsonObject): UnsealCredentialResponse? {
            return try {
                val unsealResult = json.getAsJsonObject("unseal_result") ?: return null
                UnsealCredentialResponse(
                    sessionToken = unsealResult.get("session_token")?.asString ?: return null,
                    expiresAt = unsealResult.get("expires_at")?.asLong ?: 0
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Sealed credential blob structure.
 * This is what gets stored locally after credential.create.
 */
data class SealedCredentialBlob(
    val version: Int,
    val algorithm: String,
    val encryptedDek: String,
    val nonce: String,
    val ciphertext: String,
    val pcrBound: Boolean
)

// MARK: - App Authenticate (Restore Flow)

/**
 * Request for app.authenticate during credential restore.
 */
data class AppAuthenticateRequest(
    @com.google.gson.annotations.SerializedName("device_id") val deviceId: String,
    @com.google.gson.annotations.SerializedName("device_type") val deviceType: String = "android",
    @com.google.gson.annotations.SerializedName("app_version") val appVersion: String,
    @com.google.gson.annotations.SerializedName("encrypted_credential") val encryptedCredential: String,
    @com.google.gson.annotations.SerializedName("key_id") val keyId: String,
    @com.google.gson.annotations.SerializedName("password_hash") val passwordHash: String,
    val nonce: String,
    @com.google.gson.annotations.SerializedName("app_session_public_key") val appSessionPublicKey: String? = null
)

/**
 * Response from app.authenticate handler.
 */
data class AppAuthenticateResponse(
    val success: Boolean,
    val message: String,
    val credentials: String?,
    @com.google.gson.annotations.SerializedName("nats_endpoint") val natsEndpoint: String?,
    @com.google.gson.annotations.SerializedName("owner_space") val ownerSpace: String?,
    @com.google.gson.annotations.SerializedName("message_space") val messageSpace: String?,
    @com.google.gson.annotations.SerializedName("expires_at") val expiresAt: String?,
    @com.google.gson.annotations.SerializedName("credential_id") val credentialId: String?,
    @com.google.gson.annotations.SerializedName("session_info") val sessionInfo: com.vettid.app.core.crypto.SessionInfo?,
    @com.google.gson.annotations.SerializedName("requires_immediate_rotation") val requiresImmediateRotation: Boolean?,
    @com.google.gson.annotations.SerializedName("user_guid") val userGuid: String?,
    @com.google.gson.annotations.SerializedName("credential_version") val credentialVersion: Int?
) {
    companion object {
        fun fromJson(json: com.google.gson.JsonObject): AppAuthenticateResponse {
            return AppAuthenticateResponse(
                success = json.get("success")?.asBoolean ?: false,
                message = json.get("message")?.asString ?: "",
                credentials = json.get("credentials")?.asString,
                natsEndpoint = json.get("nats_endpoint")?.asString,
                ownerSpace = json.get("owner_space")?.asString,
                messageSpace = json.get("message_space")?.asString,
                expiresAt = json.get("expires_at")?.asString,
                credentialId = json.get("credential_id")?.asString,
                sessionInfo = json.getAsJsonObject("session_info")?.let { sessionJson ->
                    com.vettid.app.core.crypto.SessionInfo(
                        sessionId = sessionJson.get("session_id")?.asString ?: "",
                        vaultSessionPublicKey = sessionJson.get("vault_session_public_key")?.asString ?: "",
                        sessionExpiresAt = sessionJson.get("expires_at")?.asString ?: "",
                        encryptionEnabled = sessionJson.get("encryption_enabled")?.asBoolean ?: true
                    )
                },
                requiresImmediateRotation = json.get("requires_immediate_rotation")?.asBoolean,
                userGuid = json.get("user_guid")?.asString,
                credentialVersion = json.get("credential_version")?.asInt
            )
        }
    }
}

// MARK: - Credential Rotation

/**
 * Credential rotation message from vault.
 *
 * The vault proactively pushes new credentials before expiry.
 * Reason values:
 * - "scheduled_rotation" - Normal rotation (2+ hours remaining)
 * - "expiry_imminent" - Urgent rotation (<30 minutes remaining)
 */
data class CredentialRotationMessage(
    /** New NATS credentials (.creds file content) */
    val credentials: String,
    /** ISO 8601 expiration time */
    val expiresAt: String,
    /** Time to live in seconds (default: 604800 = 7 days) */
    val ttlSeconds: Long,
    /** Unique credential ID for tracking */
    val credentialId: String,
    /** Reason for rotation */
    val reason: String,
    /** Previous credential ID that was rotated out */
    val oldCredentialId: String?
)

// MARK: - Call Signaling Events (Vault-Routed)

/**
 * Call signaling events received from vault.
 *
 * These events are routed through the user's vault for:
 * - Security verification of caller identity
 * - Block list enforcement
 * - Audit logging
 */
sealed class CallSignalEvent {
    abstract val callId: String

    /** Incoming call notification */
    data class Incoming(
        override val callId: String,
        val callerGuid: String,
        val callerDisplayName: String,
        val callType: String,  // "voice" or "video"
        val sdpOffer: String?,
        val timestamp: Long
    ) : CallSignalEvent()

    /** WebRTC SDP offer */
    data class Offer(
        override val callId: String,
        val callerGuid: String,
        val sdpOffer: String
    ) : CallSignalEvent()

    /** WebRTC SDP answer */
    data class Answer(
        override val callId: String,
        val sdpAnswer: String
    ) : CallSignalEvent()

    /** ICE candidate for WebRTC */
    data class IceCandidate(
        override val callId: String,
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?
    ) : CallSignalEvent()

    /** Call was accepted by callee */
    data class Accepted(
        override val callId: String,
        val sdpAnswer: String?,
        val sharedSecret: String? = null // E2EE shared secret (base64) from vault
    ) : CallSignalEvent()

    /** Call was rejected by callee */
    data class Rejected(
        override val callId: String,
        val reason: String?
    ) : CallSignalEvent()

    /** Call ended */
    data class Ended(
        override val callId: String,
        val reason: String,
        val duration: Long
    ) : CallSignalEvent()

    /** Call was not answered (timeout) */
    data class Missed(
        override val callId: String,
        val callerGuid: String,
        val callerDisplayName: String,
        val timestamp: Long
    ) : CallSignalEvent()

    /** Caller is blocked by callee */
    data class Blocked(
        override val callId: String,
        val targetGuid: String
    ) : CallSignalEvent()

    /** Callee is busy on another call */
    data class Busy(
        override val callId: String
    ) : CallSignalEvent()
}

// MARK: - Feed Notifications (Issue #15)

/**
 * Feed notification events from the vault.
 * Used for real-time push notifications when new feed events arrive or status changes.
 */
sealed class FeedNotification {
    abstract val eventId: String

    /**
     * New actionable feed event created.
     * Published to {space}.forApp.feed.new
     */
    data class NewEvent(
        override val eventId: String,
        val eventType: String,
        val sourceId: String?,
        val sourceType: String?,
        val title: String,
        val message: String?,
        val priority: Int,
        val actionType: String?,
        val createdAt: Long
    ) : FeedNotification() {
        /** Convert priority to notification importance level */
        val importance: NotificationImportance get() = when (priority) {
            2 -> NotificationImportance.URGENT
            1 -> NotificationImportance.HIGH
            0 -> NotificationImportance.DEFAULT
            else -> NotificationImportance.LOW
        }
    }

    /**
     * Feed event status changed (read, archived, deleted, etc.)
     * Published to {space}.forApp.feed.updated
     */
    data class EventUpdated(
        override val eventId: String,
        val newStatus: String,
        val updatedAt: Long
    ) : FeedNotification()
}

/**
 * Notification importance levels for feed events.
 * Maps to Android NotificationManager.IMPORTANCE_* constants.
 */
enum class NotificationImportance {
    /** Priority 2 (urgent): Heads-up notification, makes sound */
    URGENT,
    /** Priority 1 (high): Default importance, makes sound */
    HIGH,
    /** Priority 0 (normal): Low importance, no sound */
    DEFAULT,
    /** Priority -1 (low): Min importance, silent */
    LOW
}

/**
 * DTO for custom categories to sync with vault.
 * Matches the enclave's CustomCategory structure.
 */
data class CustomCategoryDto(
    val id: String,
    val name: String,
    val icon: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Result of a PIN change operation.
 */
data class PINChangeResult(
    val success: Boolean,
    val error: String? = null
)

/**
 * Result of a password change operation.
 */
data class PasswordChangeResult(
    val success: Boolean,
    val error: String? = null
)

/**
 * Result of [OwnerSpaceClient.unlockIdentity]. The vault echoes back the
 * window length (so the UI can mirror the user's setting without
 * re-fetching it) and the unix timestamp when the in-memory key will
 * be wiped if no signed op extends it.
 */
data class IdentityUnlockResult(
    val expiresAtUnix: Long,
    val ttlSeconds: Long
)

/**
 * A location update received from a connection sharing their location.
 * Ephemeral: displayed in-memory only, not persisted.
 */
data class SharedLocationUpdate(
    val connectionId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timestamp: Long,
    val updatedAt: String
)

/**
 * Notification: peer just started sharing their location with us (V3),
 * or just stopped (V5). `startedAt`/`stoppedAt` are RFC3339 UTC
 * timestamps from the vault. The receiver-side connection id is what
 * the UI uses to route the notification — the peer's own connection
 * id is hidden inside `fromOwnerSpace` and not surfaced.
 */
data class PeerLocationShareTransition(
    val connectionId: String,
    val fromOwnerSpace: String,
    val transition: Transition,
    val at: String
) {
    enum class Transition { STARTED, STOPPED, REQUESTED }
}

/**
 * Cached peer location returned by `location.peer.get`. Mirrors the
 * vault's CachedPeerLocation struct. `firstReceivedAt` distinguishes
 * a fresh share (recent FirstReceivedAt) from a long-running one and
 * is used by the UI to format the "shared X minutes ago" label.
 */
data class CachedPeerLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timestamp: Long,
    val updatedAt: String,
    val firstReceivedAt: String
)

/**
 * A vault handler category with its sub-operations, as reported by the enclave.
 */
data class VaultHandler(
    val id: String,
    val name: String,
    val description: String,
    val operations: List<String>,
    // Classification declared by the enclave's catalog. Immutable per
    // PCR0; toggles below are user state.
    val category: String = "",       // "system" | "default" | "optional"
    val required: Boolean = false,    // owner cannot disable
    val shareable: Boolean = false,   // may appear in published profile
    // User toggles persisted in vault state.
    val enabled: Boolean = true,
    val shareGlobally: Boolean = false,
)

/**
 * An agent approval request received from the vault.
 * The vault sends this when an agent requests a secret or action and the
 * approval mode is "always_ask".
 */
data class AgentApprovalRequest(
    val requestId: String,
    val connectionId: String,
    val agentName: String,
    val secretId: String,
    val secretName: String,
    val category: String,
    val purpose: String,
    val action: String,
    val eventType: String  // "agent.secret.request" or "agent.action.request"
)

/**
 * Vault → app event emitted by the grant flow. One sealed type per
 * subject so ViewModels can `when`-exhaust. Plaintext lives only in
 * memory; never persist FetchResponse.value.
 */
sealed class GrantEvent {
    data class RequestReceived(
        val connectionId: String,
        val requesterGuid: String,
        val requestId: String,
        val itemKind: String,
        val itemRef: String,
        val itemLabel: String,
        val requestedMode: String,
        val requestedExpiresAt: Long,
        val requestedMaxUses: Int,
        val deliverTo: String,
        val reason: String,
    ) : GrantEvent()

    data class GrantCreated(
        val connectionId: String,
        val granterGuid: String,
        val grantId: String,
        val requestId: String,
        val itemKind: String,
        val itemLabel: String,
        val mode: String,
        val expiresAt: Long,
        val maxUses: Int,
    ) : GrantEvent()

    data class GrantDenied(
        val connectionId: String,
        val granterGuid: String,
        val requestId: String,
        val reason: String,
    ) : GrantEvent()

    data class GrantRevoked(
        val connectionId: String,
        val granterGuid: String,
        val grantId: String,
        val reason: String,
    ) : GrantEvent()

    data class FetchResponse(
        val connectionId: String,
        val granterGuid: String,
        val requestId: String,
        val grantId: String,
        val status: String,
        val value: String,
        val error: String,
    ) : GrantEvent()

    data class CriticalUseRequested(
        val connectionId: String,
        val requesterGuid: String,
        val requestId: String,
        val itemRef: String,
        val itemLabel: String,
        val operation: String,
        val context: String,
    ) : GrantEvent()

    data class CriticalUseResponse(
        val connectionId: String,
        val granterGuid: String,
        val requestId: String,
        val status: String,
        val result: String,
        val error: String,
    ) : GrantEvent()

    data class AuthenticateResult(
        val connectionId: String,
        val peerGuid: String,
        val requestId: String,
        val authenticated: Boolean,
        val failureReason: String,
    ) : GrantEvent()
}
