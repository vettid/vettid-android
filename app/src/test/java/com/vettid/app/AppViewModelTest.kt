package com.vettid.app

import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.network.NatsConnectionInfo
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private lateinit var viewModel: AppViewModel
    private lateinit var credentialStore: CredentialStore
    private lateinit var natsAutoConnector: NatsAutoConnector

    private val testDispatcher = StandardTestDispatcher()
    private val connectionStateFlow = MutableStateFlow<NatsAutoConnector.AutoConnectState>(
        NatsAutoConnector.AutoConnectState.Idle
    )

    private val testEndpoint = "tls://nats.vettid.dev:443"
    private val testOwnerSpace = "OwnerSpace.test-user-123"
    private val testMessageSpace = "MessageSpace.test-user-123"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        credentialStore = mock()
        natsAutoConnector = mock()

        // Default mock behavior
        whenever(credentialStore.hasStoredCredential()).thenReturn(false)
        whenever(natsAutoConnector.connectionState).thenReturn(connectionStateFlow)
        whenever(natsAutoConnector.isConnected()).thenReturn(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AppViewModel {
        return AppViewModel(credentialStore, natsAutoConnector)
    }

    // MARK: - refreshNatsCredentials Tests

    @Test
    fun `refreshNatsCredentials disconnects before reconnecting`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Setup for reconnection
        whenever(natsAutoConnector.autoConnect()).thenReturn(
            NatsAutoConnector.ConnectionResult.Success
        )
        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(testOwnerSpace)
        whenever(credentialStore.getNatsConnection()).thenReturn(null)
        whenever(credentialStore.getNatsCredentialsExpiryTime()).thenReturn(null)

        viewModel.refreshNatsCredentials()
        advanceUntilIdle()

        // Verify disconnect was called before autoConnect
        val inOrder = inOrder(natsAutoConnector)
        inOrder.verify(natsAutoConnector).disconnect()
        inOrder.verify(natsAutoConnector).autoConnect()
    }

    @Test
    fun `refreshNatsCredentials sets state to Connecting`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        var stateWasConnectingDuringRefresh = false

        // Setup to capture intermediate state
        whenever(natsAutoConnector.autoConnect()).thenAnswer {
            // Capture state during autoConnect call
            stateWasConnectingDuringRefresh =
                viewModel.appState.value.natsConnectionState == NatsConnectionState.Connecting
            NatsAutoConnector.ConnectionResult.Success
        }
        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(testOwnerSpace)
        whenever(credentialStore.getNatsConnection()).thenReturn(null)
        whenever(credentialStore.getNatsCredentialsExpiryTime()).thenReturn(null)

        viewModel.refreshNatsCredentials()
        advanceUntilIdle()

        assertTrue("State should have been Connecting during refresh", stateWasConnectingDuringRefresh)
    }

    @Test
    fun `refreshNatsCredentials clears error state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // First, set an error state
        whenever(natsAutoConnector.autoConnect()).thenReturn(
            NatsAutoConnector.ConnectionResult.Error("Test error")
        )

        viewModel.connectToNats()
        advanceUntilIdle()

        assertEquals(NatsConnectionState.Failed, viewModel.appState.first().natsConnectionState)
        assertEquals("Test error", viewModel.appState.first().natsError)

        // Now refresh - should clear error
        whenever(natsAutoConnector.autoConnect()).thenReturn(
            NatsAutoConnector.ConnectionResult.Success
        )
        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(testOwnerSpace)
        whenever(credentialStore.getNatsConnection()).thenReturn(null)
        whenever(credentialStore.getNatsCredentialsExpiryTime()).thenReturn(null)

        viewModel.refreshNatsCredentials()
        advanceUntilIdle()

        assertNull(viewModel.appState.first().natsError)
    }

    @Test
    fun `refreshNatsCredentials updates connection details on success`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val testConnection = NatsConnectionInfo(
            endpoint = testEndpoint,
            credentials = "test-creds",
            ownerSpace = testOwnerSpace,
            messageSpace = testMessageSpace
        )

        whenever(natsAutoConnector.autoConnect()).thenReturn(
            NatsAutoConnector.ConnectionResult.Success
        )
        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(testOwnerSpace)
        whenever(credentialStore.getNatsConnection()).thenReturn(testConnection)
        whenever(credentialStore.getNatsCredentialsExpiryTime()).thenReturn(
            System.currentTimeMillis() + 23 * 60 * 60 * 1000 // 23 hours from now
        )

        viewModel.refreshNatsCredentials()
        advanceUntilIdle()

        val state = viewModel.appState.first()
        assertEquals(NatsConnectionState.Connected, state.natsConnectionState)
        assertEquals(testEndpoint, state.natsEndpoint)
        assertEquals(testOwnerSpace, state.natsOwnerSpaceId)
        assertEquals(testMessageSpace, state.natsMessageSpaceId)
        assertNotNull(state.natsCredentialsExpiry)
        assertTrue(state.natsCredentialsExpiry!!.contains("h")) // Should show hours
    }

    @Test
    fun `refreshNatsCredentials handles connection failure`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        whenever(natsAutoConnector.autoConnect()).thenReturn(
            NatsAutoConnector.ConnectionResult.Error("Connection refused")
        )

        viewModel.refreshNatsCredentials()
        advanceUntilIdle()

        val state = viewModel.appState.first()
        assertEquals(NatsConnectionState.Failed, state.natsConnectionState)
        assertEquals("Connection refused", state.natsError)
    }

    @Test
    fun `refreshNatsCredentials handles expired credentials`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        whenever(natsAutoConnector.autoConnect()).thenReturn(
            NatsAutoConnector.ConnectionResult.CredentialsExpired
        )

        viewModel.refreshNatsCredentials()
        advanceUntilIdle()

        val state = viewModel.appState.first()
        assertEquals(NatsConnectionState.CredentialsExpired, state.natsConnectionState)
        assertNotNull(state.natsError)
    }

    // MARK: - retryNatsConnection Tests

    @Test
    fun `retryNatsConnection calls connectToNats`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        whenever(natsAutoConnector.autoConnect()).thenReturn(
            NatsAutoConnector.ConnectionResult.Success
        )
        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(testOwnerSpace)
        whenever(credentialStore.getNatsConnection()).thenReturn(null)
        whenever(credentialStore.getNatsCredentialsExpiryTime()).thenReturn(null)

        viewModel.retryNatsConnection()
        advanceUntilIdle()

        verify(natsAutoConnector).autoConnect()
    }

    // MARK: - setAuthenticated Tests

    @Test
    fun `setAuthenticated true triggers NATS connection`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        whenever(natsAutoConnector.autoConnect()).thenReturn(
            NatsAutoConnector.ConnectionResult.Success
        )
        whenever(credentialStore.getNatsEndpoint()).thenReturn(testEndpoint)
        whenever(credentialStore.getNatsOwnerSpace()).thenReturn(testOwnerSpace)
        whenever(credentialStore.getNatsConnection()).thenReturn(null)
        whenever(credentialStore.getNatsCredentialsExpiryTime()).thenReturn(null)

        viewModel.setAuthenticated(true)
        advanceUntilIdle()

        assertTrue(viewModel.appState.first().isAuthenticated)
        verify(natsAutoConnector).autoConnect()
    }

    @Test
    fun `setAuthenticated false does not trigger NATS connection`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setAuthenticated(false)
        advanceUntilIdle()

        assertFalse(viewModel.appState.first().isAuthenticated)
        verify(natsAutoConnector, never()).autoConnect()
    }

    // MARK: - signOut Tests

    @Test
    fun `signOut disconnects from NATS`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        verify(natsAutoConnector).disconnect()
    }

    @Test
    fun `signOut resets authentication state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // First authenticate
        viewModel.setAuthenticated(true)
        whenever(natsAutoConnector.autoConnect()).thenReturn(
            NatsAutoConnector.ConnectionResult.NotEnrolled
        )
        advanceUntilIdle()

        assertTrue(viewModel.appState.first().isAuthenticated)

        // Sign out
        viewModel.signOut()
        advanceUntilIdle()

        val state = viewModel.appState.first()
        assertFalse(state.isAuthenticated)
        assertEquals(NatsConnectionState.Idle, state.natsConnectionState)
        assertNull(state.natsError)
    }

    // MARK: - isNatsConnected Tests

    @Test
    fun `isNatsConnected returns true when connected`() = runTest {
        whenever(natsAutoConnector.isConnected()).thenReturn(true)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.isNatsConnected())
    }

    @Test
    fun `isNatsConnected returns false when not connected`() = runTest {
        whenever(natsAutoConnector.isConnected()).thenReturn(false)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.isNatsConnected())
    }

    // MARK: - Connection State Observation Tests

    @Test
    fun `observes NatsAutoConnector connection state changes`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Simulate state change from NatsAutoConnector
        connectionStateFlow.value = NatsAutoConnector.AutoConnectState.Connecting
        advanceUntilIdle()

        assertEquals(NatsConnectionState.Connecting, viewModel.appState.first().natsConnectionState)

        connectionStateFlow.value = NatsAutoConnector.AutoConnectState.Connected
        advanceUntilIdle()

        assertEquals(NatsConnectionState.Connected, viewModel.appState.first().natsConnectionState)
    }

    @Test
    fun `maps Failed state with CredentialsExpired correctly`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        connectionStateFlow.value = NatsAutoConnector.AutoConnectState.Failed(
            NatsAutoConnector.ConnectionResult.CredentialsExpired
        )
        advanceUntilIdle()

        assertEquals(
            NatsConnectionState.CredentialsExpired,
            viewModel.appState.first().natsConnectionState
        )
    }

    @Test
    fun `maps Failed state with Error correctly`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        connectionStateFlow.value = NatsAutoConnector.AutoConnectState.Failed(
            NatsAutoConnector.ConnectionResult.Error("Test error")
        )
        advanceUntilIdle()

        assertEquals(NatsConnectionState.Failed, viewModel.appState.first().natsConnectionState)
    }
}
