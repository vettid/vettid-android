package com.vettid.app.core.nats

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.attestation.AttestationVerificationException
import com.vettid.app.core.attestation.NitroAttestationVerifier
import com.vettid.app.core.attestation.PcrConfigManager
import com.vettid.app.core.attestation.VerifiedAttestation
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
 * Flow (with Nitro Enclave attestation):
 * 1. App connects with bootstrap credentials (limited permissions)
 * 2. App sends nonce in bootstrap request for replay protection
 * 3. App calls app.bootstrap with session public key
 * 4. Vault responds with:
 *    - Attestation document (CBOR/COSE signed by AWS Nitro PKI)
 *    - Full NATS credentials
 *    - Session info for E2E encryption
 * 5. App verifies attestation:
 *    - Certificate chain to AWS Nitro root
 *    - PCR values match expected (proves correct code is running)
 *    - Nonce matches (replay protection)
 * 6. App extracts enclave public key from verified attestation
 * 7. App establishes E2E session using verified enclave key
 *
 * This implements the credential exchange documented in app-vault-encryption.md.
 */
@Singleton
class BootstrapClient @Inject constructor(
    private val credentialStore: CredentialStore,
    private val attestationVerifier: NitroAttestationVerifier,
    private val pcrConfigManager: PcrConfigManager
) {
    companion object {
        private const val TAG = "BootstrapClient"
        private const val BOOTSTRAP_TIMEOUT_MS = 30_000L
        private const val NONCE_SIZE = 32
    }

    private val gson = Gson()
    private val secureRandom = java.security.SecureRandom()
    private var pendingKeyPair: SessionKeyPair? = null
    private var pendingRequestId: String? = null
    private var pendingNonce: ByteArray? = null
    private var pendingDeferred: CompletableDeferred<BootstrapResult>? = null

    /** Whether to require attestation verification (can be disabled for testing) */
    var requireAttestation: Boolean = true

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
        Log.i(TAG, "Starting bootstrap flow for $ownerSpaceId (attestation: $requireAttestation)")

        // Generate session keypair for E2E encryption
        val keyPair = SessionCrypto.generateKeyPair()
        pendingKeyPair = keyPair

        val requestId = UUID.randomUUID().toString()
        pendingRequestId = requestId

        // Generate nonce for replay protection (included in attestation)
        val nonce = ByteArray(NONCE_SIZE)
        secureRandom.nextBytes(nonce)
        pendingNonce = nonce

        // Create deferred for response
        val deferred = CompletableDeferred<BootstrapResult>()
        pendingDeferred = deferred

        // Subscribe to bootstrap response topic
        // Bootstrap response comes on: ${ownerSpace}.forApp.{eventType}.{requestId}
        // Since event type is "app.bootstrap", we subscribe to forApp.app.bootstrap.>
        val responseSubject = "$ownerSpaceId.forApp.app.bootstrap.>"
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
            // Build bootstrap request payload (nested structure per API-STATUS.md)
            val payload = JsonObject().apply {
                addProperty("device_id", deviceId)
                addProperty("device_type", "android")
                addProperty("app_version", appVersion)
                addProperty("app_session_public_key", keyPair.publicKeyBase64())
                // Include nonce for attestation replay protection
                addProperty("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
                // Request attestation if required
                addProperty("request_attestation", requireAttestation)
            }

            // Build the full message envelope
            val message = JsonObject().apply {
                addProperty("id", requestId)
                addProperty("type", "app.bootstrap")
                addProperty("timestamp", Instant.now().toString())
                add("payload", payload)
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

            val sessionId = sessionInfo.get("session_id")?.asString ?: UUID.randomUUID().toString()
            val sessionExpiresAt = sessionInfo.get("session_expires_at")?.asString

            // Parse optional fields
            val ownerSpace = data.get("owner_space")?.asString
            val messageSpace = data.get("message_space")?.asString
            val credentialId = data.get("credential_id")?.asString
            val ttlSeconds = data.get("ttl_seconds")?.asLong ?: 604800L
            val requiresImmediateRotation = data.get("requires_immediate_rotation")?.asBoolean ?: false

            // Check for attestation document
            val attestationDoc = data.get("attestation_document")?.asString
            var verifiedAttestation: VerifiedAttestation? = null

            // Verify attestation if present (Nitro Enclave flow)
            if (!attestationDoc.isNullOrBlank()) {
                Log.d(TAG, "Attestation document present, verifying...")
                try {
                    val expectedPcrs = pcrConfigManager.getCurrentPcrs()
                    Log.d(TAG, "Using PCRs version: ${expectedPcrs.version}")

                    verifiedAttestation = attestationVerifier.verify(
                        attestationDocBase64 = attestationDoc,
                        expectedPcrs = expectedPcrs,
                        expectedNonce = pendingNonce
                    )

                    Log.i(TAG, "Attestation verified! Module: ${verifiedAttestation.moduleId}")
                } catch (e: AttestationVerificationException) {
                    Log.e(TAG, "Attestation verification failed: ${e.message}")
                    if (requireAttestation) {
                        pendingDeferred?.completeExceptionally(
                            NatsException("Attestation verification failed: ${e.message}", e)
                        )
                        cleanup()
                        return
                    }
                    // If attestation not required, continue with warning
                    Log.w(TAG, "Continuing without verified attestation (requireAttestation=false)")
                }
            } else if (requireAttestation) {
                Log.e(TAG, "Attestation required but not present in response")
                pendingDeferred?.completeExceptionally(
                    NatsException("Attestation document missing from bootstrap response")
                )
                cleanup()
                return
            }

            // Get vault public key - from attestation if verified, otherwise from response
            val vaultPublicKeyBytes: ByteArray? = if (verifiedAttestation != null) {
                // Use the key from the verified attestation (most secure)
                Log.d(TAG, "Using enclave public key from verified attestation")
                verifiedAttestation.enclavePublicKey
            } else {
                // Fall back to key from response (legacy flow, less secure)
                val vaultSessionPublicKey = sessionInfo.get("vault_session_public_key")?.asString
                if (!vaultSessionPublicKey.isNullOrBlank()) {
                    Log.d(TAG, "Using vault public key from response (legacy)")
                    Base64.decode(vaultSessionPublicKey, Base64.NO_WRAP)
                } else {
                    null
                }
            }

            // Establish E2E session if we have vault's public key
            var sessionCrypto: SessionCrypto? = null
            if (vaultPublicKeyBytes != null && pendingKeyPair != null) {
                try {
                    val expiresAtMs = parseExpiresAt(sessionExpiresAt)

                    sessionCrypto = SessionCrypto.fromKeyExchange(
                        sessionId = sessionId,
                        appPrivateKey = pendingKeyPair!!.privateKey,
                        appPublicKey = pendingKeyPair!!.publicKey,
                        vaultPublicKey = vaultPublicKeyBytes,
                        expiresAt = expiresAtMs
                    )

                    val attestationStatus = if (verifiedAttestation != null) "verified" else "unverified"
                    Log.i(TAG, "E2E session established: $sessionId (attestation: $attestationStatus)")
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
                ttlSeconds = ttlSeconds,
                requiresImmediateRotation = requiresImmediateRotation,
                attestationVerified = verifiedAttestation != null,
                enclaveModuleId = verifiedAttestation?.moduleId
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
        pendingNonce?.fill(0)
        pendingNonce = null
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
 *
 * When attestation is verified, it proves:
 * - The enclave is running trusted VettID code (PCRs match)
 * - The session key is bound to that specific enclave instance
 * - No man-in-the-middle attack is possible (key from attestation)
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
    val ttlSeconds: Long,

    /** Whether credentials must be rotated immediately (short-lived bootstrap creds) */
    val requiresImmediateRotation: Boolean = false,

    /** Whether Nitro Enclave attestation was verified */
    val attestationVerified: Boolean = false,

    /** Enclave module ID (if attestation was verified) */
    val enclaveModuleId: String? = null
)
