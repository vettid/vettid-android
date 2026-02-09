package com.vettid.app.features.unlock

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.network.TransactionKeyInfo
import com.vettid.app.core.storage.PersonalDataStore
import com.vettid.app.core.storage.SystemPersonalData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

private const val TAG = "PinUnlockViewModel"

/**
 * PIN Unlock state
 */
sealed class PinUnlockState {
    data object Idle : PinUnlockState()
    data class EnteringPin(
        val pin: String = "",
        val error: String? = null
    ) : PinUnlockState()
    data object Connecting : PinUnlockState()
    data object Verifying : PinUnlockState()
    data class Success(val firstName: String? = null) : PinUnlockState()
    data class Error(val message: String) : PinUnlockState()
}

/**
 * PIN Unlock events
 */
sealed class PinUnlockEvent {
    data class PinChanged(val pin: String) : PinUnlockEvent()
    data object SubmitPin : PinUnlockEvent()
    data object Retry : PinUnlockEvent()
}

/**
 * PIN Unlock effects
 */
sealed class PinUnlockEffect {
    data object UnlockSuccess : PinUnlockEffect()
}

/**
 * ViewModel for PIN-based app unlock.
 *
 * Verifies the user's vault PIN against the enclave via NATS.
 */
@HiltViewModel
class PinUnlockViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val cryptoManager: CryptoManager,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager,
    private val natsAutoConnector: NatsAutoConnector,
    private val personalDataStore: PersonalDataStore
) : ViewModel() {

    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
        private const val RESPONSE_TIMEOUT_MS = 30000L
    }

    private val _state = MutableStateFlow<PinUnlockState>(PinUnlockState.Idle)
    val state: StateFlow<PinUnlockState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PinUnlockEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<PinUnlockEffect> = _effects.asSharedFlow()

    private val gson = Gson()

    // Owner space for NATS communication (set during connection)
    private var ownerSpace: String? = null

    // Mutex to prevent concurrent PIN submission (e.g., double-tap)
    private val submitMutex = Mutex()

    init {
        // Start in PIN entry mode
        _state.value = PinUnlockState.EnteringPin()
        // Log credential diagnostics on startup
        logCredentialState()
    }

    /**
     * Log credential diagnostics to help debug PIN unlock issues.
     */
    private fun logCredentialState() {
        Log.d(TAG, "Credential diagnostics:")
        Log.d(TAG, "  - Has stored credential: ${credentialStore.hasStoredCredential()}")
        Log.d(TAG, "  - Has enclave public key: ${credentialStore.getEnclavePublicKey() != null}")
        Log.d(TAG, "  - Has encrypted blob: ${credentialStore.getEncryptedBlob() != null}")
        Log.d(TAG, "  - UTK pool size: ${credentialStore.getUtkPool().size}")
        Log.d(TAG, "  - Has NATS connection: ${credentialStore.hasNatsConnection()}")
        Log.d(TAG, "  - NATS credentials valid: ${credentialStore.areNatsCredentialsValid()}")
        Log.d(TAG, "  - Has user GUID: ${credentialStore.getUserGuid() != null}")
    }

    /**
     * Check if user has stored credentials (is enrolled)
     */
    fun isEnrolled(): Boolean {
        return credentialStore.hasStoredCredential()
    }

    /**
     * Process UI events
     */
    fun onEvent(event: PinUnlockEvent) {
        viewModelScope.launch {
            when (event) {
                is PinUnlockEvent.PinChanged -> updatePin(event.pin)
                is PinUnlockEvent.SubmitPin -> submitPin()
                is PinUnlockEvent.Retry -> retry()
            }
        }
    }

    private fun updatePin(pin: String) {
        val current = _state.value
        if (current is PinUnlockState.EnteringPin) {
            // Only allow digits and max MAX_PIN_LENGTH
            val sanitizedPin = pin.filter { it.isDigit() }.take(MAX_PIN_LENGTH)
            _state.value = current.copy(pin = sanitizedPin, error = null)
        }
    }

    private suspend fun submitPin() {
        // Use tryLock to prevent double-tap / concurrent submission
        // If already submitting, just return without blocking
        if (!submitMutex.tryLock()) {
            Log.d(TAG, "PIN submission already in progress, ignoring")
            return
        }

        try {
            val current = _state.value
            if (current !is PinUnlockState.EnteringPin) {
                return
            }

            if (current.pin.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
                _state.value = current.copy(error = "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits")
                return
            }

            val pin = current.pin
            // Connect to NATS if not connected
            _state.value = PinUnlockState.Connecting

            if (!connectToNats()) {
                _state.value = PinUnlockState.Error("Failed to connect to vault")
                return
            }

            // Verify PIN with enclave using OwnerSpaceClient (event_id correlation)
            _state.value = PinUnlockState.Verifying

            // Retry up to 3 times if vault is not ready (just started)
            var retryCount = 0
            val maxRetries = 3
            while (true) {
                val result = verifyPinWithEnclave(pin)

                when (result) {
                    PinVerificationResult.Success -> {
                        Log.i(TAG, "PIN verification successful")

                        // Get first name for welcome message
                        val firstName = personalDataStore.getSystemFields()?.firstName

                        // Load profile from vault in background
                        loadProfileFromVault()

                        _state.value = PinUnlockState.Success(firstName = firstName)
                        _effects.emit(PinUnlockEffect.UnlockSuccess)
                        return
                    }
                    PinVerificationResult.VaultNotReady -> {
                        retryCount++
                        if (retryCount >= maxRetries) {
                            Log.e(TAG, "Vault still not ready after $maxRetries retries")
                            _state.value = PinUnlockState.Error("Vault is starting up. Please try again.")
                            return
                        }
                        Log.i(TAG, "Vault not ready, retrying in 2s (attempt ${retryCount + 1}/$maxRetries)")
                        kotlinx.coroutines.delay(2000)
                        // Continue loop to retry
                    }
                    PinVerificationResult.InvalidPin -> {
                        Log.w(TAG, "PIN verification failed - invalid PIN")
                        _state.value = PinUnlockState.EnteringPin(pin = "", error = "Invalid PIN")
                        return
                    }
                    is PinVerificationResult.Error -> {
                        Log.e(TAG, "PIN verification failed - error: ${result.message}")
                        _state.value = PinUnlockState.Error(result.message)
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            // Coroutine cancelled - rethrow, don't treat as error
            Log.d(TAG, "PIN submission cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "PIN unlock error", e)
            _state.value = PinUnlockState.Error(e.message ?: "Unknown error")
        } finally {
            submitMutex.unlock()
        }
    }

    /**
     * Result of PIN verification attempt.
     */
    private sealed class PinVerificationResult {
        data object Success : PinVerificationResult()
        data object InvalidPin : PinVerificationResult()
        data object VaultNotReady : PinVerificationResult()
        data class Error(val message: String = "Verification error") : PinVerificationResult()
    }

    private fun retry() {
        _state.value = PinUnlockState.EnteringPin()
    }

    private suspend fun connectToNats(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Get user GUID for owner space
            val userGuid = credentialStore.getUserGuid()
            if (userGuid == null) {
                Log.e(TAG, "Missing user GUID")
                return@withContext false
            }

            ownerSpace = "OwnerSpace.$userGuid"

            // Check if already connected
            if (connectionManager.isConnected()) {
                Log.i(TAG, "Using existing NATS connection for PIN verification")
                return@withContext true
            }

            // Trigger NATS auto-connect to establish the shared connection
            // This uses stored credentials and handles all the connection setup
            Log.i(TAG, "Triggering NATS auto-connect for PIN verification")
            val result = natsAutoConnector.autoConnect(autoStartVault = true)

            when (result) {
                is NatsAutoConnector.ConnectionResult.Success -> {
                    Log.i(TAG, "NATS auto-connect successful for PIN verification")
                    return@withContext true
                }
                is NatsAutoConnector.ConnectionResult.NotEnrolled -> {
                    Log.e(TAG, "NATS auto-connect: Not enrolled")
                    return@withContext false
                }
                is NatsAutoConnector.ConnectionResult.CredentialsExpired -> {
                    Log.e(TAG, "NATS auto-connect: Credentials expired")
                    return@withContext false
                }
                is NatsAutoConnector.ConnectionResult.MissingData -> {
                    Log.e(TAG, "NATS auto-connect: Missing ${result.field}")
                    return@withContext false
                }
                is NatsAutoConnector.ConnectionResult.Error -> {
                    Log.e(TAG, "NATS auto-connect failed: ${result.message}")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "NATS connection error", e)
            return@withContext false
        }
    }

    private suspend fun verifyPinWithEnclave(pin: String): PinVerificationResult = withContext(Dispatchers.IO) {
        // Verify owner space is set (connection was established)
        if (ownerSpace == null) {
            Log.e(TAG, "Owner space not set - connection not established")
            return@withContext PinVerificationResult.Error()
        }

        try {
            // Get enclave public key
            val enclavePublicKey = credentialStore.getEnclavePublicKey()
            if (enclavePublicKey == null) {
                Log.e(TAG, "CRITICAL: Enclave public key missing - user must re-enroll")
                return@withContext PinVerificationResult.Error("Security key missing. Please contact support or re-enroll.")
            }

            // Get an available UTK
            val utkPool = credentialStore.getUtkPool()
            val availableUtk = utkPool.firstOrNull()

            if (availableUtk == null) {
                Log.e(TAG, "No available UTKs")
                return@withContext PinVerificationResult.Error()
            }

            Log.d(TAG, "Using UTK: ${availableUtk.keyId}")

            // Encrypt PIN with enclave's public key using X25519 + ChaCha20-Poly1305
            val pinPayload = JsonObject().apply {
                addProperty("pin", pin)
            }
            val encryptedResult = cryptoManager.encryptToPublicKey(
                plaintext = pinPayload.toString().toByteArray(),
                publicKeyBase64 = android.util.Base64.encodeToString(enclavePublicKey, android.util.Base64.NO_WRAP)
            )

            // Combine ephemeral public key + nonce + ciphertext
            val ephemeralPubKeyBytes = android.util.Base64.decode(encryptedResult.ephemeralPublicKey, android.util.Base64.NO_WRAP)
            val nonceBytes = android.util.Base64.decode(encryptedResult.nonce, android.util.Base64.NO_WRAP)
            val ciphertextBytes = android.util.Base64.decode(encryptedResult.ciphertext, android.util.Base64.NO_WRAP)

            val combined = ephemeralPubKeyBytes + nonceBytes + ciphertextBytes
            val encryptedPayloadBase64 = android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)

            // Build request payload for enclave pin-unlock handler
            val requestPayload = JsonObject().apply {
                addProperty("utk_id", availableUtk.keyId)
                addProperty("encrypted_payload", encryptedPayloadBase64)
            }

            Log.d(TAG, "Sending PIN unlock request via OwnerSpaceClient (event_id correlation)")

            // Ensure we're subscribed to vault responses
            ownerSpaceClient.subscribeToVault()

            // Use OwnerSpaceClient.sendAndAwaitResponse for proper event_id correlation
            // Use pin-unlock (hyphen) topic to match enclave expectations
            val response = ownerSpaceClient.sendAndAwaitResponse(
                messageType = "pin-unlock",
                payload = requestPayload,
                timeoutMs = RESPONSE_TIMEOUT_MS
            )

            // Remove used UTK immediately - UTKs are single-use tokens
            // Must be removed regardless of success/failure to prevent reuse
            credentialStore.removeUtk(availableUtk.keyId)
            Log.d(TAG, "Removed used UTK: ${availableUtk.keyId}")

            if (response == null) {
                Log.e(TAG, "PIN unlock request timed out")
                return@withContext PinVerificationResult.Error()
            }

            // Parse response based on type
            when (response) {
                is com.vettid.app.core.nats.VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        val responseJson = response.result
                        Log.d(TAG, "PIN unlock response: ${responseJson.toString().take(200)}")

                        val status = responseJson.get("status")?.asString
                        Log.d(TAG, "PIN unlock response status: $status")

                        // Vault returns {status: "unlocked", new_utks: [...]} on first unlock,
                        // but returns profile data ({first_name, email, ...}) if already unlocked.
                        // Both indicate successful PIN verification.
                        val isAlreadyUnlocked = status == null &&
                            responseJson.has("first_name") && responseJson.has("email")
                        if (isAlreadyUnlocked) {
                            Log.i(TAG, "Vault already unlocked - PIN verified via profile response")
                        }

                        // Detect "vault not ready" response: when the vault just started,
                        // the pin-unlock handler may not be initialized yet. The vault
                        // returns a generic response (connections list, events list, etc.)
                        // instead of a proper pin-unlock result. Detect this by checking
                        // that NONE of the expected pin-unlock fields are present.
                        val hasExpectedFields = status != null ||
                            responseJson.has("encrypted_credential") ||
                            responseJson.has("first_name") ||
                            responseJson.has("error")
                        if (!hasExpectedFields) {
                            Log.w(TAG, "Vault not ready - got unexpected response: ${responseJson.keySet()}")
                            return@withContext PinVerificationResult.VaultNotReady
                        }

                        if (status == "unlocked" || status == "success" || status == "vault_ready" || isAlreadyUnlocked) {
                            // Store any new UTKs from response (format: "ID:base64PublicKey")
                            val newUtksArray = responseJson.getAsJsonArray("new_utks")
                            if (newUtksArray != null && newUtksArray.size() > 0) {
                                Log.d(TAG, "Received ${newUtksArray.size()} new UTKs")
                                val newKeys = mutableListOf<TransactionKeyInfo>()
                                for (i in 0 until newUtksArray.size()) {
                                    val utkString = newUtksArray.get(i).asString
                                    val parts = utkString.split(":", limit = 2)
                                    if (parts.size == 2) {
                                        newKeys.add(TransactionKeyInfo(
                                            keyId = parts[0],
                                            publicKey = parts[1],
                                            algorithm = "X25519"
                                        ))
                                    }
                                }
                                if (newKeys.isNotEmpty()) {
                                    credentialStore.addUtks(newKeys)
                                    Log.i(TAG, "Added ${newKeys.size} new UTKs to pool. Total: ${credentialStore.getUtkCount()}")
                                }
                            }

                            return@withContext PinVerificationResult.Success
                        } else {
                            val error = responseJson.get("error")?.asString
                            Log.w(TAG, "PIN unlock failed: $error")
                            return@withContext PinVerificationResult.InvalidPin
                        }
                    } else {
                        val error = response.error ?: "Unknown error"
                        Log.w(TAG, "PIN unlock request failed: $error")
                        // Check if it's an invalid PIN error vs other errors
                        if (error.contains("invalid", ignoreCase = true) ||
                            error.contains("wrong", ignoreCase = true) ||
                            error.contains("incorrect", ignoreCase = true)) {
                            return@withContext PinVerificationResult.InvalidPin
                        }
                        return@withContext PinVerificationResult.Error()
                    }
                }
                is com.vettid.app.core.nats.VaultResponse.Error -> {
                    Log.e(TAG, "PIN unlock error: ${response.code} - ${response.message}")
                    if (response.message.contains("invalid", ignoreCase = true) ||
                        response.message.contains("wrong", ignoreCase = true) ||
                        response.message.contains("incorrect", ignoreCase = true)) {
                        return@withContext PinVerificationResult.InvalidPin
                    }
                    return@withContext PinVerificationResult.Error()
                }
                else -> {
                    Log.e(TAG, "Unexpected response type for PIN unlock: ${response::class.simpleName}")
                    return@withContext PinVerificationResult.Error()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PIN verification error", e)
            return@withContext PinVerificationResult.Error()
        }
    }

    /**
     * Load profile data from vault after successful PIN unlock.
     * Populates PersonalDataStore with system and optional fields from vault.
     */
    private fun loadProfileFromVault() {
        viewModelScope.launch {
            try {
                if (!connectionManager.isConnected()) {
                    Log.d(TAG, "Not connected to vault, skipping profile load")
                    return@launch
                }

                Log.d(TAG, "Loading profile from vault after PIN unlock")

                // Subscribe to vault responses
                ownerSpaceClient.subscribeToVault()

                // Send profile.get request
                val requestResult = ownerSpaceClient.getProfileFromVault()
                if (requestResult.isFailure) {
                    Log.e(TAG, "Failed to request profile from vault: ${requestResult.exceptionOrNull()?.message}")
                    return@launch
                }

                val requestId = requestResult.getOrThrow()
                Log.d(TAG, "Profile request sent, waiting for response: $requestId")

                // Wait for response with timeout
                val response = withTimeoutOrNull(10000L) {
                    ownerSpaceClient.vaultResponses.first { it.requestId == requestId }
                }

                if (response == null) {
                    Log.w(TAG, "Profile request timed out")
                    return@launch
                }

                when (response) {
                    is com.vettid.app.core.nats.VaultResponse.HandlerResult -> {
                        if (response.success && response.result != null) {
                            processVaultProfileResponse(response.result)
                        } else {
                            Log.w(TAG, "Profile request failed: ${response.error}")
                        }
                    }
                    is com.vettid.app.core.nats.VaultResponse.Error -> {
                        Log.e(TAG, "Profile request error: ${response.code} - ${response.message}")
                    }
                    else -> {
                        Log.w(TAG, "Unexpected response type for profile request")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading profile from vault", e)
            }
        }
    }

    /**
     * Process profile data from vault response and store in PersonalDataStore.
     */
    private fun processVaultProfileResponse(result: JsonObject) {
        try {
            Log.d(TAG, "Processing vault profile response: ${result.keySet()}")

            // Extract system fields (_system_* prefix)
            val systemFirstName = result.get("_system_first_name")?.asString
            val systemLastName = result.get("_system_last_name")?.asString
            val systemEmail = result.get("_system_email")?.asString

            if (systemFirstName != null && systemLastName != null && systemEmail != null) {
                Log.d(TAG, "Storing system fields from vault")
                personalDataStore.storeSystemFields(
                    SystemPersonalData(
                        firstName = systemFirstName,
                        lastName = systemLastName,
                        email = systemEmail
                    )
                )
            }

            // Extract optional fields (non-system fields)
            result.entrySet().forEach { (key, value) ->
                if (!key.startsWith("_") && value.isJsonPrimitive) {
                    val stringValue = value.asString
                    if (stringValue.isNotEmpty()) {
                        val updated = personalDataStore.updateOptionalFieldByKey(key, stringValue)
                        if (updated) {
                            Log.d(TAG, "Updated optional field from vault: $key")
                        }
                    }
                }
            }

            // Mark sync as complete since we just synced from vault
            personalDataStore.markSyncComplete()
            Log.i(TAG, "Profile loaded from vault successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing vault profile response", e)
        }
    }

}
