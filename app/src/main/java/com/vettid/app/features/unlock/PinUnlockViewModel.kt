package com.vettid.app.features.unlock

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.JetStreamNatsClient
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    data object Success : PinUnlockState()
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
    @ApplicationContext private val context: Context,
    private val credentialStore: CredentialStore,
    private val cryptoManager: CryptoManager
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

    // NATS client for PIN verification
    private var natsClient: JetStreamNatsClient? = null
    private var ownerSpace: String? = null

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
        val current = _state.value
        if (current !is PinUnlockState.EnteringPin) return

        if (current.pin.length != PIN_LENGTH) {
            _state.value = current.copy(error = "PIN must be $PIN_LENGTH digits")
            return
        }

        val pin = current.pin

        try {
            // Connect to NATS if not connected
            _state.value = PinUnlockState.Connecting

            if (!connectToNats()) {
                _state.value = PinUnlockState.Error("Failed to connect to vault")
                return
            }

            // Verify PIN with enclave
            _state.value = PinUnlockState.Verifying

            val result = verifyPinWithEnclave(pin)

            if (result) {
                Log.i(TAG, "PIN verification successful")
                _state.value = PinUnlockState.Success
                _effects.emit(PinUnlockEffect.UnlockSuccess)
            } else {
                Log.w(TAG, "PIN verification failed")
                _state.value = PinUnlockState.EnteringPin(pin = "", error = "Invalid PIN")
            }
        } catch (e: Exception) {
            Log.e(TAG, "PIN unlock error", e)
            _state.value = PinUnlockState.Error(e.message ?: "Unknown error")
        }
    }

    private fun retry() {
        _state.value = PinUnlockState.EnteringPin()
    }

    private suspend fun connectToNats(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Get stored NATS credentials
            val natsUrl = credentialStore.getNatsEndpoint()
            val natsCredentials = credentialStore.getNatsCredentials()
            val userGuid = credentialStore.getUserGuid()

            if (natsUrl == null || natsCredentials == null || userGuid == null) {
                Log.e(TAG, "Missing NATS credentials - url: ${natsUrl != null}, creds: ${natsCredentials != null}, guid: ${userGuid != null}")
                return@withContext false
            }

            ownerSpace = "OwnerSpace.$userGuid"

            // Parse credentials file to get JWT and seed
            val parsed = credentialStore.parseNatsCredentialFile(natsCredentials)
            if (parsed == null) {
                Log.e(TAG, "Failed to parse NATS credentials")
                return@withContext false
            }

            val (jwt, seed) = parsed

            Log.d(TAG, "Connecting to NATS: $natsUrl")

            // Create and connect NATS client
            val client = JetStreamNatsClient()
            val result = client.connect(natsUrl, jwt, seed)

            if (result.isFailure) {
                Log.e(TAG, "Failed to connect to NATS: ${result.exceptionOrNull()?.message}")
                return@withContext false
            }

            natsClient = client
            Log.i(TAG, "Connected to NATS successfully")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "NATS connection error", e)
            return@withContext false
        }
    }

    private suspend fun verifyPinWithEnclave(pin: String): Boolean = withContext(Dispatchers.IO) {
        val client = natsClient ?: return@withContext false
        val space = ownerSpace ?: return@withContext false

        try {
            // Get enclave public key
            val enclavePublicKey = credentialStore.getEnclavePublicKey()
            if (enclavePublicKey == null) {
                Log.e(TAG, "Missing enclave public key")
                return@withContext false
            }

            // Get an available UTK
            val utkPool = credentialStore.getUtkPool()
            val availableUtk = utkPool.firstOrNull()

            if (availableUtk == null) {
                Log.e(TAG, "No available UTKs")
                return@withContext false
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
                return@withContext false
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
                return@withContext false
            }

            val response = result.getOrNull()
            if (response == null) {
                Log.e(TAG, "PIN unlock request returned null")
                return@withContext false
            }

            // Parse response
            val responseJson = gson.fromJson(String(response.data), JsonObject::class.java)
            val status = responseJson.get("status")?.asString

            Log.d(TAG, "PIN unlock response status: $status")

            if (status == "unlocked" || status == "success" || status == "vault_ready") {
                // Remove used UTK from pool
                credentialStore.removeUtk(availableUtk.keyId)

                // Store any new UTKs from response
                val newUtks = responseJson.getAsJsonArray("utks")
                if (newUtks != null && newUtks.size() > 0) {
                    Log.d(TAG, "Received ${newUtks.size()} new UTKs")
                    // TODO: Parse and store new UTKs
                }

                return@withContext true
            } else {
                val error = responseJson.get("error")?.asString
                Log.w(TAG, "PIN unlock failed: $error")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "PIN verification error", e)
            return@withContext false
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            try {
                natsClient?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing NATS connection", e)
            }
        }
    }
}
