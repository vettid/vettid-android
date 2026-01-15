package com.vettid.app.features.enrollment

import android.content.Context
import com.vettid.app.core.attestation.AttestationResult
import com.vettid.app.core.attestation.HardwareAttestationManager
import com.vettid.app.core.attestation.NitroAttestationVerifier
import com.vettid.app.core.attestation.PcrConfigManager
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.crypto.PasswordEncryptionResult
import com.vettid.app.core.nats.NitroEnrollmentClient
import com.vettid.app.core.network.*
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.security.cert.X509Certificate

@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentViewModelTest {

    private lateinit var viewModel: EnrollmentViewModel
    private lateinit var context: Context
    private lateinit var vaultServiceClient: VaultServiceClient
    private lateinit var cryptoManager: CryptoManager
    private lateinit var attestationManager: HardwareAttestationManager
    private lateinit var nitroAttestationVerifier: NitroAttestationVerifier
    private lateinit var nitroEnrollmentClient: NitroEnrollmentClient
    private lateinit var credentialStore: CredentialStore
    private lateinit var pcrConfigManager: PcrConfigManager

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mock()
        vaultServiceClient = mock()
        cryptoManager = mock()
        attestationManager = mock()
        nitroAttestationVerifier = mock()
        nitroEnrollmentClient = mock()
        credentialStore = mock()
        pcrConfigManager = mock()

        viewModel = EnrollmentViewModel(
            context = context,
            vaultServiceClient = vaultServiceClient,
            cryptoManager = cryptoManager,
            attestationManager = attestationManager,
            nitroAttestationVerifier = nitroAttestationVerifier,
            nitroEnrollmentClient = nitroEnrollmentClient,
            credentialStore = credentialStore,
            pcrConfigManager = pcrConfigManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // MARK: - Initial State Tests

    @Test
    fun `initial state is Initial`() = runTest {
        assertEquals(EnrollmentState.Initial, viewModel.state.first())
    }

    @Test
    fun `StartScanning event transitions to ScanningQR state`() = runTest {
        viewModel.onEvent(EnrollmentEvent.StartScanning)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is EnrollmentState.ScanningQR)
    }

    // MARK: - Invite Code Processing Tests
    // NOTE: QR code processing tests require complex suspend function mocking with viewModelScope.
    // These are better tested as instrumented tests. See EnrollmentFlowInstrumentedTest.

    @Test
    @org.junit.Ignore("Requires instrumented test - suspend function mocking with viewModelScope is complex")
    fun `QRCodeScanned transitions to ProcessingInvite then SettingPassword on success`() = runTest {
        // Valid QR code format
        val qrData = """{"type":"vettid_enrollment","version":1,"api_url":"https://api.vettid.com","session_token":"test-token","user_guid":"user-123"}"""
        val sessionId = "session-uuid"

        // Mock setEnrollmentApiUrl (non-suspend function)
        doNothing().whenever(vaultServiceClient).setEnrollmentApiUrl(any())

        // Mock authenticate response - use stub/onBlocking for suspend functions
        val authResponse = EnrollAuthenticateResponse(
            enrollmentToken = "jwt-token",
            tokenType = "Bearer",
            expiresIn = 3600,
            expiresAt = "2025-01-01T12:00:00Z",
            enrollmentSessionId = sessionId,
            userGuid = "user-guid"
        )
        vaultServiceClient.stub {
            onBlocking { enrollAuthenticate(any(), any()) } doReturn Result.success(authResponse)
        }

        val enrollStartResponse = EnrollStartResponse(
            enrollmentSessionId = sessionId,
            userGuid = "user-guid",
            transactionKeys = listOf(
                TransactionKeyPublic(keyId = "key1", publicKey = "base64pubkey", algorithm = "X25519")
            ),
            passwordKeyId = "key1",
            attestationRequired = false  // Skip attestation for simpler test
        )

        vaultServiceClient.stub {
            onBlocking { enrollStart(any()) } doReturn Result.success(enrollStartResponse)
        }

        whenever(cryptoManager.generateSalt()).thenReturn(ByteArray(16))

        // Start scanning first
        viewModel.onEvent(EnrollmentEvent.StartScanning)
        advanceUntilIdle()

        // Scan QR code
        viewModel.onEvent(EnrollmentEvent.QRCodeScanned(qrData))
        advanceUntilIdle()

        val state = viewModel.state.first()
        // After enrollment start completes, should be in SettingPassword
        assertTrue("Expected SettingPassword state but got $state", state is EnrollmentState.SettingPassword)
    }

    @Test
    @org.junit.Ignore("Requires instrumented test - suspend function mocking with viewModelScope is complex")
    fun `QRCodeScanned transitions to Error on API failure`() = runTest {
        // Valid QR code format but API will fail
        val qrData = """{"type":"vettid_enrollment","version":1,"api_url":"https://api.vettid.com","session_token":"invalid-token","user_guid":"user-123"}"""

        // Mock setEnrollmentApiUrl
        doNothing().whenever(vaultServiceClient).setEnrollmentApiUrl(any())

        // Mock authenticate to fail - use stub/onBlocking for suspend functions
        vaultServiceClient.stub {
            onBlocking { enrollAuthenticate(any(), any()) } doReturn Result.failure(VaultServiceException("Invalid invite code", code = 400))
        }

        viewModel.onEvent(EnrollmentEvent.StartScanning)
        advanceUntilIdle()

        viewModel.onEvent(EnrollmentEvent.QRCodeScanned(qrData))
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue("Expected Error state but got $state", state is EnrollmentState.Error)
        assertTrue((state as EnrollmentState.Error).retryable)
    }

    // MARK: - Password Validation Tests

    @Test
    fun `PasswordChanged updates password and strength`() = runTest {
        // Setup a SettingPassword state
        setUpSettingPasswordState()

        viewModel.onEvent(EnrollmentEvent.PasswordChanged("weakpass"))
        advanceUntilIdle()

        val state = viewModel.state.first() as EnrollmentState.SettingPassword
        assertEquals("weakpass", state.password)
        assertEquals(PasswordStrength.WEAK, state.strength)
    }

    @Test
    fun `strong password has STRONG strength`() = runTest {
        setUpSettingPasswordState()

        viewModel.onEvent(EnrollmentEvent.PasswordChanged("MyStr0ng!P@ssword123"))
        advanceUntilIdle()

        val state = viewModel.state.first() as EnrollmentState.SettingPassword
        assertEquals(PasswordStrength.STRONG, state.strength)
    }

    @Test
    fun `SubmitPassword with mismatched passwords shows error`() = runTest {
        setUpSettingPasswordState()

        viewModel.onEvent(EnrollmentEvent.PasswordChanged("StrongP@ssword123"))
        viewModel.onEvent(EnrollmentEvent.ConfirmPasswordChanged("DifferentPassword"))
        viewModel.onEvent(EnrollmentEvent.SubmitPassword)
        advanceUntilIdle()

        val state = viewModel.state.first() as EnrollmentState.SettingPassword
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("match"))
    }

    @Test
    fun `SubmitPassword with short password shows error`() = runTest {
        setUpSettingPasswordState()

        viewModel.onEvent(EnrollmentEvent.PasswordChanged("short"))
        viewModel.onEvent(EnrollmentEvent.ConfirmPasswordChanged("short"))
        viewModel.onEvent(EnrollmentEvent.SubmitPassword)
        advanceUntilIdle()

        val state = viewModel.state.first() as EnrollmentState.SettingPassword
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("12 characters"))
    }

    // MARK: - Retry and Cancel Tests

    @Test
    fun `Cancel event resets to Initial state`() = runTest {
        setUpSettingPasswordState()

        viewModel.onEvent(EnrollmentEvent.Cancel)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertEquals(EnrollmentState.Initial, state)
    }

    @Test
    @org.junit.Ignore("Requires instrumented test - suspend function mocking with viewModelScope is complex")
    fun `Retry from Error returns to previous state`() = runTest {
        // Force an error state with retryable = true
        val qrData = """{"type":"vettid_enrollment","version":1,"api_url":"https://api.vettid.com","session_token":"test-token","user_guid":"user-123"}"""

        // Mock setEnrollmentApiUrl
        doNothing().whenever(vaultServiceClient).setEnrollmentApiUrl(any())

        // Mock authenticate to fail - use stub/onBlocking for suspend functions
        vaultServiceClient.stub {
            onBlocking { enrollAuthenticate(any(), any()) } doReturn Result.failure(VaultServiceException("Network error"))
        }

        viewModel.onEvent(EnrollmentEvent.StartScanning)
        advanceUntilIdle()

        viewModel.onEvent(EnrollmentEvent.QRCodeScanned(qrData))
        advanceUntilIdle()

        // Should be in Error state
        assertTrue(viewModel.state.first() is EnrollmentState.Error)

        viewModel.onEvent(EnrollmentEvent.Retry)
        advanceUntilIdle()

        // Should return to scanning state
        assertTrue(viewModel.state.first() is EnrollmentState.ScanningQR)
    }

    // MARK: - Password Strength Tests

    @Test
    fun `password strength calculation - weak`() {
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("abc"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("password"))
    }

    @Test
    fun `password strength calculation - fair`() {
        assertEquals(PasswordStrength.FAIR, PasswordStrength.calculate("Password1"))
    }

    @Test
    fun `password strength calculation - good`() {
        assertEquals(PasswordStrength.GOOD, PasswordStrength.calculate("Password1!"))
    }

    @Test
    fun `password strength calculation - strong`() {
        assertEquals(PasswordStrength.STRONG, PasswordStrength.calculate("MyStr0ng!P@ssword"))
    }

    // MARK: - Helper Methods

    private suspend fun setUpSettingPasswordState() {
        // Directly set state to SettingPassword for testing password-related events
        val field = EnrollmentViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<EnrollmentState>
        stateFlow.value = EnrollmentState.SettingPassword(
            sessionId = "test-session",
            transactionKeys = listOf(
                TransactionKeyPublic(keyId = "key1", publicKey = "base64key", algorithm = "X25519")
            ),
            passwordKeyId = "key1"
        )
    }
}
