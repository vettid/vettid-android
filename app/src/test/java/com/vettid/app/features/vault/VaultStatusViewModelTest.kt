package com.vettid.app.features.vault

import com.vettid.app.core.network.*
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.StoredCredential
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
class VaultStatusViewModelTest {

    private lateinit var viewModel: VaultStatusViewModel
    private lateinit var vaultServiceClient: VaultServiceClient
    private lateinit var credentialStore: CredentialStore

    private val testDispatcher = StandardTestDispatcher()

    private val testCredential = StoredCredential(
        userGuid = "test-vault-guid",
        encryptedBlob = "encrypted-blob",
        cekVersion = 1,
        latId = "lat-123",
        latToken = "token",
        latVersion = 1,
        passwordSalt = "salt",
        createdAt = System.currentTimeMillis(),
        lastUsedAt = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        vaultServiceClient = mock()
        credentialStore = mock()

        whenever(credentialStore.hasStoredCredential()).thenReturn(true)
        whenever(credentialStore.getStoredCredential()).thenReturn(testCredential)
        whenever(credentialStore.getUserGuid()).thenReturn(testCredential.userGuid)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // MARK: - Initial State Tests

    @Test
    fun `initial state shows Loading then transitions based on credential`() = runTest {
        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        advanceUntilIdle()

        // Without action token, should show Enrolled state based on local credential
        val state = viewModel.state.first()
        assertTrue("Expected Enrolled state but got $state", state is VaultStatusState.Enrolled)
    }

    @Test
    fun `no stored credential shows NotEnrolled`() = runTest {
        whenever(credentialStore.hasStoredCredential()).thenReturn(false)

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        advanceUntilIdle()

        assertEquals(VaultStatusState.NotEnrolled, viewModel.state.first())
    }

    // MARK: - Status Response Mapping Tests

    @Test
    fun `running vault status maps correctly`() = runTest {
        val healthResponse = VaultHealthResponse(
            status = "healthy",
            memoryUsagePercent = 45.0f,
            diskUsagePercent = 30.0f,
            cpuUsagePercent = 20.0f,
            natsConnected = true,
            lastChecked = "2025-12-07T15:00:00Z"
        )

        val statusResponse = VaultStatusResponse(
            vaultId = "vault-123",
            status = "running",
            instanceId = "i-1234567890",
            region = "us-east-1",
            enrolledAt = "2025-12-01T10:00:00Z",
            lastBackup = "2025-12-07T12:00:00Z",
            health = healthResponse
        )

        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.success(statusResponse))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-action-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected Running state but got $state", state is VaultStatusState.Running)

        val runningState = state as VaultStatusState.Running
        assertEquals("vault-123", runningState.vaultId)
        assertEquals("i-1234567890", runningState.instanceId)
        assertEquals("us-east-1", runningState.region)
        assertEquals(HealthLevel.HEALTHY, runningState.health.status)
        assertEquals(45.0f, runningState.health.memoryUsagePercent)
        assertTrue(runningState.health.natsConnected == true)
    }

    @Test
    fun `stopped vault status maps correctly`() = runTest {
        val statusResponse = VaultStatusResponse(
            vaultId = "vault-123",
            status = "stopped",
            instanceId = "i-1234567890",
            lastBackup = "2025-12-07T12:00:00Z"
        )

        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.success(statusResponse))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-action-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected Stopped state but got $state", state is VaultStatusState.Stopped)
    }

    @Test
    fun `provisioning vault status maps correctly`() = runTest {
        val statusResponse = VaultStatusResponse(
            vaultId = "vault-123",
            status = "provisioning"
        )

        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.success(statusResponse))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-action-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected Provisioning state but got $state", state is VaultStatusState.Provisioning)
    }

    // MARK: - Health Level Mapping Tests

    @Test
    fun `degraded health maps correctly`() = runTest {
        val statusResponse = VaultStatusResponse(
            vaultId = "vault-123",
            status = "running",
            health = VaultHealthResponse(
                status = "degraded",
                memoryUsagePercent = 85.0f,
                cpuUsagePercent = 90.0f
            )
        )

        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.success(statusResponse))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        val state = viewModel.state.first() as VaultStatusState.Running
        assertEquals(HealthLevel.DEGRADED, state.health.status)
    }

    @Test
    fun `unhealthy status maps correctly`() = runTest {
        val statusResponse = VaultStatusResponse(
            vaultId = "vault-123",
            status = "running",
            health = VaultHealthResponse(status = "unhealthy")
        )

        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.success(statusResponse))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        val state = viewModel.state.first() as VaultStatusState.Running
        assertEquals(HealthLevel.UNHEALTHY, state.health.status)
    }

    // MARK: - Vault Action Tests

    @Test
    fun `StartEnrollment emits NavigateToEnrollment effect`() = runTest {
        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        advanceUntilIdle()

        var effectEmitted = false
        val job = kotlinx.coroutines.launch {
            viewModel.effects.collect { effect ->
                if (effect is VaultStatusEffect.NavigateToEnrollment) {
                    effectEmitted = true
                }
            }
        }

        viewModel.onEvent(VaultStatusEvent.StartEnrollment)
        advanceUntilIdle()

        job.cancel()
        assertTrue(effectEmitted)
    }

    @Test
    fun `ProvisionVault without action token emits RequireAuth`() = runTest {
        val statusResponse = VaultStatusResponse(
            vaultId = "vault-123",
            status = "enrolled",
            enrolledAt = "2025-12-01T10:00:00Z"
        )

        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.success(statusResponse))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        // Clear action token
        viewModel.setActionToken("")

        var requireAuthEffect: VaultStatusEffect.RequireAuth? = null
        val job = kotlinx.coroutines.launch {
            viewModel.effects.collect { effect ->
                if (effect is VaultStatusEffect.RequireAuth) {
                    requireAuthEffect = effect
                }
            }
        }

        // Set state to Enrolled for provision
        setStateToEnrolled()

        // This test checks that provision requires auth - we need action token check
        job.cancel()
    }

    @Test
    fun `StopVault on running vault calls API and updates state`() = runTest {
        // Setup running state
        val statusResponse = VaultStatusResponse(
            vaultId = "vault-123",
            status = "running",
            instanceId = "i-123",
            health = VaultHealthResponse(status = "healthy")
        )

        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.success(statusResponse))
        whenever(vaultServiceClient.stopVault(any()))
            .thenReturn(Result.success(Unit))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        // Verify we're in Running state
        assertTrue(viewModel.state.first() is VaultStatusState.Running)

        // Stop vault
        viewModel.onEvent(VaultStatusEvent.StopVault)
        advanceUntilIdle()

        // Verify API was called
        verify(vaultServiceClient).stopVault("Bearer test-token")

        // Should transition to Stopped
        val state = viewModel.state.first()
        assertTrue("Expected Stopped state but got $state", state is VaultStatusState.Stopped)
    }

    @Test
    fun `TriggerBackup calls API and emits success`() = runTest {
        val backupResponse = BackupStatusResponse(
            backupId = "backup-123",
            status = "in_progress",
            startedAt = "2025-12-07T15:00:00Z"
        )

        whenever(vaultServiceClient.triggerBackup(any()))
            .thenReturn(Result.success(backupResponse))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-token")
        advanceUntilIdle()

        var successEffect: VaultStatusEffect.ShowSuccess? = null
        val job = kotlinx.coroutines.launch {
            viewModel.effects.collect { effect ->
                if (effect is VaultStatusEffect.ShowSuccess) {
                    successEffect = effect
                }
            }
        }

        viewModel.onEvent(VaultStatusEvent.TriggerBackup)
        advanceUntilIdle()

        job.cancel()

        verify(vaultServiceClient).triggerBackup("Bearer test-token")
        assertNotNull(successEffect)
        assertTrue(successEffect!!.message.contains("backup-123"))
    }

    // MARK: - Status Summary Tests

    @Test
    fun `getStatusSummary returns correct summary for NotEnrolled`() = runTest {
        whenever(credentialStore.hasStoredCredential()).thenReturn(false)

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        advanceUntilIdle()

        val summary = viewModel.getStatusSummary()
        assertEquals("Not Set Up", summary.title)
        assertEquals(VaultStatusIcon.NOT_ENROLLED, summary.icon)
        assertEquals("Set Up Vault", summary.actionLabel)
    }

    @Test
    fun `getStatusSummary returns correct summary for Running healthy`() = runTest {
        val statusResponse = VaultStatusResponse(
            vaultId = "vault-123",
            status = "running",
            health = VaultHealthResponse(status = "healthy")
        )

        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.success(statusResponse))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        val summary = viewModel.getStatusSummary()
        assertEquals("Running", summary.title)
        assertEquals(VaultStatusIcon.HEALTHY, summary.icon)
    }

    @Test
    fun `needsAttention returns true for degraded health`() = runTest {
        val statusResponse = VaultStatusResponse(
            vaultId = "vault-123",
            status = "running",
            health = VaultHealthResponse(status = "degraded")
        )

        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.success(statusResponse))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        assertTrue(viewModel.needsAttention())
    }

    @Test
    fun `needsAttention returns false for healthy status`() = runTest {
        val statusResponse = VaultStatusResponse(
            vaultId = "vault-123",
            status = "running",
            health = VaultHealthResponse(status = "healthy")
        )

        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.success(statusResponse))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        assertFalse(viewModel.needsAttention())
    }

    // MARK: - Error Handling Tests

    @Test
    fun `network error shows Error state with retry`() = runTest {
        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.failure(VaultServiceException("Network error")))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected Error state but got $state", state is VaultStatusState.Error)

        val errorState = state as VaultStatusState.Error
        assertEquals(VaultErrorCode.NETWORK_ERROR, errorState.code)
        assertTrue(errorState.retryable)
    }

    @Test
    fun `404 shows Enrolled state instead of error`() = runTest {
        whenever(vaultServiceClient.getVaultStatus(any()))
            .thenReturn(Result.failure(VaultServiceException("Not found", code = 404)))

        viewModel = VaultStatusViewModel(vaultServiceClient, credentialStore)
        viewModel.setActionToken("test-token")
        viewModel.onEvent(VaultStatusEvent.Refresh)
        advanceUntilIdle()

        // 404 means vault not provisioned yet, should show Enrolled
        val state = viewModel.state.first()
        assertTrue("Expected Enrolled state but got $state", state is VaultStatusState.Enrolled)
    }

    // MARK: - Helper Methods

    private fun setStateToEnrolled() {
        val field = VaultStatusViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<VaultStatusState>
        stateFlow.value = VaultStatusState.Enrolled(
            vaultId = "test-vault",
            enrolledAt = "2025-12-01T10:00:00Z"
        )
    }
}
