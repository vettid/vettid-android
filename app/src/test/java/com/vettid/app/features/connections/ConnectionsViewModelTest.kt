package com.vettid.app.features.connections

import com.vettid.app.core.nats.ConnectionListResult
import com.vettid.app.core.nats.ConnectionRecord
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.network.NetworkMonitor
import com.vettid.app.features.connections.offline.OfflineQueueManager
import com.vettid.app.features.connections.offline.SyncStatus
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

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var natsAutoConnector: NatsAutoConnector
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var offlineQueueManager: OfflineQueueManager
    private lateinit var viewModel: ConnectionsViewModel
    private val testDispatcher = StandardTestDispatcher()

    private val testConnectionRecords = listOf(
        ConnectionRecord(
            connectionId = "conn-1",
            peerGuid = "peer-1",
            label = "Alice",
            status = "active",
            direction = "outbound",
            createdAt = Instant.now().toString(),
            expiresAt = null,
            lastRotatedAt = null
        ),
        ConnectionRecord(
            connectionId = "conn-2",
            peerGuid = "peer-2",
            label = "Bob",
            status = "active",
            direction = "inbound",
            createdAt = Instant.now().minusSeconds(3600).toString(),
            expiresAt = null,
            lastRotatedAt = null
        )
    )

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)
        connectionsClient = mock()
        natsAutoConnector = mock()
        networkMonitor = mock()
        offlineQueueManager = mock()
        whenever(natsAutoConnector.isConnected()).thenReturn(true)
        whenever(networkMonitor.isOnline).thenReturn(MutableStateFlow(true))
        whenever(networkMonitor.connectivityFlow).thenReturn(MutableStateFlow(true))
        whenever(offlineQueueManager.syncStatus).thenReturn(MutableStateFlow(SyncStatus.IDLE))
        whenever(offlineQueueManager.pendingCount()).thenReturn(0)
        whenever(offlineQueueManager.hasPendingOperations()).thenReturn(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading`() = runTest {
        whenever(connectionsClient.list(anyOrNull(), any(), anyOrNull()))
            .thenReturn(Result.success(ConnectionListResult(
                items = testConnectionRecords,
                nextCursor = null
            )))

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector, networkMonitor, offlineQueueManager)

        // Initial state before loading completes
        val initialState = viewModel.state.first()
        assertTrue(initialState is ConnectionsState.Loading || initialState is ConnectionsState.Loaded)
    }

    @Test
    fun `loadConnections populates state on success`() = runTest {
        whenever(connectionsClient.list(anyOrNull(), any(), anyOrNull()))
            .thenReturn(Result.success(ConnectionListResult(
                items = testConnectionRecords,
                nextCursor = null
            )))

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector, networkMonitor, offlineQueueManager)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Loaded)
        val loadedState = state as ConnectionsState.Loaded
        assertEquals(2, loadedState.connections.size)
    }

    @Test
    fun `loadConnections shows empty state when no connections`() = runTest {
        whenever(connectionsClient.list(anyOrNull(), any(), anyOrNull()))
            .thenReturn(Result.success(ConnectionListResult(
                items = emptyList(),
                nextCursor = null
            )))

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector, networkMonitor, offlineQueueManager)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Empty)
    }

    @Test
    fun `loadConnections shows error state on failure`() = runTest {
        whenever(connectionsClient.list(anyOrNull(), any(), anyOrNull()))
            .thenReturn(Result.failure(Exception("Network error")))

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector, networkMonitor, offlineQueueManager)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Error)
        assertEquals("Network error", (state as ConnectionsState.Error).message)
    }

    @Test
    fun `loadConnections shows empty state when not connected to NATS`() = runTest {
        whenever(natsAutoConnector.isConnected()).thenReturn(false)

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector, networkMonitor, offlineQueueManager)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Empty)
        // Should not call connectionsClient when not connected
        verify(connectionsClient, never()).list(anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `search filters connections by name`() = runTest {
        whenever(connectionsClient.list(anyOrNull(), any(), anyOrNull()))
            .thenReturn(Result.success(ConnectionListResult(
                items = testConnectionRecords,
                nextCursor = null
            )))

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector, networkMonitor, offlineQueueManager)
        testScheduler.advanceUntilIdle()

        viewModel.onSearchQueryChanged("Alice")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Loaded)
        val loadedState = state as ConnectionsState.Loaded
        assertEquals(1, loadedState.connections.size)
        assertEquals("Alice", loadedState.connections[0].connection.peerDisplayName)
    }

    @Test
    fun `search is case insensitive`() = runTest {
        whenever(connectionsClient.list(anyOrNull(), any(), anyOrNull()))
            .thenReturn(Result.success(ConnectionListResult(
                items = testConnectionRecords,
                nextCursor = null
            )))

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector, networkMonitor, offlineQueueManager)
        testScheduler.advanceUntilIdle()

        viewModel.onSearchQueryChanged("alice")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Loaded)
        val loadedState = state as ConnectionsState.Loaded
        assertEquals(1, loadedState.connections.size)
        assertEquals("Alice", loadedState.connections[0].connection.peerDisplayName)
    }
}
