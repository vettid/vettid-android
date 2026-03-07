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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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
 *
 * I/O uses raw byte streams (not character readers/writers) so that NATS
 * protocol byte counts are honoured exactly — avoids desync when payloads
 * contain multi-byte UTF-8.
 */
class AndroidNatsClient {

    companion object {
        private const val TAG = "AndroidNatsClient"
        private const val DEFAULT_TIMEOUT_MS = 30000
        // Shorter ping interval to prevent NAT/firewall timeouts (mobile networks often timeout at 30-60s)
        private const val PING_INTERVAL_MS = 20000L
        private const val RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_ATTEMPTS = Int.MAX_VALUE
        private const val PONG_TIMEOUT_MS = 10000L
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
    }

    private var socket: SSLSocket? = null
    private var inputStream: BufferedInputStream? = null
    private var outputStream: BufferedOutputStream? = null

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

    // Track last successful communication for connection health
    private val lastPongTime = AtomicLong(0)
    private val reconnecting = AtomicBoolean(false)

    // Serialize connect/reconnect to prevent concurrent connection races
    private val connectMutex = Mutex()

    val isConnected: Boolean
        get() = connected.get()

    // --- Byte-level I/O helpers ---

    /**
     * Read a NATS protocol line (terminated by \r\n) from the given stream.
     * Returns the line content WITHOUT the trailing \r\n, or null on EOF.
     */
    private fun readProtocolLine(stream: BufferedInputStream? = inputStream): String? {
        val s = stream ?: return null
        val baos = ByteArrayOutputStream(256)
        while (true) {
            val b = s.read()
            if (b == -1) return null
            if (b == '\n'.code) {
                val bytes = baos.toByteArray()
                // Strip trailing \r if present (NATS uses \r\n)
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            baos.write(b)
        }
    }

    /**
     * Read exactly [numBytes] bytes from the given stream.
     * Returns null on premature EOF.
     */
    private fun readExactBytes(numBytes: Int, stream: BufferedInputStream? = inputStream): ByteArray? {
        val s = stream ?: return null
        val buffer = ByteArray(numBytes)
        var read = 0
        while (read < numBytes) {
            val n = s.read(buffer, read, numBytes - read)
            if (n < 0) return null
            read += n
        }
        return buffer
    }

    /** Write a protocol line (adds \r\n, flushes). */
    private fun sendLine(line: String) {
        outputStream?.apply {
            write(line.toByteArray(Charsets.UTF_8))
            write(CRLF)
            flush()
        }
    }

    /** Write raw payload bytes (adds trailing \r\n, flushes). */
    private fun sendPayloadBytes(data: ByteArray) {
        outputStream?.apply {
            write(data)
            write(CRLF)
            flush()
        }
    }

    // --- Public API ---

    /**
     * Connect to NATS server with JWT and NKEY seed authentication.
     */
    suspend fun connect(
        endpoint: String,
        jwt: String,
        seed: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Result<Unit> = withContext(Dispatchers.IO) {
        connectInternal(endpoint, jwt, seed, timeoutMs, isReconnect = false)
    }

    /**
     * Internal connect method that handles both initial connect and reconnect.
     */
    private suspend fun connectInternal(
        endpoint: String,
        jwt: String,
        seed: String,
        timeoutMs: Int,
        isReconnect: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // Serialize connection attempts to prevent races where two connects
        // both succeed and the second closes the first's reader/writer
        connectMutex.withLock {
            // If already connected (e.g., a concurrent attempt succeeded while we waited),
            // skip this attempt
            if (isReconnect && connected.get()) {
                return@withContext Result.success(Unit)
            }

        var newSocket: SSLSocket? = null
        var newInput: BufferedInputStream? = null
        var newOutput: BufferedOutputStream? = null

        try {
            // Store for reconnection (only on initial connect)
            if (!isReconnect) {
                currentEndpoint = endpoint
                currentJwt = jwt
                currentSeed = seed
            }

            // Parse endpoint (tls://host:port or nats://host:port)
            val (host, port) = parseEndpoint(endpoint)

            Log.i(TAG, "Connecting to $host:$port... (reconnect=$isReconnect)")

            // Create TLS socket using Android's default factory
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            newSocket = sslFactory.createSocket() as SSLSocket
            newSocket.connect(InetSocketAddress(host, port), timeoutMs)
            newSocket.soTimeout = timeoutMs
            newSocket.startHandshake()

            newInput = BufferedInputStream(newSocket.inputStream)
            newOutput = BufferedOutputStream(newSocket.outputStream)

            Log.i(TAG, "TLS connected, reading INFO...")

            // Read INFO message
            val infoLine = readProtocolLine(newInput)
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
                    put("headers", true)
                    put("no_responders", true)
                    put("jwt", jwt)
                    put("nkey", String(nkey.publicKey))
                    put("sig", signature)
                }

                newOutput.write("CONNECT $connectJson\r\n".toByteArray(Charsets.UTF_8))
                newOutput.flush()
            }

            // Send PING to verify connection
            newOutput.write("PING\r\n".toByteArray(Charsets.US_ASCII))
            newOutput.flush()

            val response = readProtocolLine(newInput)
            if (response == "PONG") {
                // Success - atomically swap in the new connection
                val oldSocket = socket
                val oldInput = inputStream
                val oldOutput = outputStream

                socket = newSocket
                inputStream = newInput
                outputStream = newOutput

                // Close old connection (if any)
                try { oldOutput?.close() } catch (e: Exception) { }
                try { oldInput?.close() } catch (e: Exception) { }
                try { oldSocket?.close() } catch (e: Exception) { }

                connected.set(true)

                // Only start background tasks on initial connect
                // For reconnect, the existing reader loop will be restarted
                if (!isReconnect) {
                    startBackgroundTasks()
                }

                Log.i(TAG, "Connected successfully!")
                Result.success(Unit)
            } else if (response?.startsWith("-ERR") == true) {
                throw IOException("Authentication failed: $response")
            } else {
                throw IOException("Unexpected response: $response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)

            // Clean up the NEW socket only - don't touch existing state or scope
            try { newOutput?.close() } catch (ex: Exception) { }
            try { newInput?.close() } catch (ex: Exception) { }
            try { newSocket?.close() } catch (ex: Exception) { }

            // Only do full disconnect on initial connect failure
            if (!isReconnect) {
                disconnect()
            }

            Result.failure(e)
        }
        } // connectMutex.withLock
    }

    /**
     * Disconnect from NATS server.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            connected.set(false)
            reconnecting.set(false)  // Stop any reconnection attempts

            // Cancel background tasks
            readerJob?.cancel()
            pingJob?.cancel()
            scope?.cancel()
            scope = null

            // Close streams and socket
            try { outputStream?.close() } catch (e: Exception) { }
            try { inputStream?.close() } catch (e: Exception) { }
            try { socket?.close() } catch (e: Exception) { }

            outputStream = null
            inputStream = null
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
                sendLine("PUB $subject ${data.size}")
                sendPayloadBytes(data)
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
                sendLine("SUB $subject $sid")
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
                    sendLine("UNSUB $sid")
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
                sendLine("SUB $inbox $sid")
                sendLine("UNSUB $sid 1") // Auto-unsubscribe after 1 message

                // Publish request with reply-to
                sendLine("PUB $subject $inbox ${data.size}")
                sendPayloadBytes(data)
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
                outputStream?.flush()
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
        val port = if (parts.size > 1) parts[1].toInt() else 443

        return Pair(host, port)
    }

    private fun startBackgroundTasks() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Initialize pong time
        lastPongTime.set(System.currentTimeMillis())

        // Start message reader
        readerJob = scope?.launch {
            readMessages()
        }

        // Start ping task with health monitoring
        pingJob = scope?.launch {
            while (isActive && connected.get()) {
                delay(PING_INTERVAL_MS)

                if (!connected.get()) break

                try {
                    // Check if we've received a pong recently
                    val timeSinceLastPong = System.currentTimeMillis() - lastPongTime.get()
                    if (timeSinceLastPong > PING_INTERVAL_MS + PONG_TIMEOUT_MS) {
                        Log.w(TAG, "No PONG received in ${timeSinceLastPong}ms, connection may be stale")
                        handleDisconnect()
                        break
                    }

                    synchronized(this@AndroidNatsClient) {
                        sendLine("PING")
                    }
                    Log.d(TAG, "Sent PING")
                } catch (e: Exception) {
                    Log.w(TAG, "Ping failed: ${e.message}")
                    handleDisconnect()
                    break
                }
            }
        }
    }

    private suspend fun readMessages() {
        try {
            while (connected.get()) {
                val line = readProtocolLine() ?: break

                when {
                    line == "PING" -> {
                        synchronized(this@AndroidNatsClient) {
                            sendLine("PONG")
                        }
                        Log.d(TAG, "Received PING, sent PONG")
                    }
                    line == "PONG" -> {
                        // Track pong time for connection health monitoring
                        lastPongTime.set(System.currentTimeMillis())
                        Log.d(TAG, "Received PONG")
                    }
                    line.startsWith("MSG ") -> {
                        // Any message means connection is alive
                        lastPongTime.set(System.currentTimeMillis())
                        handleMessage(line)
                    }
                    line.startsWith("HMSG ") -> {
                        // JetStream header message — same as MSG but with headers
                        lastPongTime.set(System.currentTimeMillis())
                        handleHeaderMessage(line)
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
            Log.w(TAG, "Reader loop exited (connected=${connected.get()})")
            // EOF or null read — trigger reconnect if we didn't disconnect intentionally
            if (connected.get()) {
                handleDisconnect()
            }
        } catch (e: Exception) {
            if (connected.get()) {
                Log.e(TAG, "Read error: ${e.message}")
                handleDisconnect()
            }
        }
    }

    private fun handleMessage(msgLine: String) {
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

        // Read payload as raw bytes (numBytes is a byte count)
        val payload = if (numBytes > 0) {
            val bytes = readExactBytes(numBytes) ?: return
            readProtocolLine() // consume trailing CRLF
            bytes
        } else {
            readProtocolLine() // consume trailing CRLF
            ByteArray(0)
        }

        val message = NatsMessage(subject, payload, replyTo)

        // Check pending requests first (inbox responses)
        if (pendingRequests[sid]?.complete(message) != null) {
            return
        }

        // Then check subscriptions (push notifications)
        subscriptions[sid]?.let { handler ->
            try {
                handler.callback(message)
            } catch (e: Exception) {
                Log.e(TAG, "Subscription callback error: ${e.message}")
            }
        }
    }

    private fun handleHeaderMessage(hmsgLine: String) {
        // HMSG <subject> <sid> [reply-to] <header-bytes> <total-bytes>
        val parts = hmsgLine.split(" ")
        if (parts.size < 5) return

        val subject = parts[1]
        val sid = parts[2]
        val replyTo: String?
        val headerBytes: Int
        val totalBytes: Int

        if (parts.size == 6) {
            replyTo = parts[3]
            headerBytes = parts[4].toIntOrNull() ?: 0
            totalBytes = parts[5].toIntOrNull() ?: 0
        } else {
            replyTo = null
            headerBytes = parts[3].toIntOrNull() ?: 0
            totalBytes = parts[4].toIntOrNull() ?: 0
        }

        // Read the entire block (headers + payload) as raw bytes
        val fullBytes = if (totalBytes > 0) {
            val bytes = readExactBytes(totalBytes) ?: return
            readProtocolLine() // consume trailing CRLF
            bytes
        } else {
            readProtocolLine() // consume trailing CRLF
            ByteArray(0)
        }

        // Extract header section (for status parsing only)
        val headerSection = if (headerBytes in 1..fullBytes.size) {
            String(fullBytes, 0, headerBytes, Charsets.UTF_8)
        } else {
            String(fullBytes, Charsets.UTF_8)
        }

        // Parse status from first header line: "NATS/1.0 <status>\r\n"
        val statusLine = headerSection.lineSequence().firstOrNull() ?: ""
        val statusCode = if (statusLine.startsWith("NATS/1.0 ")) {
            statusLine.substring(9).trim().split(" ").firstOrNull()?.toIntOrNull()
        } else null

        // Status-only messages (no payload) — complete with error info
        if (statusCode != null && statusCode >= 400) {
            Log.d(TAG, "HMSG status $statusCode on $subject (sid=$sid, pending=${pendingRequests.containsKey(sid)})")
            // Complete the pending request so it doesn't hang — caller checks empty payload
            val message = NatsMessage(subject, ByteArray(0), replyTo)
            pendingRequests[sid]?.complete(message)
            return
        }

        // Extract payload bytes (skip header section)
        val payloadBytes = if (headerBytes in 1..fullBytes.size) {
            fullBytes.copyOfRange(headerBytes, fullBytes.size)
        } else {
            fullBytes
        }

        Log.d(TAG, "HMSG $subject (sid=$sid, ${payloadBytes.size} bytes, pending=${pendingRequests.containsKey(sid)}, sub=${subscriptions.containsKey(sid)})")

        val message = NatsMessage(subject, payloadBytes, replyTo)

        // Check pending requests first (JetStream NEXT responses)
        if (pendingRequests[sid]?.complete(message) != null) {
            return
        }

        // Then check subscriptions (push notifications on forApp.>)
        subscriptions[sid]?.let { handler ->
            try {
                handler.callback(message)
            } catch (e: Exception) {
                Log.e(TAG, "Subscription callback error (HMSG): ${e.message}")
            }
        }
    }

    private fun handleDisconnect() {
        if (connected.compareAndSet(true, false)) {
            // Only start reconnection if not already reconnecting
            if (reconnecting.compareAndSet(false, true)) {
                Log.w(TAG, "Connection lost, will attempt reconnect...")

                scope?.launch {
                    try {
                        attemptReconnect()
                    } finally {
                        reconnecting.set(false)
                    }
                }
            } else {
                Log.d(TAG, "Reconnection already in progress")
            }
        }
    }

    private suspend fun attemptReconnect() {
        var attempts = 0
        var backoffMs = RECONNECT_DELAY_MS

        while (attempts < MAX_RECONNECT_ATTEMPTS && !connected.get()) {
            attempts++
            delay(backoffMs)

            if (attempts % 10 == 1 || attempts <= 3) {
                Log.i(TAG, "Reconnect attempt $attempts (backoff: ${backoffMs}ms)")
            }

            val endpoint = currentEndpoint
            val jwt = currentJwt
            val seed = currentSeed

            if (endpoint != null && jwt != null && seed != null) {
                // Use connectInternal with isReconnect=true to avoid canceling scope on failure
                val result = connectInternal(endpoint, jwt, seed, DEFAULT_TIMEOUT_MS, isReconnect = true)
                if (result.isSuccess) {
                    Log.i(TAG, "Reconnected successfully!")

                    // Reset pong time for health monitoring
                    lastPongTime.set(System.currentTimeMillis())

                    // Resubscribe to all active subscriptions
                    subscriptions.forEach { (sid, handler) ->
                        try {
                            synchronized(this@AndroidNatsClient) {
                                sendLine("SUB ${handler.subject} $sid")
                            }
                            Log.d(TAG, "Resubscribed to ${handler.subject} (sid=$sid)")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to resubscribe to ${handler.subject}: ${e.message}")
                        }
                    }

                    // Cancel old jobs if any
                    readerJob?.cancel()
                    pingJob?.cancel()

                    // Start a new reader job (the old one exited due to read error)
                    readerJob = scope?.launch {
                        readMessages()
                    }

                    // Start a new ping job
                    pingJob = scope?.launch {
                        while (isActive && connected.get()) {
                            delay(PING_INTERVAL_MS)

                            if (!connected.get()) break

                            try {
                                val timeSinceLastPong = System.currentTimeMillis() - lastPongTime.get()
                                if (timeSinceLastPong > PING_INTERVAL_MS + PONG_TIMEOUT_MS) {
                                    Log.w(TAG, "No PONG received in ${timeSinceLastPong}ms, connection may be stale")
                                    handleDisconnect()
                                    break
                                }

                                synchronized(this@AndroidNatsClient) {
                                    sendLine("PING")
                                }
                                Log.d(TAG, "Sent PING")
                            } catch (e: Exception) {
                                Log.w(TAG, "Ping failed: ${e.message}")
                                handleDisconnect()
                                break
                            }
                        }
                    }

                    return
                }
            } else {
                Log.w(TAG, "Cannot reconnect: missing credentials")
                break
            }

            // Exponential backoff with cap at 60 seconds
            backoffMs = (backoffMs * 2).coerceAtMost(60000L)
        }

        Log.e(TAG, "Failed to reconnect after $attempts attempts")
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
