package com.vettid.app.features.handlers

import androidx.lifecycle.SavedStateHandle
import com.google.gson.JsonArray
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
class HandlerExecutionViewModelTest {

    private lateinit var viewModel: HandlerExecutionViewModel
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

    private fun createSampleHandler(): HandlerDetailResponse {
        val inputSchema = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("message", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("title", "Message")
                    addProperty("description", "The message to send")
                })
                add("priority", JsonObject().apply {
                    addProperty("type", "integer")
                    addProperty("title", "Priority")
                    addProperty("default", 1)
                })
                add("enabled", JsonObject().apply {
                    addProperty("type", "boolean")
                    addProperty("title", "Enabled")
                    addProperty("default", true)
                })
            })
            add("required", JsonArray().apply {
                add("message")
            })
        }

        return HandlerDetailResponse(
            id = "test-handler",
            name = "Test Handler",
            description = "A test handler",
            version = "1.0.0",
            category = "messaging",
            iconUrl = null,
            publisher = "VettID",
            publishedAt = "2025-01-01T00:00:00Z",
            sizeBytes = 1024,
            permissions = emptyList(),
            inputSchema = inputSchema,
            outputSchema = JsonObject(),
            changelog = null,
            installed = true,
            installedVersion = "1.0.0"
        )
    }

    @Test
    fun `initial state loads handler and parses input schema`() = runTest {
        // Arrange
        val handler = createSampleHandler()

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))

        // Act
        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first()
        assertTrue("Expected Ready but got $state", state is HandlerExecutionState.Ready)

        val readyState = state as HandlerExecutionState.Ready
        assertEquals(3, readyState.inputFields.size)

        // Check field parsing
        val messageField = readyState.inputFields.find { it.name == "message" }
        assertNotNull(messageField)
        assertEquals("Message", messageField!!.label)
        assertTrue(messageField.required)
        assertTrue(messageField.type is InputFieldType.String)

        val priorityField = readyState.inputFields.find { it.name == "priority" }
        assertNotNull(priorityField)
        assertTrue(priorityField!!.type is InputFieldType.Integer)
        assertEquals(1, priorityField.defaultValue)

        val enabledField = readyState.inputFields.find { it.name == "enabled" }
        assertNotNull(enabledField)
        assertTrue(enabledField!!.type is InputFieldType.Boolean)
        assertEquals(true, enabledField.defaultValue)
    }

    @Test
    fun `updateFieldValue updates form values`() = runTest {
        // Arrange
        val handler = createSampleHandler()

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))

        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Act
        viewModel.updateFieldValue("message", "Hello World")
        viewModel.updateFieldValue("priority", "5")

        // Assert
        val formValues = viewModel.formValues.first()
        assertEquals("Hello World", formValues["message"])
        assertEquals("5", formValues["priority"])
    }

    @Test
    fun `executeHandler does not call API when no token`() = runTest {
        // Arrange
        val handler = createSampleHandler()

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))

        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        viewModel.updateFieldValue("message", "Test")

        // Act - no token set
        viewModel.executeHandler()
        advanceUntilIdle()

        // Assert - API should not be called
        verify(vaultHandlerClient, never()).executeHandler(any(), any(), any(), any())
    }

    @Test
    fun `executeHandler does not call API when required field missing`() = runTest {
        // Arrange
        val handler = createSampleHandler()

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))

        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        viewModel.setActionToken("test-token")
        advanceUntilIdle()

        // Act - don't set required "message" field
        viewModel.executeHandler()
        advanceUntilIdle()

        // Assert - API should not be called
        verify(vaultHandlerClient, never()).executeHandler(any(), any(), any(), any())
    }

    @Test
    fun `executeHandler calls API with correct input`() = runTest {
        // Arrange
        val handler = createSampleHandler()
        val executeResponse = ExecuteHandlerResponse(
            requestId = "req-123",
            status = "success",
            output = JsonObject().apply {
                addProperty("result", "OK")
            },
            error = null,
            executionTimeMs = 150
        )

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))
        whenever(vaultHandlerClient.executeHandler(any(), any(), any(), any()))
            .thenReturn(Result.success(executeResponse))

        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        viewModel.setActionToken("test-token")
        advanceUntilIdle()

        viewModel.updateFieldValue("message", "Hello World")
        viewModel.updateFieldValue("priority", "3")
        viewModel.updateFieldValue("enabled", false)

        // Act
        viewModel.executeHandler()
        advanceUntilIdle()

        // Assert
        val captor = argumentCaptor<JsonObject>()
        verify(vaultHandlerClient).executeHandler(eq("test-token"), eq("test-handler"), captor.capture(), any())

        val input = captor.firstValue
        assertEquals("Hello World", input.get("message").asString)
        assertEquals(3, input.get("priority").asInt)
        assertEquals(false, input.get("enabled").asBoolean)

        // Check state transitioned to Completed
        val state = viewModel.state.first()
        assertTrue("Expected Completed but got $state", state is HandlerExecutionState.Completed)

        val completedState = state as HandlerExecutionState.Completed
        assertEquals(150L, completedState.executionTimeMs)
        assertNotNull(completedState.output)
    }

    @Test
    fun `executeHandler handles error response`() = runTest {
        // Arrange
        val handler = createSampleHandler()
        val executeResponse = ExecuteHandlerResponse(
            requestId = "req-123",
            status = "error",
            output = null,
            error = "Handler execution failed",
            executionTimeMs = 50
        )

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))
        whenever(vaultHandlerClient.executeHandler(any(), any(), any(), any()))
            .thenReturn(Result.success(executeResponse))

        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        viewModel.setActionToken("test-token")
        advanceUntilIdle()

        viewModel.updateFieldValue("message", "Test")

        // Act
        viewModel.executeHandler()
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first()
        assertTrue("Expected Failed but got $state", state is HandlerExecutionState.Failed)
        assertEquals("Handler execution failed", (state as HandlerExecutionState.Failed).error)
    }

    @Test
    fun `executeHandler handles timeout`() = runTest {
        // Arrange
        val handler = createSampleHandler()
        val executeResponse = ExecuteHandlerResponse(
            requestId = "req-123",
            status = "timeout",
            output = null,
            error = null,
            executionTimeMs = 30000
        )

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))
        whenever(vaultHandlerClient.executeHandler(any(), any(), any(), any()))
            .thenReturn(Result.success(executeResponse))

        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        viewModel.setActionToken("test-token")
        advanceUntilIdle()

        viewModel.updateFieldValue("message", "Test")

        // Act
        viewModel.executeHandler()
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first()
        assertTrue("Expected Failed but got $state", state is HandlerExecutionState.Failed)
        assertEquals("Execution timed out", (state as HandlerExecutionState.Failed).error)
    }

    @Test
    fun `resetExecution returns to Ready state`() = runTest {
        // Arrange
        val handler = createSampleHandler()
        val executeResponse = ExecuteHandlerResponse(
            requestId = "req-123",
            status = "success",
            output = JsonObject(),
            error = null,
            executionTimeMs = 100
        )

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))
        whenever(vaultHandlerClient.executeHandler(any(), any(), any(), any()))
            .thenReturn(Result.success(executeResponse))

        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        viewModel.setActionToken("test-token")
        advanceUntilIdle()

        viewModel.updateFieldValue("message", "Test")
        viewModel.executeHandler()
        advanceUntilIdle()

        assertTrue(viewModel.state.first() is HandlerExecutionState.Completed)

        // Act
        viewModel.resetExecution()
        advanceUntilIdle()

        // Assert
        assertTrue(viewModel.state.first() is HandlerExecutionState.Ready)
    }

    @Test
    fun `isExecuting is false by default`() = runTest {
        // Arrange
        val handler = createSampleHandler()

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))

        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Assert
        assertFalse(viewModel.isExecuting.first())
    }

    @Test
    fun `parseInputSchema handles select type`() = runTest {
        // Arrange
        val inputSchema = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("status", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("title", "Status")
                    add("enum", JsonArray().apply {
                        add("pending")
                        add("active")
                        add("completed")
                    })
                })
            })
        }

        val handler = HandlerDetailResponse(
            id = "test-handler",
            name = "Test",
            description = "Test",
            version = "1.0.0",
            category = "test",
            iconUrl = null,
            publisher = "Test",
            publishedAt = "",
            sizeBytes = 0,
            permissions = emptyList(),
            inputSchema = inputSchema,
            outputSchema = JsonObject(),
            changelog = null,
            installed = true
        )

        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.success(handler))

        // Act
        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first() as HandlerExecutionState.Ready
        val statusField = state.inputFields.find { it.name == "status" }
        assertNotNull(statusField)
        assertTrue(statusField!!.type is InputFieldType.Select)

        val selectType = statusField.type as InputFieldType.Select
        assertEquals(3, selectType.options.size)
        assertEquals(listOf("pending", "active", "completed"), selectType.options)
    }

    @Test
    fun `error state when loading fails`() = runTest {
        // Arrange
        whenever(registryClient.getHandler("test-handler"))
            .thenReturn(Result.failure(HandlerRegistryException("Network error")))

        // Act
        viewModel = HandlerExecutionViewModel(registryClient, vaultHandlerClient, savedStateHandle)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.first()
        assertTrue("Expected Error but got $state", state is HandlerExecutionState.Error)
        assertEquals("Network error", (state as HandlerExecutionState.Error).message)
    }
}
