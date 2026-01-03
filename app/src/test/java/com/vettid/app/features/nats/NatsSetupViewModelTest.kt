package com.vettid.app.features.nats

import com.vettid.app.core.nats.*
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
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalCoroutinesApi::class)
class NatsSetupViewModelTest {

    private lateinit var viewModel: NatsSetupViewModel
    private lateinit var connectionManager: NatsConnectionManager
    private lateinit var ownerSpaceClient: OwnerSpaceClient
    private lateinit var credentialStore: CredentialStore

    private val testDispatcher = StandardTestDispatcher()
    private val connectionStateFlow = MutableStateFlow<NatsConnectionState>(NatsConnectionState.Disconnected)
    private val accountFlow = MutableStateFlow<NatsAccount?>(null)

    private val testAccount = NatsAccount(
        ownerSpaceId = "OwnerSpace.test-guid",
        messageSpaceId = "MessageSpace.test-guid",
        natsEndpoint = "nats://nats.vettid.dev:443",
        status = NatsAccountStatus.ACTIVE,
        createdAt = "2025-12-07T10:00:00Z"
    )

    private val testCredentials = NatsCredentials(
        tokenId = "nats_test-token",
        jwt = "test.jwt.token",
        seed = "SUAM1234567890",
        endpoint = "nats://nats.vettid.dev:443",
        expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
        permissions = NatsPermissions(
            publish = listOf("OwnerSpace.test-guid.forVault.>"),
            subscribe = listOf("OwnerSpace.test-guid.forApp.>")
        )
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        connectionManager = mock()
        ownerSpaceClient = mock()
        credentialStore = mock()

        whenever(connectionManager.connectionState).thenReturn(connectionStateFlow)
        whenever(connectionManager.account).thenReturn(accountFlow)
        whenever(connectionManager.getNatsClient()).thenReturn(mock())
        whenever(credentialStore.getUserGuid()).thenReturn("test-guid")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): NatsSetupViewModel {
        return NatsSetupViewModel(connectionManager, ownerSpaceClient, credentialStore)
    }

    @Test
    fun `initial state is Initial`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(NatsSetupState.Initial, viewModel.state.first())
    }

    @Test
    fun `setupNats checks account status first`() = runTest {
        val status = NatsStatus(
            hasAccount = true,
            account = testAccount,
            activeTokens = emptyList(),
            natsEndpoint = "nats://nats.vettid.dev:4222"
        )

        whenever(connectionManager.checkAccountStatus(any()))
            .thenReturn(Result.success(status))
        whenever(connectionManager.connect(any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault())
            .thenReturn(Result.success(Unit))

        viewModel = createViewModel()
        viewModel.onEvent(NatsSetupEvent.SetupNats("test-auth-token"))
        advanceUntilIdle()

        verify(connectionManager).checkAccountStatus("test-auth-token")
    }

    @Test
    fun `setupNats creates account if not exists`() = runTest {
        val statusNoAccount = NatsStatus(
            hasAccount = false,
            account = null,
            activeTokens = emptyList(),
            natsEndpoint = null
        )

        whenever(connectionManager.checkAccountStatus(any()))
            .thenReturn(Result.success(statusNoAccount))
        whenever(connectionManager.createAccount(any()))
            .thenReturn(Result.success(testAccount))
        whenever(connectionManager.connect(any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault())
            .thenReturn(Result.success(Unit))

        viewModel = createViewModel()
        viewModel.onEvent(NatsSetupEvent.SetupNats("test-auth-token"))
        advanceUntilIdle()

        verify(connectionManager).createAccount("test-auth-token")
    }

    @Test
    fun `setupNats connects after account check`() = runTest {
        val status = NatsStatus(
            hasAccount = true,
            account = testAccount,
            activeTokens = emptyList(),
            natsEndpoint = "nats://nats.vettid.dev:4222"
        )

        whenever(connectionManager.checkAccountStatus(any()))
            .thenReturn(Result.success(status))
        whenever(connectionManager.connect(any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault())
            .thenReturn(Result.success(Unit))

        viewModel = createViewModel()
        viewModel.onEvent(NatsSetupEvent.SetupNats("test-auth-token"))
        advanceUntilIdle()

        verify(connectionManager).connect(eq("test-auth-token"), any())
    }

    @Test
    fun `setupNats subscribes to vault after connect`() = runTest {
        val status = NatsStatus(
            hasAccount = true,
            account = testAccount,
            activeTokens = emptyList(),
            natsEndpoint = "nats://nats.vettid.dev:4222"
        )

        whenever(connectionManager.checkAccountStatus(any()))
            .thenReturn(Result.success(status))
        whenever(connectionManager.connect(any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault())
            .thenReturn(Result.success(Unit))

        viewModel = createViewModel()
        viewModel.onEvent(NatsSetupEvent.SetupNats("test-auth-token"))
        advanceUntilIdle()

        verify(ownerSpaceClient).subscribeToVault()
    }

    @Test
    fun `connection state change to Connected updates state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Simulate connection state change
        accountFlow.value = testAccount
        connectionStateFlow.value = NatsConnectionState.Connected(testCredentials)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected Connected state but got $state", state is NatsSetupState.Connected)

        val connectedState = state as NatsSetupState.Connected
        assertEquals(testAccount, connectedState.account)
    }

    @Test
    fun `disconnect unsubscribes and disconnects`() = runTest {
        val status = NatsStatus(
            hasAccount = true,
            account = testAccount,
            activeTokens = emptyList(),
            natsEndpoint = "nats://nats.vettid.dev:4222"
        )

        whenever(connectionManager.checkAccountStatus(any()))
            .thenReturn(Result.success(status))
        whenever(connectionManager.connect(any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault())
            .thenReturn(Result.success(Unit))

        viewModel = createViewModel()
        viewModel.onEvent(NatsSetupEvent.SetupNats("test-auth-token"))
        advanceUntilIdle()

        viewModel.onEvent(NatsSetupEvent.Disconnect)
        advanceUntilIdle()

        verify(ownerSpaceClient).unsubscribeFromVault()
        verify(connectionManager).disconnect()
    }

    @Test
    fun `error state is set on connection failure`() = runTest {
        whenever(connectionManager.checkAccountStatus(any()))
            .thenReturn(Result.failure(NatsApiException("Network error")))

        viewModel = createViewModel()
        viewModel.onEvent(NatsSetupEvent.SetupNats("test-auth-token"))
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected Error state but got $state", state is NatsSetupState.Error)

        val errorState = state as NatsSetupState.Error
        assertEquals(NatsErrorCode.NETWORK_ERROR, errorState.code)
    }

    @Test
    fun `getConnectionSummary returns correct summary for Connected state`() = runTest {
        viewModel = createViewModel()
        accountFlow.value = testAccount
        connectionStateFlow.value = NatsConnectionState.Connected(testCredentials)
        advanceUntilIdle()

        val summary = viewModel.getConnectionSummary()
        assertEquals("Connected", summary.statusText)
        assertEquals(NatsStatusIcon.CONNECTED, summary.icon)
        assertEquals(testAccount.ownerSpaceId, summary.ownerSpaceId)
    }

    @Test
    fun `getConnectionSummary returns correct summary for Error state`() = runTest {
        whenever(connectionManager.checkAccountStatus(any()))
            .thenReturn(Result.failure(NatsApiException("Test error")))

        viewModel = createViewModel()
        viewModel.onEvent(NatsSetupEvent.SetupNats("test-auth-token"))
        advanceUntilIdle()

        val summary = viewModel.getConnectionSummary()
        assertEquals("Error", summary.statusText)
        assertEquals(NatsStatusIcon.ERROR, summary.icon)
    }

    @Test
    fun `isConnected delegates to connectionManager`() = runTest {
        whenever(connectionManager.isConnected()).thenReturn(true)

        viewModel = createViewModel()

        assertTrue(viewModel.isConnected())
        verify(connectionManager).isConnected()
    }

    @Test
    fun `retry uses last auth token`() = runTest {
        val status = NatsStatus(
            hasAccount = true,
            account = testAccount,
            activeTokens = emptyList(),
            natsEndpoint = "nats://nats.vettid.dev:4222"
        )

        whenever(connectionManager.checkAccountStatus(any()))
            .thenReturn(Result.success(status))
        whenever(connectionManager.connect(any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(ownerSpaceClient.subscribeToVault())
            .thenReturn(Result.success(Unit))

        viewModel = createViewModel()
        viewModel.onEvent(NatsSetupEvent.SetupNats("original-token"))
        advanceUntilIdle()

        // Reset mocks for retry
        reset(connectionManager)
        whenever(connectionManager.connectionState).thenReturn(connectionStateFlow)
        whenever(connectionManager.account).thenReturn(accountFlow)
        whenever(connectionManager.checkAccountStatus(any()))
            .thenReturn(Result.success(status))
        whenever(connectionManager.connect(any(), any()))
            .thenReturn(Result.success(Unit))

        viewModel.onEvent(NatsSetupEvent.Retry)
        advanceUntilIdle()

        verify(connectionManager).checkAccountStatus("original-token")
    }
}
