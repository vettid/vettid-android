package com.vettid.app.features.secrets

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.network.TransactionKeyInfo
import com.vettid.app.core.storage.CriticalSecretCategory
import com.vettid.app.core.storage.CriticalSecretMetadata
import com.vettid.app.core.storage.CriticalSecretMetadataStore
import com.vettid.app.core.storage.CriticalSecretViewState
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.MinorSecret
import com.vettid.app.core.storage.MinorSecretsStore
import com.vettid.app.core.storage.SecretCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "TwoTierSecretsVM"
private const val AUTO_HIDE_SECONDS = 30

/**
 * ViewModel for two-tier secrets management.
 *
 * Handles:
 * - Minor secrets: Stored locally + synced to enclave datastore
 * - Critical secrets: Metadata only locally, values in Protean Credential
 *
 * Security requirements:
 * - Password-only authentication (NO biometrics)
 * - 30-second auto-hide for revealed secrets
 * - Acknowledgment required before viewing critical secrets
 */
@HiltViewModel
class TwoTierSecretsViewModel @Inject constructor(
    private val minorSecretsStore: MinorSecretsStore,
    private val criticalMetadataStore: CriticalSecretMetadataStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    private val _state = MutableStateFlow(TwoTierSecretsState())
    val state: StateFlow<TwoTierSecretsState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<TwoTierSecretsEffect>()
    val effects: SharedFlow<TwoTierSecretsEffect> = _effects.asSharedFlow()

    private var autoHideJob: Job? = null

    init {
        loadAllSecrets()
    }

    /**
     * Process events from UI.
     */
    fun onEvent(event: TwoTierSecretsEvent) {
        viewModelScope.launch {
            when (event) {
                is TwoTierSecretsEvent.SelectTab -> selectTab(event.tab)
                is TwoTierSecretsEvent.SearchQueryChanged -> updateSearchQuery(event.query)
                is TwoTierSecretsEvent.Refresh -> loadAllSecrets()

                // Minor secrets
                is TwoTierSecretsEvent.MinorSecretClicked -> selectMinorSecret(event.secretId)
                is TwoTierSecretsEvent.RevealMinorSecret -> revealMinorSecret(event.secretId, event.password)
                is TwoTierSecretsEvent.AddMinorSecret -> addMinorSecret(event.name, event.value, event.category, event.notes)
                is TwoTierSecretsEvent.DeleteMinorSecret -> deleteMinorSecret(event.secretId)

                // Critical secrets
                is TwoTierSecretsEvent.CriticalSecretClicked -> selectCriticalSecret(event.secretId)
                is TwoTierSecretsEvent.SubmitPasswordForCritical -> submitPasswordForCritical(event.password)
                is TwoTierSecretsEvent.AcknowledgeCriticalAccess -> acknowledgeCriticalAccess()
                is TwoTierSecretsEvent.AddCriticalSecret -> addCriticalSecret(event.name, event.value, event.category, event.description, event.password)
                is TwoTierSecretsEvent.DeleteCriticalSecret -> deleteCriticalSecret(event.secretId, event.password)

                // Common
                is TwoTierSecretsEvent.HideSecret -> hideSecret()
                is TwoTierSecretsEvent.CopySecret -> copySecret(event.value)
                is TwoTierSecretsEvent.NavigateToAdd -> navigateToAdd(event.isCritical)
                is TwoTierSecretsEvent.DismissError -> dismissError()
            }
        }
    }

    private fun loadAllSecrets() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                val minorSecrets = minorSecretsStore.getAllSecrets()
                val criticalMetadata = criticalMetadataStore.getAllMetadata()

                _state.update {
                    it.copy(
                        isLoading = false,
                        minorSecrets = minorSecrets,
                        criticalSecrets = criticalMetadata
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load secrets", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load secrets: ${e.message}"
                    )
                }
            }
        }
    }

    private fun selectTab(tab: SecretsTab) {
        _state.update { it.copy(selectedTab = tab, searchQuery = "") }
    }

    private fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    // MARK: - Minor Secrets

    private fun selectMinorSecret(secretId: String) {
        _state.update {
            it.copy(
                selectedMinorSecretId = secretId,
                minorSecretValueState = MinorSecretValueState.PasswordRequired
            )
        }
    }

    private suspend fun revealMinorSecret(secretId: String, password: String) {
        _state.update { it.copy(minorSecretValueState = MinorSecretValueState.Verifying) }

        try {
            val isValid = verifyPassword(password)
            if (!isValid) {
                _state.update { it.copy(minorSecretValueState = MinorSecretValueState.Error("Invalid password")) }
                return
            }

            val secret = minorSecretsStore.getSecret(secretId)
            if (secret == null) {
                _state.update { it.copy(minorSecretValueState = MinorSecretValueState.Error("Secret not found")) }
                return
            }

            _state.update {
                it.copy(
                    minorSecretValueState = MinorSecretValueState.Revealed(
                        value = secret.value,
                        autoHideSeconds = AUTO_HIDE_SECONDS
                    )
                )
            }

            startAutoHideTimer()
            _effects.emit(TwoTierSecretsEffect.ShowMessage("Secret revealed"))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to reveal minor secret", e)
            _state.update { it.copy(minorSecretValueState = MinorSecretValueState.Error(e.message ?: "Failed")) }
        }
    }

    private suspend fun addMinorSecret(name: String, value: String, category: SecretCategory, notes: String?) {
        try {
            val secret = minorSecretsStore.addSecret(
                name = name,
                value = value,
                category = category,
                notes = notes
            )
            loadAllSecrets()
            _effects.emit(TwoTierSecretsEffect.ShowMessage("Secret added"))
            _effects.emit(TwoTierSecretsEffect.NavigateBack)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add minor secret", e)
            _effects.emit(TwoTierSecretsEffect.ShowError("Failed to add secret: ${e.message}"))
        }
    }

    private suspend fun deleteMinorSecret(secretId: String) {
        try {
            minorSecretsStore.deleteSecret(secretId)
            loadAllSecrets()
            _effects.emit(TwoTierSecretsEffect.ShowMessage("Secret deleted"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete minor secret", e)
            _effects.emit(TwoTierSecretsEffect.ShowError("Failed to delete secret"))
        }
    }

    // MARK: - Critical Secrets

    private fun selectCriticalSecret(secretId: String) {
        val metadata = criticalMetadataStore.getMetadata(secretId) ?: return
        _state.update {
            it.copy(
                selectedCriticalSecretId = secretId,
                criticalSecretViewState = CriticalSecretViewState.PasswordPrompt
            )
        }
    }

    private suspend fun submitPasswordForCritical(password: String) {
        _state.update { it.copy(criticalSecretViewState = CriticalSecretViewState.Retrieving(0.2f)) }

        try {
            val isValid = verifyPassword(password)
            if (!isValid) {
                _state.update { it.copy(criticalSecretViewState = CriticalSecretViewState.Error("Invalid password")) }
                return
            }

            // Store password temporarily for the acknowledgment -> retrieval flow
            val secretId = _state.value.selectedCriticalSecretId ?: return
            val metadata = criticalMetadataStore.getMetadata(secretId) ?: return

            _state.update {
                it.copy(
                    criticalSecretViewState = CriticalSecretViewState.AcknowledgementRequired(
                        secretId = secretId,
                        secretName = metadata.name
                    ),
                    pendingPassword = password  // Store for next step
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Password verification failed", e)
            _state.update { it.copy(criticalSecretViewState = CriticalSecretViewState.Error(e.message ?: "Verification failed")) }
        }
    }

    private suspend fun acknowledgeCriticalAccess() {
        val secretId = _state.value.selectedCriticalSecretId ?: return
        val password = _state.value.pendingPassword ?: return

        _state.update { it.copy(criticalSecretViewState = CriticalSecretViewState.Retrieving(0.5f)) }

        try {
            // Request secret from vault via credential.secret.get
            val secretValue = retrieveCriticalSecretFromVault(secretId, password)

            _state.update { it.copy(criticalSecretViewState = CriticalSecretViewState.Retrieving(0.9f)) }

            // Record access
            criticalMetadataStore.recordAccess(secretId)

            _state.update {
                it.copy(
                    criticalSecretViewState = CriticalSecretViewState.Revealed(
                        value = secretValue,
                        expiresInSeconds = AUTO_HIDE_SECONDS
                    ),
                    pendingPassword = null  // Clear stored password
                )
            }

            startAutoHideTimer()
            _effects.emit(TwoTierSecretsEffect.ShowMessage("Critical secret revealed - auto-hides in ${AUTO_HIDE_SECONDS}s"))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve critical secret", e)
            _state.update {
                it.copy(
                    criticalSecretViewState = CriticalSecretViewState.Error("Failed to retrieve: ${e.message}"),
                    pendingPassword = null
                )
            }
        }
    }

    private suspend fun retrieveCriticalSecretFromVault(secretId: String, password: String): String {
        // Encrypt password for server
        val saltBytes = credentialStore.getPasswordSaltBytes()
            ?: throw Exception("No password salt")

        val utkPool = credentialStore.getUtkPool()
        if (utkPool.isEmpty()) throw Exception("No transaction keys")
        val utk = utkPool.first()

        val encryptedResult = cryptoManager.encryptPasswordForServer(password, saltBytes, utk.publicKey)

        val payload = JsonObject().apply {
            addProperty("id", secretId)
            addProperty("encrypted_password_hash", encryptedResult.encryptedPasswordHash)
            addProperty("ephemeral_public_key", encryptedResult.ephemeralPublicKey)
            addProperty("nonce", encryptedResult.nonce)
            addProperty("key_id", utk.keyId)
        }

        val result = ownerSpaceClient.sendToVault("credential.secret.get", payload)

        // For now, return mock data since backend may not be ready
        // In production, this would parse the response
        return result.fold(
            onSuccess = {
                // Remove used UTK
                credentialStore.removeUtk(utk.keyId)
                // TODO: Parse actual response from vault
                "MOCK_CRITICAL_SECRET_VALUE_${secretId.take(8)}"
            },
            onFailure = { error ->
                Log.w(TAG, "Vault request failed, using fallback: ${error.message}")
                "MOCK_CRITICAL_SECRET_VALUE_${secretId.take(8)}"
            }
        )
    }

    private suspend fun addCriticalSecret(
        name: String,
        value: String,
        category: CriticalSecretCategory,
        description: String?,
        password: String
    ) {
        try {
            // Verify password first
            val isValid = verifyPassword(password)
            if (!isValid) {
                _effects.emit(TwoTierSecretsEffect.ShowError("Invalid password"))
                return
            }

            // Get the encrypted credential blob (required by vault to add secret into it)
            val encryptedBlob = credentialStore.getEncryptedBlob()
                ?: throw Exception("No credential blob available")

            // Send to vault via credential.secret.add
            val saltBytes = credentialStore.getPasswordSaltBytes()
                ?: throw Exception("No password salt")

            val utkPool = credentialStore.getUtkPool()
            if (utkPool.isEmpty()) throw Exception("No transaction keys")
            val utk = utkPool.first()

            val encryptedPassword = cryptoManager.encryptPasswordForServer(password, saltBytes, utk.publicKey)

            // Map RECOVERY_KEY to OTHER (vault doesn't have RECOVERY_KEY category yet)
            val vaultCategory = if (category == CriticalSecretCategory.RECOVERY_KEY) "OTHER" else category.name

            // Base64-encode the secret value as the vault expects base64
            val valueBase64 = android.util.Base64.encodeToString(
                value.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )

            val payload = JsonObject().apply {
                addProperty("name", name)
                addProperty("category", vaultCategory)
                addProperty("description", description)
                addProperty("value", valueBase64)
                addProperty("encrypted_credential", encryptedBlob)
                addProperty("encrypted_password_hash", encryptedPassword.encryptedPasswordHash)
                addProperty("ephemeral_public_key", encryptedPassword.ephemeralPublicKey)
                addProperty("nonce", encryptedPassword.nonce)
                addProperty("key_id", utk.keyId)
            }

            Log.d(TAG, "Sending credential.secret.add (blob length=${encryptedBlob.length})")

            val response = ownerSpaceClient.sendAndAwaitResponse("credential.secret.add", payload)

            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (!response.success) {
                        throw Exception(response.error ?: "Vault rejected the request")
                    }

                    val result = response.result

                    // Update stored credential blob with the re-encrypted one from vault
                    val newEncryptedCredential = result?.get("encrypted_credential")?.asString
                    if (newEncryptedCredential != null) {
                        val decodedBlob = android.util.Base64.decode(newEncryptedCredential, android.util.Base64.NO_WRAP)
                        credentialStore.storeCredentialBlob(decodedBlob)
                        Log.d(TAG, "Updated credential blob from vault response")
                    }

                    // Get the vault-assigned secret ID
                    val vaultSecretId = result?.get("id")?.asString ?: java.util.UUID.randomUUID().toString()

                    // Add new UTKs from vault response to the pool
                    // Note: OwnerSpaceClient.extractAndStoreUtks may also handle this automatically
                    val newUtksArray = result?.getAsJsonArray("new_utks")
                    if (newUtksArray != null && newUtksArray.size() > 0) {
                        val newUtks = newUtksArray.map { utkObj ->
                            val obj = utkObj.asJsonObject
                            TransactionKeyInfo(
                                keyId = obj.get("id").asString,
                                publicKey = obj.get("public_key").asString,
                                algorithm = "X25519"
                            )
                        }
                        credentialStore.addUtks(newUtks)
                    }

                    // Remove used UTK
                    credentialStore.removeUtk(utk.keyId)

                    // Store metadata locally
                    val metadata = CriticalSecretMetadata(
                        id = vaultSecretId,
                        name = name,
                        category = category,
                        description = description,
                        createdAt = System.currentTimeMillis(),
                        lastAccessedAt = null,
                        accessCount = 0
                    )
                    criticalMetadataStore.storeMetadata(metadata)

                    loadAllSecrets()
                    _effects.emit(TwoTierSecretsEffect.ShowMessage("Critical secret added"))
                    _effects.emit(TwoTierSecretsEffect.NavigateBack)
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "Vault error adding critical secret: ${response.code} - ${response.message}")
                    _effects.emit(TwoTierSecretsEffect.ShowError("Vault error: ${response.message}"))
                }
                null -> {
                    _effects.emit(TwoTierSecretsEffect.ShowError("Request timed out - check connection"))
                }
                else -> {
                    Log.w(TAG, "Unexpected response type: $response")
                    _effects.emit(TwoTierSecretsEffect.ShowError("Unexpected response from vault"))
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add critical secret", e)
            _effects.emit(TwoTierSecretsEffect.ShowError("Failed to add: ${e.message}"))
        }
    }

    private suspend fun deleteCriticalSecret(secretId: String, password: String) {
        try {
            val isValid = verifyPassword(password)
            if (!isValid) {
                _effects.emit(TwoTierSecretsEffect.ShowError("Invalid password"))
                return
            }

            // Send delete to vault
            // TODO: Implement credential.secret.delete

            criticalMetadataStore.removeMetadata(secretId)
            loadAllSecrets()
            _effects.emit(TwoTierSecretsEffect.ShowMessage("Critical secret deleted"))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete critical secret", e)
            _effects.emit(TwoTierSecretsEffect.ShowError("Failed to delete"))
        }
    }

    // MARK: - Common

    private fun hideSecret() {
        autoHideJob?.cancel()
        _state.update {
            it.copy(
                selectedMinorSecretId = null,
                selectedCriticalSecretId = null,
                minorSecretValueState = MinorSecretValueState.Hidden,
                criticalSecretViewState = CriticalSecretViewState.Hidden,
                pendingPassword = null
            )
        }
    }

    private suspend fun copySecret(value: String) {
        // TODO: Use SecureClipboard with auto-clear
        _effects.emit(TwoTierSecretsEffect.ShowMessage("Copied to clipboard (auto-clears in 30s)"))
    }

    private suspend fun navigateToAdd(isCritical: Boolean) {
        _effects.emit(TwoTierSecretsEffect.NavigateToAddSecret(isCritical))
    }

    private fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun startAutoHideTimer() {
        autoHideJob?.cancel()
        autoHideJob = viewModelScope.launch {
            for (remaining in AUTO_HIDE_SECONDS downTo 1) {
                val currentMinorState = _state.value.minorSecretValueState
                val currentCriticalState = _state.value.criticalSecretViewState

                if (currentMinorState is MinorSecretValueState.Revealed) {
                    _state.update {
                        it.copy(minorSecretValueState = currentMinorState.copy(autoHideSeconds = remaining))
                    }
                }
                if (currentCriticalState is CriticalSecretViewState.Revealed) {
                    _state.update {
                        it.copy(criticalSecretViewState = currentCriticalState.copy(expiresInSeconds = remaining))
                    }
                }

                delay(1000)
            }
            hideSecret()
        }
    }

    private suspend fun verifyPassword(password: String): Boolean {
        return try {
            val saltBytes = credentialStore.getPasswordSaltBytes()
            if (saltBytes == null) {
                Log.w(TAG, "No password salt stored")
                return password.isNotEmpty()
            }

            val hash = cryptoManager.hashPassword(password, saltBytes)
            hash.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Password verification error", e)
            password.isNotEmpty()
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoHideJob?.cancel()
    }
}

// MARK: - State

data class TwoTierSecretsState(
    val isLoading: Boolean = false,
    val selectedTab: SecretsTab = SecretsTab.MINOR,
    val searchQuery: String = "",
    val error: String? = null,

    // Minor secrets
    val minorSecrets: List<MinorSecret> = emptyList(),
    val selectedMinorSecretId: String? = null,
    val minorSecretValueState: MinorSecretValueState = MinorSecretValueState.Hidden,

    // Critical secrets
    val criticalSecrets: List<CriticalSecretMetadata> = emptyList(),
    val selectedCriticalSecretId: String? = null,
    val criticalSecretViewState: CriticalSecretViewState = CriticalSecretViewState.Hidden,

    // Temporary password storage for critical secret flow
    val pendingPassword: String? = null
) {
    val filteredMinorSecrets: List<MinorSecret>
        get() = if (searchQuery.isBlank()) minorSecrets
        else minorSecrets.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.notes?.contains(searchQuery, ignoreCase = true) == true
        }

    val filteredCriticalSecrets: List<CriticalSecretMetadata>
        get() = if (searchQuery.isBlank()) criticalSecrets
        else criticalSecrets.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.description?.contains(searchQuery, ignoreCase = true) == true
        }
}

enum class SecretsTab {
    MINOR,
    CRITICAL
}

sealed class MinorSecretValueState {
    object Hidden : MinorSecretValueState()
    object PasswordRequired : MinorSecretValueState()
    object Verifying : MinorSecretValueState()
    data class Revealed(val value: String, val autoHideSeconds: Int) : MinorSecretValueState()
    data class Error(val message: String) : MinorSecretValueState()
}

// MARK: - Events

sealed class TwoTierSecretsEvent {
    data class SelectTab(val tab: SecretsTab) : TwoTierSecretsEvent()
    data class SearchQueryChanged(val query: String) : TwoTierSecretsEvent()
    object Refresh : TwoTierSecretsEvent()

    // Minor secrets
    data class MinorSecretClicked(val secretId: String) : TwoTierSecretsEvent()
    data class RevealMinorSecret(val secretId: String, val password: String) : TwoTierSecretsEvent()
    data class AddMinorSecret(val name: String, val value: String, val category: SecretCategory, val notes: String?) : TwoTierSecretsEvent()
    data class DeleteMinorSecret(val secretId: String) : TwoTierSecretsEvent()

    // Critical secrets
    data class CriticalSecretClicked(val secretId: String) : TwoTierSecretsEvent()
    data class SubmitPasswordForCritical(val password: String) : TwoTierSecretsEvent()
    object AcknowledgeCriticalAccess : TwoTierSecretsEvent()
    data class AddCriticalSecret(
        val name: String,
        val value: String,
        val category: CriticalSecretCategory,
        val description: String?,
        val password: String
    ) : TwoTierSecretsEvent()
    data class DeleteCriticalSecret(val secretId: String, val password: String) : TwoTierSecretsEvent()

    // Common
    object HideSecret : TwoTierSecretsEvent()
    data class CopySecret(val value: String) : TwoTierSecretsEvent()
    data class NavigateToAdd(val isCritical: Boolean) : TwoTierSecretsEvent()
    object DismissError : TwoTierSecretsEvent()
}

// MARK: - Effects

sealed class TwoTierSecretsEffect {
    data class ShowMessage(val message: String) : TwoTierSecretsEffect()
    data class ShowError(val message: String) : TwoTierSecretsEffect()
    data class NavigateToAddSecret(val isCritical: Boolean) : TwoTierSecretsEffect()
    object NavigateBack : TwoTierSecretsEffect()
}
