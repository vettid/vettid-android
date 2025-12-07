package com.vettid.app.features.auth

import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.crypto.PasswordEncryptionResult
import com.vettid.app.core.network.AuthExecuteResponse
import com.vettid.app.core.network.AuthRequestResponse
import com.vettid.app.core.network.CredentialBlob
import com.vettid.app.core.network.LAT
import com.vettid.app.core.network.TransactionKeyInfo
import com.vettid.app.core.network.TransactionKeyPublic
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.network.VaultServiceException
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
class AuthenticationViewModelTest {

    private lateinit var viewModel: AuthenticationViewModel
    private lateinit var vaultServiceClient: VaultServiceClient
    private lateinit var cryptoManager: CryptoManager
    private lateinit var credentialStore: CredentialStore

    private val testDispatcher = StandardTestDispatcher()

    private val testCredential = StoredCredential(
        userGuid = "test-user-guid",
        encryptedBlob = "encrypted-blob-base64",
        cekVersion = 1,
        latId = "lat-123",
        latToken = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234",
        latVersion = 1,
        passwordSalt = "c2FsdA==",
        createdAt = System.currentTimeMillis(),
        lastUsedAt = System.currentTimeMillis()
    )

    private val testTransactionKey = TransactionKeyInfo(
        keyId = "tk-123",
        publicKey = "dGVzdC1wdWJsaWMta2V5",
        algorithm = "X25519"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        vaultServiceClient = mock()
        cryptoManager = mock()
        credentialStore = mock()

        // Default mock responses
        whenever(credentialStore.getStoredCredential()).thenReturn(testCredential)
        whenever(credentialStore.getUtkPool()).thenReturn(listOf(testTransactionKey))
        whenever(credentialStore.getPasswordSaltBytes()).thenReturn(ByteArray(16))

        viewModel = AuthenticationViewModel(
            vaultServiceClient = vaultServiceClient,
            cryptoManager = cryptoManager,
            credentialStore = credentialStore
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // MARK: - Initial State Tests

    @Test
    fun `initial state is Initial`() = runTest {
        assertEquals(AuthenticationState.Initial, viewModel.state.first())
    }

    // MARK: - Start Authentication Tests

    @Test
    fun `StartAuth transitions to RequestingAction then VerifyingLAT on success`() = runTest {
        val authResponse = AuthRequestResponse(
            authSessionId = "session-123",
            lat = LAT(latId = "lat-123", token = testCredential.latToken, version = 1),
            endpoint = "/vault/auth/execute"
        )

        whenever(vaultServiceClient.authRequest(eq(testCredential.userGuid), any()))
            .thenReturn(Result.success(authResponse))

        whenever(cryptoManager.verifyLat(any(), any())).thenReturn(true)

        viewModel.onEvent(AuthenticationEvent.StartAuth("vault_start"))
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected VerifyingLAT state but got $state", state is AuthenticationState.VerifyingLAT)

        val verifyState = state as AuthenticationState.VerifyingLAT
        assertEquals("session-123", verifyState.authSessionId)
        assertTrue(verifyState.latMatch)
    }

    @Test
    fun `StartAuth with no stored credential shows error`() = runTest {
        whenever(credentialStore.getStoredCredential()).thenReturn(null)

        viewModel.onEvent(AuthenticationEvent.StartAuth("vault_start"))
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected Error state but got $state", state is AuthenticationState.Error)
        assertEquals(AuthErrorCode.CREDENTIAL_NOT_FOUND, (state as AuthenticationState.Error).code)
    }

    @Test
    fun `StartAuth with API failure shows network error`() = runTest {
        whenever(vaultServiceClient.authRequest(any(), any()))
            .thenReturn(Result.failure(VaultServiceException("Network error")))

        viewModel.onEvent(AuthenticationEvent.StartAuth("vault_start"))
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected Error state but got $state", state is AuthenticationState.Error)
        assertEquals(AuthErrorCode.NETWORK_ERROR, (state as AuthenticationState.Error).code)
    }

    @Test
    fun `StartAuth with concurrent session shows conflict error`() = runTest {
        whenever(vaultServiceClient.authRequest(any(), any()))
            .thenReturn(Result.failure(VaultServiceException("Concurrent session", code = 409)))

        viewModel.onEvent(AuthenticationEvent.StartAuth("vault_start"))
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is AuthenticationState.Error)
        assertEquals(AuthErrorCode.CONCURRENT_SESSION, (state as AuthenticationState.Error).code)
    }

    // MARK: - LAT Verification Tests

    @Test
    fun `LAT mismatch sets latMatch to false`() = runTest {
        val authResponse = AuthRequestResponse(
            authSessionId = "session-123",
            lat = LAT(latId = "lat-456", token = "different-token", version = 1),
            endpoint = "/vault/auth/execute"
        )

        whenever(vaultServiceClient.authRequest(any(), any()))
            .thenReturn(Result.success(authResponse))

        whenever(cryptoManager.verifyLat(any(), any())).thenReturn(false)

        viewModel.onEvent(AuthenticationEvent.StartAuth("vault_start"))
        advanceUntilIdle()

        val state = viewModel.state.first() as AuthenticationState.VerifyingLAT
        assertFalse(state.latMatch)
    }

    @Test
    fun `ConfirmLAT with match transitions to EnteringPassword`() = runTest {
        // Setup state to VerifyingLAT with match
        setUpVerifyingLATState(latMatch = true)

        viewModel.onEvent(AuthenticationEvent.ConfirmLAT)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected EnteringPassword but got $state", state is AuthenticationState.EnteringPassword)
    }

    @Test
    fun `ConfirmLAT with mismatch does not proceed`() = runTest {
        setUpVerifyingLATState(latMatch = false)

        viewModel.onEvent(AuthenticationEvent.ConfirmLAT)
        advanceUntilIdle()

        // Should still be in VerifyingLAT state
        val state = viewModel.state.first()
        assertTrue(state is AuthenticationState.VerifyingLAT)
    }

    @Test
    fun `RejectLAT returns to Initial state`() = runTest {
        setUpVerifyingLATState(latMatch = false)

        viewModel.onEvent(AuthenticationEvent.RejectLAT)
        advanceUntilIdle()

        assertEquals(AuthenticationState.Initial, viewModel.state.first())
    }

    // MARK: - Password Entry Tests

    @Test
    fun `PasswordChanged updates password in state`() = runTest {
        setUpEnteringPasswordState()

        viewModel.onEvent(AuthenticationEvent.PasswordChanged("mypassword"))
        advanceUntilIdle()

        val state = viewModel.state.first() as AuthenticationState.EnteringPassword
        assertEquals("mypassword", state.password)
    }

    @Test
    fun `SubmitPassword with empty password shows error`() = runTest {
        setUpEnteringPasswordState()

        viewModel.onEvent(AuthenticationEvent.SubmitPassword)
        advanceUntilIdle()

        val state = viewModel.state.first() as AuthenticationState.EnteringPassword
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("enter"))
    }

    @Test
    fun `SubmitPassword with no UTKs shows error`() = runTest {
        setUpEnteringPasswordState()
        whenever(credentialStore.getUtkPool()).thenReturn(emptyList())

        viewModel.onEvent(AuthenticationEvent.PasswordChanged("testpassword"))
        viewModel.onEvent(AuthenticationEvent.SubmitPassword)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is AuthenticationState.Error)
        assertEquals(AuthErrorCode.NO_TRANSACTION_KEYS, (state as AuthenticationState.Error).code)
    }

    // MARK: - Authentication Execution Tests

    @Test
    fun `successful authentication updates credentials and transitions to Success`() = runTest {
        setUpEnteringPasswordState()

        val encryptionResult = PasswordEncryptionResult(
            encryptedPasswordHash = "encrypted-hash",
            ephemeralPublicKey = "ephemeral-key",
            nonce = "nonce"
        )
        whenever(cryptoManager.encryptPasswordForServer(any(), any(), any()))
            .thenReturn(encryptionResult)

        val authResponse = AuthExecuteResponse(
            success = true,
            newCredentialBlob = CredentialBlob(
                data = "new-blob",
                version = 2,
                cekVersion = 2
            ),
            newLat = LAT(latId = "lat-new", token = "new-token", version = 2),
            actionToken = "action-token-123",
            newTransactionKeys = null
        )
        whenever(vaultServiceClient.authExecute(any(), any(), any(), any()))
            .thenReturn(Result.success(authResponse))

        viewModel.onEvent(AuthenticationEvent.PasswordChanged("correctpassword"))
        viewModel.onEvent(AuthenticationEvent.SubmitPassword)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected Success but got $state", state is AuthenticationState.Success)
        assertEquals("action-token-123", (state as AuthenticationState.Success).actionToken)

        // Verify credential rotation
        verify(credentialStore).removeUtk(testTransactionKey.keyId)
        verify(credentialStore).updateCredentialBlob(
            encryptedBlob = "new-blob",
            cekVersion = 2,
            newLat = authResponse.newLat,
            newTransactionKeys = null
        )
    }

    @Test
    fun `authentication with wrong password shows error`() = runTest {
        setUpEnteringPasswordState()

        val encryptionResult = PasswordEncryptionResult(
            encryptedPasswordHash = "encrypted-hash",
            ephemeralPublicKey = "ephemeral-key",
            nonce = "nonce"
        )
        whenever(cryptoManager.encryptPasswordForServer(any(), any(), any()))
            .thenReturn(encryptionResult)

        whenever(vaultServiceClient.authExecute(any(), any(), any(), any()))
            .thenReturn(Result.failure(VaultServiceException("Unauthorized", code = 401)))

        viewModel.onEvent(AuthenticationEvent.PasswordChanged("wrongpassword"))
        viewModel.onEvent(AuthenticationEvent.SubmitPassword)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is AuthenticationState.Error)
        assertEquals(AuthErrorCode.INVALID_CREDENTIALS, (state as AuthenticationState.Error).code)
        assertFalse(state.retryable)
    }

    @Test
    fun `authentication with key replenishment tracks count`() = runTest {
        setUpEnteringPasswordState()

        val encryptionResult = PasswordEncryptionResult(
            encryptedPasswordHash = "encrypted-hash",
            ephemeralPublicKey = "ephemeral-key",
            nonce = "nonce"
        )
        whenever(cryptoManager.encryptPasswordForServer(any(), any(), any()))
            .thenReturn(encryptionResult)

        val newKeys = listOf(
            TransactionKeyPublic(keyId = "new-key-1", publicKey = "key1", algorithm = "X25519"),
            TransactionKeyPublic(keyId = "new-key-2", publicKey = "key2", algorithm = "X25519")
        )

        val authResponse = AuthExecuteResponse(
            success = true,
            newCredentialBlob = CredentialBlob(data = "new-blob", version = 2, cekVersion = 2),
            newLat = LAT(latId = "lat-new", token = "new-token", version = 2),
            actionToken = "action-token-123",
            newTransactionKeys = newKeys
        )
        whenever(vaultServiceClient.authExecute(any(), any(), any(), any()))
            .thenReturn(Result.success(authResponse))

        viewModel.onEvent(AuthenticationEvent.PasswordChanged("correctpassword"))
        viewModel.onEvent(AuthenticationEvent.SubmitPassword)
        advanceUntilIdle()

        val state = viewModel.state.first() as AuthenticationState.Success
        assertEquals(2, state.keysReplenished)
    }

    // MARK: - Cancel and Retry Tests

    @Test
    fun `Cancel resets to Initial state`() = runTest {
        setUpEnteringPasswordState()

        viewModel.onEvent(AuthenticationEvent.Cancel)
        advanceUntilIdle()

        assertEquals(AuthenticationState.Initial, viewModel.state.first())
    }

    @Test
    fun `Retry from retryable error restarts flow`() = runTest {
        // Setup error state with retryable = true
        setUpErrorState(retryable = true)

        // Reset mock for retry
        val authResponse = AuthRequestResponse(
            authSessionId = "session-retry",
            lat = LAT(latId = "lat-123", token = testCredential.latToken, version = 1),
            endpoint = "/vault/auth/execute"
        )
        whenever(vaultServiceClient.authRequest(any(), any()))
            .thenReturn(Result.success(authResponse))
        whenever(cryptoManager.verifyLat(any(), any())).thenReturn(true)

        viewModel.onEvent(AuthenticationEvent.Retry)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected VerifyingLAT but got $state", state is AuthenticationState.VerifyingLAT)
    }

    // MARK: - Key Pool Health Tests

    @Test
    fun `checkKeyPoolHealth returns EMPTY when no keys`() {
        whenever(credentialStore.getUtkCount()).thenReturn(0)
        assertEquals(KeyPoolHealth.EMPTY, viewModel.checkKeyPoolHealth())
    }

    @Test
    fun `checkKeyPoolHealth returns LOW when few keys`() {
        whenever(credentialStore.getUtkCount()).thenReturn(3)
        assertEquals(KeyPoolHealth.LOW, viewModel.checkKeyPoolHealth())
    }

    @Test
    fun `checkKeyPoolHealth returns MODERATE when some keys`() {
        whenever(credentialStore.getUtkCount()).thenReturn(7)
        assertEquals(KeyPoolHealth.MODERATE, viewModel.checkKeyPoolHealth())
    }

    @Test
    fun `checkKeyPoolHealth returns HEALTHY when many keys`() {
        whenever(credentialStore.getUtkCount()).thenReturn(15)
        assertEquals(KeyPoolHealth.HEALTHY, viewModel.checkKeyPoolHealth())
    }

    // MARK: - Helper Methods

    private suspend fun setUpVerifyingLATState(latMatch: Boolean) {
        val field = AuthenticationViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<AuthenticationState>
        stateFlow.value = AuthenticationState.VerifyingLAT(
            authSessionId = "test-session",
            serverLat = LAT(latId = "lat-123", token = "server-token", version = 1),
            storedLatToken = testCredential.latToken,
            endpoint = "/vault/auth/execute",
            latMatch = latMatch
        )

        // Also set currentAction for retry
        val actionField = AuthenticationViewModel::class.java.getDeclaredField("currentAction")
        actionField.isAccessible = true
        actionField.set(viewModel, "test_action")
    }

    private suspend fun setUpEnteringPasswordState() {
        val field = AuthenticationViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<AuthenticationState>
        stateFlow.value = AuthenticationState.EnteringPassword(
            authSessionId = "test-session",
            endpoint = "/vault/auth/execute"
        )

        val actionField = AuthenticationViewModel::class.java.getDeclaredField("currentAction")
        actionField.isAccessible = true
        actionField.set(viewModel, "test_action")
    }

    private suspend fun setUpErrorState(retryable: Boolean) {
        val field = AuthenticationViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<AuthenticationState>
        stateFlow.value = AuthenticationState.Error(
            message = "Test error",
            code = AuthErrorCode.NETWORK_ERROR,
            retryable = retryable,
            previousState = AuthenticationState.RequestingAction("test_action")
        )

        val actionField = AuthenticationViewModel::class.java.getDeclaredField("currentAction")
        actionField.isAccessible = true
        actionField.set(viewModel, "test_action")
    }
}

