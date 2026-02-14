package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Stateless JetStream request-response helper.
 *
 * Performs a single request-response cycle using an ephemeral JetStream consumer:
 * 1. Creates consumer on ENROLLMENT stream with filter_subject for the response topic
 * 2. Uses deliver_policy "by_start_time" to only receive new messages
 * 3. Publishes the request
 * 4. Fetches response, verifies event_id matches requestId
 * 5. Deletes consumer in finally block
 *
 * Unlike JetStreamNatsClient, this does NOT use a global mutex — concurrent requests
 * use separate consumers with unique names and distinct filter_subjects, so they
 * cannot receive each other's responses.
 */
object JetStreamRequestHelper {

    private const val TAG = "JetStreamRequestHelper"
    private const val ENROLLMENT_STREAM = "ENROLLMENT"
    private const val MAX_EVENT_ID_RETRIES = 3

    private val gson = Gson()

    /**
     * Send a request and fetch the response via JetStream.
     *
     * @param client The AndroidNatsClient to use for NATS operations
     * @param requestSubject Subject to publish the request to
     * @param responseSubject Subject to filter the response on (unique per request)
     * @param requestPayload The serialized request payload
     * @param expectedEventId The request ID to verify in the response's event_id
     * @param timeoutMs Timeout for the entire operation
     * @return The response message, or failure
     */
    suspend fun sendAndFetchResponse(
        client: AndroidNatsClient,
        requestSubject: String,
        responseSubject: String,
        requestPayload: ByteArray,
        expectedEventId: String,
        timeoutMs: Long = 30_000L
    ): Result<NatsMessage> = withContext(Dispatchers.IO) {
        val consumerName = "app-req-${UUID.randomUUID().toString().take(8)}"

        try {
            // Get current timestamp for deliver_policy
            val startTime = Instant.now()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT)

            // Step 1: Create ephemeral consumer with filter for our specific response subject
            val createRequest = JsonObject().apply {
                addProperty("stream_name", ENROLLMENT_STREAM)
                add("config", JsonObject().apply {
                    addProperty("name", consumerName)
                    addProperty("filter_subject", responseSubject)
                    addProperty("deliver_policy", "by_start_time")
                    addProperty("opt_start_time", startTime)
                    addProperty("ack_policy", "none")
                    addProperty("max_deliver", 1)
                    addProperty("num_replicas", 1)
                    addProperty("mem_storage", true)
                })
            }

            val createSubject = "\$JS.API.CONSUMER.CREATE.$ENROLLMENT_STREAM.$consumerName"
            val createResult = client.request(
                createSubject,
                gson.toJson(createRequest).toByteArray(),
                timeoutMs = 5000
            )

            if (createResult.isFailure) {
                Log.e(TAG, "Failed to create consumer: ${createResult.exceptionOrNull()?.message}")
                return@withContext Result.failure(createResult.exceptionOrNull()!!)
            }

            // Verify consumer was created successfully
            val createResponse = createResult.getOrThrow()
            val responseJson = gson.fromJson(createResponse.dataString, JsonObject::class.java)
            if (responseJson.has("error")) {
                val error = responseJson.getAsJsonObject("error")
                val errMsg = error.get("description")?.asString ?: "Unknown error"
                Log.e(TAG, "JetStream error creating consumer: $errMsg")
                return@withContext Result.failure(NatsException("JetStream: $errMsg"))
            }

            Log.d(TAG, "Consumer '$consumerName' created for $responseSubject")

            // Step 2: Publish the request
            val pubResult = client.publish(requestSubject, requestPayload)
            if (pubResult.isFailure) {
                Log.e(TAG, "Failed to publish request to $requestSubject")
                return@withContext Result.failure(pubResult.exceptionOrNull()!!)
            }
            client.flush()

            Log.d(TAG, "Request published to $requestSubject, fetching response...")

            // Step 3: Fetch response from consumer with event_id verification
            val fetchSubject = "\$JS.API.CONSUMER.MSG.NEXT.$ENROLLMENT_STREAM.$consumerName"
            val fetchRequest = JsonObject().apply {
                addProperty("batch", 1)
                addProperty("expires", timeoutMs * 1_000_000) // nanoseconds
            }
            val fetchPayload = gson.toJson(fetchRequest).toByteArray()

            var retries = 0
            while (retries < MAX_EVENT_ID_RETRIES) {
                val fetchResult = client.request(fetchSubject, fetchPayload, timeoutMs = timeoutMs)

                if (fetchResult.isFailure) {
                    Log.e(TAG, "Failed to fetch from consumer: ${fetchResult.exceptionOrNull()?.message}")
                    return@withContext Result.failure(NatsException("Request timed out"))
                }

                val fetchResponse = fetchResult.getOrThrow()

                // Verify event_id matches our requestId
                try {
                    val msgJson = gson.fromJson(fetchResponse.dataString, JsonObject::class.java)
                    val eventId = msgJson.get("event_id")?.asString
                        ?: msgJson.get("id")?.asString

                    if (eventId != null && eventId != expectedEventId) {
                        retries++
                        Log.w(TAG, "event_id mismatch: expected=$expectedEventId got=$eventId (retry $retries/$MAX_EVENT_ID_RETRIES)")
                        continue
                    }
                } catch (e: Exception) {
                    // Can't parse as JSON — return as-is, let caller handle
                    Log.w(TAG, "Could not verify event_id, returning response as-is")
                }

                Log.d(TAG, "Response received for $responseSubject")
                return@withContext Result.success(fetchResponse)
            }

            Log.e(TAG, "Exhausted event_id retries ($MAX_EVENT_ID_RETRIES)")
            return@withContext Result.failure(NatsException("Response event_id mismatch after $MAX_EVENT_ID_RETRIES retries"))
        } finally {
            // Always clean up the consumer
            try {
                val deleteSubject = "\$JS.API.CONSUMER.DELETE.$ENROLLMENT_STREAM.$consumerName"
                client.publish(deleteSubject, "{}".toByteArray())
                Log.d(TAG, "Consumer '$consumerName' deleted")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete consumer '$consumerName' (will auto-expire): ${e.message}")
            }
        }
    }
}
