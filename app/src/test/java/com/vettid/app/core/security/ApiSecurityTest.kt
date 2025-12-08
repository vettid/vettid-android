package com.vettid.app.core.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApiSecurityTest {

    private lateinit var apiSecurity: ApiSecurity

    @Before
    fun setup() {
        apiSecurity = ApiSecurity()
    }

    @Test
    fun `generateNonce creates unique values`() {
        val nonce1 = apiSecurity.generateNonce()
        val nonce2 = apiSecurity.generateNonce()

        assertNotEquals(nonce1, nonce2)
        assertTrue(nonce1.isNotEmpty())
        assertTrue(nonce2.isNotEmpty())
    }

    @Test
    fun `generateRequestId creates unique UUIDs`() {
        val id1 = apiSecurity.generateRequestId()
        val id2 = apiSecurity.generateRequestId()

        assertNotEquals(id1, id2)
        // Validate UUID format
        assertTrue(id1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun `signRequest produces consistent signature for same inputs`() {
        val secretKey = "test-secret-key".toByteArray()
        val timestamp = 1699999999L
        val nonce = "test-nonce"
        val body = """{"test": "data"}"""

        val sig1 = apiSecurity.signRequest("POST", "/api/test", timestamp, nonce, body, secretKey)
        val sig2 = apiSecurity.signRequest("POST", "/api/test", timestamp, nonce, body, secretKey)

        assertEquals(sig1, sig2)
    }

    @Test
    fun `signRequest produces different signatures for different inputs`() {
        val secretKey = "test-secret-key".toByteArray()
        val timestamp = 1699999999L
        val nonce = "test-nonce"

        val sig1 = apiSecurity.signRequest("POST", "/api/test", timestamp, nonce, "body1", secretKey)
        val sig2 = apiSecurity.signRequest("POST", "/api/test", timestamp, nonce, "body2", secretKey)

        assertNotEquals(sig1, sig2)
    }

    @Test
    fun `signRequest different method produces different signature`() {
        val secretKey = "test-secret-key".toByteArray()
        val timestamp = 1699999999L
        val nonce = "test-nonce"
        val body = """{"test": "data"}"""

        val sig1 = apiSecurity.signRequest("GET", "/api/test", timestamp, nonce, body, secretKey)
        val sig2 = apiSecurity.signRequest("POST", "/api/test", timestamp, nonce, body, secretKey)

        assertNotEquals(sig1, sig2)
    }

    @Test
    fun `signRequest different path produces different signature`() {
        val secretKey = "test-secret-key".toByteArray()
        val timestamp = 1699999999L
        val nonce = "test-nonce"
        val body = """{"test": "data"}"""

        val sig1 = apiSecurity.signRequest("POST", "/api/test1", timestamp, nonce, body, secretKey)
        val sig2 = apiSecurity.signRequest("POST", "/api/test2", timestamp, nonce, body, secretKey)

        assertNotEquals(sig1, sig2)
    }

    @Test
    fun `validateNonce accepts unused nonce`() {
        val nonce = apiSecurity.generateNonce()

        assertTrue(apiSecurity.validateNonce(nonce))
    }

    @Test
    fun `validateNonce rejects already used nonce`() {
        val nonce = apiSecurity.generateNonce()

        // First use should succeed
        assertTrue(apiSecurity.validateNonce(nonce))

        // Second use should fail (replay protection)
        assertFalse(apiSecurity.validateNonce(nonce))
    }

    @Test
    fun `validateTimestamp accepts recent timestamp`() {
        val now = System.currentTimeMillis()

        assertTrue(apiSecurity.validateTimestamp(now))
        assertTrue(apiSecurity.validateTimestamp(now - 1000)) // 1 second ago
        assertTrue(apiSecurity.validateTimestamp(now - 60_000)) // 1 minute ago
    }

    @Test
    fun `validateTimestamp rejects old timestamp`() {
        val now = System.currentTimeMillis()
        val sixMinutesAgo = now - (6 * 60 * 1000)

        assertFalse(apiSecurity.validateTimestamp(sixMinutesAgo))
    }

    @Test
    fun `validateTimestamp rejects far future timestamp`() {
        val now = System.currentTimeMillis()
        val twoSecondsInFuture = now + 2000

        assertFalse(apiSecurity.validateTimestamp(twoSecondsInFuture))
    }

    @Test
    fun `validateTimestamp allows small clock drift`() {
        val now = System.currentTimeMillis()
        val halfSecondInFuture = now + 500

        // Small future drift should be allowed
        assertTrue(apiSecurity.validateTimestamp(halfSecondInFuture))
    }

    @Test
    fun `clearNonceCache removes all nonces`() {
        // Add some nonces
        val nonce1 = apiSecurity.generateNonce()
        val nonce2 = apiSecurity.generateNonce()
        apiSecurity.validateNonce(nonce1)
        apiSecurity.validateNonce(nonce2)

        // Clear cache
        apiSecurity.clearNonceCache()

        // Nonces should be valid again (cache cleared)
        assertTrue(apiSecurity.validateNonce(nonce1))
        assertTrue(apiSecurity.validateNonce(nonce2))
    }

    @Test
    fun `signRequest with empty body produces valid signature`() {
        val secretKey = "test-secret-key".toByteArray()
        val timestamp = 1699999999L
        val nonce = "test-nonce"

        val signature = apiSecurity.signRequest("GET", "/api/test", timestamp, nonce, "", secretKey)

        assertTrue(signature.isNotEmpty())
    }
}
