package com.vettid.app.core.nats

import android.util.Log
import io.nats.client.NKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Custom NATS client for Android that works with ACM TLS termination.
 *
 * Uses Android's default SSLSocketFactory which properly handles TLS
 * with publicly trusted certificates (like ACM).
 */
class AndroidNatsClient {

    companion object {
        private const val TAG = "AndroidNatsClient"
        private const val DEFAULT_TIMEOUT_MS = 30000
        private const val PING_INTERVAL_MS = 30000L
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private var socket: SSLSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private val connected = AtomicBoolean(false)
    private val subscriptionIdCounter = AtomicLong(1)
    private val subscriptions = ConcurrentHashMap<String, SubscriptionHandler>()
    private val pendingRequests = ConcurrentHashMap<String, RequestHandler>()

    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null
    private var pingJob: Job? = null

    private var currentEndpoint: String? = null
    private var currentJwt: String? = null
    private var currentSeed: String? = null

    val isConnected: Boolean
        get() = connected.get()

    /**
     * Connect to NATS server with JWT and NKEY seed authentication.
     */
    suspend fun connect(
        endpoint: String,
        jwt: String,
        seed: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Store for reconnection
            currentEndpoint = endpoint
            currentJwt = jwt
            currentSeed = seed

            // Parse endpoint (tls://host:port or nats://host:port)
            val (host, port) = parseEndpoint(endpoint)

            Log.i(TAG, "Connecting to $host:$port...")

            // Create TLS socket using Android's default factory
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket() as SSLSocket
            sslSocket.connect(InetSocketAddress(host, port), timeoutMs)
            sslSocket.soTimeout = timeoutMs
            sslSocket.startHandshake()

            socket = sslSocket
            reader = sslSocket.inputStream.bufferedReader()
            writer = sslSocket.outputStream.bufferedWriter()

            Log.i(TAG, "TLS connected, reading INFO...")

            // Read INFO message
            val infoLine = reader!!.readLine()
                ?: throw IOException("Connection closed before INFO received")

            if (!infoLine.startsWith("INFO ")) {
                throw IOException("Expected INFO, got: $infoLine")
            }

            val infoJson = JSONObject(infoLine.substring(5))
            val nonce = infoJson.optString("nonce", "")
            val authRequired = infoJson.optBoolean("auth_required", false)

            Log.d(TAG, "Server nonce: $nonce, auth_required: $authRequired")

            // Authenticate with JWT and signed nonce
            if (authRequired && nonce.isNotEmpty()) {
                val nkey = NKey.fromSeed(seed.toCharArray())
                val signedBytes = nkey.sign(nonce.toByteArray())
                val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signedBytes)

                val connectJson = JSONObject().apply {
                    put("verbose", false)
                    put("pedantic", false)
                    put("tls_required", true)
                    put("name", "android-vettid")
                    put("lang", "kotlin")
                    put("version", "1.0.0")
                    put("protocol", 1)
                    put("jwt", jwt)
                    put("nkey", String(nkey.publicKey))
                    put("sig", signature)
                }

                sendCommand("CONNECT $connectJson")
            }

            // Send PING to verify connection
            sendCommand("PING")

            val response = reader!!.readLine()
            if (response == "PONG") {
                connected.set(true)
                startBackgroundTasks()
                Log.i(TAG, "Connected successfully!")
                Result.success(Unit)
            } else if (response?.startsWith("-ERR") == true) {
                throw IOException("Authentication failed: $response")
            } else {
                throw IOException("Unexpected response: $response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            disconnect()
            Result.failure(e)
        }
    }

    /**
     * Disconnect from NATS server.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            connected.set(false)

            // Cancel background tasks
            readerJob?.cancel()
            pingJob?.cancel()
            scope?.cancel()
            scope = null

            // Close streams and socket
            try { writer?.close() } catch (e: Exception) { }
            try { reader?.close() } catch (e: Exception) { }
            try { socket?.close() } catch (e: Exception) { }

            writer = null
            reader = null
            socket = null

            subscriptions.clear()
            pendingRequests.clear()

            Log.i(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect: ${e.message}")
        }
    }

    /**
     * Publish a message to a subject.
     */
    suspend fun publish(subject: String, data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!connected.get()) {
                return@withContext Result.failure(IOException("Not connected"))
            }

            synchronized(this@AndroidNatsClient) {
                val payload = String(data, Charsets.UTF_8)
                sendCommand("PUB $subject ${data.size}")
                sendCommand(payload)
            }

            Log.d(TAG, "Published to $subject (${data.size} bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Publish failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Publish a string message.
     */
    suspend fun publish(subject: String, message: String): Result<Unit> {
        return publish(subject, message.toByteArray(Charsets.UTF_8))
    }

    /**
     * Subscribe to a subject.
     */
    suspend fun subscribe(
        subject: String,
        callback: (NatsMessage) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!connected.get()) {
                return@withContext Result.failure(IOException("Not connected"))
            }

            val sid = subscriptionIdCounter.getAndIncrement().toString()
            subscriptions[sid] = SubscriptionHandler(subject, callback)

            synchronized(this@AndroidNatsClient) {
                sendCommand("SUB $subject $sid")
            }

            Log.d(TAG, "Subscribed to $subject (sid=$sid)")
            Result.success(sid)
        } catch (e: Exception) {
            Log.e(TAG, "Subscribe failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Unsubscribe from a subscription.
     */
    suspend fun unsubscribe(sid: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            subscriptions.remove(sid)

            if (connected.get()) {
                synchronized(this@AndroidNatsClient) {
                    sendCommand("UNSUB $sid")
                }
            }

            Log.d(TAG, "Unsubscribed from sid=$sid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Unsubscribe failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Request-reply pattern.
     */
    suspend fun request(
        subject: String,
        data: ByteArray,
        timeoutMs: Long = 5000
    ): Result<NatsMessage> = withContext(Dispatchers.IO) {
        try {
            if (!connected.get()) {
                return@withContext Result.failure(IOException("Not connected"))
            }

            // Create unique inbox for reply
            val inbox = "_INBOX.${System.currentTimeMillis()}.${subscriptionIdCounter.getAndIncrement()}"
            val handler = RequestHandler()

            // Subscribe to inbox
            val sid = subscriptionIdCounter.getAndIncrement().toString()
            pendingRequests[sid] = handler

            synchronized(this@AndroidNatsClient) {
                sendCommand("SUB $inbox $sid")
                sendCommand("UNSUB $sid 1") // Auto-unsubscribe after 1 message

                // Publish request with reply-to
                val payload = String(data, Charsets.UTF_8)
                sendCommand("PUB $subject $inbox ${data.size}")
                sendCommand(payload)
            }

            // Wait for response
            val response = handler.await(timeoutMs)
            pendingRequests.remove(sid)

            if (response != null) {
                Result.success(response)
            } else {
                Result.failure(IOException("Request timed out"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Flush pending messages.
     */
    suspend fun flush(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            synchronized(this@AndroidNatsClient) {
                writer?.flush()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Private methods ---

    private fun parseEndpoint(endpoint: String): Pair<String, Int> {
        // Handle tls://, nats://, or plain host:port
        val cleanUrl = endpoint
            .removePrefix("tls://")
            .removePrefix("nats://")

        val parts = cleanUrl.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toInt() else 4222

        return Pair(host, port)
    }

    private fun sendCommand(command: String) {
        writer?.apply {
            write(command)
            write("\r\n")
            flush()
        }
    }

    private fun startBackgroundTasks() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Start message reader
        readerJob = scope?.launch {
            readMessages()
        }

        // Start ping task
        pingJob = scope?.launch {
            while (isActive && connected.get()) {
                delay(PING_INTERVAL_MS)
                try {
                    synchronized(this@AndroidNatsClient) {
                        sendCommand("PING")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ping failed: ${e.message}")
                    handleDisconnect()
                }
            }
        }
    }

    private suspend fun readMessages() {
        try {
            while (connected.get()) {
                val line = reader?.readLine() ?: break

                when {
                    line == "PING" -> {
                        synchronized(this@AndroidNatsClient) {
                            sendCommand("PONG")
                        }
                    }
                    line == "PONG" -> {
                        // Ignore pong responses
                    }
                    line.startsWith("MSG ") -> {
                        handleMessage(line)
                    }
                    line.startsWith("-ERR") -> {
                        Log.e(TAG, "Server error: $line")
                    }
                    line.startsWith("+OK") -> {
                        // Ignore OK responses
                    }
                    else -> {
                        Log.d(TAG, "Unknown message: $line")
                    }
                }
            }
        } catch (e: Exception) {
            if (connected.get()) {
                Log.e(TAG, "Read error: ${e.message}")
                handleDisconnect()
            }
        }
    }

    private suspend fun handleMessage(msgLine: String) {
        // MSG <subject> <sid> [reply-to] <#bytes>
        val parts = msgLine.split(" ")
        if (parts.size < 4) return

        val subject = parts[1]
        val sid = parts[2]
        val replyTo: String?
        val numBytes: Int

        if (parts.size == 5) {
            replyTo = parts[3]
            numBytes = parts[4].toIntOrNull() ?: 0
        } else {
            replyTo = null
            numBytes = parts[3].toIntOrNull() ?: 0
        }

        // Read payload
        val payload = if (numBytes > 0) {
            val buffer = CharArray(numBytes)
            var read = 0
            while (read < numBytes) {
                val n = reader?.read(buffer, read, numBytes - read) ?: break
                if (n < 0) break
                read += n
            }
            reader?.readLine() // Consume trailing CRLF
            String(buffer).toByteArray(Charsets.UTF_8)
        } else {
            reader?.readLine() // Consume trailing CRLF
            ByteArray(0)
        }

        val message = NatsMessage(subject, payload, replyTo)

        // Check pending requests first
        pendingRequests[sid]?.complete(message)

        // Then check subscriptions
        subscriptions[sid]?.let { handler ->
            try {
                handler.callback(message)
            } catch (e: Exception) {
                Log.e(TAG, "Subscription callback error: ${e.message}")
            }
        }
    }

    private fun handleDisconnect() {
        if (connected.compareAndSet(true, false)) {
            Log.w(TAG, "Connection lost, will attempt reconnect...")

            scope?.launch {
                attemptReconnect()
            }
        }
    }

    private suspend fun attemptReconnect() {
        var attempts = 0

        while (attempts < MAX_RECONNECT_ATTEMPTS && !connected.get()) {
            attempts++
            delay(RECONNECT_DELAY_MS)

            Log.i(TAG, "Reconnect attempt $attempts/$MAX_RECONNECT_ATTEMPTS")

            val endpoint = currentEndpoint
            val jwt = currentJwt
            val seed = currentSeed

            if (endpoint != null && jwt != null && seed != null) {
                val result = connect(endpoint, jwt, seed)
                if (result.isSuccess) {
                    Log.i(TAG, "Reconnected successfully!")

                    // Resubscribe
                    subscriptions.forEach { (sid, handler) ->
                        try {
                            synchronized(this@AndroidNatsClient) {
                                sendCommand("SUB ${handler.subject} $sid")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to resubscribe: ${e.message}")
                        }
                    }
                    return
                }
            }
        }

        Log.e(TAG, "Failed to reconnect after $MAX_RECONNECT_ATTEMPTS attempts")
    }

    // --- Helper classes ---

    private data class SubscriptionHandler(
        val subject: String,
        val callback: (NatsMessage) -> Unit
    )

    private class RequestHandler {
        private var response: NatsMessage? = null
        private val lock = Object()
        private var completed = false

        fun complete(message: NatsMessage) {
            synchronized(lock) {
                response = message
                completed = true
                lock.notifyAll()
            }
        }

        fun await(timeoutMs: Long): NatsMessage? {
            synchronized(lock) {
                if (!completed) {
                    lock.wait(timeoutMs)
                }
                return response
            }
        }
    }
}
