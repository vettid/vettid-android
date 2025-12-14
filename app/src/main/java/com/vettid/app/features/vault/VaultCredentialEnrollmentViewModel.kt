package com.vettid.app.features.vault

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "VaultCredentialEnrollmentVM"
private const val MIN_PASSWORD_LENGTH = 12

/**
 * State for vault credential enrollment.
 */
data class VaultCredentialEnrollmentState(
    val password: String = "",
    val confirmPassword: String = "",
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val isCreating: Boolean = false
) {
    val isValid: Boolean
        get() = password.length >= MIN_PASSWORD_LENGTH &&
                password == confirmPassword &&
                passwordError == null &&
                confirmPasswordError == null
}

/**
 * State for vault authentication.
 */
data class VaultAuthenticationState(
    val password: String = "",
    val error: String? = null,
    val isAuthenticating: Boolean = false
)

/**
 * Effects emitted by the view model.
 */
sealed class VaultCredentialEnrollmentEffect {
    object EnrollmentComplete : VaultCredentialEnrollmentEffect()
    data class ShowError(val message: String) : VaultCredentialEnrollmentEffect()
}

@HiltViewModel
class VaultCredentialEnrollmentViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    private val _state = MutableStateFlow(VaultCredentialEnrollmentState())
    val state: StateFlow<VaultCredentialEnrollmentState> = _state.asStateFlow()

    private val _authState = MutableStateFlow(VaultAuthenticationState())
    val authState: StateFlow<VaultAuthenticationState> = _authState.asStateFlow()

    private val _effects = MutableSharedFlow<VaultCredentialEnrollmentEffect>()
    val effects: SharedFlow<VaultCredentialEnrollmentEffect> = _effects.asSharedFlow()

    // Store the created password hash for verification
    private var createdPasswordHash: ByteArray? = null

    fun updatePassword(password: String) {
        _state.value = _state.value.copy(
            password = password,
            passwordError = validatePassword(password),
            confirmPasswordError = if (_state.value.confirmPassword.isNotEmpty() &&
                                        password != _state.value.confirmPassword) {
                "Passwords don't match"
            } else null
        )
    }

    fun updateConfirmPassword(confirmPassword: String) {
        _state.value = _state.value.copy(
            confirmPassword = confirmPassword,
            confirmPasswordError = if (confirmPassword != _state.value.password) {
                "Passwords don't match"
            } else null
        )
    }

    private fun validatePassword(password: String): String? {
        return when {
            password.isEmpty() -> null  // Don't show error for empty field
            password.length < MIN_PASSWORD_LENGTH ->
                "Password must be at least $MIN_PASSWORD_LENGTH characters"
            else -> null
        }
    }

    fun createCredential() {
        val currentState = _state.value
        if (!currentState.isValid) return

        viewModelScope.launch {
            _state.value = currentState.copy(isCreating = true)

            try {
                // Generate salt and hash password
                val salt = withContext(Dispatchers.Default) {
                    cryptoManager.generateSalt()
                }

                val passwordHash = withContext(Dispatchers.Default) {
                    cryptoManager.hashPassword(currentState.password, salt)
                }

                // Store vault credential (separate from vault services credential)
                // In production, this would be sent to the vault's NATS datastore
                credentialStore.setVaultCredentialSalt(salt)
                createdPasswordHash = passwordHash

                Log.d(TAG, "Vault credential created successfully")
                _effects.emit(VaultCredentialEnrollmentEffect.EnrollmentComplete)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create vault credential", e)
                _state.value = currentState.copy(isCreating = false)
                _effects.emit(VaultCredentialEnrollmentEffect.ShowError(
                    e.message ?: "Failed to create credential"
                ))
            }
        }
    }

    fun updateAuthPassword(password: String) {
        _authState.value = _authState.value.copy(
            password = password,
            error = null
        )
    }

    fun authenticate() {
        val currentState = _authState.value
        if (currentState.password.isEmpty()) return

        viewModelScope.launch {
            _authState.value = currentState.copy(isAuthenticating = true)

            try {
                // Get stored salt
                val salt = credentialStore.getVaultCredentialSalt()
                if (salt == null) {
                    _authState.value = currentState.copy(
                        isAuthenticating = false,
                        error = "No vault credential found"
                    )
                    return@launch
                }

                // Hash provided password
                val passwordHash = withContext(Dispatchers.Default) {
                    cryptoManager.hashPassword(currentState.password, salt)
                }

                // Verify against stored hash (or in production, authenticate with vault)
                val isValid = createdPasswordHash?.contentEquals(passwordHash) ?: run {
                    // If we don't have the hash in memory, accept for demo
                    currentState.password.length >= MIN_PASSWORD_LENGTH
                }

                if (isValid) {
                    // Mark vault as active
                    credentialStore.setVaultActive(true)
                    Log.d(TAG, "Vault authentication successful")
                    _effects.emit(VaultCredentialEnrollmentEffect.EnrollmentComplete)
                } else {
                    _authState.value = currentState.copy(
                        isAuthenticating = false,
                        error = "Invalid password"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Authentication failed", e)
                _authState.value = currentState.copy(
                    isAuthenticating = false,
                    error = e.message ?: "Authentication failed"
                )
            }
        }
    }
}
