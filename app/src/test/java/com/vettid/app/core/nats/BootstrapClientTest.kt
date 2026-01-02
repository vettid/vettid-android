package com.vettid.app.core.nats

import com.vettid.app.core.attestation.ExpectedPcrs
import com.vettid.app.core.attestation.NitroAttestationVerifier
import com.vettid.app.core.attestation.PcrConfigManager
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class BootstrapClientTest {

    private lateinit var credentialStore: CredentialStore
    private lateinit var attestationVerifier: NitroAttestationVerifier
    private lateinit var pcrConfigManager: PcrConfigManager
    private lateinit var bootstrapClient: BootstrapClient
    private lateinit var natsClient: NatsClient

    private val testOwnerSpaceId = "OwnerSpace.test-user-123"
    private val testDeviceId = "device-abc123"

    @Before
    fun setup() {
        credentialStore = mock()
        attestationVerifier = mock()
        pcrConfigManager = mock()
        natsClient = mock()

        // Default mock behaviors
        whenever(pcrConfigManager.getCurrentPcrs()).thenReturn(
            ExpectedPcrs(
                pcr0 = "0".repeat(96),
                pcr1 = "0".repeat(96),
                pcr2 = "0".repeat(96),
                version = "test-1.0.0"
            )
        )

        bootstrapClient = BootstrapClient(credentialStore, attestationVerifier, pcrConfigManager)
        // Disable attestation for most tests
        bootstrapClient.requireAttestation = false
    }

    // MARK: - hasBootstrapCredentials Tests

    @Test
    fun `hasBootstrapCredentials returns true when bootstrap topic is stored`() {
        whenever(credentialStore.getNatsBootstrapTopic()).thenReturn("OwnerSpace.test.forVault.app.bootstrap")
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)

        assertTrue(bootstrapClient.hasBootstrapCredentials())
    }

    @Test
    fun `hasBootstrapCredentials returns false when no bootstrap topic`() {
        whenever(credentialStore.getNatsBootstrapTopic()).thenReturn(null)
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)

        assertFalse(bootstrapClient.hasBootstrapCredentials())
    }

    @Test
    fun `hasBootstrapCredentials returns false when no NATS connection`() {
        whenever(credentialStore.getNatsBootstrapTopic()).thenReturn("topic")
        whenever(credentialStore.hasNatsConnection()).thenReturn(false)

        assertFalse(bootstrapClient.hasBootstrapCredentials())
    }

    // MARK: - hasFullCredentials Tests

    @Test
    fun `hasFullCredentials returns true when NATS connection without bootstrap topic`() {
        whenever(credentialStore.getNatsBootstrapTopic()).thenReturn(null)
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)

        assertTrue(bootstrapClient.hasFullCredentials())
    }

    @Test
    fun `hasFullCredentials returns false when bootstrap topic exists`() {
        whenever(credentialStore.getNatsBootstrapTopic()).thenReturn("topic")
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)

        assertFalse(bootstrapClient.hasFullCredentials())
    }

    @Test
    fun `hasFullCredentials returns false when no NATS connection`() {
        whenever(credentialStore.getNatsBootstrapTopic()).thenReturn(null)
        whenever(credentialStore.hasNatsConnection()).thenReturn(false)

        assertFalse(bootstrapClient.hasFullCredentials())
    }

    // MARK: - executeBootstrap Tests

    @Test
    fun `executeBootstrap fails when subscription fails`() = runTest {
        whenever(natsClient.subscribe(any(), any())).thenReturn(
            Result.failure(NatsException("Subscription failed"))
        )

        val result = bootstrapClient.executeBootstrap(
            natsClient = natsClient,
            ownerSpaceId = testOwnerSpaceId,
            deviceId = testDeviceId
        )

        assertTrue(result.isFailure)
        val errorMessage = result.exceptionOrNull()?.message ?: ""
        assertTrue("Expected error to contain 'Subscription failed' but was: $errorMessage",
            errorMessage.contains("Subscription failed"))
    }
}
