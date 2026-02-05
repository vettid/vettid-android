package com.vettid.app.features.secrets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.MinorSecret
import com.vettid.app.core.storage.MinorSecretsStore
import com.vettid.app.core.storage.SecretCategory
import com.vettid.app.core.storage.SecretType
import com.vettid.app.worker.SecretsSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "SecretsViewModel"

@HiltViewModel
class SecretsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val minorSecretsStore: MinorSecretsStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore,
    private val connectionManager: NatsConnectionManager
) : ViewModel() {

    private val _state = MutableStateFlow<SecretsState>(SecretsState.Loading)
    val state: StateFlow<SecretsState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _editState = MutableStateFlow(EditSecretState())
    val editState: StateFlow<EditSecretState> = _editState.asStateFlow()

    private val _effects = MutableSharedFlow<SecretsEffect>()
    val effects: SharedFlow<SecretsEffect> = _effects.asSharedFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    private val _showDeleteConfirmDialog = MutableStateFlow<String?>(null)
    val showDeleteConfirmDialog: StateFlow<String?> = _showDeleteConfirmDialog.asStateFlow()

    init {
        Log.i(TAG, "SecretsViewModel initialized")
        loadSecrets()
    }

    fun onEvent(event: SecretsEvent) {
        when (event) {
            is SecretsEvent.SearchQueryChanged -> updateSearchQuery(event.query)
            is SecretsEvent.SecretClicked -> handleSecretClicked(event.secretId)
            is SecretsEvent.CopySecret -> copySecretToClipboard(event.secretId)
            is SecretsEvent.ShowQRCode -> showSecretQRCode(event.secretId)
            is SecretsEvent.AddSecret -> showAddSecretDialog()
            is SecretsEvent.DeleteSecret -> confirmDeleteSecret(event.secretId)
            is SecretsEvent.TogglePublicProfile -> togglePublicProfile(event.secretId)
            is SecretsEvent.MoveSecretUp -> moveSecretUp(event.secretId)
            is SecretsEvent.MoveSecretDown -> moveSecretDown(event.secretId)
            is SecretsEvent.Refresh -> loadSecrets()
            is SecretsEvent.PublishPublicKeys -> publishPublicKeys()
        }
    }

    // MARK: - Load Secrets

    private fun loadSecrets() {
        viewModelScope.launch {
            // Only show full-screen loading on initial load, not on refresh
            val isRefresh = _state.value !is SecretsState.Loading
            if (!isRefresh) {
                _state.value = SecretsState.Loading
            } else {
                _isRefreshing.value = true
            }

            try {
                val secrets = minorSecretsStore.getAllSecrets()
                val hasUnpublished = checkForUnpublishedChanges(secrets)

                if (secrets.isEmpty()) {
                    _state.value = SecretsState.Empty
                } else {
                    _state.value = SecretsState.Loaded(
                        items = secrets,
                        hasUnpublishedChanges = hasUnpublished
                    )
                }

                Log.d(TAG, "Loaded ${secrets.size} secrets")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load secrets", e)
                _state.value = SecretsState.Error(e.message ?: "Failed to load secrets")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private fun checkForUnpublishedChanges(secrets: List<MinorSecret>): Boolean {
        return secrets.any {
            it.type == SecretType.PUBLIC_KEY &&
            it.isInPublicProfile &&
            it.syncStatus == com.vettid.app.core.storage.SyncStatus.PENDING
        }
    }

    // MARK: - Search

    private fun updateSearchQuery(query: String) {
        val currentState = _state.value
        if (currentState is SecretsState.Loaded) {
            if (query.isBlank()) {
                // Reset to full list
                val allSecrets = minorSecretsStore.getAllSecrets()
                _state.value = currentState.copy(
                    items = allSecrets,
                    searchQuery = ""
                )
            } else {
                // Filter secrets
                val filtered = minorSecretsStore.searchSecrets(query)
                _state.value = currentState.copy(
                    items = filtered,
                    searchQuery = query
                )
            }
        }
    }

    // MARK: - Secret Click Handling

    private fun handleSecretClicked(secretId: String) {
        viewModelScope.launch {
            val secret = minorSecretsStore.getSecret(secretId)
            if (secret != null) {
                if (secret.type == SecretType.PUBLIC_KEY) {
                    _effects.emit(SecretsEffect.ShowQRCode(secret))
                } else {
                    showEditSecretDialog(secretId)
                }
            }
        }
    }

    // MARK: - QR Code Display

    private fun showSecretQRCode(secretId: String) {
        viewModelScope.launch {
            val secret = minorSecretsStore.getSecret(secretId)
            if (secret != null && secret.type == SecretType.PUBLIC_KEY) {
                _effects.emit(SecretsEffect.ShowQRCode(secret))
            } else if (secret != null) {
                showEditSecretDialog(secretId)
            } else {
                _effects.emit(SecretsEffect.ShowError("Secret not found"))
            }
        }
    }

    // MARK: - Clipboard

    private fun copySecretToClipboard(secretId: String) {
        viewModelScope.launch {
            val secret = minorSecretsStore.getSecret(secretId)
            if (secret != null) {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(secret.name, secret.value)
                    clipboard.setPrimaryClip(clip)
                    _effects.emit(SecretsEffect.SecretCopied)
                    Log.d(TAG, "Copied secret to clipboard: ${secret.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy to clipboard", e)
                    _effects.emit(SecretsEffect.ShowError("Failed to copy"))
                }
            }
        }
    }

    // MARK: - Add/Edit Secret Dialog

    fun showAddSecretDialog() {
        _editState.value = EditSecretState()
        _showAddDialog.value = true
    }

    fun showEditSecretDialog(secretId: String) {
        val secret = minorSecretsStore.getSecret(secretId)
        if (secret != null) {
            _editState.value = EditSecretState(
                id = secret.id,
                name = secret.name,
                value = secret.value,
                type = secret.type,
                category = secret.category,
                notes = secret.notes ?: "",
                isInPublicProfile = secret.isInPublicProfile,
                isEditing = true
            )
            _showAddDialog.value = true
        }
    }

    fun dismissAddDialog() {
        _showAddDialog.value = false
        _editState.value = EditSecretState()
    }

    fun updateEditState(newState: EditSecretState) {
        _editState.value = newState
    }

    fun saveSecret() {
        val currentEdit = _editState.value

        // Validate
        var hasError = false
        var newEditState = currentEdit

        if (currentEdit.name.isBlank()) {
            newEditState = newEditState.copy(nameError = "Name is required")
            hasError = true
        }
        if (currentEdit.value.isBlank()) {
            newEditState = newEditState.copy(valueError = "Value is required")
            hasError = true
        }

        if (hasError) {
            _editState.value = newEditState
            return
        }

        _editState.value = currentEdit.copy(isSaving = true)

        viewModelScope.launch {
            try {
                if (currentEdit.isEditing && currentEdit.id != null) {
                    // Update existing secret
                    val existing = minorSecretsStore.getSecret(currentEdit.id)
                    if (existing != null) {
                        val updated = existing.copy(
                            name = currentEdit.name,
                            value = currentEdit.value,
                            type = currentEdit.type,
                            category = currentEdit.category,
                            notes = currentEdit.notes.takeIf { it.isNotBlank() },
                            isInPublicProfile = if (currentEdit.type == SecretType.PUBLIC_KEY)
                                currentEdit.isInPublicProfile else false
                        )
                        minorSecretsStore.updateSecret(updated)
                        Log.i(TAG, "Updated secret: ${updated.name}")
                    }
                } else {
                    // Add new secret
                    val newSecret = minorSecretsStore.addSecret(
                        name = currentEdit.name,
                        value = currentEdit.value,
                        category = currentEdit.category,
                        type = currentEdit.type,
                        notes = currentEdit.notes.takeIf { it.isNotBlank() },
                        isInPublicProfile = if (currentEdit.type == SecretType.PUBLIC_KEY)
                            currentEdit.isInPublicProfile else false
                    )
                    Log.i(TAG, "Added new secret: ${newSecret.name}")
                }

                // Trigger sync
                SecretsSyncWorker.scheduleImmediate(context)

                dismissAddDialog()
                loadSecrets()
                _effects.emit(SecretsEffect.ShowSuccess("Secret saved"))

            } catch (e: Exception) {
                Log.e(TAG, "Failed to save secret", e)
                _editState.value = currentEdit.copy(isSaving = false)
                _effects.emit(SecretsEffect.ShowError(e.message ?: "Failed to save"))
            }
        }
    }

    // MARK: - Save Template

    fun saveTemplate(templateState: TemplateFormState) {
        viewModelScope.launch {
            try {
                val template = templateState.template
                var savedCount = 0

                template.fields.forEachIndexed { index, field ->
                    val value = templateState.getValue(index)
                    if (value.isNotBlank()) {
                        minorSecretsStore.addSecret(
                            name = field.name,
                            value = value,
                            category = template.category,
                            type = field.type,
                            isInPublicProfile = false
                        )
                        savedCount++
                    }
                }

                if (savedCount > 0) {
                    SecretsSyncWorker.scheduleImmediate(context)
                    loadSecrets()
                    _effects.emit(SecretsEffect.ShowSuccess("Added $savedCount ${template.name} field${if (savedCount > 1) "s" else ""}"))
                    Log.i(TAG, "Saved template ${template.name} with $savedCount fields")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save template", e)
                _effects.emit(SecretsEffect.ShowError(e.message ?: "Failed to save"))
            }
        }
    }

    // MARK: - Delete Secret

    private fun confirmDeleteSecret(secretId: String) {
        val secret = minorSecretsStore.getSecret(secretId)
        if (secret?.isSystemField == true) {
            viewModelScope.launch {
                _effects.emit(SecretsEffect.ShowError("Cannot delete system fields"))
            }
            return
        }
        _showDeleteConfirmDialog.value = secretId
    }

    fun dismissDeleteConfirmDialog() {
        _showDeleteConfirmDialog.value = null
    }

    fun deleteSecret(secretId: String) {
        viewModelScope.launch {
            try {
                val secret = minorSecretsStore.getSecret(secretId)
                if (secret?.isSystemField == true) {
                    _effects.emit(SecretsEffect.ShowError("Cannot delete system fields"))
                    return@launch
                }

                minorSecretsStore.deleteSecret(secretId)

                // Also delete from vault
                try {
                    val payload = JsonObject().apply {
                        addProperty("id", secretId)
                    }
                    ownerSpaceClient.sendToVault("secrets.delete", payload)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete from vault (will sync later)", e)
                }

                dismissDeleteConfirmDialog()
                loadSecrets()
                _effects.emit(SecretsEffect.ShowSuccess("Secret deleted"))
                Log.i(TAG, "Deleted secret: $secretId")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete secret", e)
                _effects.emit(SecretsEffect.ShowError(e.message ?: "Failed to delete"))
            }
        }
    }

    // MARK: - Public Profile Toggle

    private fun togglePublicProfile(secretId: String) {
        viewModelScope.launch {
            try {
                val result = minorSecretsStore.togglePublicProfile(secretId)
                if (result) {
                    loadSecrets()
                    _effects.emit(SecretsEffect.ShowSuccess("Public profile updated"))
                } else {
                    _effects.emit(SecretsEffect.ShowError("Only public keys can be shared to profile"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle public profile", e)
                _effects.emit(SecretsEffect.ShowError(e.message ?: "Failed to update"))
            }
        }
    }

    // MARK: - Reordering

    private fun moveSecretUp(secretId: String) {
        viewModelScope.launch {
            val result = minorSecretsStore.moveSecretUp(secretId)
            if (result) {
                loadSecrets()
            }
        }
    }

    private fun moveSecretDown(secretId: String) {
        viewModelScope.launch {
            val result = minorSecretsStore.moveSecretDown(secretId)
            if (result) {
                loadSecrets()
            }
        }
    }

    // MARK: - Publish Public Keys

    private fun publishPublicKeys() {
        viewModelScope.launch {
            try {
                val publicSecrets = minorSecretsStore.getPublicProfileSecrets()

                if (publicSecrets.isEmpty()) {
                    _effects.emit(SecretsEffect.ShowError("No public keys to publish"))
                    return@launch
                }

                // Build the list of public key namespaces to publish
                val namespaces = publicSecrets.map { secret ->
                    "secrets.public_key.${secret.id}"
                }

                // Call profile.publish with the updated fields
                val result = ownerSpaceClient.publishProfile(namespaces)

                result.fold(
                    onSuccess = {
                        // Mark all public keys as synced
                        publicSecrets.forEach { secret ->
                            minorSecretsStore.updateSyncStatus(
                                secret.id,
                                com.vettid.app.core.storage.SyncStatus.SYNCED
                            )
                        }
                        loadSecrets()
                        _effects.emit(SecretsEffect.ShowSuccess("Public keys published"))
                        Log.i(TAG, "Published ${publicSecrets.size} public keys to profile")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to publish public keys", error)
                        _effects.emit(SecretsEffect.ShowError(error.message ?: "Failed to publish"))
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish public keys", e)
                _effects.emit(SecretsEffect.ShowError(e.message ?: "Failed to publish"))
            }
        }
    }

    // MARK: - Sync from Vault

    fun syncFromVault() {
        viewModelScope.launch {
            try {
                if (!connectionManager.isConnected()) {
                    _effects.emit(SecretsEffect.ShowError("Not connected"))
                    return@launch
                }

                val response = ownerSpaceClient.sendAndAwaitResponse(
                    "secrets.retrieve",
                    JsonObject(),
                    15000L
                )

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success && response.result != null) {
                            val secretsArray = response.result.getAsJsonArray("secrets")
                            if (secretsArray != null) {
                                val secretsData = parseSecretsFromVault(secretsArray)
                                minorSecretsStore.importFromSync(secretsData)
                                loadSecrets()
                                _effects.emit(SecretsEffect.ShowSuccess("Synced from vault"))
                                Log.i(TAG, "Synced ${secretsData.size} secrets from vault")
                            }
                        } else {
                            _effects.emit(SecretsEffect.ShowError(response.error ?: "Sync failed"))
                        }
                    }
                    is VaultResponse.Error -> {
                        _effects.emit(SecretsEffect.ShowError(response.message))
                    }
                    null -> {
                        _effects.emit(SecretsEffect.ShowError("Sync timed out"))
                    }
                    else -> {
                        _effects.emit(SecretsEffect.ShowError("Unexpected response"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync from vault", e)
                _effects.emit(SecretsEffect.ShowError(e.message ?: "Sync failed"))
            }
        }
    }

    private fun parseSecretsFromVault(secretsArray: JsonArray): List<Map<String, Any?>> {
        return secretsArray.mapNotNull { element ->
            try {
                val obj = element.asJsonObject
                mapOf(
                    "id" to obj.get("id").asString,
                    "name" to obj.get("name").asString,
                    "value" to obj.get("value").asString,
                    "category" to obj.get("category")?.asString,
                    "type" to obj.get("type")?.asString,
                    "notes" to obj.get("notes")?.asString,
                    "isShareable" to obj.get("isShareable")?.asBoolean,
                    "isInPublicProfile" to obj.get("isInPublicProfile")?.asBoolean,
                    "isSystemField" to obj.get("isSystemField")?.asBoolean,
                    "sortOrder" to obj.get("sortOrder")?.asInt,
                    "createdAt" to obj.get("createdAt")?.asLong,
                    "updatedAt" to obj.get("updatedAt")?.asLong
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse secret from vault", e)
                null
            }
        }
    }

    // MARK: - User's Unique Public Key

    /**
     * Ensure the user's unique public key is in the local secrets store.
     *
     * The unique key pair is generated by the vault during enrollment:
     * - Private key is sealed in the credential (enclave-side)
     * - Public key is stored in the vault's datastore
     *
     * This method syncs from vault if the key is not present locally.
     */
    fun ensureEnrollmentKeyInSecrets() {
        viewModelScope.launch {
            val existingKey = minorSecretsStore.getEnrollmentPublicKey()
            if (existingKey == null) {
                Log.d(TAG, "User's public key not found locally, fetching from vault...")
                fetchUserPublicKeyFromVault()
            }
        }
    }

    /**
     * Fetch the user's unique Ed25519 identity public key from the vault.
     * The key is generated during enrollment and stored in the credential.
     */
    private suspend fun fetchUserPublicKeyFromVault() {
        try {
            if (!connectionManager.isConnected()) {
                Log.w(TAG, "Cannot fetch user public key: not connected to vault")
                return
            }

            // Use the dedicated identity endpoint
            val response = ownerSpaceClient.sendAndAwaitResponse(
                "secrets.identity",
                JsonObject(),
                15000L
            )

            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        val publicKeyBase64 = response.result.get("public_key")?.asString
                        val keyType = response.result.get("key_type")?.asString ?: "Ed25519"

                        if (publicKeyBase64 != null) {
                            minorSecretsStore.setEnrollmentPublicKey(publicKeyBase64, keyType)
                            loadSecrets()
                            Log.i(TAG, "Fetched user's $keyType identity public key from vault")
                            return
                        }
                        Log.w(TAG, "Public key not found in identity response")
                    } else {
                        Log.w(TAG, "Identity request failed: ${response.error}")
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "Failed to fetch user public key: ${response.message}")
                }
                null -> {
                    Log.w(TAG, "Timeout fetching user public key from vault")
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching user public key from vault", e)
        }
    }

}
