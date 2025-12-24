package com.vettid.app.core.nats

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented test for NATS connection using stored credentials.
 *
 * Prerequisites:
 * - Complete enrollment on the device first to store NATS credentials
 * - Run: adb shell am instrument -w -e class com.vettid.app.core.nats.NatsConnectionTest com.vettid.app.automation.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class NatsConnectionTest {

    private lateinit var credentialStore: CredentialStore
    private lateinit var natsClient: NatsClient

    companion object {
        private const val TAG = "NatsConnectionTest"
    }

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        credentialStore = CredentialStore(context)
        natsClient = NatsClient()
    }

    @After
    fun tearDown() {
        runBlocking {
            natsClient.disconnect()
        }
    }

    @Test
    fun testStoredCredentialsExist() {
        assumeTrue("NATS credentials not stored - enroll device first", credentialStore.hasNatsConnection())

        val connection = credentialStore.getNatsConnection()
        assertNotNull("NATS connection info should not be null", connection)

        Log.i(TAG, "NATS endpoint: ${connection?.endpoint}")
        Log.i(TAG, "NATS owner space: ${connection?.ownerSpace}")
        Log.i(TAG, "NATS send topic: ${connection?.topics?.sendToVault}")
        Log.i(TAG, "NATS receive topic: ${connection?.topics?.receiveFromVault}")
    }

    @Test
    fun testCredentialFileParsing() {
        assumeTrue("NATS credentials not stored - enroll device first", credentialStore.hasNatsConnection())

        val parsed = credentialStore.getParsedNatsCredentials()
        assertNotNull("Parsed credentials should not be null", parsed)

        val (jwt, seed) = parsed!!
        assertTrue("JWT should not be empty", jwt.isNotEmpty())
        assertTrue("Seed should not be empty", seed.isNotEmpty())
        assertTrue("JWT should be a valid JWT format", jwt.contains("."))
        assertTrue("Seed should start with S", seed.startsWith("S"))

        Log.i(TAG, "JWT length: ${jwt.length}")
        Log.i(TAG, "Seed: ${seed.take(10)}...")
    }

    @Test
    fun testNatsConnection() {
        assumeTrue("NATS credentials not stored - enroll device first", credentialStore.hasNatsConnection())

        runBlocking {
        // Get stored credentials
        val connection = credentialStore.getNatsConnection()
        assertNotNull("NATS connection info required", connection)

        val parsed = credentialStore.getParsedNatsCredentials()
        assertNotNull("Parsed credentials required", parsed)

        val (jwt, seed) = parsed!!

        // Create NatsCredentials object
        val credentials = NatsCredentials(
            tokenId = "test-token",
            jwt = jwt,
            seed = seed,
            endpoint = connection!!.endpoint,
            expiresAt = Instant.now().plusSeconds(3600),
            permissions = NatsPermissions(
                publish = listOf(connection.topics?.sendToVault ?: ""),
                subscribe = listOf(connection.topics?.receiveFromVault ?: "")
            )
        )

        Log.i(TAG, "Connecting to NATS at ${credentials.endpoint}...")

        // Connect to NATS
        val result = natsClient.connect(credentials)

        assertTrue("Connection should succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertTrue("Should be connected", natsClient.isConnected)
        assertEquals("Status should be CONNECTED", ConnectionStatus.CONNECTED, natsClient.connectionStatus)

        Log.i(TAG, "Successfully connected to NATS!")
        }
    }

    @Test
    fun testPublishToVault() {
        assumeTrue("NATS credentials not stored - enroll device first", credentialStore.hasNatsConnection())

        runBlocking {
        // Get stored credentials
        val connection = credentialStore.getNatsConnection()
        assertNotNull("NATS connection info required", connection)

        val parsed = credentialStore.getParsedNatsCredentials()
        assertNotNull("Parsed credentials required", parsed)

        val (jwt, seed) = parsed!!

        // Create NatsCredentials and connect
        val credentials = NatsCredentials(
            tokenId = "test-token",
            jwt = jwt,
            seed = seed,
            endpoint = connection!!.endpoint,
            expiresAt = Instant.now().plusSeconds(3600),
            permissions = NatsPermissions(
                publish = listOf(connection.topics?.sendToVault ?: ""),
                subscribe = listOf(connection.topics?.receiveFromVault ?: "")
            )
        )

        val connectResult = natsClient.connect(credentials)
        assertTrue("Connection should succeed", connectResult.isSuccess)

        // Try to publish a test message
        val sendTopic = connection.topics?.sendToVault?.replace(".>", ".ping")
            ?: "${connection.ownerSpace}.forVault.ping"

        Log.i(TAG, "Publishing to topic: $sendTopic")

        val testMessage = """{"type":"ping","timestamp":"${Instant.now()}"}"""
        val publishResult = natsClient.publish(sendTopic, testMessage)

        assertTrue("Publish should succeed: ${publishResult.exceptionOrNull()?.message}", publishResult.isSuccess)

        // Flush to ensure message is sent
        val flushResult = natsClient.flush()
        assertTrue("Flush should succeed", flushResult.isSuccess)

        Log.i(TAG, "Successfully published message to vault topic!")
        }
    }

    @Test
    fun testSubscribeToAppTopic() {
        assumeTrue("NATS credentials not stored - enroll device first", credentialStore.hasNatsConnection())

        runBlocking {
        // Get stored credentials
        val connection = credentialStore.getNatsConnection()
        assertNotNull("NATS connection info required", connection)

        val parsed = credentialStore.getParsedNatsCredentials()
        assertNotNull("Parsed credentials required", parsed)

        val (jwt, seed) = parsed!!

        // Create NatsCredentials and connect
        val credentials = NatsCredentials(
            tokenId = "test-token",
            jwt = jwt,
            seed = seed,
            endpoint = connection!!.endpoint,
            expiresAt = Instant.now().plusSeconds(3600),
            permissions = NatsPermissions(
                publish = listOf(connection.topics?.sendToVault ?: ""),
                subscribe = listOf(connection.topics?.receiveFromVault ?: "")
            )
        )

        val connectResult = natsClient.connect(credentials)
        assertTrue("Connection should succeed", connectResult.isSuccess)

        // Subscribe to app topic
        val receiveTopic = connection.topics?.receiveFromVault
            ?: "${connection.ownerSpace}.forApp.>"

        Log.i(TAG, "Subscribing to topic: $receiveTopic")

        val latch = CountDownLatch(1)
        var receivedMessage: NatsMessage? = null

        val subscribeResult = natsClient.subscribe(receiveTopic) { message ->
            Log.i(TAG, "Received message on ${message.subject}: ${message.dataString}")
            receivedMessage = message
            latch.countDown()
        }

        assertTrue("Subscribe should succeed: ${subscribeResult.exceptionOrNull()?.message}", subscribeResult.isSuccess)

        val subscription = subscribeResult.getOrNull()
        assertNotNull("Subscription should not be null", subscription)
        assertTrue("Subscription should be active", subscription!!.isActive())

        Log.i(TAG, "Successfully subscribed to app topic!")

        // Wait briefly for any messages (vault may send events)
        val received = latch.await(2, TimeUnit.SECONDS)
        if (received) {
            Log.i(TAG, "Received message from vault: ${receivedMessage?.dataString}")
        } else {
            Log.i(TAG, "No messages received (vault may not be running)")
        }
        }
    }
}
