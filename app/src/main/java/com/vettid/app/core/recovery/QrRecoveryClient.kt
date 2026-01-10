package com.vettid.app.core.recovery

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.nats.AndroidNatsClient
import com.vettid.app.core.nats.NatsException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Client for handling QR code-based credential recovery via NATS.
 *
 * Flow:
 * 1. User scans QR code from Account Portal (after 24h delay)
 * 2. App connects to vault using temporary credentials from QR
 * 3. App sends recovery token to claim the credential
 * 4. Vault verifies token and returns new full credentials
 */
@Singleton
class QrRecoveryClient @Inject constructor() {
    private val gson = Gson()
    private var tempNatsClient: AndroidNatsClient? = null

    companion object {
        private const val TAG = "QrRecoveryClient"
        private const val RECOVERY_TIMEOUT_MS = 30_000L
    }

    /**
     * Exchange recovery token for new credentials.
     *
     * @param recoveryQr Parsed QR code content
     * @param deviceId Device identifier for the new credential
     * @param appVersion App version string
     * @return Result containing new credentials or error
     */
    suspend fun exchangeRecoveryToken(
        recoveryQr: RecoveryQrCode,
        deviceId: String,
        appVersion: String = "1.0.0"
    ): Result<RecoveryExchangeResult> {
        Log.i(TAG, "Starting QR recovery exchange to ${recoveryQr.getNatsEndpoint()}")

        // 1. Parse NATS credentials from QR
        val credentials = parseCredentialFile(recoveryQr.credentials)
            ?: return Result.failure(NatsException("Failed to parse recovery credentials"))

        // 2. Connect with temporary credentials
        val client = AndroidNatsClient()
        tempNatsClient = client

        val connectResult = client.connect(
            endpoint = recoveryQr.getNatsEndpoint(),
            jwt = credentials.first,
            seed = credentials.second
        )

        if (connectResult.isFailure) {
            cleanup()
            return Result.failure(
                NatsException("Failed to connect: ${connectResult.exceptionOrNull()?.message}")
            )
        }

        Log.i(TAG, "Connected with recovery credentials")

        try {
            // 3. Build recovery claim request
            val requestId = UUID.randomUUID().toString()
            val request = JsonObject().apply {
                addProperty("token", recoveryQr.token)
                addProperty("nonce", recoveryQr.nonce)
                addProperty("device_id", deviceId)
                addProperty("device_type", "android")
                addProperty("app_version", appVersion)
            }

            val message = JsonObject().apply {
                addProperty("id", requestId)
                addProperty("type", "recovery.claim")
                add("payload", request)
                addProperty("timestamp", java.time.Instant.now().toString())
            }

            Log.d(TAG, "Sending recovery claim request: $requestId")

            // 4. Subscribe to response topic and wait for response
            val response = withTimeout(RECOVERY_TIMEOUT_MS) {
                suspendCancellableCoroutine<Result<RecoveryExchangeResult>> { continuation ->
                    var subscription: String? = null

                    // Subscribe to response topic
                    kotlinx.coroutines.runBlocking {
                        val subResult = client.subscribe(recoveryQr.getResponseTopic()) { responseMessage ->
                            try {
                                Log.d(TAG, "Received response on ${recoveryQr.getResponseTopic()}")
                                val responseJson = gson.fromJson(
                                    responseMessage.dataString,
                                    JsonObject::class.java
                                )

                                // Check if this is our response
                                val eventId = responseJson.get("event_id")?.asString
                                    ?: responseJson.get("id")?.asString

                                if (eventId == requestId || eventId == null) {
                                    // Parse result
                                    val resultJson = responseJson.getAsJsonObject("result")
                                        ?: responseJson

                                    val exchangeResult = RecoveryExchangeResult.fromJson(resultJson)

                                    if (continuation.isActive) {
                                        continuation.resume(Result.success(exchangeResult))
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse recovery response", e)
                                if (continuation.isActive) {
                                    continuation.resume(
                                        Result.failure(NatsException("Failed to parse response: ${e.message}"))
                                    )
                                }
                            }
                        }

                        subResult.onSuccess { sid ->
                            subscription = sid
                            Log.d(TAG, "Subscribed to ${recoveryQr.getResponseTopic()}")
                        }.onFailure { e ->
                            if (continuation.isActive) {
                                continuation.resume(
                                    Result.failure(NatsException("Failed to subscribe: ${e.message}"))
                                )
                            }
                        }
                    }

                    // Publish recovery claim request
                    kotlinx.coroutines.runBlocking {
                        val publishResult = client.publish(
                            recoveryQr.getRecoveryTopic(),
                            gson.toJson(message).toByteArray(Charsets.UTF_8)
                        )

                        if (publishResult.isFailure && continuation.isActive) {
                            continuation.resume(
                                Result.failure(
                                    NatsException("Failed to publish: ${publishResult.exceptionOrNull()?.message}")
                                )
                            )
                        } else if (publishResult.isSuccess) {
                            Log.d(TAG, "Published recovery claim to ${recoveryQr.getRecoveryTopic()}")
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
            Log.e(TAG, "Recovery exchange timed out")
            return Result.failure(NatsException("Recovery exchange timed out"))
        } catch (e: Exception) {
            Log.e(TAG, "Recovery exchange failed", e)
            return Result.failure(NatsException("Recovery exchange failed: ${e.message}"))
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
