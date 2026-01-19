package com.vettid.app.core.nats

import android.util.Log
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.ConsumerConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import javax.net.ssl.SSLContext

/**
 * JetStream-enabled NATS client using the official jnats library.
 *
 * This client provides reliable message delivery through JetStream consumers,
 * solving the timing issue where responses are published before subscriptions
 * are ready in the core NATS protocol.
 *
 * Uses Android's default TrustManager for ACM certificate compatibility.
 */
class JetStreamNatsClient {

    companion object {
        private const val TAG = "JetStreamNatsClient"
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val ENROLLMENT_STREAM = "ENROLLMENT"
    }

    private var connection: Connection? = null
    private var jetStream: JetStream? = null

    val isConnected: Boolean
        get() = connection?.status == Connection.Status.CONNECTED

    /**
     * Connect to NATS server with JWT and NKEY seed authentication.
     * Uses Android's default SSLContext for ACM certificate compatibility.
     */
    suspend fun connect(
        endpoint: String,
        jwt: String,
        seed: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Parse endpoint
            val (host, port) = parseEndpoint(endpoint)
            // Use tls:// scheme for jnats library to enable TLS
            val serverUrl = "tls://$host:$port"

            Log.i(TAG, "Connecting to JetStream at $serverUrl")

            // Use Android's default SSLContext (trusts ACM certificates)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)

            val options = Options.Builder()
                .server(serverUrl)
                .userInfo(jwt.toCharArray(), seed.toCharArray())
                .sslContext(sslContext)
                .connectionTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .maxReconnects(3)
                .build()

            Log.d(TAG, "Attempting connection with TLS...")
            val conn = Nats.connect(options)
            connection = conn

            // Initialize JetStream
            jetStream = conn.jetStream()

            Log.i(TAG, "Connected to JetStream successfully!")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "JetStream connection failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Send a request and wait for response using JetStream consumer.
     *
     * This is more reliable than core NATS subscribe-then-publish because:
     * 1. Consumer is created before publishing
     * 2. Messages persist in the stream until consumed
     * 3. No race condition between subscribe and publish
     */
    suspend fun requestWithJetStream(
        requestSubject: String,
        responseSubject: String,
        payload: ByteArray,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): Result<NatsMessage> = withContext(Dispatchers.IO) {
        val conn = connection
            ?: return@withContext Result.failure(NatsException("Not connected"))
        val js = jetStream
            ?: return@withContext Result.failure(NatsException("JetStream not initialized"))

        try {
            Log.d(TAG, "Creating ephemeral consumer for $responseSubject")

            // Create ephemeral pull consumer for the response subject
            val consumerConfig = ConsumerConfiguration.builder()
                .filterSubject(responseSubject)
                .build()

            val pullOptions = PullSubscribeOptions.builder()
                .stream(ENROLLMENT_STREAM)
                .configuration(consumerConfig)
                .build()

            // Subscribe creates the consumer
            val subscription = js.subscribe(responseSubject, pullOptions)

            Log.d(TAG, "Publishing to $requestSubject (${payload.size} bytes)")

            // Publish the request (consumer is already waiting)
            conn.publish(requestSubject, payload)
            conn.flush(Duration.ofSeconds(1))

            Log.d(TAG, "Waiting for response on $responseSubject")

            // Fetch response from consumer
            val messages = subscription.fetch(1, Duration.ofSeconds(timeoutSeconds))

            // Clean up
            subscription.unsubscribe()

            if (messages.isEmpty()) {
                Log.e(TAG, "No response received within ${timeoutSeconds}s")
                return@withContext Result.failure(NatsException("Request timed out"))
            }

            val msg = messages.first()
            val natsMessage = NatsMessage(
                subject = msg.subject,
                data = msg.data,
                replyTo = msg.replyTo
            )

            Log.d(TAG, "Received response: ${natsMessage.dataString.take(100)}")
            Result.success(natsMessage)
        } catch (e: JetStreamApiException) {
            Log.e(TAG, "JetStream API error: ${e.message}", e)
            // Fall back to describing the error
            Result.failure(NatsException("JetStream error: ${e.apiErrorCode} - ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "JetStream request failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Publish a message without waiting for response.
     */
    suspend fun publish(subject: String, payload: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        val conn = connection
            ?: return@withContext Result.failure(NatsException("Not connected"))

        try {
            conn.publish(subject, payload)
            conn.flush(Duration.ofSeconds(1))
            Log.d(TAG, "Published to $subject (${payload.size} bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Publish failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Disconnect from NATS.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            connection?.close()
            Log.i(TAG, "Disconnected from JetStream")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        }
        connection = null
        jetStream = null
    }

    private fun parseEndpoint(endpoint: String): Pair<String, Int> {
        val cleanUrl = endpoint
            .removePrefix("tls://")
            .removePrefix("nats://")

        val parts = cleanUrl.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toInt() else 443

        return Pair(host, port)
    }
}
