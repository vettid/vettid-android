package com.vettid.app.core.nats

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.network.TransactionKeyInfo
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Client for authenticating via NATS after enrollment completes.
 *
 * This client verifies that the newly enrolled credential works by:
 * 1. Connecting to NATS with the credentials stored during enrollment
 * 2. Publishing authenticate request to {OwnerSpace}.forVault.app.authenticate
 * 3. Waiting for response confirming the credential is valid
 *
 * This is the first step in the post-enrollment flow to ensure the user's
 * credential was properly created and can authenticate with the vault.
 */
@Singleton
class PostEnrollmentAuthClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val credentialStore: CredentialStore
) {
    private val gson = Gson()
    private var natsClient: AndroidNatsClient? = null

    companion object {
        private const val TAG = "PostEnrollAuthClient"
        private const val AUTH_TIMEOUT_MS = 30_000L
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Result of post-enrollment authentication.
     */
    data class PostEnrollmentAuthResult(
        val success: Boolean,
        val userGuid: String,
        val message: String? = null,
        val natsCredentials: String? = null,
        val ownerSpace: String? = null,
        val messageSpace: String? = null,
        val credentialVersion: Int? = null
    )

    /**
     * Authenticate with the vault after enrollment.
     *
     * This verifies the credential created during enrollment works correctly.
     * Uses the NATS credentials and encrypted credential blob stored during enrollment.
     *
     * @param password User's password (will be hashed with Argon2id)
     * @return Result containing authentication response
     */
    suspend fun authenticate(password: String): Result<PostEnrollmentAuthResult> {
        Log.i(TAG, "Starting post-enrollment authentication")

        // 1. Get stored credentials from enrollment
        val natsEndpoint = credentialStore.getNatsEndpoint()
            ?: return Result.failure(NatsException("No NATS endpoint stored"))

        val natsCredentialsFile = credentialStore.getNatsCredentials()
            ?: return Result.failure(NatsException("No NATS credentials stored"))

        val ownerSpace = credentialStore.getNatsOwnerSpace()
            ?: return Result.failure(NatsException("No owner space stored"))

        val encryptedCredential = credentialStore.getEncryptedBlob()
            ?: return Result.failure(NatsException("No encrypted credential stored"))

        val passwordSaltBytes = credentialStore.getPasswordSaltBytes()
            ?: return Result.failure(NatsException("No password salt stored"))

        // Get UTK for password encryption
        val utkPool = credentialStore.getUtkPool()
        if (utkPool.isEmpty()) {
            return Result.failure(NatsException("No transaction keys available"))
        }
        val utk = utkPool.first()

        // 2. Parse NATS credentials
        val credentials = credentialStore.parseNatsCredentialFile(natsCredentialsFile)
            ?: return Result.failure(NatsException("Failed to parse NATS credentials"))

        // 3. Connect to NATS
        val client = AndroidNatsClient()
        natsClient = client

        val connectResult = client.connect(
            endpoint = natsEndpoint,
            jwt = credentials.first,
            seed = credentials.second
        )

        if (connectResult.isFailure) {
            cleanup()
            return Result.failure(NatsException("Failed to connect to NATS: ${connectResult.exceptionOrNull()?.message}"))
        }

        Log.i(TAG, "Connected to NATS at $natsEndpoint")

        try {
            // 4. Encrypt password with UTK
            val encryptedResult = cryptoManager.encryptPasswordForServer(
                password = password,
                salt = passwordSaltBytes,
                utkPublicKeyBase64 = utk.publicKey
            )

            // 5. Build authenticate request
            val requestId = UUID.randomUUID().toString()

            val request = JsonObject().apply {
                addProperty("device_id", getDeviceId())
                addProperty("device_type", "android")
                addProperty("app_version", getAppVersion())
                addProperty("encrypted_credential", encryptedCredential)
                addProperty("key_id", utk.keyId)
                addProperty("encrypted_password_hash", encryptedResult.encryptedPasswordHash)
                addProperty("ephemeral_public_key", encryptedResult.ephemeralPublicKey)
                addProperty("nonce", encryptedResult.nonce)
            }

            val message = JsonObject().apply {
                addProperty("id", requestId)
                addProperty("type", "app.authenticate")
                add("payload", request)
                addProperty("timestamp", java.time.Instant.now().toString())
            }

            Log.d(TAG, "Sending authenticate request: $requestId")

            // 6. Determine topics
            val authTopic = "$ownerSpace.forVault.app.authenticate"
            val responseTopic = "$ownerSpace.forApp.>"

            // 7. Subscribe and publish
            val response = withTimeout(AUTH_TIMEOUT_MS) {
                suspendCancellableCoroutine<Result<PostEnrollmentAuthResult>> { continuation ->
                    var subscription: String? = null

                    // Subscribe to response topic
                    kotlinx.coroutines.runBlocking {
                        val subResult = client.subscribe(responseTopic) { responseMessage ->
                            try {
                                Log.d(TAG, "Received response on ${responseMessage.subject}")
                                val responseJson = gson.fromJson(responseMessage.dataString, JsonObject::class.java)

                                // Check if this is our response
                                val eventId = responseJson.get("event_id")?.asString
                                    ?: responseJson.get("id")?.asString

                                if (eventId == requestId || eventId == null) {
                                    val result = parseAuthResponse(responseJson)
                                    if (continuation.isActive) {
                                        continuation.resume(result)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse response", e)
                                if (continuation.isActive) {
                                    continuation.resume(Result.failure(NatsException("Failed to parse response: ${e.message}")))
                                }
                            }
                        }

                        subResult.onSuccess { sid ->
                            subscription = sid
                            Log.d(TAG, "Subscribed to $responseTopic")
                        }.onFailure { e ->
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(NatsException("Failed to subscribe: ${e.message}")))
                            }
                        }
                    }

                    // Publish authenticate request
                    kotlinx.coroutines.runBlocking {
                        val publishResult = client.publish(
                            authTopic,
                            gson.toJson(message).toByteArray(Charsets.UTF_8)
                        )

                        if (publishResult.isFailure && continuation.isActive) {
                            continuation.resume(Result.failure(NatsException("Failed to publish: ${publishResult.exceptionOrNull()?.message}")))
                        } else if (publishResult.isSuccess) {
                            Log.d(TAG, "Published authenticate request to $authTopic")
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

            // 8. Mark UTK as used
            credentialStore.removeUtk(utk.keyId)
            Log.d(TAG, "Removed used UTK: ${utk.keyId}")

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
     * Parse authentication response from vault.
     */
    private fun parseAuthResponse(json: JsonObject): Result<PostEnrollmentAuthResult> {
        val success = json.get("success")?.asBoolean ?: false
        val message = json.get("message")?.asString

        if (!success) {
            val error = json.get("error")?.asString ?: message ?: "Authentication failed"
            return Result.failure(NatsException(error))
        }

        // Extract result payload
        val result = json.getAsJsonObject("result") ?: json

        // Extract and store any new UTKs from the response (automatic replenishment)
        extractAndStoreUtks(json)

        return Result.success(PostEnrollmentAuthResult(
            success = true,
            userGuid = result.get("user_guid")?.asString
                ?: credentialStore.getUserGuid()
                ?: "",
            message = message,
            natsCredentials = result.get("credentials")?.asString,
            ownerSpace = result.get("owner_space")?.asString,
            messageSpace = result.get("message_space")?.asString,
            credentialVersion = result.get("credential_version")?.asInt
        ))
    }

    /**
     * Extract and store any new UTKs from vault response.
     * The vault automatically includes replacement UTKs when one is consumed.
     * Format: ["keyId:base64PublicKey", ...]
     */
    private fun extractAndStoreUtks(json: JsonObject) {
        try {
            val newUtksArray = json.getAsJsonArray("new_utks") ?: return

            if (newUtksArray.size() == 0) return

            Log.d(TAG, "Received ${newUtksArray.size()} new UTKs from auth response")

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
            Log.v(TAG, "No new_utks in response")
        }
    }

    /**
     * Check if the client is currently connected.
     */
    val isConnected: Boolean
        get() = natsClient?.isConnected == true

    /**
     * Disconnect from NATS.
     */
    suspend fun disconnect() {
        cleanup()
    }

    private suspend fun cleanup() {
        try {
            natsClient?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        natsClient = null
    }
}
