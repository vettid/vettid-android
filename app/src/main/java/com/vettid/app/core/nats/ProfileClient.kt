package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for profile operations via NATS.
 *
 * Handles:
 * - Fetching registration profile (system fields: firstName, lastName, email)
 * - Syncing profile updates to vault
 */
@Singleton
class ProfileClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager
) {
    private val gson = Gson()
    private var jetStreamClient: JetStreamNatsClient? = null

    companion object {
        private const val TAG = "ProfileClient"
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

    /**
     * Get the registration profile from the vault.
     * This contains the system fields (firstName, lastName, email) from registration.
     *
     * @return RegistrationProfile with user's registration data
     */
    suspend fun getRegistrationProfile(): Result<RegistrationProfile> {
        Log.d(TAG, "Fetching registration profile from vault")

        val ownerSpace = ownerSpaceClient.getOwnerSpace()
        if (ownerSpace == null) {
            Log.e(TAG, "OwnerSpace not set")
            return Result.failure(NatsException("OwnerSpace not set"))
        }

        val jsResult = ensureJetStreamInitialized()
        if (jsResult.isFailure) {
            Log.w(TAG, "JetStream not available: ${jsResult.exceptionOrNull()?.message}")
            return Result.failure(jsResult.exceptionOrNull() ?: NatsException("JetStream not available"))
        }

        val jsClient = jsResult.getOrThrow()
        val requestId = UUID.randomUUID().toString()

        val request = JsonObject().apply {
            addProperty("id", requestId)
            addProperty("type", "profile.get")
            addProperty("timestamp", java.time.Instant.now().toString())
        }

        val topic = "$ownerSpace.forVault.profile.get"
        val responseTopic = "$ownerSpace.forApp.profile.get.response"

        return try {
            val response = jsClient.requestWithJetStream(
                requestSubject = topic,
                responseSubject = responseTopic,
                payload = gson.toJson(request).toByteArray(Charsets.UTF_8),
                timeoutMs = REQUEST_TIMEOUT_MS
            )

            response.fold(
                onSuccess = { msg ->
                    Log.d(TAG, "Profile response received: ${msg.dataString.take(200)}")
                    val jsonResponse = gson.fromJson(msg.dataString, JsonObject::class.java)

                    // Check for error response
                    if (jsonResponse.has("error") && !jsonResponse.get("error").isJsonNull) {
                        val error = jsonResponse.get("error").asString
                        Log.w(TAG, "Profile fetch failed: $error")
                        Result.failure(NatsException(error))
                    } else {
                        // Parse profile from response
                        val profileObj = if (jsonResponse.has("profile")) {
                            jsonResponse.getAsJsonObject("profile")
                        } else if (jsonResponse.has("result")) {
                            jsonResponse.getAsJsonObject("result")
                        } else {
                            jsonResponse
                        }

                        val profile = RegistrationProfile(
                            firstName = profileObj.get("first_name")?.asString
                                ?: profileObj.get("firstName")?.asString ?: "",
                            lastName = profileObj.get("last_name")?.asString
                                ?: profileObj.get("lastName")?.asString ?: "",
                            email = profileObj.get("email")?.asString ?: ""
                        )

                        Log.d(TAG, "Parsed profile: $profile")
                        Result.success(profile)
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Profile request failed: ${e.message}", e)
                    Result.failure(e)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "JetStream profile request failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Sync profile data to the vault.
     *
     * @param data Map of profile fields to sync
     * @return Success or failure
     */
    suspend fun syncProfile(data: Map<String, Any?>): Result<Unit> {
        Log.d(TAG, "Syncing profile to vault")

        val ownerSpace = ownerSpaceClient.getOwnerSpace()
        if (ownerSpace == null) {
            Log.e(TAG, "OwnerSpace not set")
            return Result.failure(NatsException("OwnerSpace not set"))
        }

        val jsResult = ensureJetStreamInitialized()
        if (jsResult.isFailure) {
            return Result.failure(jsResult.exceptionOrNull() ?: NatsException("JetStream not available"))
        }

        val jsClient = jsResult.getOrThrow()
        val requestId = UUID.randomUUID().toString()

        val request = JsonObject().apply {
            addProperty("id", requestId)
            addProperty("type", "profile.update")
            addProperty("timestamp", java.time.Instant.now().toString())
            add("data", gson.toJsonTree(data))
        }

        val topic = "$ownerSpace.forVault.profile.update"
        val responseTopic = "$ownerSpace.forApp.profile.update.response"

        return try {
            val response = jsClient.requestWithJetStream(
                requestSubject = topic,
                responseSubject = responseTopic,
                payload = gson.toJson(request).toByteArray(Charsets.UTF_8),
                timeoutMs = REQUEST_TIMEOUT_MS
            )

            response.fold(
                onSuccess = { msg ->
                    Log.d(TAG, "Profile sync response: ${msg.dataString.take(200)}")
                    val jsonResponse = gson.fromJson(msg.dataString, JsonObject::class.java)

                    if (jsonResponse.has("error") && !jsonResponse.get("error").isJsonNull) {
                        val error = jsonResponse.get("error").asString
                        Result.failure(NatsException(error))
                    } else {
                        Result.success(Unit)
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Profile sync failed: ${e.message}", e)
                    Result.failure(e)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "JetStream profile sync failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}

/**
 * Registration profile containing system fields.
 */
data class RegistrationProfile(
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
    val email: String
)
