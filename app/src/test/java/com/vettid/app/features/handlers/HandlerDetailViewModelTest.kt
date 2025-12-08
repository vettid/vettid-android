package com.vettid.app.features.handlers

import androidx.lifecycle.SavedStateHandle
import com.google.gson.JsonObject
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
class HandlerDetailViewModelTest {

    private lateinit var viewModel: HandlerDetailViewModel
    private lateinit var registryClient: HandlerRegistryClient
    private lateinit var vaultHandlerClient: VaultHandlerClient
    private lateinit var savedStateHandle: SavedStateHandle

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        registryClient = mock()
        vaultHandlerClient = mock()
        savedStateHandle = SavedStateHandle(mapOf("handlerId" to "test-handler"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createSampleHandler(installed: Boolean = false): HandlerDetailResponse {
        return HandlerDetailResponse(
            id = "test-handler",
            name = "Test Handler",
            description = "A detailed test handler description",
            version = "1.0.0",
            category = "messaging",
            iconUrl = "https://example.com/icon.png",
            publisher = "VettID",
            publishedAt = "2025-01-01T00:00:00Z",
            sizeBytes = 1024000,
            permissions = listOf(
                HandlerPermission("network", "api.example.com", "Access to example API"),
                HandlerPermission("storage", "local", "Local storage access")
            ),
            inputSchema = JsonObject().apply {
                addProperty("type", "object")
            },
            outputSchema = JsonObject(),
            changelog = "Initial release",
            installed = installed,
            installedVersion = if (installed) "1.0.0" else null,
            readme = "# Test Handler\n\nA test handler for unit tests.",
            screenshots = listOf("https://example.com/screenshot1.png"),
            rating = 4.5f,
            ratingCount = 100,
            installCount = 5000
        )
    }

    @Test
    fun `initial state is Loading then loads handler`() = runTest {
        // Arrange
        val handler = createSampleHandler()

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))

        // Act
        viewModel = HandlerDetailViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first()
        assertTrue(state is HandlerDetailState.Loaded)

        val loadedState = state as HandlerDetailState.Loaded
        assertEquals("test-handler", loadedState.handler.id)
        assertEquals("Test Handler", loadedState.handler.name)
        assertEquals(2, loadedState.handler.permissions.size)
    }

    @Test
    fun `error state when loading fails`() = runTest {
        // Arrange
        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.failure(HandlerRegistryException("Handler not found")))

        // Act
        viewModel = HandlerDetailViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first()
        assertTrue(state is HandlerDetailState.Error)
        assertEquals("Handler not found", (state as HandlerDetailState.Error).message)
    }

    @Test
    fun `installHandler does not call API when no token`() = runTest {
        // Arrange
        val handler = createSampleHandler(installed = false)

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))

        viewModel = HandlerDetailViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Act - no token set
        viewModel.installHandler()
        advanceUntilIdle()

        // Assert - API should not be called
        verify(vaultHandlerClient, never()).installHandler(any(), any(), any())
    }

    @Test
    fun `installHandler calls API when token is set`() = runTest {
        // Arrange
        val handler = createSampleHandler(installed = false)
        val installResponse = InstallHandlerResponse(
            status = "installed",
            handlerId = "test-handler",
            version = "1.0.0",
            installedAt = "2025-01-01T12:00:00Z"
        )

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))
        whenever(vaultHandlerClient.installHandler(any(), any(), any()))
            .thenReturn(Result.success(installResponse))

        viewModel = HandlerDetailViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        viewModel.setActionToken("test-token")
        advanceUntilIdle()

        // Act
        viewModel.installHandler()
        advanceUntilIdle()

        // Assert
        verify(vaultHandlerClient).installHandler("test-token", "test-handler", "1.0.0")

        // Check state updated
        val state = viewModel.state.first() as HandlerDetailState.Loaded
        assertTrue(state.handler.installed)
        assertEquals("1.0.0", state.handler.installedVersion)
    }

    @Test
    fun `uninstallHandler calls API when token is set`() = runTest {
        // Arrange
        val handler = createSampleHandler(installed = true)
        val uninstallResponse = UninstallHandlerResponse(
            status = "uninstalled",
            handlerId = "test-handler",
            uninstalledAt = "2025-01-01T13:00:00Z"
        )

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))
        whenever(vaultHandlerClient.uninstallHandler(any(), any()))
            .thenReturn(Result.success(uninstallResponse))

        viewModel = HandlerDetailViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        viewModel.setActionToken("test-token")
        advanceUntilIdle()

        // Act
        viewModel.uninstallHandler()
        advanceUntilIdle()

        // Assert
        verify(vaultHandlerClient).uninstallHandler("test-token", "test-handler")

        // Check state updated
        val state = viewModel.state.first() as HandlerDetailState.Loaded
        assertFalse(state.handler.installed)
        assertNull(state.handler.installedVersion)
    }

    @Test
    fun `refresh reloads handler`() = runTest {
        // Arrange
        val handler = createSampleHandler()

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))

        viewModel = HandlerDetailViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Act
        viewModel.refresh()
        advanceUntilIdle()

        // Assert
        verify(registryClient, times(2)).getHandler("test-handler")
    }

    @Test
    fun `isInstalling is false by default`() = runTest {
        // Arrange
        val handler = createSampleHandler()

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))

        viewModel = HandlerDetailViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Assert
        assertFalse(viewModel.isInstalling.first())
    }

    @Test
    fun `empty handlerId does not load handler`() = runTest {
        // Arrange
        savedStateHandle = SavedStateHandle(mapOf("handlerId" to ""))

        // Act
        viewModel = HandlerDetailViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Assert - API should not be called
        verify(registryClient, never()).getHandler(any())
    }
}
