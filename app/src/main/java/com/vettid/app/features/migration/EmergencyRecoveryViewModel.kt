package com.vettid.app.features.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.MigrationClient
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Emergency Recovery screen.
 *
 * Emergency recovery is only used when BOTH old and new enclaves
 * are unavailable (disaster scenario). The user must provide their
 * PIN to re-derive the DEK and restore vault access.
 */
@HiltViewModel
class EmergencyRecoveryViewModel @Inject constructor(
    private val migrationClient: MigrationClient,
    private val cryptoManager: CryptoManager,
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _state = MutableStateFlow<EmergencyRecoveryState>(EmergencyRecoveryState.Idle)
    val state: StateFlow<EmergencyRecoveryState> = _state.asStateFlow()

    /**
     * Perform emergency recovery with user-provided PIN.
     *
     * @param pin User's master PIN
     */
    fun performRecovery(pin: String) {
        if (pin.length < 4) {
            _state.value = EmergencyRecoveryState.Error(
                message = "PIN must be at least 4 digits",
                isPasswordError = true
            )
            return
        }

        viewModelScope.launch {
            _state.value = EmergencyRecoveryState.Recovering

            try {
                // Get salt for PIN hashing
                val saltBytes = credentialStore.getPasswordSaltBytes()
                if (saltBytes == null) {
                    _state.value = EmergencyRecoveryState.Error(
                        message = "No recovery data found. Please contact support.",
                        isPasswordError = false
                    )
                    return@launch
                }

                // Hash PIN with Argon2id
                val pinHash = cryptoManager.hashPassword(pin, saltBytes)

                // Get emergency recovery public key from credential store
                // This would be populated during enrollment or from backend config
                val recoveryPublicKey = credentialStore.getEmergencyRecoveryPublicKey()
                if (recoveryPublicKey == null) {
                    _state.value = EmergencyRecoveryState.Error(
                        message = "Cannot contact recovery service. Please try again later.",
                        isPasswordError = false
                    )
                    return@launch
                }

                // Encrypt PIN hash for transport using X25519 + ChaCha20-Poly1305
                val encryptedData = cryptoManager.encryptToPublicKey(
                    plaintext = pinHash,
                    publicKeyBase64 = recoveryPublicKey,
                    context = "emergency-recovery-v1"
                )

                // Clear sensitive data
                pinHash.fill(0)

                // Perform recovery via NATS
                migrationClient.performEmergencyRecovery(
                    encryptedPinHash = encryptedData.ciphertext,
                    ephemeralPublicKey = encryptedData.ephemeralPublicKey,
                    nonce = encryptedData.nonce
                ).onSuccess {
                    _state.value = EmergencyRecoveryState.Success
                }.onFailure { error ->
                    val message = error.message ?: "Recovery failed"
                    val isPasswordError = message.contains("invalid", ignoreCase = true) ||
                            message.contains("pin", ignoreCase = true) ||
                            message.contains("password", ignoreCase = true)

                    _state.value = EmergencyRecoveryState.Error(
                        message = if (isPasswordError) "Incorrect PIN. Please try again." else message,
                        isPasswordError = isPasswordError
                    )
                }

            } catch (e: Exception) {
                _state.value = EmergencyRecoveryState.Error(
                    message = e.message ?: "Recovery failed",
                    isPasswordError = false
                )
            }
        }
    }

    /**
     * Reset state to allow retry.
     */
    fun resetState() {
        _state.value = EmergencyRecoveryState.Idle
    }
}

/**
 * UI state for Emergency Recovery screen.
 */
sealed class EmergencyRecoveryState {
    object Idle : EmergencyRecoveryState()
    object Recovering : EmergencyRecoveryState()
    object Success : EmergencyRecoveryState()
    data class Error(
        val message: String,
        val isPasswordError: Boolean
    ) : EmergencyRecoveryState()
}
