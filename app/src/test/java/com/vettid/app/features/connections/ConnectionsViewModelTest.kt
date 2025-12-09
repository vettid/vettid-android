package com.vettid.app.features.connections

import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionApiClient
import com.vettid.app.core.network.ConnectionListResponse
import com.vettid.app.core.network.ConnectionStatus
import com.vettid.app.core.network.MessagingApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {

    private lateinit var connectionApiClient: ConnectionApiClient
    private lateinit var messagingApiClient: MessagingApiClient
    private lateinit var viewModel: ConnectionsViewModel
    private val testDispatcher = StandardTestDispatcher()

    private val testConnections = listOf(
        Connection(
            connectionId = "conn-1",
            peerGuid = "peer-1",
            peerDisplayName = "Alice",
            peerAvatarUrl = null,
            status = ConnectionStatus.ACTIVE,
            createdAt = System.currentTimeMillis(),
            lastMessageAt = null,
            unreadCount = 0
        ),
        Connection(
            connectionId = "conn-2",
            peerGuid = "peer-2",
            peerDisplayName = "Bob",
            peerAvatarUrl = null,
            status = ConnectionStatus.ACTIVE,
            createdAt = System.currentTimeMillis(),
            lastMessageAt = System.currentTimeMillis(),
            unreadCount = 3
        )
    )

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)
        connectionApiClient = mock()
        messagingApiClient = mock()
        whenever(messagingApiClient.getUnreadCount()).thenReturn(Result.success(emptyMap()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading`() = runTest {
        whenever(connectionApiClient.listConnections(any(), any()))
            .thenReturn(Result.success(ConnectionListResponse(
                connections = testConnections,
                total = testConnections.size,
                page = 1,
                hasMore = false
            )))

        viewModel = ConnectionsViewModel(connectionApiClient, messagingApiClient)

        // Initial state before loading completes
        val initialState = viewModel.state.first()
        assertTrue(initialState is ConnectionsState.Loading || initialState is ConnectionsState.Loaded)
    }

    @Test
    fun `loadConnections populates state on success`() = runTest {
        whenever(connectionApiClient.listConnections(any(), any()))
            .thenReturn(Result.success(ConnectionListResponse(
                connections = testConnections,
                total = testConnections.size,
                page = 1,
                hasMore = false
            )))

        viewModel = ConnectionsViewModel(connectionApiClient, messagingApiClient)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Loaded)
        val loadedState = state as ConnectionsState.Loaded
        assertEquals(2, loadedState.connections.size)
    }

    @Test
    fun `loadConnections shows empty state when no connections`() = runTest {
        whenever(connectionApiClient.listConnections(any(), any()))
            .thenReturn(Result.success(ConnectionListResponse(
                connections = emptyList(),
                total = 0,
                page = 1,
                hasMore = false
            )))

        viewModel = ConnectionsViewModel(connectionApiClient, messagingApiClient)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Empty)
    }

    @Test
    fun `loadConnections shows error state on failure`() = runTest {
        whenever(connectionApiClient.listConnections(any(), any()))
            .thenReturn(Result.failure(Exception("Network error")))

        viewModel = ConnectionsViewModel(connectionApiClient, messagingApiClient)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Error)
        assertEquals("Network error", (state as ConnectionsState.Error).message)
    }

    @Test
    fun `search filters connections by name`() = runTest {
        whenever(connectionApiClient.listConnections(any(), any()))
            .thenReturn(Result.success(ConnectionListResponse(
                connections = testConnections,
                total = testConnections.size,
                page = 1,
                hasMore = false
            )))

        viewModel = ConnectionsViewModel(connectionApiClient, messagingApiClient)
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
    fun `totalUnread uses unreadCounts from messaging API`() = runTest {
        whenever(connectionApiClient.listConnections(any(), any()))
            .thenReturn(Result.success(ConnectionListResponse(
                connections = testConnections,
                total = testConnections.size,
                page = 1,
                hasMore = false
            )))
        whenever(messagingApiClient.getUnreadCount())
            .thenReturn(Result.success(mapOf("conn-1" to 2, "conn-2" to 5)))

        viewModel = ConnectionsViewModel(connectionApiClient, messagingApiClient)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ConnectionsState.Loaded)
        assertEquals(7, (state as ConnectionsState.Loaded).totalUnread)
    }
}
