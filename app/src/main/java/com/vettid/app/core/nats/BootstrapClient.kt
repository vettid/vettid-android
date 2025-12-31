package com.vettid.app.core.nats

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.SessionCrypto
import com.vettid.app.core.crypto.SessionInfo
import com.vettid.app.core.crypto.SessionKeyPair
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the app.bootstrap flow to exchange bootstrap credentials for full credentials.
 *
 * Flow:
 * 1. App connects with bootstrap credentials (limited permissions)
 * 2. App calls app.bootstrap with session public key
 * 3. Vault responds with full NATS credentials + session info
 * 4. App stores full credentials and reconnects
 *
 * This implements the credential exchange documented in app-vault-encryption.md.
 */
@Singleton
class BootstrapClient @Inject constructor(
    private val credentialStore: CredentialStore
) {
    companion object {
        private const val TAG = "BootstrapClient"
        private const val BOOTSTRAP_TIMEOUT_MS = 30_000L
    }

    private val gson = Gson()
    private var pendingKeyPair: SessionKeyPair? = null
    private var pendingRequestId: String? = null
    private var pendingDeferred: CompletableDeferred<BootstrapResult>? = null

    /**
     * Execute the bootstrap flow to exchange bootstrap credentials for full credentials.
     *
     * This should be called when:
     * 1. We have bootstrap credentials (from enrollment)
     * 2. We're connected to NATS with those bootstrap credentials
     * 3. We need full credentials to access vault handlers
     *
     * @param natsClient Connected NATS client with bootstrap credentials
     * @param ownerSpaceId The OwnerSpace ID for topic construction
     * @param deviceId Device identifier
     * @param appVersion App version string
     * @return BootstrapResult containing full credentials and session info
     */
    suspend fun executeBootstrap(
        natsClient: NatsClient,
        ownerSpaceId: String,
        deviceId: String,
        appVersion: String = "1.0.0"
    ): Result<BootstrapResult> {
        Log.i(TAG, "Starting bootstrap flow for $ownerSpaceId")

        // Generate session keypair for E2E encryption
        val keyPair = SessionCrypto.generateKeyPair()
        pendingKeyPair = keyPair

        val requestId = UUID.randomUUID().toString()
        pendingRequestId = requestId

        // Create deferred for response
        val deferred = CompletableDeferred<BootstrapResult>()
        pendingDeferred = deferred

        // Subscribe to bootstrap response topic
        // Bootstrap response comes on: ${ownerSpace}.forApp.bootstrap.{requestId}
        // We subscribe to the wildcard to catch any response
        val responseSubject = "$ownerSpaceId.forApp.bootstrap.>"
        Log.d(TAG, "Subscribing to bootstrap response: $responseSubject")

        val subscriptionResult = natsClient.subscribe(responseSubject) { message ->
            handleBootstrapResponse(message, requestId)
        }

        if (subscriptionResult.isFailure) {
            cleanup()
            return Result.failure(
                subscriptionResult.exceptionOrNull()
                    ?: NatsException("Failed to subscribe to bootstrap response")
            )
        }

        val subscription = subscriptionResult.getOrThrow()

        try {
            // Build bootstrap request payload
            val payload = JsonObject().apply {
                addProperty("request_id", requestId)
                addProperty("app_session_public_key", keyPair.publicKeyBase64())
                addProperty("device_id", deviceId)
                addProperty("device_type", "android")
                addProperty("app_version", appVersion)
                addProperty("timestamp", Instant.now().toString())
            }

            // Build the full message
            val message = JsonObject().apply {
                addProperty("id", requestId)
                addProperty("type", "app.bootstrap")
                add("payload", payload)
                addProperty("timestamp", Instant.now().toString())
            }

            // Publish to bootstrap topic
            val bootstrapTopic = "$ownerSpaceId.forVault.app.bootstrap"
            Log.i(TAG, "Publishing bootstrap request to: $bootstrapTopic")

            val publishResult = natsClient.publish(bootstrapTopic, gson.toJson(message))
            if (publishResult.isFailure) {
                subscription.unsubscribe()
                cleanup()
                return Result.failure(
                    publishResult.exceptionOrNull()
                        ?: NatsException("Failed to publish bootstrap request")
                )
            }

            // Wait for response with timeout
            Log.d(TAG, "Waiting for bootstrap response (timeout: ${BOOTSTRAP_TIMEOUT_MS}ms)")
            val result = withTimeout(BOOTSTRAP_TIMEOUT_MS) {
                deferred.await()
            }

            subscription.unsubscribe()
            Log.i(TAG, "Bootstrap completed successfully")
            return Result.success(result)

        } catch (e: TimeoutCancellationException) {
            subscription.unsubscribe()
            cleanup()
            Log.e(TAG, "Bootstrap timed out after ${BOOTSTRAP_TIMEOUT_MS}ms")
            return Result.failure(NatsException("Bootstrap timed out - vault may not be ready"))
        } catch (e: Exception) {
            subscription.unsubscribe()
            cleanup()
            Log.e(TAG, "Bootstrap failed", e)
            return Result.failure(e)
        }
    }

    /**
     * Handle bootstrap response from vault.
     */
    private fun handleBootstrapResponse(message: NatsMessage, expectedRequestId: String) {
        try {
            Log.d(TAG, "Received bootstrap response on: ${message.subject}")
            val responseString = String(message.data, Charsets.UTF_8)
            Log.d(TAG, "Bootstrap response: ${responseString.take(500)}...")

            val json = gson.fromJson(responseString, JsonObject::class.java)

            // Check for error
            if (json.has("error") && !json.get("error").isJsonNull) {
                val error = json.get("error").asString
                Log.e(TAG, "Bootstrap error: $error")
                pendingDeferred?.completeExceptionally(NatsException("Bootstrap failed: $error"))
                cleanup()
                return
            }

            // Verify request ID matches
            val responseId = json.get("event_id")?.asString
                ?: json.get("id")?.asString
                ?: json.get("request_id")?.asString

            if (responseId != null && responseId != expectedRequestId) {
                Log.w(TAG, "Response ID mismatch: expected $expectedRequestId, got $responseId")
                // Continue anyway - might be a different response format
            }

            // Extract response data (may be in 'result' or directly in json)
            val data = if (json.has("result") && json.get("result").isJsonObject) {
                json.getAsJsonObject("result")
            } else {
                json
            }

            // Parse full NATS credentials (backend uses "credentials" not "nats_credentials")
            val natsCredentials = data.get("credentials")?.asString
                ?: data.get("nats_credentials")?.asString  // Fallback for compatibility
            if (natsCredentials.isNullOrBlank()) {
                Log.e(TAG, "Bootstrap response missing credentials")
                pendingDeferred?.completeExceptionally(
                    NatsException("Bootstrap response missing full NATS credentials")
                )
                cleanup()
                return
            }

            // Parse session info for E2E encryption (may be nested in session_info object)
            val sessionInfo = if (data.has("session_info") && data.get("session_info").isJsonObject) {
                data.getAsJsonObject("session_info")
            } else {
                data // Fallback to flat structure
            }

            val vaultSessionPublicKey = sessionInfo.get("vault_session_public_key")?.asString
            val sessionId = sessionInfo.get("session_id")?.asString ?: UUID.randomUUID().toString()
            val sessionExpiresAt = sessionInfo.get("session_expires_at")?.asString

            // Parse optional fields
            val ownerSpace = data.get("owner_space")?.asString
            val messageSpace = data.get("message_space")?.asString
            val credentialId = data.get("credential_id")?.asString
            val ttlSeconds = data.get("ttl_seconds")?.asLong ?: 604800L

            // Establish E2E session if vault provided session key
            var sessionCrypto: SessionCrypto? = null
            if (!vaultSessionPublicKey.isNullOrBlank() && pendingKeyPair != null) {
                try {
                    val vaultPubKey = Base64.decode(vaultSessionPublicKey, Base64.NO_WRAP)
                    val expiresAtMs = parseExpiresAt(sessionExpiresAt)

                    sessionCrypto = SessionCrypto.fromKeyExchange(
                        sessionId = sessionId,
                        appPrivateKey = pendingKeyPair!!.privateKey,
                        appPublicKey = pendingKeyPair!!.publicKey,
                        vaultPublicKey = vaultPubKey,
                        expiresAt = expiresAtMs
                    )

                    Log.i(TAG, "E2E session established: $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to establish E2E session: ${e.message}")
                    // Continue without E2E - credentials are still valid
                }
            }

            val result = BootstrapResult(
                natsCredentials = natsCredentials,
                ownerSpace = ownerSpace,
                messageSpace = messageSpace,
                sessionCrypto = sessionCrypto,
                sessionId = sessionId,
                sessionExpiresAt = sessionExpiresAt,
                credentialId = credentialId,
                ttlSeconds = ttlSeconds
            )

            pendingDeferred?.complete(result)
            cleanup()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bootstrap response", e)
            pendingDeferred?.completeExceptionally(e)
            cleanup()
        }
    }

    private fun parseExpiresAt(expiresAt: String?): Long {
        return try {
            expiresAt?.let { Instant.parse(it).toEpochMilli() }
                ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            System.currentTimeMillis() + 24 * 60 * 60 * 1000L
        }
    }

    private fun cleanup() {
        pendingKeyPair?.clear()
        pendingKeyPair = null
        pendingRequestId = null
        pendingDeferred = null
    }

    /**
     * Check if we currently have bootstrap (limited) credentials.
     *
     * Bootstrap credentials are identified by having NATS credentials but
     * with limited topic patterns in the stored bootstrap_topic field.
     */
    fun hasBootstrapCredentials(): Boolean {
        // If we have stored bootstrap_topic, we're using bootstrap credentials
        val bootstrapTopic = credentialStore.getNatsBootstrapTopic()
        return bootstrapTopic != null && credentialStore.hasNatsConnection()
    }

    /**
     * Check if we have full credentials (not bootstrap).
     *
     * Full credentials are indicated by absence of bootstrap_topic marker.
     */
    fun hasFullCredentials(): Boolean {
        return credentialStore.hasNatsConnection() &&
               credentialStore.getNatsBootstrapTopic() == null
    }
}

/**
 * Result of the bootstrap flow.
 *
 * Contains the full NATS credentials from the vault plus session info
 * for E2E encrypted communication.
 */
data class BootstrapResult(
    /** Full NATS credentials (.creds file content) */
    val natsCredentials: String,

    /** OwnerSpace ID (may be updated from bootstrap response) */
    val ownerSpace: String?,

    /** MessageSpace ID (may be updated from bootstrap response) */
    val messageSpace: String?,

    /** E2E session crypto (if session key exchange succeeded) */
    val sessionCrypto: SessionCrypto?,

    /** Session ID for E2E encryption */
    val sessionId: String,

    /** Session expiration (ISO 8601) */
    val sessionExpiresAt: String?,

    /** Credential ID for tracking */
    val credentialId: String?,

    /** Time-to-live in seconds for the credentials */
    val ttlSeconds: Long
)
