package com.vettid.app.features.handlers

import com.vettid.app.core.network.*
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
class HandlerDiscoveryViewModelTest {

    private lateinit var viewModel: HandlerDiscoveryViewModel
    private lateinit var registryClient: HandlerRegistryClient
    private lateinit var vaultHandlerClient: VaultHandlerClient

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        registryClient = mock()
        vaultHandlerClient = mock()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createSampleHandlers(): List<HandlerSummary> {
        return listOf(
            HandlerSummary(
                id = "handler-1",
                name = "Test Handler 1",
                description = "A test handler",
                version = "1.0.0",
                category = "messaging",
                iconUrl = null,
                publisher = "VettID",
                installed = false,
                installedVersion = null,
                rating = 4.5f,
                installCount = 1000
            ),
            HandlerSummary(
                id = "handler-2",
                name = "Test Handler 2",
                description = "Another test handler",
                version = "2.0.0",
                category = "social",
                iconUrl = "https://example.com/icon.png",
                publisher = "Test Publisher",
                installed = true,
                installedVersion = "2.0.0",
                rating = 4.0f,
                installCount = 500
            )
        )
    }

    private suspend fun setupDefaultMocks(handlers: List<HandlerSummary> = createSampleHandlers()) {
        whenever(registryClient.getCategories())
            .thenReturn(Result.success(CategoryListResponse(emptyList())))
        whenever(registryClient.listHandlers(anyOrNull(), any(), any()))
            .thenReturn(Result.success(HandlerListResponse(handlers, handlers.size, 1, false)))
    }

    @Test
    fun `initial state loads handlers`() = runTest {
        // Arrange
        val handlers = createSampleHandlers()
        val response = HandlerListResponse(
            handlers = handlers,
            total = 2,
            page = 1,
            hasMore = false
        )
        val categoryResponse = CategoryListResponse(
            categories = listOf(
                HandlerCategory("messaging", "Messaging", "Communication handlers", "chat", 10)
            )
        )

        whenever(registryClient.listHandlers(anyOrNull(), any(), any()))
            .thenReturn(Result.success(response))
        whenever(registryClient.getCategories())
            .thenReturn(Result.success(categoryResponse))

        // Act
        viewModel = HandlerDiscoveryViewModel(registryClient, vaultHandlerClient)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first()
        assertTrue("Expected Loaded but got $state", state is HandlerDiscoveryState.Loaded)

        val loadedState = state as HandlerDiscoveryState.Loaded
        assertEquals(2, loadedState.handlers.size)
        assertEquals("handler-1", loadedState.handlers[0].id)
        assertEquals("handler-2", loadedState.handlers[1].id)
    }

    @Test
    fun `selectCategory loads handlers for that category`() = runTest {
        // Arrange
        val allHandlers = createSampleHandlers()
        val messagingHandlers = listOf(allHandlers[0])

        whenever(registryClient.getCategories())
            .thenReturn(Result.success(CategoryListResponse(emptyList())))
        whenever(registryClient.listHandlers(isNull(), any(), any()))
            .thenReturn(Result.success(HandlerListResponse(allHandlers, 2, 1, false)))
        whenever(registryClient.listHandlers(eq("messaging"), any(), any()))
            .thenReturn(Result.success(HandlerListResponse(messagingHandlers, 1, 1, false)))

        viewModel = HandlerDiscoveryViewModel(registryClient, vaultHandlerClient)
        advanceUntilIdle()

        // Act
        viewModel.selectCategory("messaging")
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first() as HandlerDiscoveryState.Loaded
        assertEquals(1, state.handlers.size)
        assertEquals("handler-1", state.handlers[0].id)
        assertEquals("messaging", viewModel.selectedCategory.first())
    }

    @Test
    fun `search updates state with search results`() = runTest {
        // Arrange
        val allHandlers = createSampleHandlers()
        val searchResults = listOf(allHandlers[0])

        setupDefaultMocks(allHandlers)
        whenever(registryClient.searchHandlers(eq("Test Handler 1"), any(), any()))
            .thenReturn(Result.success(HandlerListResponse(searchResults, 1, 1, false)))

        viewModel = HandlerDiscoveryViewModel(registryClient, vaultHandlerClient)
        advanceUntilIdle()

        // Act
        viewModel.search("Test Handler 1")
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first() as HandlerDiscoveryState.Loaded
        assertEquals(1, state.handlers.size)
        assertTrue(state.isSearchResult)
    }

    @Test
    fun `installHandler does not call API when no token`() = runTest {
        // Arrange
        val handlers = createSampleHandlers()
        setupDefaultMocks(handlers)

        viewModel = HandlerDiscoveryViewModel(registryClient, vaultHandlerClient)
        advanceUntilIdle()

        // Act - no token set
        viewModel.installHandler(handlers[0])
        advanceUntilIdle()

        // Assert - API should not be called
        verify(vaultHandlerClient, never()).installHandler(any(), any(), any())
    }

    @Test
    fun `installHandler calls API and updates state on success`() = runTest {
        // Arrange
        val handlers = createSampleHandlers()
        val installResponse = InstallHandlerResponse(
            status = "installed",
            handlerId = "handler-1",
            version = "1.0.0",
            installedAt = "2025-01-01T12:00:00Z"
        )

        setupDefaultMocks(handlers)
        whenever(vaultHandlerClient.installHandler(any(), any(), any()))
            .thenReturn(Result.success(installResponse))

        viewModel = HandlerDiscoveryViewModel(registryClient, vaultHandlerClient)
        viewModel.setActionToken("test-token")
        advanceUntilIdle()

        // Act
        viewModel.installHandler(handlers[0])
        advanceUntilIdle()

        // Assert
        verify(vaultHandlerClient).installHandler("test-token", "handler-1", "1.0.0")

        // Check handler state updated
        val state = viewModel.state.first() as HandlerDiscoveryState.Loaded
        assertTrue(state.handlers.find { it.id == "handler-1" }?.installed == true)
    }

    @Test
    fun `uninstallHandler calls API and updates state on success`() = runTest {
        // Arrange
        val handlers = createSampleHandlers()
        val uninstallResponse = UninstallHandlerResponse(
            status = "uninstalled",
            handlerId = "handler-2",
            uninstalledAt = "2025-01-01T13:00:00Z"
        )

        setupDefaultMocks(handlers)
        whenever(vaultHandlerClient.uninstallHandler(any(), any()))
            .thenReturn(Result.success(uninstallResponse))

        viewModel = HandlerDiscoveryViewModel(registryClient, vaultHandlerClient)
        viewModel.setActionToken("test-token")
        advanceUntilIdle()

        // Act
        viewModel.uninstallHandler(handlers[1]) // handler-2 is installed
        advanceUntilIdle()

        // Assert
        verify(vaultHandlerClient).uninstallHandler("test-token", "handler-2")

        // Check handler state updated
        val state = viewModel.state.first() as HandlerDiscoveryState.Loaded
        assertTrue(state.handlers.find { it.id == "handler-2" }?.installed == false)
    }

    @Test
    fun `loadMore loads next page and appends handlers`() = runTest {
        // Arrange
        val handlers = createSampleHandlers()
        val moreHandlers = listOf(
            HandlerSummary(
                id = "handler-3",
                name = "Handler 3",
                description = "Third handler",
                version = "1.0.0",
                category = "utilities",
                iconUrl = null,
                publisher = "Test",
                installed = false
            )
        )

        whenever(registryClient.getCategories())
            .thenReturn(Result.success(CategoryListResponse(emptyList())))
        whenever(registryClient.listHandlers(isNull(), eq(1), any()))
            .thenReturn(Result.success(HandlerListResponse(handlers, 3, 1, true)))
        whenever(registryClient.listHandlers(isNull(), eq(2), any()))
            .thenReturn(Result.success(HandlerListResponse(moreHandlers, 3, 2, false)))

        viewModel = HandlerDiscoveryViewModel(registryClient, vaultHandlerClient)
        advanceUntilIdle()

        // Verify initial state
        var state = viewModel.state.first() as HandlerDiscoveryState.Loaded
        assertEquals(2, state.handlers.size)
        assertTrue(state.hasMore)

        // Act
        viewModel.loadMore()
        advanceUntilIdle()

        // Assert
        state = viewModel.state.first() as HandlerDiscoveryState.Loaded
        assertEquals(3, state.handlers.size)
        assertFalse(state.hasMore)
    }

    @Test
    fun `error state when loading fails`() = runTest {
        // Arrange
        whenever(registryClient.getCategories())
            .thenReturn(Result.success(CategoryListResponse(emptyList())))
        whenever(registryClient.listHandlers(anyOrNull(), any(), any()))
            .thenReturn(Result.failure(HandlerRegistryException("Network error")))

        // Act
        viewModel = HandlerDiscoveryViewModel(registryClient, vaultHandlerClient)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first()
        assertTrue("Expected Error but got $state", state is HandlerDiscoveryState.Error)
        assertEquals("Network error", (state as HandlerDiscoveryState.Error).message)
    }

    @Test
    fun `refresh reloads handlers from page 1`() = runTest {
        // Arrange
        val handlers = createSampleHandlers()
        setupDefaultMocks(handlers)

        viewModel = HandlerDiscoveryViewModel(registryClient, vaultHandlerClient)
        advanceUntilIdle()

        // Act
        viewModel.refresh()
        advanceUntilIdle()

        // Assert - should have called listHandlers twice
        verify(registryClient, times(2)).listHandlers(isNull(), eq(1), eq(20))
    }
}
