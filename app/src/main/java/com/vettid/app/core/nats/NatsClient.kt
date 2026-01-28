package com.vettid.app.core.nats

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NATS client for Android using custom AndroidNatsClient implementation.
 *
 * This wrapper provides a coroutine-friendly interface for connecting to NATS,
 * publishing messages, and subscribing to subjects.
 *
 * Uses AndroidNatsClient which works reliably with ACM TLS termination
 * on Android, unlike the jnats library which has compatibility issues.
 */
@Singleton
class NatsClient @Inject constructor() {

    private val androidClient = AndroidNatsClient()
    private val subscriptions = ConcurrentHashMap<String, String>() // subject -> sid

    /**
     * Current connection status.
     */
    val isConnected: Boolean
        get() = androidClient.isConnected

    /**
     * Connection status for detailed state information.
     */
    val connectionStatus: ConnectionStatus
        get() = if (androidClient.isConnected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED

    /**
     * Connect to NATS cluster using the provided credentials.
     *
     * @param credentials NATS credentials from the backend
     * @return Result indicating success or failure
     */
    suspend fun connect(credentials: NatsCredentials): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Attempting NATS connection to: ${credentials.endpoint}")
            Log.d(TAG, "JWT length: ${credentials.jwt.length}, Seed length: ${credentials.seed.length}")

            val result = androidClient.connect(
                endpoint = credentials.endpoint,
                jwt = credentials.jwt,
                seed = credentials.seed
            )

            result.fold(
                onSuccess = {
                    Log.i(TAG, "Connected to NATS at ${credentials.endpoint}")
                    Result.success(Unit)
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to connect to NATS", e)
                    Result.failure(NatsException("Failed to connect: ${e.message}", e))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to NATS", e)
            Result.failure(NatsException("Failed to connect: ${e.message}", e))
        }
    }

    /**
     * Disconnect from NATS cluster.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            subscriptions.clear()
            androidClient.disconnect()
            Log.i(TAG, "Disconnected from NATS")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    /**
     * Publish a message to a subject.
     *
     * @param subject The subject to publish to
     * @param data The message payload
     * @return Result indicating success or failure
     */
    suspend fun publish(subject: String, data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val result = androidClient.publish(subject, data)
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Published message to $subject (${data.size} bytes)")
                    Result.success(Unit)
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to publish to $subject", e)
                    Result.failure(NatsException("Failed to publish: ${e.message}", e))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish to $subject", e)
            Result.failure(NatsException("Failed to publish: ${e.message}", e))
        }
    }

    /**
     * Publish a string message to a subject.
     */
    suspend fun publish(subject: String, message: String): Result<Unit> {
        return publish(subject, message.toByteArray(Charsets.UTF_8))
    }

    /**
     * Request-reply pattern with timeout.
     *
     * @param subject The subject to send the request to
     * @param data The request payload
     * @param timeoutMs Timeout in milliseconds
     * @return Result containing the reply message
     */
    suspend fun request(
        subject: String,
        data: ByteArray,
        timeoutMs: Long = 5000
    ): Result<NatsMessage> = withContext(Dispatchers.IO) {
        try {
            val result = androidClient.request(subject, data, timeoutMs)
            result.fold(
                onSuccess = { message ->
                    Result.success(message)
                },
                onFailure = { e ->
                    Log.e(TAG, "Request to $subject failed", e)
                    Result.failure(NatsException("Request failed: ${e.message}", e))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Request to $subject failed", e)
            Result.failure(NatsException("Request failed: ${e.message}", e))
        }
    }

    /**
     * Subscribe to a subject with a callback.
     *
     * @param subject The subject pattern to subscribe to (supports wildcards)
     * @param callback Handler for received messages
     * @return NatsSubscription for managing the subscription
     */
    fun subscribe(subject: String, callback: (NatsMessage) -> Unit): Result<NatsSubscription> {
        return try {
            val result = kotlinx.coroutines.runBlocking {
                androidClient.subscribe(subject, callback)
            }
            result.fold(
                onSuccess = { sid ->
                    subscriptions[subject] = sid
                    Log.d(TAG, "Subscribed to $subject")
                    val subscription = NatsSubscription(subject, sid) {
                        // Unsubscribe callback
                        subscriptions.remove(subject)
                        kotlinx.coroutines.runBlocking {
                            androidClient.unsubscribe(sid)
                        }
                    }
                    Result.success(subscription)
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to subscribe to $subject", e)
                    Result.failure(NatsException("Failed to subscribe: ${e.message}", e))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to $subject", e)
            Result.failure(NatsException("Failed to subscribe: ${e.message}", e))
        }
    }

    /**
     * Unsubscribe from a subject.
     *
     * @param subject The subject to unsubscribe from
     */
    suspend fun unsubscribe(subject: String) {
        val sid = subscriptions.remove(subject)
        if (sid != null) {
            androidClient.unsubscribe(sid)
            Log.d(TAG, "Unsubscribed from $subject")
        }
    }

    /**
     * Flush any pending messages.
     */
    suspend fun flush(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            androidClient.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(NatsException("Flush failed: ${e.message}", e))
        }
    }

    /**
     * Get the underlying AndroidNatsClient for JetStream operations.
     * This is needed because JetStream requires direct access to the low-level client.
     */
    fun getAndroidClient(): AndroidNatsClient = androidClient

    companion object {
        private const val TAG = "NatsClient"

        /**
         * Format JWT and seed into NATS credential file format.
         */
        fun formatCredentialFile(jwt: String, seed: String): String {
            return """-----BEGIN NATS USER JWT-----
$jwt
-----END NATS USER JWT-----

************************* IMPORTANT *************************
NKEY Seed printed below can be used to sign and prove identity.
NKEYs are sensitive and should be treated as secrets.

-----BEGIN USER NKEY SEED-----
$seed
-----END USER NKEY SEED-----
"""
        }
    }
}

/**
 * Connection status enum.
 */
enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    RECONNECTING,
    DISCONNECTED,
    CLOSED
}

/**
 * Wrapper for NATS messages.
 */
data class NatsMessage(
    val subject: String,
    val data: ByteArray,
    val replyTo: String?
) {
    /**
     * Get message data as string.
     */
    val dataString: String
        get() = data.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NatsMessage
        return subject == other.subject && data.contentEquals(other.data) && replyTo == other.replyTo
    }

    override fun hashCode(): Int {
        var result = subject.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (replyTo?.hashCode() ?: 0)
        return result
    }
}

/**
 * Wrapper for NATS subscriptions.
 */
class NatsSubscription(
    val subject: String,
    private val sid: String,
    private val onUnsubscribe: () -> Unit
) {
    private var active = true

    /**
     * Check if subscription is still active.
     */
    fun isActive(): Boolean = active

    /**
     * Unsubscribe from the subject.
     */
    fun unsubscribe() {
        if (active) {
            try {
                onUnsubscribe()
            } catch (e: Exception) {
                // Ignore errors during unsubscribe
            }
            active = false
        }
    }
}

/**
 * NATS-specific exception.
 */
class NatsException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
