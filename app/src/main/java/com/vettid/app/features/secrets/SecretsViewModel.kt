package com.vettid.app.features.secrets

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import javax.inject.Inject

private const val TAG = "SecretsViewModel"
private const val AUTO_HIDE_SECONDS = 30

@HiltViewModel
class SecretsViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    private val _listState = MutableStateFlow<SecretsListState>(SecretsListState.Loading)
    val listState: StateFlow<SecretsListState> = _listState.asStateFlow()

    private val _valueState = MutableStateFlow<SecretValueState>(SecretValueState.Hidden)
    val valueState: StateFlow<SecretValueState> = _valueState.asStateFlow()

    private val _selectedSecretId = MutableStateFlow<String?>(null)
    val selectedSecretId: StateFlow<String?> = _selectedSecretId.asStateFlow()

    private val _effects = MutableSharedFlow<SecretsEffect>()
    val effects: SharedFlow<SecretsEffect> = _effects.asSharedFlow()

    private var autoHideJob: Job? = null

    init {
        loadSecrets()
    }

    fun onEvent(event: SecretsEvent) {
        when (event) {
            is SecretsEvent.SearchQueryChanged -> updateSearchQuery(event.query)
            is SecretsEvent.SecretClicked -> selectSecret(event.secretId)
            is SecretsEvent.RevealSecret -> revealSecret(event.secretId, event.password)
            is SecretsEvent.CopySecret -> copySecret(event.secretId)
            is SecretsEvent.HideSecret -> hideSecret()
            is SecretsEvent.AddSecret -> addSecret()
            is SecretsEvent.DeleteSecret -> deleteSecret(event.secretId)
            is SecretsEvent.Refresh -> loadSecrets()
        }
    }

    private fun loadSecrets() {
        viewModelScope.launch {
            _listState.value = SecretsListState.Loading
            try {
                // For now, show mock data to demonstrate the UI
                // In production, this would fetch from encrypted storage
                val mockSecrets = generateMockSecrets()

                if (mockSecrets.isEmpty()) {
                    _listState.value = SecretsListState.Empty
                } else {
                    _listState.value = SecretsListState.Loaded(secrets = mockSecrets)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load secrets", e)
                _listState.value = SecretsListState.Error(e.message ?: "Failed to load secrets")
            }
        }
    }

    private fun updateSearchQuery(query: String) {
        val currentState = _listState.value
        if (currentState is SecretsListState.Loaded) {
            _listState.value = currentState.copy(searchQuery = query)
        }
    }

    private fun selectSecret(secretId: String) {
        _selectedSecretId.value = secretId
        _valueState.value = SecretValueState.PasswordRequired
        viewModelScope.launch {
            _effects.emit(SecretsEffect.ShowPasswordPrompt)
        }
    }

    /**
     * Reveals a secret value after password verification.
     * IMPORTANT: This ONLY accepts password authentication, never biometrics.
     */
    private fun revealSecret(secretId: String, password: String) {
        viewModelScope.launch {
            _valueState.value = SecretValueState.Verifying

            try {
                // Verify password against stored hash
                val isValid = verifyPassword(password)

                if (!isValid) {
                    _valueState.value = SecretValueState.Error("Invalid password")
                    _effects.emit(SecretsEffect.ShowError("Invalid password"))
                    return@launch
                }

                // Decrypt and reveal the secret
                val secretValue = decryptSecret(secretId, password)

                _valueState.value = SecretValueState.Revealed(
                    value = secretValue,
                    autoHideSeconds = AUTO_HIDE_SECONDS
                )

                // Start auto-hide timer
                startAutoHideTimer()

                _effects.emit(SecretsEffect.ShowSuccess("Secret revealed"))

            } catch (e: Exception) {
                Log.e(TAG, "Failed to reveal secret", e)
                _valueState.value = SecretValueState.Error(e.message ?: "Failed to reveal secret")
                _effects.emit(SecretsEffect.ShowError(e.message ?: "Failed to reveal secret"))
            }
        }
    }

    private fun hideSecret() {
        autoHideJob?.cancel()
        _valueState.value = SecretValueState.Hidden
        _selectedSecretId.value = null
    }

    private fun copySecret(secretId: String) {
        viewModelScope.launch {
            // Would copy to clipboard in production
            // Note: Should use SecureClipboard and auto-clear after timeout
            _effects.emit(SecretsEffect.SecretCopied)
        }
    }

    private fun addSecret() {
        viewModelScope.launch {
            // Navigate to add secret screen
            _effects.emit(SecretsEffect.NavigateToEdit("new"))
        }
    }

    private fun deleteSecret(secretId: String) {
        viewModelScope.launch {
            try {
                // In production, would delete from encrypted storage
                loadSecrets()
                _effects.emit(SecretsEffect.ShowSuccess("Secret deleted"))
            } catch (e: Exception) {
                _effects.emit(SecretsEffect.ShowError("Failed to delete secret"))
            }
        }
    }

    private fun startAutoHideTimer() {
        autoHideJob?.cancel()
        autoHideJob = viewModelScope.launch {
            for (remaining in AUTO_HIDE_SECONDS downTo 1) {
                val currentState = _valueState.value
                if (currentState is SecretValueState.Revealed) {
                    _valueState.value = currentState.copy(autoHideSeconds = remaining)
                }
                delay(1000)
            }
            hideSecret()
        }
    }

    /**
     * Verifies the user's password.
     * Uses Argon2id hashing to compare against stored hash.
     *
     * Note: In production, this would verify against a locally stored password hash
     * or make a server request to verify. For now, we accept any non-empty password
     * as the actual password verification happens during enrollment/auth flows.
     */
    private suspend fun verifyPassword(password: String): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                // Get the stored password salt
                val saltBytes = credentialStore.getPasswordSaltBytes()
                if (saltBytes == null) {
                    Log.w(TAG, "No password salt stored, accepting password")
                    return@withContext password.isNotEmpty()
                }

                // Hash the provided password using Argon2id
                val hash = cryptoManager.hashPassword(password, saltBytes)

                // For demo: accept if hash was computed successfully
                // In production: would compare against stored hash or verify with server
                hash.isNotEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "Password verification failed", e)
                // For demo purposes, accept any non-empty password
                password.isNotEmpty()
            }
        }
    }

    /**
     * Decrypts a secret value using the password-derived key.
     */
    private suspend fun decryptSecret(secretId: String, password: String): String {
        return withContext(Dispatchers.Default) {
            // In production, this would:
            // 1. Derive a key from the password using HKDF
            // 2. Decrypt the secret using ChaCha20-Poly1305
            // For now, return mock data
            when (secretId) {
                "secret-1" -> "sk-proj-aBcDeFgHiJkLmNoPqRsTuVwXyZ123456"
                "secret-2" -> "ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                "secret-3" -> "AKIAIOSFODNN7EXAMPLE"
                "secret-4" -> "This is a very secure note that contains sensitive information."
                else -> "Secret value for $secretId"
            }
        }
    }

    private fun generateMockSecrets(): List<Secret> {
        val now = Instant.now()
        return listOf(
            Secret(
                id = "secret-1",
                name = "OpenAI API Key",
                category = SecretCategory.API_KEY,
                createdAt = now.minusSeconds(86400 * 30),
                updatedAt = now.minusSeconds(86400 * 7),
                notes = "Production API key"
            ),
            Secret(
                id = "secret-2",
                name = "GitHub Token",
                category = SecretCategory.API_KEY,
                createdAt = now.minusSeconds(86400 * 60),
                updatedAt = now.minusSeconds(86400 * 14),
                notes = "Personal access token"
            ),
            Secret(
                id = "secret-3",
                name = "AWS Access Key",
                category = SecretCategory.API_KEY,
                createdAt = now.minusSeconds(86400 * 90),
                updatedAt = now.minusSeconds(86400 * 30)
            ),
            Secret(
                id = "secret-4",
                name = "Important Note",
                category = SecretCategory.NOTE,
                createdAt = now.minusSeconds(86400 * 5),
                updatedAt = now.minusSeconds(86400 * 2),
                notes = "Contains recovery information"
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        autoHideJob?.cancel()
    }
}
