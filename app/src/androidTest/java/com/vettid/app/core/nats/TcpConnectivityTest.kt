package com.vettid.app.core.nats

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.app.core.storage.CredentialStore
import io.nats.client.NKey
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Simple TCP/TLS connectivity test to diagnose NATS connection issues.
 */
@RunWith(AndroidJUnit4::class)
class TcpConnectivityTest {

    companion object {
        private const val TAG = "TcpConnectivityTest"
        private const val NATS_HOST = "nats.vettid.dev"
        private const val NATS_PORT = 4222
        private const val TIMEOUT_MS = 10000
        private const val TEST_API_BASE = "https://tiqpij5mue.execute-api.us-east-1.amazonaws.com"
        private const val TEST_API_KEY = "vettid-test-key-dev-only"
    }

    @Test
    fun testPlainTcpConnection() {
        Log.i(TAG, "Testing plain TCP connection to $NATS_HOST:$NATS_PORT")

        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(NATS_HOST, NATS_PORT), TIMEOUT_MS)

            Log.i(TAG, "TCP connected! Local: ${socket.localAddress}:${socket.localPort}")
            Log.i(TAG, "TCP remote: ${socket.inetAddress}:${socket.port}")

            // Try to read initial data
            socket.soTimeout = 5000
            val buffer = ByteArray(1024)
            val bytesRead = socket.inputStream.read(buffer)

            if (bytesRead > 0) {
                val response = String(buffer, 0, bytesRead)
                Log.i(TAG, "Received ${bytesRead} bytes: ${response.take(200)}")
            }

            socket.close()
            Log.i(TAG, "Plain TCP test PASSED")
        } catch (e: Exception) {
            Log.e(TAG, "Plain TCP connection failed: ${e.javaClass.name}: ${e.message}", e)
            fail("Plain TCP connection failed: ${e.message}")
        }
    }

    @Test
    @Ignore("NATS uses STARTTLS-like protocol - connect via plain TCP first, then upgrade. Direct TLS handshake is not supported.")
    fun testTlsConnection() {
        Log.i(TAG, "Testing TLS connection to $NATS_HOST:$NATS_PORT")

        try {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val socket = sslFactory.createSocket() as SSLSocket

            Log.i(TAG, "Connecting TLS socket...")
            socket.connect(InetSocketAddress(NATS_HOST, NATS_PORT), TIMEOUT_MS)

            Log.i(TAG, "TLS connected, starting handshake...")
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()

            Log.i(TAG, "TLS handshake complete!")
            Log.i(TAG, "Protocol: ${socket.session.protocol}")
            Log.i(TAG, "Cipher: ${socket.session.cipherSuite}")

            // Try to read initial data
            val buffer = ByteArray(1024)
            val bytesRead = socket.inputStream.read(buffer)

            if (bytesRead > 0) {
                val response = String(buffer, 0, bytesRead)
                Log.i(TAG, "Received ${bytesRead} bytes: ${response.take(200)}")
            }

            socket.close()
            Log.i(TAG, "TLS test PASSED")
        } catch (e: Exception) {
            Log.e(TAG, "TLS connection failed: ${e.javaClass.name}: ${e.message}", e)
            fail("TLS connection failed: ${e.message}")
        }
    }

    /**
     * Test manual NATS protocol handshake with credentials.
     * This isolates whether the issue is with credential format or jnats library.
     */
    @Test
    fun testManualNatsProtocol() {
        Log.i(TAG, "Testing manual NATS protocol with credentials")

        // Get stored credentials
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentialStore = CredentialStore(context)

        if (!credentialStore.hasNatsConnection()) {
            Log.w(TAG, "No NATS credentials stored, skipping test")
            return
        }

        val parsed = credentialStore.getParsedNatsCredentials()
        if (parsed == null) {
            fail("Failed to parse NATS credentials")
            return
        }

        val (jwt, seed) = parsed
        Log.i(TAG, "JWT length: ${jwt.length}, Seed prefix: ${seed.take(10)}...")

        try {
            // Connect via TLS
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val socket = sslFactory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(NATS_HOST, NATS_PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()

            Log.i(TAG, "TLS connected, reading INFO...")

            // Read INFO message
            val reader = socket.inputStream.bufferedReader()
            val writer = socket.outputStream.bufferedWriter()

            val infoLine = reader.readLine()
            Log.i(TAG, "Received: $infoLine")

            if (!infoLine.startsWith("INFO ")) {
                fail("Expected INFO, got: $infoLine")
                return
            }

            // Parse INFO JSON
            val infoJson = JSONObject(infoLine.substring(5))
            val nonce = infoJson.optString("nonce", "")
            Log.i(TAG, "Server nonce: $nonce")
            Log.i(TAG, "Server requires auth: ${infoJson.optBoolean("auth_required", false)}")

            // Create NKEY from seed and sign the nonce
            val nkey = NKey.fromSeed(seed.toCharArray())
            val signature = if (nonce.isNotEmpty()) {
                val signedBytes = nkey.sign(nonce.toByteArray())
                Base64.getUrlEncoder().withoutPadding().encodeToString(signedBytes)
            } else {
                ""
            }

            Log.i(TAG, "NKEY public key: ${String(nkey.publicKey)}")
            Log.i(TAG, "Signature: ${signature.take(20)}...")

            // Build CONNECT message
            val connectJson = JSONObject().apply {
                put("verbose", true)
                put("pedantic", false)
                put("tls_required", true)
                put("name", "android-test")
                put("lang", "kotlin")
                put("version", "1.0.0")
                put("protocol", 1)
                put("jwt", jwt)
                put("nkey", String(nkey.publicKey))
                if (signature.isNotEmpty()) {
                    put("sig", signature)
                }
            }

            val connectCmd = "CONNECT ${connectJson}\r\n"
            Log.i(TAG, "Sending: CONNECT ${connectJson.toString().take(100)}...")

            writer.write(connectCmd)
            writer.flush()

            // Send PING to test connection
            writer.write("PING\r\n")
            writer.flush()

            // Read response
            val response = reader.readLine()
            Log.i(TAG, "Response: $response")

            if (response == "+OK") {
                val pongResponse = reader.readLine()
                Log.i(TAG, "PING response: $pongResponse")
                if (pongResponse == "PONG") {
                    Log.i(TAG, "Manual NATS protocol test PASSED!")
                } else {
                    fail("Expected PONG, got: $pongResponse")
                }
            } else if (response == "PONG") {
                Log.i(TAG, "Manual NATS protocol test PASSED! (no verbose)")
            } else if (response?.startsWith("-ERR") == true) {
                Log.e(TAG, "NATS error: $response")
                fail("NATS authentication failed: $response")
            } else {
                Log.w(TAG, "Unexpected response: $response")
            }

            socket.close()

        } catch (e: Exception) {
            Log.e(TAG, "Manual NATS test failed: ${e.javaClass.name}: ${e.message}", e)
            fail("Manual NATS test failed: ${e.message}")
        }
    }

    /**
     * Test AndroidNatsClient with stored credentials.
     */
    @Test
    fun testAndroidNatsClientWithStoredCredentials() {
        Log.i(TAG, "Testing AndroidNatsClient with stored credentials")

        // Get stored credentials
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentialStore = CredentialStore(context)

        if (!credentialStore.hasNatsConnection()) {
            Log.w(TAG, "No NATS credentials stored, skipping test")
            return
        }

        val connection = credentialStore.getNatsConnection()
        val parsed = credentialStore.getParsedNatsCredentials()

        if (connection == null || parsed == null) {
            fail("Failed to get NATS credentials")
            return
        }

        val (jwt, seed) = parsed

        Log.i(TAG, "Connecting to: ${connection.endpoint}")
        Log.i(TAG, "JWT (first 100 chars): ${jwt.take(100)}...")
        Log.i(TAG, "Seed (first 10 chars): ${seed.take(10)}...")

        try {
            val androidClient = AndroidNatsClient()

            // Run connection in blocking way for test
            val result = kotlinx.coroutines.runBlocking {
                androidClient.connect(connection.endpoint, jwt, seed)
            }

            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Connection failed")
            }

            Log.i(TAG, "Connected! Status: ${androidClient.isConnected}")

            kotlinx.coroutines.runBlocking {
                androidClient.disconnect()
            }
            Log.i(TAG, "AndroidNatsClient test with stored credentials PASSED!")

        } catch (e: Exception) {
            Log.e(TAG, "AndroidNatsClient connection failed: ${e.javaClass.name}: ${e.message}", e)
            e.cause?.let { cause ->
                Log.e(TAG, "  Cause: ${cause.javaClass.name}: ${cause.message}")
            }
            fail("AndroidNatsClient connection failed: ${e.message}")
        }
    }

    /**
     * Test manual protocol with fresh credentials from test API.
     * This verifies that credentials work before trying jnats.
     */
    @Test
    fun testManualProtocolWithFreshCredentials() {
        Log.i(TAG, "Testing manual protocol with fresh credentials from test API")

        try {
            // Step 1: Create fresh test invitation
            Log.i(TAG, "Creating test invitation...")
            val testUserId = "android_manual_test_${System.currentTimeMillis()}"
            val invitationCode = createTestInvitation(testUserId)
            Log.i(TAG, "Got invitation code: $invitationCode")

            // Step 2: Start enrollment
            Log.i(TAG, "Starting enrollment...")
            val enrollSession = startEnrollment(invitationCode)
            val enrollmentToken = enrollSession.getString("enrollment_token")
            val passwordKeyId = enrollSession.getString("password_key_id")

            // Step 3: Set password
            Log.i(TAG, "Setting password...")
            setPassword(enrollSession.getString("enrollment_session_id"), enrollmentToken, passwordKeyId)

            // Step 4: Finalize enrollment
            Log.i(TAG, "Finalizing enrollment...")
            val credentials = finalizeEnrollment(enrollSession.getString("enrollment_session_id"), enrollmentToken)

            // Get NATS credentials
            val vaultBootstrap = credentials.getJSONObject("vault_bootstrap")
            val credsFile = vaultBootstrap.getString("credentials")
            val (jwt, seed) = parseCredsFile(credsFile)

            Log.i(TAG, "Testing manual protocol with fresh JWT (${jwt.length} chars)")

            // Connect via TLS and test manually
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val socket = sslFactory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(NATS_HOST, NATS_PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()

            Log.i(TAG, "TLS connected (ACM), reading INFO...")

            val reader = socket.inputStream.bufferedReader()
            val writer = socket.outputStream.bufferedWriter()

            val infoLine = reader.readLine()
            Log.i(TAG, "INFO received: ${infoLine.take(100)}...")

            if (!infoLine.startsWith("INFO ")) {
                fail("Expected INFO, got: $infoLine")
                return
            }

            val infoJson = JSONObject(infoLine.substring(5))
            val nonce = infoJson.optString("nonce", "")
            Log.i(TAG, "Server nonce: $nonce")

            // Sign nonce with fresh credentials
            val nkey = NKey.fromSeed(seed.toCharArray())
            val signature = if (nonce.isNotEmpty()) {
                val signedBytes = nkey.sign(nonce.toByteArray())
                Base64.getUrlEncoder().withoutPadding().encodeToString(signedBytes)
            } else ""

            // Build CONNECT
            val connectJson = JSONObject().apply {
                put("verbose", true)
                put("pedantic", false)
                put("tls_required", true)
                put("name", "android-fresh-test")
                put("lang", "kotlin")
                put("version", "1.0.0")
                put("protocol", 1)
                put("jwt", jwt)
                put("nkey", String(nkey.publicKey))
                if (signature.isNotEmpty()) put("sig", signature)
            }

            writer.write("CONNECT ${connectJson}\r\n")
            writer.write("PING\r\n")
            writer.flush()

            val response = reader.readLine()
            Log.i(TAG, "Response: $response")

            if (response == "+OK") {
                val pong = reader.readLine()
                if (pong == "PONG") {
                    Log.i(TAG, "✅ Manual protocol with fresh credentials PASSED!")
                } else {
                    fail("Expected PONG, got: $pong")
                }
            } else if (response == "PONG") {
                Log.i(TAG, "✅ Manual protocol with fresh credentials PASSED!")
            } else {
                fail("NATS auth failed: $response")
            }

            socket.close()
            cleanupTestUser(testUserId)

        } catch (e: Exception) {
            Log.e(TAG, "Manual fresh credentials test failed: ${e.message}", e)
            fail("Manual fresh credentials test failed: ${e.message}")
        }
    }

    /**
     * Test jnats connection with fresh credentials from test API.
     * This bypasses the EncryptedSharedPreferences issue in instrumented tests.
     */
    @Test
    fun testJnatsWithFreshCredentials() {
        Log.i(TAG, "Testing jnats with fresh credentials from test API")

        try {
            // Step 1: Create fresh test invitation
            Log.i(TAG, "Creating test invitation...")
            val testUserId = "android_jnats_test_${System.currentTimeMillis()}"
            val invitationCode = createTestInvitation(testUserId)
            Log.i(TAG, "Got invitation code: $invitationCode")

            // Step 2: Start enrollment
            Log.i(TAG, "Starting enrollment...")
            val enrollSession = startEnrollment(invitationCode)
            Log.i(TAG, "Start enrollment response: $enrollSession")
            val enrollmentSessionId = enrollSession.getString("enrollment_session_id")
            val enrollmentToken = enrollSession.getString("enrollment_token")
            val passwordKeyId = enrollSession.getString("password_key_id")
            Log.i(TAG, "Enrollment session: $enrollmentSessionId, PasswordKeyId: $passwordKeyId")

            // Step 3: Set password (simplified - no encryption for test)
            Log.i(TAG, "Setting password...")
            setPassword(enrollmentSessionId, enrollmentToken, passwordKeyId)

            // Step 4: Finalize enrollment
            Log.i(TAG, "Finalizing enrollment...")
            val credentials = finalizeEnrollment(enrollmentSessionId, enrollmentToken)
            Log.i(TAG, "Finalize response keys: ${credentials.keys().asSequence().toList()}")
            Log.i(TAG, "Finalize response: $credentials")

            // NATS credentials are in vault_bootstrap
            val vaultBootstrap = credentials.getJSONObject("vault_bootstrap")
            val endpoint = vaultBootstrap.optString("nats_endpoint", "tls://nats.vettid.dev:4222")
            val credsFile = vaultBootstrap.getString("credentials")

            Log.i(TAG, "NATS endpoint: $endpoint")
            Log.i(TAG, "Credentials length: ${credsFile.length}")
            Log.i(TAG, "Credentials preview: ${credsFile.take(100)}...")

            // Parse JWT and seed from credentials file
            val (jwt, seed) = parseCredsFile(credsFile)
            Log.i(TAG, "Parsed JWT (${jwt.length} chars): ${jwt.take(50)}...")
            Log.i(TAG, "Parsed seed (${seed.length} chars): ${seed.take(10)}...")

            // Step 5: Connect to NATS with custom AndroidNatsClient
            Log.i(TAG, "Connecting to NATS with AndroidNatsClient...")

            val androidClient = AndroidNatsClient()
            val connectResult = kotlinx.coroutines.runBlocking {
                androidClient.connect(endpoint, jwt, seed)
            }

            if (connectResult.isFailure) {
                throw connectResult.exceptionOrNull() ?: Exception("Connection failed")
            }

            Log.i(TAG, "Connected! Status: ${androidClient.isConnected}")

            // Step 6: Test publish
            val publishResult = kotlinx.coroutines.runBlocking {
                androidClient.publish("test.ping", "Hello from Android!".toByteArray())
            }
            if (publishResult.isFailure) {
                throw publishResult.exceptionOrNull() ?: Exception("Publish failed")
            }
            kotlinx.coroutines.runBlocking { androidClient.flush() }
            Log.i(TAG, "Published test message")

            kotlinx.coroutines.runBlocking { androidClient.disconnect() }
            Log.i(TAG, "✅ AndroidNatsClient test with fresh credentials PASSED!")

            // Cleanup
            cleanupTestUser(testUserId)

        } catch (e: Exception) {
            Log.e(TAG, "jnats fresh credentials test failed: ${e.javaClass.name}: ${e.message}", e)
            e.printStackTrace()
            fail("jnats fresh credentials test failed: ${e.message}")
        }
    }

    // Helper function to parse JWT and seed from creds file
    private fun parseCredsFile(credsFile: String): Pair<String, String> {
        // Use dotall mode (s flag) and non-greedy match for multiline content
        val jwtPattern = "-----BEGIN NATS USER JWT-----\\s*([A-Za-z0-9._-]+)\\s*-----END NATS USER JWT-----".toRegex()
        val seedPattern = "-----BEGIN USER NKEY SEED-----\\s*([A-Za-z0-9]+)\\s*-----END USER NKEY SEED-----".toRegex()

        Log.i(TAG, "Parsing creds file (${credsFile.length} chars)")

        val jwt = jwtPattern.find(credsFile)?.groupValues?.get(1)?.trim()
        if (jwt == null) {
            Log.e(TAG, "JWT pattern not found. Creds preview: ${credsFile.take(500)}")
            throw IllegalArgumentException("JWT not found in creds file")
        }

        val seed = seedPattern.find(credsFile)?.groupValues?.get(1)?.trim()
        if (seed == null) {
            Log.e(TAG, "Seed pattern not found")
            throw IllegalArgumentException("Seed not found in creds file")
        }

        return Pair(jwt, seed)
    }

    // Helper functions for test API
    private fun createTestInvitation(testUserId: String): String {
        val url = URL("https://tiqpij5mue.execute-api.us-east-1.amazonaws.com/test/create-invitation")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Test-Api-Key", "vettid-test-key-dev-only")
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use {
            it.write("""{"test_user_id": "$testUserId"}""")
        }

        val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
        val json = JSONObject(response)
        return json.getString("invitation_code")
    }

    private fun startEnrollment(invitationCode: String): JSONObject {
        // Use test API endpoint with direct flow (no auth needed)
        val url = URL("$TEST_API_BASE/vault/enroll/start-direct")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Test-Api-Key", TEST_API_KEY)
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use {
            it.write("""{"invitation_code": "$invitationCode", "device_id": "test_device_${System.currentTimeMillis()}", "device_type": "android", "skip_attestation": true}""")
        }

        if (conn.responseCode != 200) {
            val error = try { BufferedReader(InputStreamReader(conn.errorStream)).readText() } catch (e: Exception) { "unknown" }
            throw Exception("start-direct failed: ${conn.responseCode} - $error")
        }

        val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
        return JSONObject(response)
    }

    private fun setPassword(enrollmentSessionId: String, enrollmentToken: String, passwordKeyId: String) {
        val url = URL("$TEST_API_BASE/vault/enroll/set-password")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $enrollmentToken")
        conn.setRequestProperty("X-Test-Api-Key", TEST_API_KEY)
        conn.doOutput = true

        // For test, we use a simple hash (in production, this would be properly encrypted)
        val testPasswordHash = "dGVzdHBhc3N3b3JkaGFzaA==" // base64 of "testpasswordhash"

        OutputStreamWriter(conn.outputStream).use {
            it.write("""{
                "enrollment_session_id": "$enrollmentSessionId",
                "encrypted_password_hash": "$testPasswordHash",
                "ephemeral_public_key": "test_key_eph",
                "nonce": "test_nonce_12345",
                "key_id": "$passwordKeyId"
            }""".trimIndent())
        }

        if (conn.responseCode != 200) {
            val error = try { BufferedReader(InputStreamReader(conn.errorStream)).readText() } catch (e: Exception) { "unknown" }
            throw Exception("set-password failed: ${conn.responseCode} - $error")
        }
    }

    private fun finalizeEnrollment(enrollmentSessionId: String, enrollmentToken: String): JSONObject {
        val url = URL("$TEST_API_BASE/vault/enroll/finalize")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $enrollmentToken")
        conn.setRequestProperty("X-Test-Api-Key", TEST_API_KEY)
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use {
            it.write("""{
                "enrollment_session_id": "$enrollmentSessionId"
            }""".trimIndent())
        }

        if (conn.responseCode != 200) {
            val error = try { BufferedReader(InputStreamReader(conn.errorStream)).readText() } catch (e: Exception) { "unknown" }
            throw Exception("finalize failed: ${conn.responseCode} - $error")
        }

        val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
        return JSONObject(response)
    }

    private fun cleanupTestUser(testUserId: String) {
        try {
            val url = URL("$TEST_API_BASE/test/cleanup")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Test-Api-Key", TEST_API_KEY)
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use {
                it.write("""{"test_user_id": "$testUserId"}""")
            }
            conn.inputStream.close()
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup failed: ${e.message}")
        }
    }
}
