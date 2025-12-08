package com.vettid.app.core.nats

import kotlinx.coroutines.*
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles request/response correlation for vault events.
 *
 * Allows submitting events and waiting for their responses with timeout.
 * Uses CompletableDeferred for async waiting.
 */
@Singleton
class VaultResponseHandler @Inject constructor(
    private val vaultEventClient: VaultEventClient
) {
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<VaultEventResponse>>()
    private var responseCollectorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start listening for responses.
     * Should be called after NATS connection is established.
     */
    fun startListening() {
        if (responseCollectorJob?.isActive == true) return

        responseCollectorJob = scope.launch {
            vaultEventClient.subscribeToResponses().collect { response ->
                android.util.Log.d(TAG, "Received response for request: ${response.requestId}")
                pendingRequests.remove(response.requestId)?.complete(response)
            }
        }
        android.util.Log.i(TAG, "Started response listener")
    }

    /**
     * Stop listening for responses.
     */
    fun stopListening() {
        responseCollectorJob?.cancel()
        responseCollectorJob = null
        pendingRequests.clear()
        android.util.Log.i(TAG, "Stopped response listener")
    }

    /**
     * Submit an event and wait for its response.
     *
     * @param event The event to submit
     * @param timeout Maximum time to wait for response
     * @return Result containing the response or timeout error
     */
    suspend fun submitAndAwait(
        event: VaultSubmitEvent,
        timeout: Duration = Duration.ofSeconds(30)
    ): Result<VaultEventResponse> {
        val deferred = CompletableDeferred<VaultEventResponse>()

        return try {
            // Submit the event
            val requestIdResult = vaultEventClient.submitEvent(event)
            val requestId = requestIdResult.getOrElse { error ->
                return Result.failure(error)
            }

            // Register for response
            pendingRequests[requestId] = deferred
            android.util.Log.d(TAG, "Waiting for response to request: $requestId")

            // Wait with timeout
            withTimeout(timeout.toMillis()) {
                val response = deferred.await()
                Result.success(response)
            }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w(TAG, "Request timed out after ${timeout.seconds}s")
            Result.failure(VaultTimeoutException("Vault response timeout after ${timeout.seconds}s"))
        } catch (e: CancellationException) {
            android.util.Log.d(TAG, "Request cancelled")
            Result.failure(e)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Request failed", e)
            Result.failure(e)
        }
    }

    /**
     * Submit an event without waiting for response.
     *
     * @param event The event to submit
     * @return Result containing the request ID
     */
    suspend fun submitFireAndForget(event: VaultSubmitEvent): Result<String> {
        return vaultEventClient.submitEvent(event)
    }

    /**
     * Send a custom message and wait for response.
     *
     * @param topic The message topic
     * @param payload The message payload
     * @param timeout Maximum time to wait
     * @return Result containing the response
     */
    suspend fun sendAndAwait(
        topic: String,
        payload: com.google.gson.JsonObject = com.google.gson.JsonObject(),
        timeout: Duration = Duration.ofSeconds(30)
    ): Result<VaultEventResponse> {
        val event = VaultSubmitEvent.Custom(topic, payload)
        return submitAndAwait(event, timeout)
    }

    /**
     * Check if there are pending requests.
     */
    fun hasPendingRequests(): Boolean = pendingRequests.isNotEmpty()

    /**
     * Get count of pending requests.
     */
    fun pendingRequestCount(): Int = pendingRequests.size

    /**
     * Cancel all pending requests.
     */
    fun cancelAllPending() {
        val exception = CancellationException("All pending requests cancelled")
        pendingRequests.values.forEach { it.completeExceptionally(exception) }
        pendingRequests.clear()
    }

    companion object {
        private const val TAG = "VaultResponseHandler"
    }
}

/**
 * Exception for vault response timeout.
 */
class VaultTimeoutException(message: String) : Exception(message)
