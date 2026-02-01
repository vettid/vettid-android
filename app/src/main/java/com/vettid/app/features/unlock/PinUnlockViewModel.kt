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
        const val PIN_LENGTH = 6
        private const val RESPONSE_TIMEOUT_MS = 30000L
    }

    private val _state = MutableStateFlow<PinUnlockState>(PinUnlockState.Idle)
    val state: StateFlow<PinUnlockState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PinUnlockEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<PinUnlockEffect> = _effects.asSharedFlow()

    private val gson = Gson()

    // Use shared JetStream client from connection manager to avoid race conditions
    private var ownerSpace: String? = null

    // Mutex to prevent concurrent PIN submission (e.g., double-tap)
    private val submitMutex = Mutex()

    init {
        // Start in PIN entry mode
        _state.value = PinUnlockState.EnteringPin()
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
            // Only allow digits and max PIN_LENGTH
            val sanitizedPin = pin.filter { it.isDigit() }.take(PIN_LENGTH)
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

            if (current.pin.length != PIN_LENGTH) {
                _state.value = current.copy(error = "PIN must be $PIN_LENGTH digits")
                return
            }

            val pin = current.pin
            // Connect to NATS if not connected
            _state.value = PinUnlockState.Connecting

            if (!connectToNats()) {
                _state.value = PinUnlockState.Error("Failed to connect to vault")
                return
            }

            // Verify PIN with enclave - with automatic retry on race condition
            _state.value = PinUnlockState.Verifying

            var attempts = 0
            val maxAttempts = 3
            var result: PinVerificationResult

            do {
                attempts++
                result = verifyPinWithEnclave(pin)
                if (result == PinVerificationResult.RaceCondition && attempts < maxAttempts) {
                    Log.w(TAG, "Race condition detected, retrying (attempt $attempts/$maxAttempts)...")
                    kotlinx.coroutines.delay(500) // Small delay before retry
                }
            } while (result == PinVerificationResult.RaceCondition && attempts < maxAttempts)

            when (result) {
                PinVerificationResult.Success -> {
                    Log.i(TAG, "PIN verification successful")

                    // Get first name for welcome message
                    val firstName = personalDataStore.getSystemFields()?.firstName

                    // Load profile from vault in background
                    loadProfileFromVault()

                    _state.value = PinUnlockState.Success(firstName = firstName)
                    _effects.emit(PinUnlockEffect.UnlockSuccess)
                }
                PinVerificationResult.InvalidPin -> {
                    Log.w(TAG, "PIN verification failed - invalid PIN")
                    _state.value = PinUnlockState.EnteringPin(pin = "", error = "Invalid PIN")
                }
                PinVerificationResult.RaceCondition -> {
                    Log.e(TAG, "PIN verification failed - race condition persisted after $attempts attempts")
                    _state.value = PinUnlockState.EnteringPin(pin = "", error = "Verification error. Please try again.")
                }
                PinVerificationResult.Error -> {
                    Log.e(TAG, "PIN verification failed - error")
                    _state.value = PinUnlockState.Error("Verification error")
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
    private enum class PinVerificationResult {
        Success,
        InvalidPin,
        RaceCondition,  // Wrong response received due to JetStream race condition
        Error
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
                val jsClient = connectionManager.getJetStreamClient()
                if (jsClient != null && jsClient.isConnected) {
                    Log.i(TAG, "Using existing NATS connection for PIN verification")
                    return@withContext true
                }
            }

            // Trigger NATS auto-connect to establish the shared connection
            // This uses stored credentials and handles all the connection setup
            Log.i(TAG, "Triggering NATS auto-connect for PIN verification")
            val result = natsAutoConnector.autoConnect(autoStartVault = true)

            when (result) {
                is NatsAutoConnector.ConnectionResult.Success -> {
                    Log.i(TAG, "NATS auto-connect successful for PIN verification")
                    val jsClient = connectionManager.getJetStreamClient()
                    if (jsClient != null && jsClient.isConnected) {
                        return@withContext true
                    }
                    Log.e(TAG, "NATS connected but JetStream client not available")
                    return@withContext false
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
        val client = connectionManager.getJetStreamClient() ?: return@withContext PinVerificationResult.Error
        val space = ownerSpace ?: return@withContext PinVerificationResult.Error

        try {
            // Get enclave public key
            val enclavePublicKey = credentialStore.getEnclavePublicKey()
            if (enclavePublicKey == null) {
                Log.e(TAG, "Missing enclave public key")
                return@withContext PinVerificationResult.Error
            }

            // Get an available UTK
            val utkPool = credentialStore.getUtkPool()
            val availableUtk = utkPool.firstOrNull()

            if (availableUtk == null) {
                Log.e(TAG, "No available UTKs")
                return@withContext PinVerificationResult.Error
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

            // Get encrypted credential for vault state restoration
            val encryptedCredential = credentialStore.getEncryptedBlob()
            if (encryptedCredential == null) {
                Log.e(TAG, "Missing encrypted credential")
                return@withContext PinVerificationResult.Error
            }

            // Build request
            val requestId = UUID.randomUUID().toString()
            val request = JsonObject().apply {
                addProperty("type", "pin.unlock")
                addProperty("utk_id", availableUtk.keyId)
                addProperty("encrypted_payload", encryptedPayloadBase64)
                addProperty("encrypted_credential", encryptedCredential)
                addProperty("request_id", requestId)
            }

            val requestTopic = "$space.forVault.pin-unlock"
            val responseTopic = "$space.forApp.pin-unlock.response"

            Log.d(TAG, "Sending PIN unlock request to $requestTopic")

            // Send request and wait for response
            val result = client.requestWithJetStream(
                requestSubject = requestTopic,
                responseSubject = responseTopic,
                payload = request.toString().toByteArray(),
                timeoutMs = RESPONSE_TIMEOUT_MS
            )

            if (result.isFailure) {
                Log.e(TAG, "PIN unlock request failed: ${result.exceptionOrNull()?.message}")
                return@withContext PinVerificationResult.Error
            }

            val response = result.getOrNull()
            if (response == null) {
                Log.e(TAG, "PIN unlock request returned null")
                return@withContext PinVerificationResult.Error
            }

            // Parse response
            val responseStr = String(response.data)
            Log.d(TAG, "Raw PIN response: ${responseStr.take(200)}")

            val responseJson = try {
                gson.fromJson(responseStr, JsonObject::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse PIN unlock response as JSON: ${e.message}")
                return@withContext PinVerificationResult.Error
            }

            // Validate this is actually a PIN unlock response, not a response from another request
            // (race condition protection - JetStream consumer sometimes receives wrong message)

            // Check 1: If response has a "type" field that doesn't match pin.unlock or error
            // Note: "error" type is a legitimate response when PIN is wrong, not a race condition
            val responseType = responseJson.get("type")?.asString
            if (responseType != null &&
                responseType != "pin.unlock" &&
                responseType != "error" &&
                !responseType.contains("pin")) {
                Log.e(TAG, "RACE CONDITION: Wrong response type: $responseType (expected pin.unlock or error)")
                Log.e(TAG, "Response content: ${responseStr.take(500)}")
                return@withContext PinVerificationResult.RaceCondition
            }

            // Check 2: Response has list-type fields (connections, events, items)
            if (responseJson.has("connections")) {
                Log.e(TAG, "RACE CONDITION: Received connections list instead of PIN unlock response")
                Log.e(TAG, "Response content: ${responseStr.take(500)}")
                return@withContext PinVerificationResult.RaceCondition
            }
            if (responseJson.has("events")) {
                Log.e(TAG, "RACE CONDITION: Received events list instead of PIN unlock response")
                return@withContext PinVerificationResult.RaceCondition
            }
            if (responseJson.has("items")) {
                Log.e(TAG, "RACE CONDITION: Received items list instead of PIN unlock response")
                return@withContext PinVerificationResult.RaceCondition
            }

            // Check 3: Response has event_id (feed event response, not PIN)
            if (responseJson.has("event_id")) {
                Log.e(TAG, "RACE CONDITION: Received feed event response instead of PIN unlock response")
                Log.e(TAG, "Response content: ${responseStr.take(500)}")
                return@withContext PinVerificationResult.RaceCondition
            }

            // Check 3b: Response has profile fields (profile.get response, not PIN)
            if (responseJson.has("first_name") || responseJson.has("last_name") || responseJson.has("fields")) {
                Log.e(TAG, "RACE CONDITION: Received profile response instead of PIN unlock response")
                Log.e(TAG, "Response content: ${responseStr.take(500)}")
                return@withContext PinVerificationResult.RaceCondition
            }

            // Check 4: Response has sync-related fields (feed sync response)
            if (responseJson.has("latest_sequence") || responseJson.has("has_more")) {
                Log.e(TAG, "RACE CONDITION: Received feed sync response instead of PIN unlock response")
                return@withContext PinVerificationResult.RaceCondition
            }

            // Check 5: Verify request_id matches (if present in response)
            val responseRequestId = responseJson.get("request_id")?.asString
            if (responseRequestId != null && responseRequestId != requestId) {
                Log.e(TAG, "RACE CONDITION: Response request_id mismatch. Expected: $requestId, Got: $responseRequestId")
                return@withContext PinVerificationResult.RaceCondition
            }

            val status = responseJson.get("status")?.asString

            Log.d(TAG, "PIN unlock response status: $status")

            if (status == "unlocked" || status == "success" || status == "vault_ready") {
                // Remove used UTK from pool
                credentialStore.removeUtk(availableUtk.keyId)

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
        } catch (e: Exception) {
            Log.e(TAG, "PIN verification error", e)
            return@withContext PinVerificationResult.Error
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
                Log.d(TAG, "Storing system fields from vault: $systemFirstName $systemLastName")
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
