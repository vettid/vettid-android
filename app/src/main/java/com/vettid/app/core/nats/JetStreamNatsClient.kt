package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.resume

/**
 * JetStream operations using the existing AndroidNatsClient.
 *
 * Implements JetStream API calls manually via JSON messages to $JS.API.* subjects,
 * avoiding the jnats library's TLS connection issues with ACM certificates.
 *
 * This provides reliable message delivery through JetStream consumers,
 * solving the timing issue where responses are published before subscriptions
 * are ready in the core NATS protocol.
 */
class JetStreamNatsClient {

    companion object {
        private const val TAG = "JetStreamNatsClient"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val ENROLLMENT_STREAM = "ENROLLMENT"

        // Global mutex to serialize JetStream requests across all instances.
        // This prevents race conditions where multiple consumers fetch from the
        // same stream simultaneously and receive each other's responses.
        private val globalRequestMutex = Mutex()
    }

    private var natsClient: AndroidNatsClient? = null
    private val gson = Gson()

    val isConnected: Boolean
        get() = natsClient?.isConnected == true

    /**
     * Initialize with an existing AndroidNatsClient connection.
     * Reuses the working TLS connection instead of creating a new one.
     */
    fun initialize(client: AndroidNatsClient) {
        natsClient = client
        Log.i(TAG, "Initialized with existing NATS connection")
    }

    /**
     * Connect to NATS server (delegates to AndroidNatsClient).
     */
    suspend fun connect(
        endpoint: String,
        jwt: String,
        seed: String
    ): Result<Unit> {
        val client = AndroidNatsClient()
        val result = client.connect(endpoint, jwt, seed)
        if (result.isSuccess) {
            natsClient = client
        }
        return result
    }

    /**
     * Send a request and wait for response using JetStream consumer.
     *
     * Creates an ephemeral ordered consumer via $JS.API.CONSUMER.CREATE,
     * then fetches messages via $JS.API.CONSUMER.MSG.NEXT.
     */
    suspend fun requestWithJetStream(
        requestSubject: String,
        responseSubject: String,
        payload: ByteArray,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result<NatsMessage> = withContext(Dispatchers.IO) {
        val client = natsClient
            ?: return@withContext Result.failure(NatsException("Not connected"))

        // Use global mutex to serialize requests and prevent race conditions
        // where multiple consumers might receive each other's responses
        globalRequestMutex.withLock {
            requestWithJetStreamInternal(client, requestSubject, responseSubject, payload, timeoutMs)
        }
    }

    private suspend fun requestWithJetStreamInternal(
        client: AndroidNatsClient,
        requestSubject: String,
        responseSubject: String,
        payload: ByteArray,
        timeoutMs: Long
    ): Result<NatsMessage> {
        try {
            // Generate unique consumer name
            val consumerName = "app-${UUID.randomUUID().toString().take(8)}"

            Log.d(TAG, "Creating ephemeral consumer '$consumerName' for $responseSubject")

            // Get current timestamp in RFC3339 format for deliver_policy
            // This ensures we only receive messages published AFTER this time
            val startTime = Instant.now()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT)

            // Step 1: Create ephemeral ordered consumer via JetStream API
            // Use deliver_policy: "by_start_time" with current time to ensure
            // we only receive messages published AFTER consumer creation.
            // This is more reliable than "new" which has shown race condition issues.
            val createConsumerRequest = JsonObject().apply {
                addProperty("stream_name", ENROLLMENT_STREAM)
                add("config", JsonObject().apply {
                    addProperty("name", consumerName)
                    addProperty("filter_subject", responseSubject)
                    addProperty("deliver_policy", "by_start_time")
                    addProperty("opt_start_time", startTime)
                    addProperty("ack_policy", "none")     // Ephemeral, no ack needed
                    addProperty("max_deliver", 1)
                    addProperty("num_replicas", 1)
                    addProperty("mem_storage", true)      // In-memory for speed
                })
            }

            val createSubject = "\$JS.API.CONSUMER.CREATE.$ENROLLMENT_STREAM.$consumerName"

            // Use request-reply to create consumer
            val createResult = client.request(
                createSubject,
                gson.toJson(createConsumerRequest).toByteArray(),
                timeoutMs = 5000
            )

            if (createResult.isFailure) {
                Log.e(TAG, "Failed to create consumer: ${createResult.exceptionOrNull()?.message}")
                return Result.failure(createResult.exceptionOrNull()!!)
            }

            val createResponse = createResult.getOrThrow()
            Log.d(TAG, "Consumer create response: ${createResponse.dataString.take(200)}")

            // Check for errors in response
            val responseJson = gson.fromJson(createResponse.dataString, JsonObject::class.java)
            if (responseJson.has("error")) {
                val error = responseJson.getAsJsonObject("error")
                val errMsg = error.get("description")?.asString ?: "Unknown error"
                Log.e(TAG, "JetStream error creating consumer: $errMsg")
                return Result.failure(NatsException("JetStream: $errMsg"))
            }

            Log.d(TAG, "Consumer created, publishing request to $requestSubject")

            // Step 2: Publish the request
            val pubResult = client.publish(requestSubject, payload)
            if (pubResult.isFailure) {
                return Result.failure(pubResult.exceptionOrNull()!!)
            }
            client.flush()

            Log.d(TAG, "Request published, fetching response from consumer")

            // Step 3: Fetch message from consumer
            val fetchSubject = "\$JS.API.CONSUMER.MSG.NEXT.$ENROLLMENT_STREAM.$consumerName"
            val fetchRequest = JsonObject().apply {
                addProperty("batch", 1)
                addProperty("expires", timeoutMs * 1_000_000) // nanoseconds
            }

            val fetchResult = client.request(
                fetchSubject,
                gson.toJson(fetchRequest).toByteArray(),
                timeoutMs = timeoutMs
            )

            // Step 4: Delete consumer (cleanup)
            try {
                val deleteSubject = "\$JS.API.CONSUMER.DELETE.$ENROLLMENT_STREAM.$consumerName"
                client.publish(deleteSubject, "{}".toByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete consumer (will auto-expire): ${e.message}")
            }

            if (fetchResult.isFailure) {
                Log.e(TAG, "Failed to fetch from consumer: ${fetchResult.exceptionOrNull()?.message}")
                return Result.failure(NatsException("Request timed out"))
            }

            val fetchResponse = fetchResult.getOrThrow()
            Log.d(TAG, "Received response on subject '${fetchResponse.subject}': ${fetchResponse.dataString.take(100)}")
            Log.d(TAG, "Expected subject: $responseSubject")

            return Result.success(fetchResponse)
        } catch (e: CancellationException) {
            // Coroutine cancelled - rethrow to allow proper cancellation
            Log.d(TAG, "JetStream request cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "JetStream request failed: ${e.message}", e)
            return Result.failure(e)
        }
    }

    /**
     * Disconnect from NATS.
     */
    suspend fun disconnect() {
        try {
            natsClient?.disconnect()
            Log.i(TAG, "Disconnected from JetStream")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        }
        natsClient = null
    }
}
