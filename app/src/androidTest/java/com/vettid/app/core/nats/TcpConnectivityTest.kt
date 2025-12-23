package com.vettid.app.core.nats

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.app.core.storage.CredentialStore
import io.nats.client.NKey
import io.nats.client.Nats
import io.nats.client.Options
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
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
     * Test jnats library connection with stored credentials.
     */
    @Test
    fun testJnatsConnection() {
        Log.i(TAG, "Testing jnats library connection")

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
        val credentialFile = NatsClient.formatCredentialFile(jwt, seed)

        Log.i(TAG, "Connecting to: ${connection.endpoint}")
        Log.i(TAG, "Credential file:\n${credentialFile.take(200)}...")

        try {
            val options = Options.Builder()
                .server(connection.endpoint)
                .authHandler(Nats.staticCredentials(credentialFile.toByteArray(Charsets.UTF_8)))
                .connectionTimeout(Duration.ofSeconds(30))
                .traceConnection()
                .connectionListener { conn, type ->
                    Log.i(TAG, "Connection event: $type, status=${conn?.status}")
                }
                .errorListener(object : io.nats.client.ErrorListener {
                    override fun errorOccurred(conn: io.nats.client.Connection, error: String) {
                        Log.e(TAG, "NATS error: $error")
                    }
                    override fun exceptionOccurred(conn: io.nats.client.Connection, exp: Exception) {
                        Log.e(TAG, "NATS exception: ${exp.javaClass.name}: ${exp.message}", exp)
                        exp.cause?.let { cause ->
                            Log.e(TAG, "  Cause: ${cause.javaClass.name}: ${cause.message}")
                        }
                    }
                })
                .build()

            Log.i(TAG, "Calling Nats.connect()...")
            val natsConnection = Nats.connect(options)
            Log.i(TAG, "Connected! Status: ${natsConnection.status}")

            natsConnection.close()
            Log.i(TAG, "jnats test PASSED!")

        } catch (e: Exception) {
            Log.e(TAG, "jnats connection failed: ${e.javaClass.name}: ${e.message}", e)
            e.cause?.let { cause ->
                Log.e(TAG, "  Cause: ${cause.javaClass.name}: ${cause.message}")
            }
            fail("jnats connection failed: ${e.message}")
        }
    }
}
