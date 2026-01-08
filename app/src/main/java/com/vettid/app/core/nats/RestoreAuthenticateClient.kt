package com.vettid.app.core.nats

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.network.RestoreVaultBootstrap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Client for authenticating via NATS during credential restore flow.
 *
 * This client handles the special restore authentication flow:
 * 1. Connect to NATS with short-lived bootstrap credentials
 * 2. Publish authenticate request to {OwnerSpace}.forVault.app.authenticate
 * 3. Wait for response with full NATS credentials
 *
 * This is separate from OwnerSpaceClient because:
 * - Uses temporary bootstrap credentials with limited permissions
 * - Publishes to a specific auth topic (not the standard forVault.> pattern)
 * - Response contains new full credentials for subsequent connections
 */
@Singleton
class RestoreAuthenticateClient @Inject constructor(
    private val cryptoManager: CryptoManager
) {
    private val gson = Gson()
    private var tempNatsClient: AndroidNatsClient? = null

    companion object {
        private const val TAG = "RestoreAuthClient"
        private const val AUTH_TIMEOUT_MS = 30_000L
    }

    /**
     * Authenticate with the vault during credential restore.
     *
     * @param bootstrap Vault bootstrap info with NATS credentials and topics
     * @param encryptedCredential The encrypted credential blob from backup
     * @param keyId Key ID for the encrypted credential
     * @param password User's password (will be hashed with Argon2id)
     * @param passwordSalt Salt for password hashing
     * @param deviceId Device identifier
     * @param appVersion App version string
     * @return Result containing authenticate response with full NATS credentials
     */
    suspend fun authenticate(
        bootstrap: RestoreVaultBootstrap,
        encryptedCredential: String,
        keyId: String,
        password: String,
        passwordSalt: ByteArray,
        deviceId: String,
        appVersion: String = "1.0.0"
    ): Result<AppAuthenticateResponse> {
        Log.i(TAG, "Starting restore authentication to ${bootstrap.natsEndpoint}")

        // 1. Parse bootstrap credentials
        val credentials = parseCredentialFile(bootstrap.credentials)
            ?: return Result.failure(NatsException("Failed to parse bootstrap credentials"))

        // 2. Connect with bootstrap credentials
        val client = AndroidNatsClient()
        tempNatsClient = client

        val connectResult = client.connect(
            endpoint = bootstrap.natsEndpoint,
            jwt = credentials.first,
            seed = credentials.second
        )

        if (connectResult.isFailure) {
            cleanup()
            return Result.failure(NatsException("Failed to connect with bootstrap credentials: ${connectResult.exceptionOrNull()?.message}"))
        }

        Log.i(TAG, "Connected with bootstrap credentials")

        try {
            // 3. Hash password
            val passwordHash = cryptoManager.hashPassword(password, passwordSalt)
            val passwordHashBase64 = Base64.encodeToString(passwordHash, Base64.NO_WRAP)

            // Generate nonce for this request
            val nonce = cryptoManager.randomBytes(12)
            val nonceBase64 = Base64.encodeToString(nonce, Base64.NO_WRAP)

            // 4. Build authenticate request
            val requestId = UUID.randomUUID().toString()
            val request = JsonObject().apply {
                addProperty("device_id", deviceId)
                addProperty("device_type", "android")
                addProperty("app_version", appVersion)
                addProperty("encrypted_credential", encryptedCredential)
                addProperty("key_id", keyId)
                addProperty("password_hash", passwordHashBase64)
                addProperty("nonce", nonceBase64)
            }

            val message = JsonObject().apply {
                addProperty("id", requestId)
                addProperty("type", "app.authenticate")
                add("payload", request)
                addProperty("timestamp", java.time.Instant.now().toString())
            }

            Log.d(TAG, "Sending authenticate request: $requestId")

            // 5. Subscribe to response topic and wait for response
            val response = withTimeout(AUTH_TIMEOUT_MS) {
                suspendCancellableCoroutine<Result<AppAuthenticateResponse>> { continuation ->
                    var subscription: String? = null

                    // Subscribe to response topic
                    kotlinx.coroutines.runBlocking {
                        val subResult = client.subscribe(bootstrap.responseTopic) { responseMessage ->
                            try {
                                Log.d(TAG, "Received response on ${bootstrap.responseTopic}")
                                val responseJson = gson.fromJson(responseMessage.dataString, JsonObject::class.java)

                                // Check if this is our response (by event_id or id)
                                val eventId = responseJson.get("event_id")?.asString
                                    ?: responseJson.get("id")?.asString

                                if (eventId == requestId || eventId == null) {
                                    // Parse response
                                    val resultJson = responseJson.getAsJsonObject("result")
                                        ?: responseJson

                                    val authResponse = AppAuthenticateResponse.fromJson(resultJson)

                                    if (continuation.isActive) {
                                        continuation.resume(Result.success(authResponse))
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse authenticate response", e)
                                if (continuation.isActive) {
                                    continuation.resume(Result.failure(NatsException("Failed to parse response: ${e.message}")))
                                }
                            }
                        }

                        subResult.onSuccess { sid ->
                            subscription = sid
                            Log.d(TAG, "Subscribed to ${bootstrap.responseTopic}")
                        }.onFailure { e ->
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(NatsException("Failed to subscribe: ${e.message}")))
                            }
                        }
                    }

                    // Publish authenticate request
                    kotlinx.coroutines.runBlocking {
                        val publishResult = client.publish(
                            bootstrap.authTopic,
                            gson.toJson(message).toByteArray(Charsets.UTF_8)
                        )

                        if (publishResult.isFailure && continuation.isActive) {
                            continuation.resume(Result.failure(NatsException("Failed to publish: ${publishResult.exceptionOrNull()?.message}")))
                        } else if (publishResult.isSuccess) {
                            Log.d(TAG, "Published authenticate request to ${bootstrap.authTopic}")
                        }
                    }

                    continuation.invokeOnCancellation {
                        subscription?.let { sid ->
                            kotlinx.coroutines.runBlocking {
                                client.unsubscribe(sid)
                            }
                        }
                    }
                }
            }

            return response
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "Authentication timed out")
            return Result.failure(NatsException("Authentication timed out"))
        } catch (e: Exception) {
            Log.e(TAG, "Authentication failed", e)
            return Result.failure(NatsException("Authentication failed: ${e.message}"))
        } finally {
            cleanup()
        }
    }

    /**
     * Parse NATS credential file to extract JWT and seed.
     */
    private fun parseCredentialFile(credentialFile: String): Pair<String, String>? {
        try {
            // Extract JWT
            val jwtStart = credentialFile.indexOf("-----BEGIN NATS USER JWT-----")
            val jwtEnd = credentialFile.indexOf("-----END NATS USER JWT-----")
            if (jwtStart == -1 || jwtEnd == -1) return null

            val jwtContent = credentialFile.substring(
                jwtStart + "-----BEGIN NATS USER JWT-----".length,
                jwtEnd
            ).trim()

            // Extract NKEY seed
            val seedStart = credentialFile.indexOf("-----BEGIN USER NKEY SEED-----")
            val seedEnd = credentialFile.indexOf("-----END USER NKEY SEED-----")
            if (seedStart == -1 || seedEnd == -1) return null

            val seedContent = credentialFile.substring(
                seedStart + "-----BEGIN USER NKEY SEED-----".length,
                seedEnd
            ).trim()

            return Pair(jwtContent, seedContent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse NATS credential file", e)
            return null
        }
    }

    private fun cleanup() {
        try {
            tempNatsClient?.let { client ->
                kotlinx.coroutines.runBlocking {
                    client.disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        tempNatsClient = null
    }
}
