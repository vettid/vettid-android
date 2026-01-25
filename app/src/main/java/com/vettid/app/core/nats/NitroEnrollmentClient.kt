package com.vettid.app.core.nats

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.vettid.app.core.attestation.ExpectedPcrs
import com.vettid.app.core.attestation.NitroAttestationVerifier
import com.vettid.app.core.attestation.PcrConfigManager
import com.vettid.app.core.attestation.VerifiedAttestation
import com.vettid.app.core.crypto.CryptoManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Client for NATS-based Nitro Enclave enrollment operations.
 *
 * Handles communication with the enclave supervisor and vault-manager during enrollment:
 * - Phase 3: Attestation request with nonce verification
 * - Phase 4: PIN setup (encrypted to attested enclave public key)
 * - Phase 5: Receive vault ready + UTKs
 * - Phase 6: Send encrypted password, receive credential
 * - Phase 7: Verify enrollment with test operation
 */
@Singleton
class NitroEnrollmentClient @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val nitroAttestationVerifier: NitroAttestationVerifier,
    private val pcrConfigManager: PcrConfigManager,
    private val replayProtection: NatsReplayProtection
) {
    companion object {
        private const val TAG = "NitroEnrollmentClient"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val ATTESTATION_TIMEOUT_MS = 60_000L
        private const val NONCE_SIZE = 32
    }

    private val gson = Gson()
    private var natsClient: AndroidNatsClient? = null
    private var jetStreamClient: JetStreamNatsClient? = null
    private var ownerSpace: String? = null

    // Store verified attestation data during enrollment
    private var verifiedAttestation: VerifiedAttestation? = null
    private var attestationNonce: ByteArray? = null

    // Store credentials for JetStream connection
    private var currentEndpoint: String? = null
    private var currentCredentials: String? = null

    /**
     * Connect to NATS with bootstrap credentials from enrollment finalize.
     */
    suspend fun connect(
        natsEndpoint: String,
        credentials: String
    ): Result<Unit> {
        Log.i(TAG, "Connecting to NATS at $natsEndpoint")

        // Store for JetStream connection
        currentEndpoint = natsEndpoint
        currentCredentials = credentials

        val client = AndroidNatsClient()
        natsClient = client

        // Parse credentials (NATS creds file format)
        val parsedCreds = parseCredentials(credentials)
            ?: return Result.failure(NatsException("Failed to parse NATS credentials"))

        val result = client.connect(
            endpoint = natsEndpoint,
            jwt = parsedCreds.first,
            seed = parsedCreds.second
        )

        return result.map { Unit }
    }

    /**
     * Initialize JetStream client for reliable message delivery.
     * Reuses the existing AndroidNatsClient connection.
     */
    private fun ensureJetStreamInitialized(): Result<JetStreamNatsClient> {
        // Return existing client if initialized
        jetStreamClient?.let { client ->
            if (client.isConnected) return Result.success(client)
        }

        val client = natsClient
            ?: return Result.failure(NatsException("NATS client not connected"))

        Log.i(TAG, "Initializing JetStream with existing connection...")

        val jsClient = JetStreamNatsClient()
        jsClient.initialize(client)
        jetStreamClient = jsClient

        return Result.success(jsClient)
    }

    /**
     * Set the owner space for NATS topic routing.
     */
    fun setOwnerSpace(space: String) {
        ownerSpace = space
        Log.d(TAG, "Owner space set to: $space")
    }

    /**
     * Phase 3: Request attestation from the supervisor with nonce.
     *
     * @return VerifiedAttestation containing the enclave's ephemeral public key
     * @throws NatsException if attestation request fails
     * @throws AttestationVerificationException if attestation verification fails
     */
    suspend fun requestAttestation(): Result<VerifiedAttestation> {
        val client = natsClient
            ?: return Result.failure(NatsException("Not connected to NATS"))
        val space = ownerSpace
            ?: return Result.failure(NatsException("Owner space not set"))

        // Generate random nonce for replay protection
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)
        attestationNonce = nonce

        val nonceBase64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        Log.d(TAG, "Requesting attestation with nonce: ${nonceBase64.take(16)}...")

        val requestId = UUID.randomUUID().toString()
        val request = JsonObject().apply {
            addProperty("id", requestId)
            addProperty("type", "attestation.request")
            add("payload", JsonObject().apply {
                addProperty("nonce", nonceBase64)
            })
            addProperty("timestamp", java.time.Instant.now().toString())
        }

        val topic = "$space.forVault.attestation"
        val responseTopic = "$space.forApp.attestation.response"

        return try {
            withTimeout(ATTESTATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    var subscriptionId: String? = null

                    kotlinx.coroutines.runBlocking {
                        // Subscribe to response
                        val subResult = client.subscribe(responseTopic) { message ->
                            try {
                                Log.d(TAG, "Received attestation response (${message.dataString.length} chars): ${message.dataString.take(200)}")

                                // Parse as JsonObject first to debug structure
                                val jsonObject = gson.fromJson(message.dataString, JsonObject::class.java)
                                Log.d(TAG, "JSON keys: ${jsonObject.keySet()}")

                                // Try to extract attestation from various possible locations
                                val attestationDoc = when {
                                    jsonObject.has("attestation") && !jsonObject.get("attestation").isJsonNull -> {
                                        jsonObject.get("attestation").asString
                                    }
                                    jsonObject.has("payload") && jsonObject.get("payload").isJsonObject -> {
                                        val payload = jsonObject.getAsJsonObject("payload")
                                        Log.d(TAG, "Payload keys: ${payload.keySet()}")
                                        when {
                                            payload.has("attestation") -> payload.get("attestation").asString
                                            payload.has("attestation_document") -> payload.get("attestation_document").asString
                                            else -> null
                                        }
                                    }
                                    jsonObject.has("attestation_document") -> {
                                        jsonObject.get("attestation_document").asString
                                    }
                                    else -> null
                                }

                                val enclaveKey = when {
                                    jsonObject.has("public_key") && !jsonObject.get("public_key").isJsonNull -> {
                                        jsonObject.get("public_key").asString
                                    }
                                    jsonObject.has("enclave_public_key") && !jsonObject.get("enclave_public_key").isJsonNull -> {
                                        jsonObject.get("enclave_public_key").asString
                                    }
                                    jsonObject.has("payload") && jsonObject.get("payload").isJsonObject -> {
                                        val payload = jsonObject.getAsJsonObject("payload")
                                        when {
                                            payload.has("public_key") && !payload.get("public_key").isJsonNull -> {
                                                payload.get("public_key").asString
                                            }
                                            payload.has("enclave_public_key") && !payload.get("enclave_public_key").isJsonNull -> {
                                                payload.get("enclave_public_key").asString
                                            }
                                            else -> null
                                        }
                                    }
                                    else -> null
                                }

                                val eventId = if (jsonObject.has("event_id") && !jsonObject.get("event_id").isJsonNull) {
                                    jsonObject.get("event_id").asString
                                } else UUID.randomUUID().toString() // Generate ID for dedup if missing

                                val timestamp = if (jsonObject.has("timestamp") && !jsonObject.get("timestamp").isJsonNull) {
                                    jsonObject.get("timestamp").asString
                                } else null

                                // Validate message for replay attacks (#47)
                                val validationResult = replayProtection.validateMessage(
                                    eventId = eventId,
                                    timestamp = timestamp,
                                    sessionKey = "attestation"
                                )
                                if (!validationResult.isValid) {
                                    Log.w(TAG, "SECURITY: Attestation response rejected - $validationResult")
                                    return@subscribe
                                }

                                val response = AttestationResponse(
                                    eventId = eventId,
                                    attestationDocument = attestationDoc,
                                    enclavePublicKey = enclaveKey
                                )

                                Log.d(TAG, "Parsed response - eventId: ${response.eventId}, attestationDocument: ${response.attestationDocument?.take(50) ?: "NULL"}, enclavePublicKey: ${response.enclavePublicKey?.take(20) ?: "NULL"}")

                                if (response.eventId == requestId || response.eventId == null) {
                                    // Check for attestation document
                                    val attestationDoc = response.attestationDocument
                                    if (attestationDoc == null) {
                                        Log.e(TAG, "Attestation document is null in response")
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                Result.failure(NatsException("Attestation document missing from response"))
                                            )
                                        }
                                        return@subscribe
                                    }

                                    // Verify the attestation document
                                    val verified = verifyAttestationWithNonce(
                                        attestationDoc,
                                        nonce
                                    )

                                    if (verified != null) {
                                        verifiedAttestation = verified
                                        if (continuation.isActive) {
                                            continuation.resume(Result.success(verified))
                                        }
                                    } else {
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                Result.failure(NatsException("Attestation verification failed"))
                                            )
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing attestation response", e)
                                if (continuation.isActive) {
                                    continuation.resume(Result.failure(e))
                                }
                            }
                        }

                        subResult.onSuccess { sid ->
                            subscriptionId = sid
                        }.onFailure { e ->
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(e))
                            }
                        }

                        // Flush to ensure subscription is acknowledged before publishing
                        client.flush()
                        // Additional delay to ensure subscription propagates to server
                        kotlinx.coroutines.delay(500)

                        // Publish request
                        val pubResult = client.publish(
                            topic,
                            gson.toJson(request).toByteArray(Charsets.UTF_8)
                        )

                        if (pubResult.isFailure && continuation.isActive) {
                            continuation.resume(Result.failure(pubResult.exceptionOrNull()!!))
                        }
                    }

                    continuation.invokeOnCancellation {
                        subscriptionId?.let { sid ->
                            kotlinx.coroutines.runBlocking { client.unsubscribe(sid) }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(NatsException("Attestation request timed out"))
        }
    }

    /**
     * Verify attestation document with nonce check.
     */
    private fun verifyAttestationWithNonce(
        attestationDocBase64: String,
        expectedNonce: ByteArray
    ): VerifiedAttestation? {
        return try {
            // Get cached PCRs
            val expectedPcrs = pcrConfigManager.getCurrentPcrs()

            // Verify the attestation document
            val verified = nitroAttestationVerifier.verify(
                attestationDocBase64 = attestationDocBase64,
                expectedPcrs = expectedPcrs,
                expectedNonce = expectedNonce
            )

            Log.i(TAG, "Attestation verified. Module: ${verified.moduleId}")
            verified
        } catch (e: Exception) {
            Log.e(TAG, "Attestation verification failed", e)
            null
        }
    }

    /**
     * Phase 4: Send encrypted PIN to the supervisor.
     *
     * Uses JetStream for reliable message delivery, solving the timing issue
     * where responses were published before subscriptions were ready.
     *
     * @param pin 6-digit PIN entered by user
     * @return Result indicating success or failure
     */
    suspend fun setupPin(pin: String): Result<PinSetupResponse> {
        val space = ownerSpace
            ?: return Result.failure(NatsException("Owner space not set"))
        val attestation = verifiedAttestation
            ?: return Result.failure(NatsException("Attestation not completed"))

        if (pin.length != 6 || !pin.all { it.isDigit() }) {
            return Result.failure(NatsException("PIN must be exactly 6 digits"))
        }

        Log.d(TAG, "Setting up PIN via JetStream...")

        // Encrypt PIN to the attested enclave public key
        val enclavePublicKey = attestation.enclavePublicKeyBase64()
            ?: return Result.failure(NatsException("No enclave public key in attestation"))

        // Encrypt PIN as JSON object per enclave protocol
        val pinPayload = """{"pin": "$pin"}"""
        val encryptedPin = cryptoManager.encryptToPublicKey(
            plaintext = pinPayload.toByteArray(Charsets.UTF_8),
            publicKeyBase64 = enclavePublicKey
        )

        val requestId = UUID.randomUUID().toString()
        val request = JsonObject().apply {
            addProperty("id", requestId)
            addProperty("type", "pin.setup")
            add("payload", JsonObject().apply {
                addProperty("encrypted_pin", encryptedPin.ciphertext)
                addProperty("ephemeral_public_key", encryptedPin.ephemeralPublicKey)
                addProperty("nonce", encryptedPin.nonce)
            })
            addProperty("timestamp", java.time.Instant.now().toString())
        }

        val topic = "$space.forVault.pin"
        val responseTopic = "$space.forApp.pin.response"

        // Try JetStream first for reliable delivery
        val jsResult = ensureJetStreamInitialized()
        if (jsResult.isSuccess) {
            val jsClient = jsResult.getOrThrow()
            Log.d(TAG, "Using JetStream for PIN setup")

            return try {
                val response = jsClient.requestWithJetStream(
                    requestSubject = topic,
                    responseSubject = responseTopic,
                    payload = gson.toJson(request).toByteArray(Charsets.UTF_8),
                    timeoutMs = 30_000
                )

                response.map { msg ->
                    Log.d(TAG, "PIN response received: ${msg.dataString.take(200)}")

                    // Parse and validate response
                    val jsonObject = gson.fromJson(msg.dataString, JsonObject::class.java)

                    val eventId = if (jsonObject.has("event_id") && !jsonObject.get("event_id").isJsonNull) {
                        jsonObject.get("event_id").asString
                    } else UUID.randomUUID().toString()

                    val timestamp = if (jsonObject.has("timestamp") && !jsonObject.get("timestamp").isJsonNull) {
                        jsonObject.get("timestamp").asString
                    } else null

                    // Validate message for replay attacks
                    val validationResult = replayProtection.validateMessage(
                        eventId = eventId,
                        timestamp = timestamp,
                        sessionKey = responseTopic
                    )
                    if (!validationResult.isValid) {
                        Log.w(TAG, "SECURITY: PIN response rejected - $validationResult")
                        throw NatsException("Response validation failed")
                    }

                    gson.fromJson(msg.dataString, PinSetupResponse::class.java)
                }
            } catch (e: Exception) {
                Log.e(TAG, "JetStream PIN setup failed: ${e.message}", e)
                Result.failure(e)
            }
        }

        // Fallback to regular NATS (should not happen in production)
        Log.w(TAG, "JetStream unavailable, falling back to regular NATS")
        val client = natsClient
            ?: return Result.failure(NatsException("Not connected to NATS"))

        return sendRequestAndWaitForResponse(client, topic, responseTopic, request, requestId)
    }

    /**
     * Phase 5: Wait for vault ready message with UTKs.
     *
     * @return VaultReadyResponse containing UTKs
     */
    suspend fun waitForVaultReady(): Result<VaultReadyResponse> {
        val client = natsClient
            ?: return Result.failure(NatsException("Not connected to NATS"))
        val space = ownerSpace
            ?: return Result.failure(NatsException("Owner space not set"))

        val responseTopic = "$space.forApp.vault.ready"

        Log.d(TAG, "Waiting for vault ready...")

        return try {
            withTimeout(ATTESTATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    var subscriptionId: String? = null

                    kotlinx.coroutines.runBlocking {
                        val subResult = client.subscribe(responseTopic) { message ->
                            try {
                                // Parse JSON to validate message (#47)
                                val jsonObject = gson.fromJson(message.dataString, JsonObject::class.java)

                                val eventId = if (jsonObject.has("event_id") && !jsonObject.get("event_id").isJsonNull) {
                                    jsonObject.get("event_id").asString
                                } else UUID.randomUUID().toString()

                                val timestamp = if (jsonObject.has("timestamp") && !jsonObject.get("timestamp").isJsonNull) {
                                    jsonObject.get("timestamp").asString
                                } else null

                                // Validate message for replay attacks
                                val validationResult = replayProtection.validateMessage(
                                    eventId = eventId,
                                    timestamp = timestamp,
                                    sessionKey = responseTopic
                                )
                                if (!validationResult.isValid) {
                                    Log.w(TAG, "SECURITY: Vault ready response rejected - $validationResult")
                                    return@subscribe
                                }

                                val response = gson.fromJson(
                                    message.dataString,
                                    VaultReadyResponse::class.java
                                )

                                Log.i(TAG, "Vault ready! Received ${response.utks.size} UTKs")

                                if (continuation.isActive) {
                                    continuation.resume(Result.success(response))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing vault ready response", e)
                                if (continuation.isActive) {
                                    continuation.resume(Result.failure(e))
                                }
                            }
                        }

                        subResult.onSuccess { sid ->
                            subscriptionId = sid
                            Log.d(TAG, "Subscribed to vault ready topic")
                        }.onFailure { e ->
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(e))
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        subscriptionId?.let { sid ->
                            kotlinx.coroutines.runBlocking { client.unsubscribe(sid) }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(NatsException("Waiting for vault ready timed out"))
        }
    }

    /**
     * Phase 2 (Nitro): Create Protean Credential with password hash.
     *
     * Uses JetStream for reliable delivery.
     *
     * Crypto parameters (must match vault-manager):
     * - Password hash: PHC format ($argon2id$v=19$m=65536,t=3,p=4$<salt>$<hash>)
     * - Encryption: XChaCha20-Poly1305 (24-byte nonce)
     * - HKDF: Domain "vettid-utk-v1" as salt, no info
     * - Blob format: ephemeral_pubkey (32) + nonce (24) + ciphertext
     *
     * @param password User's chosen credential password (NOT the PIN)
     * @param passwordSalt Salt for Argon2id hashing
     * @param utk UTK to encrypt password hash with
     * @return CredentialResponse containing encrypted credential and new UTKs
     */
    suspend fun createCredential(
        password: String,
        passwordSalt: ByteArray,
        utk: UtkInfo
    ): Result<CredentialResponse> {
        val space = ownerSpace
            ?: return Result.failure(NatsException("Owner space not set"))

        Log.d(TAG, "Creating credential via JetStream...")

        // 1. Generate PHC format password hash
        val phcHash = cryptoManager.hashPasswordPHC(password, passwordSalt)
        Log.d(TAG, "Generated PHC hash: ${phcHash.take(50)}...")

        // 2. Create JSON payload with PHC hash
        val payloadJson = JsonObject().apply {
            addProperty("password_hash", phcHash)
        }

        // 3. Encrypt with XChaCha20-Poly1305 using UTK
        // Uses domain "vettid-utk-v1" as HKDF salt, no info
        val encryptedBlob = cryptoManager.encryptToUTK(
            plaintext = gson.toJson(payloadJson).toByteArray(Charsets.UTF_8),
            utkPublicKeyBase64 = utk.publicKey
        )

        val encryptedPayload = Base64.encodeToString(encryptedBlob, Base64.NO_WRAP)
        Log.d(TAG, "Encrypted payload size: ${encryptedBlob.size} bytes (32 pubkey + 24 nonce + ciphertext)")

        // 4. Build request with wrapper format
        val requestId = UUID.randomUUID().toString()
        val request = JsonObject().apply {
            addProperty("id", requestId)
            addProperty("type", "credential.create")
            addProperty("utk_id", utk.utkId)
            addProperty("encrypted_payload", encryptedPayload)
            addProperty("timestamp", java.time.Instant.now().toString())
        }

        val topic = "$space.forVault.credential.create"
        val responseTopic = "$space.forApp.credential.create.response"

        // Use JetStream for reliable delivery
        val jsResult = ensureJetStreamInitialized()
        if (jsResult.isSuccess) {
            val jsClient = jsResult.getOrThrow()
            Log.d(TAG, "Using JetStream for credential create")

            return try {
                val response = jsClient.requestWithJetStream(
                    requestSubject = topic,
                    responseSubject = responseTopic,
                    payload = gson.toJson(request).toByteArray(Charsets.UTF_8),
                    timeoutMs = 30_000
                )

                response.map { msg ->
                    Log.d(TAG, "Credential response received: ${msg.dataString.take(200)}")

                    // Parse and validate response
                    val jsonObject = gson.fromJson(msg.dataString, JsonObject::class.java)

                    val eventId = if (jsonObject.has("event_id") && !jsonObject.get("event_id").isJsonNull) {
                        jsonObject.get("event_id").asString
                    } else UUID.randomUUID().toString()

                    val timestamp = if (jsonObject.has("timestamp") && !jsonObject.get("timestamp").isJsonNull) {
                        jsonObject.get("timestamp").asString
                    } else null

                    // Validate message for replay attacks
                    val validationResult = replayProtection.validateMessage(
                        eventId = eventId,
                        timestamp = timestamp,
                        sessionKey = responseTopic
                    )
                    if (!validationResult.isValid) {
                        Log.w(TAG, "SECURITY: Credential response rejected - $validationResult")
                        throw NatsException("Response validation failed")
                    }

                    gson.fromJson(msg.dataString, CredentialResponse::class.java)
                }
            } catch (e: Exception) {
                Log.e(TAG, "JetStream credential create failed: ${e.message}", e)
                Result.failure(e)
            }
        }

        // Fallback to regular NATS
        Log.w(TAG, "JetStream unavailable, falling back to regular NATS")
        val client = natsClient
            ?: return Result.failure(NatsException("Not connected to NATS"))

        return sendRequestAndWaitForResponse(client, topic, responseTopic, request, requestId)
    }

    /**
     * Phase 7: Verify enrollment with a test operation.
     *
     * @return Result indicating success
     */
    suspend fun verifyEnrollment(): Result<Unit> {
        val client = natsClient
            ?: return Result.failure(NatsException("Not connected to NATS"))
        val space = ownerSpace
            ?: return Result.failure(NatsException("Owner space not set"))

        Log.d(TAG, "Verifying enrollment...")

        val requestId = UUID.randomUUID().toString()
        val request = JsonObject().apply {
            addProperty("id", requestId)
            addProperty("type", "vault.get_info")
            add("payload", JsonObject())
            addProperty("timestamp", java.time.Instant.now().toString())
        }

        val topic = "$space.forVault.info"
        val responseTopic = "$space.forApp.info.response"

        return try {
            val result: Result<JsonObject> = sendRequestAndWaitForResponse(
                client, topic, responseTopic, request, requestId
            )

            result.map {
                Log.i(TAG, "Enrollment verified successfully")
                Unit
            }
        } catch (e: Exception) {
            Result.failure(NatsException("Enrollment verification failed: ${e.message}"))
        }
    }

    /**
     * Send a generic vault operation via NATS (Issue #50: Voting).
     *
     * @param operation Map containing the operation request
     * @return Response map from the vault, or null on failure
     */
    suspend fun sendVaultOperation(operation: Map<String, Any>): Map<String, Any>? {
        val client = natsClient
            ?: run {
                Log.e(TAG, "sendVaultOperation: Not connected to NATS")
                return null
            }
        val space = ownerSpace
            ?: run {
                Log.e(TAG, "sendVaultOperation: Owner space not set")
                return null
            }

        val operationType = operation["operation"] as? String ?: "unknown"
        Log.d(TAG, "Sending vault operation: $operationType")

        val requestId = UUID.randomUUID().toString()
        val request = JsonObject().apply {
            addProperty("id", requestId)
            addProperty("type", "vault.$operationType")
            operation.forEach { (key, value) ->
                when (value) {
                    is String -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                    else -> addProperty(key, gson.toJson(value))
                }
            }
            addProperty("timestamp", java.time.Instant.now().toString())
        }

        val topic = "$space.forVault.operation"
        val responseTopic = "$space.forApp.operation.response"

        return try {
            val result: Result<JsonObject> = sendRequestAndWaitForResponse(
                client, topic, responseTopic, request, requestId
            )

            result.getOrNull()?.let { jsonObject ->
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(jsonObject, Map::class.java) as? Map<String, Any>
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vault operation failed: ${e.message}", e)
            null
        }
    }

    /**
     * Disconnect from NATS.
     */
    suspend fun disconnect() {
        try {
            natsClient?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from NATS", e)
        }
        try {
            jetStreamClient?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting JetStream", e)
        }
        natsClient = null
        jetStreamClient = null
        ownerSpace = null
        verifiedAttestation = null
        attestationNonce = null
        currentEndpoint = null
        currentCredentials = null
        // Clear replay protection state (#47)
        replayProtection.reset()
    }

    /**
     * Get the verified attestation (for storing enclave public key).
     */
    fun getVerifiedAttestation(): VerifiedAttestation? = verifiedAttestation

    /**
     * Get the current NATS endpoint.
     */
    fun getNatsEndpoint(): String? = currentEndpoint

    /**
     * Get the current NATS credentials (for storing after enrollment).
     */
    fun getNatsCredentials(): String? = currentCredentials

    /**
     * Get the owner space (for storing after enrollment).
     */
    fun getOwnerSpace(): String? = ownerSpace

    // Helper to send request and wait for response
    private suspend inline fun <reified T> sendRequestAndWaitForResponse(
        client: AndroidNatsClient,
        topic: String,
        responseTopic: String,
        request: JsonObject,
        requestId: String
    ): Result<T> {
        return try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    var subscriptionId: String? = null

                    kotlinx.coroutines.runBlocking {
                        val subResult = client.subscribe(responseTopic) { message ->
                            try {
                                // Parse JSON to validate message (#47)
                                val jsonObject = gson.fromJson(message.dataString, JsonObject::class.java)

                                val eventId = if (jsonObject.has("event_id") && !jsonObject.get("event_id").isJsonNull) {
                                    jsonObject.get("event_id").asString
                                } else UUID.randomUUID().toString()

                                val timestamp = if (jsonObject.has("timestamp") && !jsonObject.get("timestamp").isJsonNull) {
                                    jsonObject.get("timestamp").asString
                                } else null

                                // Validate message for replay attacks
                                val validationResult = replayProtection.validateMessage(
                                    eventId = eventId,
                                    timestamp = timestamp,
                                    sessionKey = responseTopic
                                )
                                if (!validationResult.isValid) {
                                    Log.w(TAG, "SECURITY: Response rejected - $validationResult")
                                    return@subscribe
                                }

                                val response = gson.fromJson(message.dataString, T::class.java)
                                if (continuation.isActive) {
                                    continuation.resume(Result.success(response))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing response", e)
                                if (continuation.isActive) {
                                    continuation.resume(Result.failure(e))
                                }
                            }
                        }

                        subResult.onSuccess { sid ->
                            subscriptionId = sid
                        }.onFailure { e ->
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(e))
                            }
                        }

                        // Flush to ensure subscription is acknowledged before publishing
                        client.flush()
                        // Additional delay to ensure subscription propagates to server
                        kotlinx.coroutines.delay(500)

                        val pubResult = client.publish(
                            topic,
                            gson.toJson(request).toByteArray(Charsets.UTF_8)
                        )

                        if (pubResult.isFailure && continuation.isActive) {
                            continuation.resume(Result.failure(pubResult.exceptionOrNull()!!))
                        }
                    }

                    continuation.invokeOnCancellation {
                        subscriptionId?.let { sid ->
                            kotlinx.coroutines.runBlocking { client.unsubscribe(sid) }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(NatsException("Request timed out"))
        }
    }

    /**
     * Parse NATS credentials file to extract JWT and seed.
     */
    private fun parseCredentials(credentialFile: String): Pair<String, String>? {
        return try {
            val jwtStart = credentialFile.indexOf("-----BEGIN NATS USER JWT-----")
            val jwtEnd = credentialFile.indexOf("-----END NATS USER JWT-----")
            if (jwtStart == -1 || jwtEnd == -1) return null

            val jwt = credentialFile.substring(
                jwtStart + "-----BEGIN NATS USER JWT-----".length,
                jwtEnd
            ).trim()

            val seedStart = credentialFile.indexOf("-----BEGIN USER NKEY SEED-----")
            val seedEnd = credentialFile.indexOf("-----END USER NKEY SEED-----")
            if (seedStart == -1 || seedEnd == -1) return null

            val seed = credentialFile.substring(
                seedStart + "-----BEGIN USER NKEY SEED-----".length,
                seedEnd
            ).trim()

            Pair(jwt, seed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse NATS credentials", e)
            null
        }
    }
}

// Response data classes

data class AttestationResponse(
    @SerializedName("event_id") val eventId: String?,
    @SerializedName("attestation") val attestationDocument: String?,
    @SerializedName("enclave_public_key") val enclavePublicKey: String?
)

/**
 * PIN setup response from Nitro enclave.
 * Returns vault_ready status with UTKs for the credential creation phase.
 */
data class PinSetupResponse(
    val status: String,
    val utks: List<UtkInfo>? = null,
    val message: String? = null
) {
    /** Returns true if vault is ready */
    val isSuccess: Boolean
        get() = status == "vault_ready"
}

data class VaultReadyResponse(
    val status: String,
    val utks: List<UtkInfo>,
    @SerializedName("password_salt") val passwordSalt: String?
)

data class UtkInfo(
    // Support both "id" (new spec) and "key_id" (legacy) formats
    @SerializedName("id") val id: String? = null,
    @SerializedName("key_id") val keyId: String? = null,
    @SerializedName("public_key") val publicKey: String
) {
    /** Get the UTK ID (supports both new and legacy formats) */
    val utkId: String
        get() = id ?: keyId ?: throw IllegalStateException("UTK has no ID")
}

/**
 * Credential creation response from Nitro enclave.
 * Returns the encrypted Protean Credential and fresh UTKs.
 */
data class CredentialResponse(
    val status: String,
    @SerializedName("encrypted_credential") val encryptedCredential: String? = null,
    @SerializedName("new_utks") val newUtks: List<UtkInfo>? = null,
    // Legacy fields for backwards compatibility
    val success: Boolean? = null,
    @SerializedName("credential_guid") val credentialGuid: String? = null,
    @SerializedName("user_guid") val userGuid: String? = null,
    val message: String? = null
) {
    /** Returns true if credential was created successfully */
    val isSuccess: Boolean
        get() = status == "created" || success == true
}
