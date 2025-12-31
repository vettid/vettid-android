package com.vettid.app.core.nats

import com.vettid.app.core.network.NatsConnectionInfo
import com.vettid.app.core.network.VaultLifecycleClient
import com.vettid.app.core.network.VaultLifecycleStatusResponse
import com.vettid.app.core.network.VaultStartResponse
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class NatsAutoConnectorTest {

    private lateinit var natsClient: NatsClient
    private lateinit var connectionManager: NatsConnectionManager
    private lateinit var ownerSpaceClient: OwnerSpaceClient
    private lateinit var credentialStore: CredentialStore
    private lateinit var credentialClient: NatsCredentialClient
    private lateinit var bootstrapClient: BootstrapClient
    private lateinit var vaultLifecycleClient: VaultLifecycleClient
    private lateinit var autoConnector: NatsAutoConnector

    private val testJwt = "eyJhbGciOiJlZDI1NTE5In0.test"
    private val testSeed = "SUAIBDPBAUTW..."
    private val testEndpoint = "tls://nats.vettid.dev:4222"
    private val testOwnerSpaceId = "OwnerSpace.test-user-123"
    private val testMessageSpaceId = "MessageSpace.test-user-123"
    private val testCredentialFile = """-----BEGIN NATS USER JWT-----
$testJwt
-----END NATS USER JWT-----

-----BEGIN USER NKEY SEED-----
$testSeed
-----END USER NKEY SEED-----"""

    private val credentialRotationFlow = MutableSharedFlow<CredentialRotationMessage>()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        natsClient = mock()
        connectionManager = mock()
        ownerSpaceClient = mock()
        credentialStore = mock()
        credentialClient = mock()
        bootstrapClient = mock()
        vaultLifecycleClient = mock()

        // Stub the credentialRotation flow
        whenever(ownerSpaceClient.credentialRotation).thenReturn(credentialRotationFlow)

        autoConnector = NatsAutoConnector(
            natsClient = natsClient,
            connectionManager = connectionManager,
            ownerSpaceClient = ownerSpaceClient,
            credentialStore = credentialStore,
            credentialClient = credentialClient,
            bootstrapClient = bootstrapClient,
            vaultLifecycleClient = vaultLifecycleClient
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // MARK: - checkCredentialState Tests

    @Test
    fun `checkCredentialState returns NotEnrolled when no credentials stored`() {
        whenever(credentialStore.hasNatsConnection()).thenReturn(false)

        val state = autoConnector.checkCredentialState()

        assertEquals(NatsAutoConnector.CredentialState.NotEnrolled, state)
    }

    @Test
    fun `checkCredentialState returns Valid when credentials exist and valid`() {
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)
        whenever(credentialStore.areNatsCredentialsValid()).thenReturn(true)

        val state = autoConnector.checkCredentialState()

        assertEquals(NatsAutoConnector.CredentialState.Valid, state)
    }

    @Test
    fun `checkCredentialState returns Expired when credentials exist but expired`() {
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)
        whenever(credentialStore.areNatsCredentialsValid()).thenReturn(false)

        val state = autoConnector.checkCredentialState()

        assertEquals(NatsAutoConnector.CredentialState.Expired, state)
    }

    // MARK: - autoConnect Tests - Not Enrolled

    @Test
    fun `autoConnect returns NotEnrolled when no credentials stored`() = runTest {
        whenever(credentialStore.hasNatsConnection()).thenReturn(false)

        val result = autoConnector.autoConnect()

        assertEquals(NatsAutoConnector.ConnectionResult.NotEnrolled, result)
        verify(natsClient, never()).connect(any())
    }

    @Test
    fun `autoConnect sets Failed state when not enrolled`() = runTest {
        whenever(credentialStore.hasNatsConnection()).thenReturn(false)

        autoConnector.autoConnect()

        val state = autoConnector.connectionState.first()
        assertTrue(state is NatsAutoConnector.AutoConnectState.Failed)
        val failedState = state as NatsAutoConnector.AutoConnectState.Failed
        assertEquals(NatsAutoConnector.ConnectionResult.NotEnrolled, failedState.result)
    }

    // MARK: - autoConnect Tests - Credentials Expired

    @Test
    fun `autoConnect returns CredentialsExpired when credentials are expired`() = runTest {
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)
        whenever(credentialStore.areNatsCredentialsValid()).thenReturn(false)

        val result = autoConnector.autoConnect()

        assertEquals(NatsAutoConnector.ConnectionResult.CredentialsExpired, result)
        verify(natsClient, never()).connect(any())
    }

    @Test
    fun `autoConnect sets Failed state when credentials expired`() = runTest {
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)
        whenever(credentialStore.areNatsCredentialsValid()).thenReturn(false)

        autoConnector.autoConnect()

        val state = autoConnector.connectionState.first()
        assertTrue(state is NatsAutoConnector.AutoConnectState.Failed)
        val failedState = state as NatsAutoConnector.AutoConnectState.Failed
        assertEquals(NatsAutoConnector.ConnectionResult.CredentialsExpired, failedState.result)
    }

    // MARK: - autoConnect Tests - Missing Data

    @Test
    fun `autoConnect returns MissingData when endpoint is null`() = runTest {
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)
        whenever(credentialStore.areNatsCredentialsValid()).thenReturn(true)
        whenever(credentialStore.getNatsEndpoint()).thenReturn(null)

        val result = autoConnector.autoConnect()

        assertTrue(result is NatsAutoConnector.ConnectionResult.MissingData)
        assertEquals("endpoint", (result as NatsAutoConnector.ConnectionResult.MissingData).field)
    }

    @Test
    fun `autoConnect returns MissingData when credentials cannot be parsed`() = runTest {
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)
        whenever(credentialStore.areNatsCredentialsValid()).thenReturn(true)
        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(testOwnerSpaceId)
        whenever(credentialStore.getParsedNatsCredentials()).thenReturn(null)

        val result = autoConnector.autoConnect()

        assertTrue(result is NatsAutoConnector.ConnectionResult.MissingData)
        assertEquals("credentials", (result as NatsAutoConnector.ConnectionResult.MissingData).field)
    }

    // MARK: - autoConnect Tests - Connection Failure

    @Test
    fun `autoConnect returns Error when natsClient connect fails`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(
            Result.failure(NatsException("Connection refused"))
        )

        val result = autoConnector.autoConnect()

        assertTrue(result is NatsAutoConnector.ConnectionResult.Error)
        assertEquals("Connection refused", (result as NatsAutoConnector.ConnectionResult.Error).message)
    }

    @Test
    fun `autoConnect sets Failed state with Error when connection fails`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(
            Result.failure(NatsException("Network error"))
        )

        autoConnector.autoConnect()

        val state = autoConnector.connectionState.first()
        assertTrue(state is NatsAutoConnector.AutoConnectState.Failed)
        val failedState = state as NatsAutoConnector.AutoConnectState.Failed
        assertTrue(failedState.result is NatsAutoConnector.ConnectionResult.Error)
    }

    // MARK: - autoConnect Tests - Success

    @Test
    fun `autoConnect returns Success when all steps succeed`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        val result = autoConnector.autoConnect()

        assertEquals(NatsAutoConnector.ConnectionResult.Success, result)
    }

    @Test
    fun `autoConnect sets Connected state on success`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        autoConnector.autoConnect()

        val state = autoConnector.connectionState.first()
        assertEquals(NatsAutoConnector.AutoConnectState.Connected, state)
    }

    @Test
    fun `autoConnect calls natsClient connect with correct credentials`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        autoConnector.autoConnect()

        verify(natsClient).connect(argThat { credentials ->
            credentials.jwt == testJwt &&
            credentials.seed == testSeed &&
            credentials.endpoint == testEndpoint
        })
    }

    @Test
    fun `autoConnect sets account on connectionManager when ownerSpaceId available`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        autoConnector.autoConnect()

        verify(connectionManager).setAccountFromStored(
            eq(testOwnerSpaceId),
            eq(testMessageSpaceId),
            eq(testEndpoint)
        )
    }

    @Test
    fun `autoConnect sets connected state on connectionManager`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        autoConnector.autoConnect()

        verify(connectionManager).setConnectedState(any())
    }

    @Test
    fun `autoConnect subscribes to vault when ownerSpaceId available`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        autoConnector.autoConnect()

        verify(ownerSpaceClient).subscribeToVault()
    }

    @Test
    fun `autoConnect still succeeds when subscribe fails`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(
            Result.failure(NatsException("Subscribe failed"))
        )

        val result = autoConnector.autoConnect()

        // Should still return success - subscribe failure is not fatal
        assertEquals(NatsAutoConnector.ConnectionResult.Success, result)
    }

    @Test
    fun `autoConnect skips account setup and subscribe when ownerSpaceId is null`() = runTest {
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)
        whenever(credentialStore.areNatsCredentialsValid()).thenReturn(true)
        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(null) // No ownerSpaceId
        whenever(credentialStore.getParsedNatsCredentials()).thenReturn(Pair(testJwt, testSeed))
        whenever(credentialStore.getNatsCaCertificate()).thenReturn(null)
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))

        val result = autoConnector.autoConnect()

        assertEquals(NatsAutoConnector.ConnectionResult.Success, result)
        verify(connectionManager, never()).setAccountFromStored(any(), any(), any())
        verify(ownerSpaceClient, never()).subscribeToVault()
    }

    // MARK: - isConnected Tests

    @Test
    fun `isConnected returns true when natsClient is connected`() {
        whenever(natsClient.isConnected).thenReturn(true)

        assertTrue(autoConnector.isConnected())
    }

    @Test
    fun `isConnected returns false when natsClient is not connected`() {
        whenever(natsClient.isConnected).thenReturn(false)

        assertFalse(autoConnector.isConnected())
    }

    // MARK: - disconnect Tests

    @Test
    fun `disconnect unsubscribes from vault`() = runTest {
        autoConnector.disconnect()

        verify(ownerSpaceClient).unsubscribeFromVault()
    }

    @Test
    fun `disconnect disconnects natsClient`() = runTest {
        autoConnector.disconnect()

        verify(natsClient).disconnect()
    }

    @Test
    fun `disconnect sets state to Idle`() = runTest {
        // First connect successfully
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))
        autoConnector.autoConnect()

        // Now disconnect
        autoConnector.disconnect()

        val state = autoConnector.connectionState.first()
        assertEquals(NatsAutoConnector.AutoConnectState.Idle, state)
    }

    // MARK: - State Transition Tests

    @Test
    fun `autoConnect transitions through correct states on success`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        autoConnector.autoConnect()

        // Final state should be Connected
        val finalState = autoConnector.connectionState.first()
        assertEquals(NatsAutoConnector.AutoConnectState.Connected, finalState)
    }

    @Test
    fun `initial state is Idle`() = runTest {
        val state = autoConnector.connectionState.first()
        assertEquals(NatsAutoConnector.AutoConnectState.Idle, state)
    }

    // MARK: - CA Certificate Tests

    @Test
    fun `autoConnect includes CA certificate when available`() = runTest {
        val testCaCert = "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----"
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)
        whenever(credentialStore.areNatsCredentialsValid()).thenReturn(true)
        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(testOwnerSpaceId)
        whenever(credentialStore.getParsedNatsCredentials()).thenReturn(Pair(testJwt, testSeed))
        whenever(credentialStore.getNatsCaCertificate()).thenReturn(testCaCert)
        whenever(credentialStore.getNatsConnection()).thenReturn(
            NatsConnectionInfo(
                endpoint = testEndpoint,
                credentials = testCredentialFile,
                ownerSpace = testOwnerSpaceId,
                messageSpace = testMessageSpaceId
            )
        )
        whenever(natsClient.connect(any())).thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        autoConnector.autoConnect()

        verify(natsClient).connect(argThat { credentials ->
            credentials.caCertificate == testCaCert
        })
    }

    // MARK: - Vault Auto-Start Tests

    @Test
    fun `autoConnect attempts vault start on authentication timeout error`() = runTest {
        setupValidCredentials()
        // First connection fails with auth timeout
        whenever(natsClient.connect(any())).thenReturn(
            Result.failure(NatsException("Authentication Timeout"))
        )
        // Vault start returns already running
        whenever(vaultLifecycleClient.startVault()).thenReturn(
            Result.success(VaultStartResponse(status = "already_running", instanceId = "i-123"))
        )

        autoConnector.autoConnect(autoStartVault = true)

        verify(vaultLifecycleClient).startVault()
    }

    @Test
    fun `autoConnect attempts vault start on auth error`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(
            Result.failure(NatsException("Authorization Violation"))
        )
        whenever(vaultLifecycleClient.startVault()).thenReturn(
            Result.success(VaultStartResponse(status = "already_running", instanceId = "i-123"))
        )

        autoConnector.autoConnect(autoStartVault = true)

        verify(vaultLifecycleClient).startVault()
    }

    @Test
    fun `autoConnect does not attempt vault start when autoStartVault is false`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(
            Result.failure(NatsException("Authentication Timeout"))
        )

        autoConnector.autoConnect(autoStartVault = false)

        verify(vaultLifecycleClient, never()).startVault()
    }

    @Test
    fun `autoConnect does not attempt vault start on non-auth errors`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(
            Result.failure(NatsException("Network unreachable"))
        )

        autoConnector.autoConnect(autoStartVault = true)

        verify(vaultLifecycleClient, never()).startVault()
    }

    @Test
    fun `autoConnect retries connection after vault is already running`() = runTest {
        setupValidCredentials()
        // First call fails, second succeeds
        whenever(natsClient.connect(any()))
            .thenReturn(Result.failure(NatsException("Authentication Timeout")))
            .thenReturn(Result.success(Unit))
        whenever(vaultLifecycleClient.startVault()).thenReturn(
            Result.success(VaultStartResponse(status = "already_running", instanceId = "i-123"))
        )
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        val result = autoConnector.autoConnect(autoStartVault = true)

        assertEquals(NatsAutoConnector.ConnectionResult.Success, result)
        verify(natsClient, times(2)).connect(any())
    }

    @Test
    fun `autoConnect retries with autoStartVault false to prevent infinite loop`() = runTest {
        setupValidCredentials()
        // Both calls fail with auth error
        whenever(natsClient.connect(any())).thenReturn(
            Result.failure(NatsException("Authentication Timeout"))
        )
        whenever(vaultLifecycleClient.startVault()).thenReturn(
            Result.success(VaultStartResponse(status = "already_running", instanceId = "i-123"))
        )

        autoConnector.autoConnect(autoStartVault = true)

        // Should only try to start vault once (not on the retry)
        verify(vaultLifecycleClient, times(1)).startVault()
        // Should connect twice (initial + retry)
        verify(natsClient, times(2)).connect(any())
    }

    @Test
    fun `autoConnect returns error when vault start fails`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(
            Result.failure(NatsException("Authentication Timeout"))
        )
        whenever(vaultLifecycleClient.startVault()).thenReturn(
            Result.failure(Exception("Vault start failed"))
        )

        val result = autoConnector.autoConnect(autoStartVault = true)

        assertTrue(result is NatsAutoConnector.ConnectionResult.Error)
        assertEquals("Authentication Timeout", (result as NatsAutoConnector.ConnectionResult.Error).message)
    }

    @Test
    fun `autoConnect waits for vault to become ready when starting`() = runTest {
        setupValidCredentials()
        // First connection fails
        whenever(natsClient.connect(any()))
            .thenReturn(Result.failure(NatsException("Authentication Timeout")))
            .thenReturn(Result.success(Unit))
        // Vault is starting
        whenever(vaultLifecycleClient.startVault()).thenReturn(
            Result.success(VaultStartResponse(status = "starting", instanceId = "i-123"))
        )
        // Status polling returns running
        whenever(vaultLifecycleClient.getVaultStatus()).thenReturn(
            Result.success(VaultLifecycleStatusResponse(
                enrollmentStatus = "active",
                instanceStatus = "running",
                instanceId = "i-123"
            ))
        )
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        val result = autoConnector.autoConnect(autoStartVault = true)

        assertEquals(NatsAutoConnector.ConnectionResult.Success, result)
        verify(vaultLifecycleClient).getVaultStatus()
    }

    @Test
    fun `autoConnect polls vault status until running`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any()))
            .thenReturn(Result.failure(NatsException("Authentication Timeout")))
            .thenReturn(Result.success(Unit))
        whenever(vaultLifecycleClient.startVault()).thenReturn(
            Result.success(VaultStartResponse(status = "starting", instanceId = "i-123"))
        )
        // First poll: pending, second poll: running
        whenever(vaultLifecycleClient.getVaultStatus())
            .thenReturn(Result.success(VaultLifecycleStatusResponse(
                enrollmentStatus = "active",
                instanceStatus = "pending",
                instanceId = "i-123"
            )))
            .thenReturn(Result.success(VaultLifecycleStatusResponse(
                enrollmentStatus = "active",
                instanceStatus = "running",
                instanceId = "i-123"
            )))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        val result = autoConnector.autoConnect(autoStartVault = true)

        assertEquals(NatsAutoConnector.ConnectionResult.Success, result)
        verify(vaultLifecycleClient, times(2)).getVaultStatus()
    }

    @Test
    fun `autoConnect returns error when vault stops unexpectedly while waiting`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any())).thenReturn(
            Result.failure(NatsException("Authentication Timeout"))
        )
        whenever(vaultLifecycleClient.startVault()).thenReturn(
            Result.success(VaultStartResponse(status = "starting", instanceId = "i-123"))
        )
        // Vault reports stopped
        whenever(vaultLifecycleClient.getVaultStatus()).thenReturn(
            Result.success(VaultLifecycleStatusResponse(
                enrollmentStatus = "active",
                instanceStatus = "stopped",
                instanceId = "i-123"
            ))
        )

        val result = autoConnector.autoConnect(autoStartVault = true)

        assertTrue(result is NatsAutoConnector.ConnectionResult.Error)
    }

    @Test
    fun `autoConnect continues polling when getVaultStatus fails`() = runTest {
        setupValidCredentials()
        whenever(natsClient.connect(any()))
            .thenReturn(Result.failure(NatsException("Authentication Timeout")))
            .thenReturn(Result.success(Unit))
        whenever(vaultLifecycleClient.startVault()).thenReturn(
            Result.success(VaultStartResponse(status = "starting", instanceId = "i-123"))
        )
        // First poll fails, second succeeds
        whenever(vaultLifecycleClient.getVaultStatus())
            .thenReturn(Result.failure(Exception("Network error")))
            .thenReturn(Result.success(VaultLifecycleStatusResponse(
                enrollmentStatus = "active",
                instanceStatus = "running",
                instanceId = "i-123"
            )))
        whenever(ownerSpaceClient.subscribeToVault()).thenReturn(Result.success(Unit))

        val result = autoConnector.autoConnect(autoStartVault = true)

        assertEquals(NatsAutoConnector.ConnectionResult.Success, result)
        verify(vaultLifecycleClient, times(2)).getVaultStatus()
    }

    // MARK: - Helper Methods

    private fun setupValidCredentials() {
        whenever(credentialStore.hasNatsConnection()).thenReturn(true)
        whenever(credentialStore.areNatsCredentialsValid()).thenReturn(true)
        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(testOwnerSpaceId)
        whenever(credentialStore.getParsedNatsCredentials()).thenReturn(Pair(testJwt, testSeed))
        whenever(credentialStore.getNatsCaCertificate()).thenReturn(null)
        whenever(credentialStore.getNatsConnection()).thenReturn(
            NatsConnectionInfo(
                endpoint = testEndpoint,
                credentials = testCredentialFile,
                ownerSpace = testOwnerSpaceId,
                messageSpace = testMessageSpaceId
            )
        )
    }
}
