package com.vettid.app.core.nats

import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.Message
import io.nats.client.Nats
import io.nats.client.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper around the NATS Java client for Android.
 *
 * Provides a coroutine-friendly interface for connecting to NATS,
 * publishing messages, and subscribing to subjects.
 */
@Singleton
class NatsClient @Inject constructor() {

    private var connection: Connection? = null
    private var dispatcher: Dispatcher? = null
    private val subscriptions = ConcurrentHashMap<String, NatsSubscription>()

    /**
     * Current connection status.
     */
    val isConnected: Boolean
        get() = connection?.status == Connection.Status.CONNECTED

    /**
     * Connection status for detailed state information.
     */
    val connectionStatus: ConnectionStatus
        get() = when (connection?.status) {
            Connection.Status.CONNECTED -> ConnectionStatus.CONNECTED
            Connection.Status.CONNECTING -> ConnectionStatus.CONNECTING
            Connection.Status.RECONNECTING -> ConnectionStatus.RECONNECTING
            Connection.Status.DISCONNECTED -> ConnectionStatus.DISCONNECTED
            Connection.Status.CLOSED -> ConnectionStatus.CLOSED
            null -> ConnectionStatus.CLOSED
        }

    /**
     * Connect to NATS cluster using the provided credentials.
     *
     * @param credentials NATS credentials from the backend
     * @return Result indicating success or failure
     */
    suspend fun connect(credentials: NatsCredentials): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Close existing connection if any
            disconnect()

            // Build connection options
            val options = Options.Builder()
                .server(credentials.endpoint)
                .userInfo(credentials.jwt, credentials.seed)
                .connectionTimeout(Duration.ofSeconds(10))
                .pingInterval(Duration.ofSeconds(30))
                .reconnectWait(Duration.ofSeconds(2))
                .maxReconnects(10)
                .connectionListener { conn, type ->
                    android.util.Log.d(TAG, "NATS connection event: $type")
                }
                .errorListener(object : io.nats.client.ErrorListener {
                    override fun errorOccurred(conn: Connection, error: String) {
                        android.util.Log.e(TAG, "NATS error: $error")
                    }
                    override fun exceptionOccurred(conn: Connection, exp: Exception) {
                        android.util.Log.e(TAG, "NATS exception: ${exp.message}", exp)
                    }
                })
                .build()

            // Connect to NATS
            connection = Nats.connect(options)
            dispatcher = connection?.createDispatcher()

            android.util.Log.i(TAG, "Connected to NATS at ${credentials.endpoint}")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to connect to NATS", e)
            Result.failure(NatsException("Failed to connect: ${e.message}", e))
        }
    }

    /**
     * Disconnect from NATS cluster.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            // Unsubscribe all
            subscriptions.values.forEach { it.unsubscribe() }
            subscriptions.clear()

            // Close dispatcher
            dispatcher?.let {
                connection?.closeDispatcher(it)
            }
            dispatcher = null

            // Close connection
            connection?.close()
            connection = null

            android.util.Log.i(TAG, "Disconnected from NATS")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error during disconnect", e)
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
            val conn = connection ?: return@withContext Result.failure(
                NatsException("Not connected to NATS")
            )

            conn.publish(subject, data)
            android.util.Log.d(TAG, "Published message to $subject (${data.size} bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to publish to $subject", e)
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
     * @param timeout Timeout duration
     * @return Result containing the reply message
     */
    suspend fun request(
        subject: String,
        data: ByteArray,
        timeout: Duration = Duration.ofSeconds(5)
    ): Result<Message> = withContext(Dispatchers.IO) {
        try {
            val conn = connection ?: return@withContext Result.failure(
                NatsException("Not connected to NATS")
            )

            val reply = conn.request(subject, data, timeout)
            if (reply != null) {
                Result.success(reply)
            } else {
                Result.failure(NatsException("Request timed out"))
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Request to $subject failed", e)
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
        try {
            val disp = dispatcher ?: return Result.failure(
                NatsException("Not connected to NATS")
            )

            val sub = disp.subscribe(subject) { msg ->
                val natsMessage = NatsMessage(
                    subject = msg.subject,
                    data = msg.data,
                    replyTo = msg.replyTo
                )
                callback(natsMessage)
            }

            val subscription = NatsSubscription(subject, sub, disp)
            subscriptions[subject] = subscription

            android.util.Log.d(TAG, "Subscribed to $subject")
            return Result.success(subscription)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to subscribe to $subject", e)
            return Result.failure(NatsException("Failed to subscribe: ${e.message}", e))
        }
    }

    /**
     * Unsubscribe from a subject.
     *
     * @param subject The subject to unsubscribe from
     */
    fun unsubscribe(subject: String) {
        subscriptions.remove(subject)?.unsubscribe()
        android.util.Log.d(TAG, "Unsubscribed from $subject")
    }

    /**
     * Flush any pending messages.
     */
    suspend fun flush(timeout: Duration = Duration.ofSeconds(5)): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            connection?.flush(timeout)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(NatsException("Flush failed: ${e.message}", e))
        }
    }

    companion object {
        private const val TAG = "NatsClient"
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
    private val subscription: io.nats.client.Subscription,
    private val dispatcher: Dispatcher
) {
    private var isActive = true

    /**
     * Check if subscription is still active.
     */
    fun isActive(): Boolean = isActive && subscription.isActive

    /**
     * Unsubscribe from the subject.
     */
    fun unsubscribe() {
        if (isActive) {
            try {
                dispatcher.unsubscribe(subscription)
            } catch (e: Exception) {
                // Ignore errors during unsubscribe
            }
            isActive = false
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
