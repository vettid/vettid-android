package com.vettid.app.core.security

import java.util.Base64
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API Security features:
 * - Request signing (HMAC-SHA256)
 * - Nonce-based replay protection
 * - Request timestamp validation
 * - Certificate pinning via network_security_config.xml
 */
@Singleton
class ApiSecurity @Inject constructor() {

    companion object {
        private const val NONCE_HEADER = "X-VettID-Nonce"
        private const val TIMESTAMP_HEADER = "X-VettID-Timestamp"
        private const val SIGNATURE_HEADER = "X-VettID-Signature"
        private const val REQUEST_ID_HEADER = "X-VettID-Request-ID"

        // Maximum age for request timestamp (5 minutes)
        private const val MAX_TIMESTAMP_AGE_MS = 5 * 60 * 1000L

        // Nonce cache size limit
        private const val MAX_NONCE_CACHE_SIZE = 10000
    }

    // Cache of used nonces to prevent replay attacks
    private val usedNonces = ConcurrentHashMap<String, Long>()
    private val secureRandom = SecureRandom()

    /**
     * Generate a cryptographically secure nonce
     */
    fun generateNonce(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Generate a unique request ID
     */
    fun generateRequestId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * Sign a request using HMAC-SHA256
     *
     * @param method HTTP method
     * @param path Request path
     * @param timestamp Unix timestamp in milliseconds
     * @param nonce Unique nonce
     * @param body Request body (empty string if no body)
     * @param secretKey Signing key (should be derived from user credentials)
     */
    fun signRequest(
        method: String,
        path: String,
        timestamp: Long,
        nonce: String,
        body: String,
        secretKey: ByteArray
    ): String {
        // Create canonical request string
        val canonicalRequest = buildString {
            append(method.uppercase())
            append("\n")
            append(path)
            append("\n")
            append(timestamp)
            append("\n")
            append(nonce)
            append("\n")
            append(hashBody(body))
        }

        // Sign with HMAC-SHA256
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey, "HmacSHA256"))
        val signature = mac.doFinal(canonicalRequest.toByteArray(Charsets.UTF_8))

        return Base64.getEncoder().encodeToString(signature)
    }

    /**
     * Hash the request body using SHA-256
     */
    private fun hashBody(body: String): String {
        if (body.isEmpty()) return ""

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(body.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hash)
    }

    /**
     * Validate that a nonce hasn't been used before
     * Returns true if nonce is valid (unused)
     */
    fun validateNonce(nonce: String): Boolean {
        // Clean up old nonces
        cleanupOldNonces()

        // Check if nonce already exists
        if (usedNonces.containsKey(nonce)) {
            return false
        }

        // Add nonce to cache
        usedNonces[nonce] = System.currentTimeMillis()
        return true
    }

    /**
     * Validate request timestamp is recent
     */
    fun validateTimestamp(timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        val age = now - timestamp

        // Timestamp should be recent and not in the future (with small tolerance)
        return age >= -1000 && age <= MAX_TIMESTAMP_AGE_MS
    }

    /**
     * Clean up old nonces from cache
     */
    private fun cleanupOldNonces() {
        val cutoff = System.currentTimeMillis() - MAX_TIMESTAMP_AGE_MS

        // Remove old entries
        usedNonces.entries.removeIf { it.value < cutoff }

        // If still too large, remove oldest entries
        if (usedNonces.size > MAX_NONCE_CACHE_SIZE) {
            val sorted = usedNonces.entries.sortedBy { it.value }
            val toRemove = sorted.take(usedNonces.size - MAX_NONCE_CACHE_SIZE / 2)
            toRemove.forEach { usedNonces.remove(it.key) }
        }
    }

    /**
     * Clear all cached nonces (call on logout)
     */
    fun clearNonceCache() {
        usedNonces.clear()
    }
}

/**
 * OkHttp Interceptor that adds security headers to all requests
 */
class SecurityHeaderInterceptor(
    private val apiSecurity: ApiSecurity,
    private val getSigningKey: () -> ByteArray?
) : Interceptor {

    companion object {
        private const val TIMESTAMP_HEADER = "X-VettID-Timestamp"
        private const val NONCE_HEADER = "X-VettID-Nonce"
        private const val SIGNATURE_HEADER = "X-VettID-Signature"
        private const val REQUEST_ID_HEADER = "X-VettID-Request-ID"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val timestamp = System.currentTimeMillis()
        val nonce = apiSecurity.generateNonce()
        val requestId = apiSecurity.generateRequestId()

        // Build request with security headers
        val requestBuilder = originalRequest.newBuilder()
            .header(TIMESTAMP_HEADER, timestamp.toString())
            .header(NONCE_HEADER, nonce)
            .header(REQUEST_ID_HEADER, requestId)

        // Add signature if signing key is available
        val signingKey = getSigningKey()
        if (signingKey != null) {
            val body = getRequestBody(originalRequest)
            val signature = apiSecurity.signRequest(
                method = originalRequest.method,
                path = originalRequest.url.encodedPath,
                timestamp = timestamp,
                nonce = nonce,
                body = body,
                secretKey = signingKey
            )
            requestBuilder.header(SIGNATURE_HEADER, signature)
        }

        return chain.proceed(requestBuilder.build())
    }

    private fun getRequestBody(request: Request): String {
        return try {
            val copy = request.newBuilder().build()
            val buffer = okio.Buffer()
            copy.body?.writeTo(buffer)
            buffer.readUtf8()
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * OkHttp Interceptor that validates response signatures (optional server-side signing)
 */
class ResponseValidationInterceptor(
    private val apiSecurity: ApiSecurity,
    private val getServerPublicKey: () -> ByteArray?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        // Validate response timestamp to prevent replay
        val responseTimestamp = response.header("X-VettID-Response-Timestamp")?.toLongOrNull()
        if (responseTimestamp != null && !apiSecurity.validateTimestamp(responseTimestamp)) {
            throw SecurityException("Response timestamp validation failed")
        }

        // Additional response validation can be added here
        // (e.g., server-side signature verification)

        return response
    }
}
