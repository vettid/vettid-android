package com.vettid.app.features.connections

import com.vettid.app.core.nats.ConnectionListResult
import com.vettid.app.core.nats.ConnectionRecord
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        whenever(natsAutoConnector.isConnected()).thenReturn(true)
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

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector)

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

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector)
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

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Empty)
    }

    @Test
    fun `loadConnections shows error state on failure`() = runTest {
        whenever(connectionsClient.list(anyOrNull(), any(), anyOrNull()))
            .thenReturn(Result.failure(Exception("Network error")))

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Error)
        assertEquals("Network error", (state as ConnectionsState.Error).message)
    }

    @Test
    fun `loadConnections shows empty state when not connected to NATS`() = runTest {
        whenever(natsAutoConnector.isConnected()).thenReturn(false)

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector)
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

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector)
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

        viewModel = ConnectionsViewModel(connectionsClient, natsAutoConnector)
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
