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
    private val pcrConfigManager: PcrConfigManager
) {
    companion object {
        private const val TAG = "NitroEnrollmentClient"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val ATTESTATION_TIMEOUT_MS = 60_000L
        private const val NONCE_SIZE = 32
    }

    private val gson = Gson()
    private var natsClient: AndroidNatsClient? = null
    private var ownerSpace: String? = null

    // Store verified attestation data during enrollment
    private var verifiedAttestation: VerifiedAttestation? = null
    private var attestationNonce: ByteArray? = null

    /**
     * Connect to NATS with bootstrap credentials from enrollment finalize.
     */
    suspend fun connect(
        natsEndpoint: String,
        credentials: String
    ): Result<Unit> {
        Log.i(TAG, "Connecting to NATS at $natsEndpoint")

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
                                } else null

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
     * @param pin 6-digit PIN entered by user
     * @return Result indicating success or failure
     */
    suspend fun setupPin(pin: String): Result<PinSetupResponse> {
        val client = natsClient
            ?: return Result.failure(NatsException("Not connected to NATS"))
        val space = ownerSpace
            ?: return Result.failure(NatsException("Owner space not set"))
        val attestation = verifiedAttestation
            ?: return Result.failure(NatsException("Attestation not completed"))

        if (pin.length != 6 || !pin.all { it.isDigit() }) {
            return Result.failure(NatsException("PIN must be exactly 6 digits"))
        }

        Log.d(TAG, "Setting up PIN...")

        // Encrypt PIN to the attested enclave public key
        val enclavePublicKey = attestation.enclavePublicKeyBase64()
            ?: return Result.failure(NatsException("No enclave public key in attestation"))

        val encryptedPin = cryptoManager.encryptToPublicKey(
            plaintext = pin.toByteArray(Charsets.UTF_8),
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
     * Phase 6: Send encrypted password hash to vault-manager.
     *
     * @param password User's chosen password
     * @param passwordSalt Salt for Argon2id hashing
     * @param utkPublicKey Public key from UTK to encrypt password hash
     * @param utkKeyId Key ID of the UTK used
     * @return CredentialResponse containing encrypted credential and new UTKs
     */
    suspend fun createCredential(
        password: String,
        passwordSalt: ByteArray,
        utkPublicKey: String,
        utkKeyId: String
    ): Result<CredentialResponse> {
        val client = natsClient
            ?: return Result.failure(NatsException("Not connected to NATS"))
        val space = ownerSpace
            ?: return Result.failure(NatsException("Owner space not set"))

        Log.d(TAG, "Creating credential...")

        // Hash password with Argon2id and encrypt with UTK
        val encryptedPassword = cryptoManager.encryptPasswordForServer(
            password = password,
            salt = passwordSalt,
            utkPublicKeyBase64 = utkPublicKey
        )

        val requestId = UUID.randomUUID().toString()
        val request = JsonObject().apply {
            addProperty("id", requestId)
            addProperty("type", "credential.create")
            add("payload", JsonObject().apply {
                addProperty("encrypted_password_hash", encryptedPassword.encryptedPasswordHash)
                addProperty("ephemeral_public_key", encryptedPassword.ephemeralPublicKey)
                addProperty("nonce", encryptedPassword.nonce)
                addProperty("key_id", utkKeyId)
                addProperty("salt", Base64.encodeToString(passwordSalt, Base64.NO_WRAP))
            })
            addProperty("timestamp", java.time.Instant.now().toString())
        }

        val topic = "$space.forVault.credential"
        val responseTopic = "$space.forApp.credential.response"

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
     * Disconnect from NATS.
     */
    suspend fun disconnect() {
        try {
            natsClient?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from NATS", e)
        }
        natsClient = null
        ownerSpace = null
        verifiedAttestation = null
        attestationNonce = null
    }

    /**
     * Get the verified attestation (for storing enclave public key).
     */
    fun getVerifiedAttestation(): VerifiedAttestation? = verifiedAttestation

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

data class PinSetupResponse(
    val success: Boolean,
    val message: String?
)

data class VaultReadyResponse(
    val status: String,
    val utks: List<UtkInfo>,
    @SerializedName("password_salt") val passwordSalt: String?
)

data class UtkInfo(
    @SerializedName("key_id") val keyId: String,
    @SerializedName("public_key") val publicKey: String
)

data class CredentialResponse(
    val success: Boolean,
    @SerializedName("encrypted_credential") val encryptedCredential: String,
    @SerializedName("credential_guid") val credentialGuid: String,
    @SerializedName("new_utks") val newUtks: List<UtkInfo>,
    @SerializedName("user_guid") val userGuid: String?
)
