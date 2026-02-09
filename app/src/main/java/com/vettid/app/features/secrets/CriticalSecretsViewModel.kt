package com.vettid.app.features.secrets

import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "CriticalSecretsVM"
private const val REVEAL_DURATION_SECONDS = 30

@HiltViewModel
class CriticalSecretsViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore,
    private val natsAutoConnector: NatsAutoConnector,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    private val _state = MutableStateFlow<CriticalSecretsState>(CriticalSecretsState.PasswordPrompt)
    val state: StateFlow<CriticalSecretsState> = _state.asStateFlow()

    // Cached metadata list for returning from reveal
    private var cachedMetadataList: CriticalSecretsState.MetadataList? = null

    // Pending secret ID for reveal after second password
    private var pendingRevealSecretId: String? = null

    // Auto-hide countdown job
    private var countdownJob: Job? = null

    fun onEvent(event: CriticalSecretsScreenEvent) {
        when (event) {
            is CriticalSecretsScreenEvent.SubmitPassword -> {
                authenticateAndLoadMetadata(event.password)
            }
            is CriticalSecretsScreenEvent.RevealSecret -> {
                pendingRevealSecretId = event.secretId
                _state.value = CriticalSecretsState.SecondPasswordPrompt(
                    secretId = event.secretId,
                    secretName = event.secretName
                )
            }
            is CriticalSecretsScreenEvent.SubmitRevealPassword -> {
                pendingRevealSecretId?.let { secretId ->
                    revealSecretValue(secretId, event.password)
                }
            }
            is CriticalSecretsScreenEvent.TimerExpired -> {
                countdownJob?.cancel()
                cachedMetadataList?.let {
                    _state.value = it
                } ?: run {
                    _state.value = CriticalSecretsState.PasswordPrompt
                }
            }
            is CriticalSecretsScreenEvent.BackToList -> {
                countdownJob?.cancel()
                cachedMetadataList?.let {
                    _state.value = it
                } ?: run {
                    _state.value = CriticalSecretsState.PasswordPrompt
                }
            }
            is CriticalSecretsScreenEvent.SearchQueryChanged -> {
                val current = _state.value
                if (current is CriticalSecretsState.MetadataList) {
                    _state.value = current.copy(searchQuery = event.query)
                }
            }
        }
    }

    private fun authenticateAndLoadMetadata(password: String) {
        viewModelScope.launch {
            _state.value = CriticalSecretsState.Authenticating

            try {
                if (natsAutoConnector.connectionState.value !is NatsAutoConnector.AutoConnectState.Connected) {
                    _state.value = CriticalSecretsState.Error("Not connected to vault. Please wait for connection.")
                    return@launch
                }

                val salt = credentialStore.getPasswordSaltBytes()
                if (salt == null) {
                    _state.value = CriticalSecretsState.Error("Password salt not found")
                    return@launch
                }

                val utkPool = credentialStore.getUtkPool()
                if (utkPool.isEmpty()) {
                    _state.value = CriticalSecretsState.Error("No UTKs available")
                    return@launch
                }

                val utk = utkPool.first()
                val encResult = cryptoManager.encryptPasswordForServer(password, salt, utk.publicKey)

                val payload = JsonObject().apply {
                    addProperty("encrypted_password_hash", encResult.encryptedPasswordHash)
                    addProperty("ephemeral_public_key", encResult.ephemeralPublicKey)
                    addProperty("nonce", encResult.nonce)
                    addProperty("key_id", utk.keyId)
                }

                val response = ownerSpaceClient.sendAndAwaitResponse(
                    "credential.secret.list",
                    payload,
                    15000L
                )

                // Consume UTK
                credentialStore.removeUtk(utk.keyId)

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success && response.result != null) {
                            val metadata = parseCriticalSecretsMetadata(response.result)
                            cachedMetadataList = metadata
                            _state.value = metadata
                            Log.i(TAG, "Loaded critical secrets metadata")
                        } else {
                            _state.value = CriticalSecretsState.Error(
                                response.error ?: "Failed to load secrets"
                            )
                        }
                    }
                    is VaultResponse.Error -> {
                        _state.value = CriticalSecretsState.Error(response.message)
                    }
                    null -> {
                        _state.value = CriticalSecretsState.Error("Request timed out")
                    }
                    else -> {
                        _state.value = CriticalSecretsState.Error("Unexpected response")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to authenticate for critical secrets", e)
                _state.value = CriticalSecretsState.Error(
                    e.message ?: "Authentication failed"
                )
            }
        }
    }

    private fun revealSecretValue(secretId: String, password: String) {
        viewModelScope.launch {
            val currentState = _state.value
            val secretName = when (currentState) {
                is CriticalSecretsState.SecondPasswordPrompt -> currentState.secretName
                else -> "Secret"
            }
            _state.value = CriticalSecretsState.Retrieving(secretName)

            try {
                if (natsAutoConnector.connectionState.value !is NatsAutoConnector.AutoConnectState.Connected) {
                    _state.value = CriticalSecretsState.Error("Not connected to vault. Please wait for connection.")
                    return@launch
                }

                val salt = credentialStore.getPasswordSaltBytes()
                if (salt == null) {
                    _state.value = CriticalSecretsState.Error("Password salt not found")
                    return@launch
                }

                val utkPool = credentialStore.getUtkPool()
                if (utkPool.isEmpty()) {
                    _state.value = CriticalSecretsState.Error("No UTKs available")
                    return@launch
                }

                val utk = utkPool.first()
                val encResult = cryptoManager.encryptPasswordForServer(password, salt, utk.publicKey)

                val encryptedCredential = credentialStore.getEncryptedBlob()
                if (encryptedCredential == null) {
                    _state.value = CriticalSecretsState.Error("Credential not found")
                    return@launch
                }

                val payload = JsonObject().apply {
                    addProperty("encrypted_credential", encryptedCredential)
                    addProperty("id", secretId)
                    addProperty("encrypted_password_hash", encResult.encryptedPasswordHash)
                    addProperty("ephemeral_public_key", encResult.ephemeralPublicKey)
                    addProperty("nonce", encResult.nonce)
                    addProperty("key_id", utk.keyId)
                }

                val response = ownerSpaceClient.sendAndAwaitResponse(
                    "credential.secret.get",
                    payload,
                    15000L
                )

                // Consume UTK
                credentialStore.removeUtk(utk.keyId)

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success && response.result != null) {
                            val valueBase64 = response.result.get("value")?.asString
                            val name = response.result.get("name")?.asString ?: secretName

                            if (valueBase64 != null) {
                                val value = String(
                                    Base64.decode(valueBase64, Base64.DEFAULT),
                                    Charsets.UTF_8
                                )
                                startRevealCountdown(secretId, name, value)
                                Log.i(TAG, "Revealed critical secret: $secretId")
                            } else {
                                _state.value = CriticalSecretsState.Error("No value returned")
                            }
                        } else {
                            _state.value = CriticalSecretsState.Error(
                                response.error ?: "Failed to retrieve secret"
                            )
                        }
                    }
                    is VaultResponse.Error -> {
                        _state.value = CriticalSecretsState.Error(response.message)
                    }
                    null -> {
                        _state.value = CriticalSecretsState.Error("Request timed out")
                    }
                    else -> {
                        _state.value = CriticalSecretsState.Error("Unexpected response")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reveal critical secret", e)
                _state.value = CriticalSecretsState.Error(
                    e.message ?: "Failed to retrieve secret"
                )
            }
        }
    }

    private fun startRevealCountdown(secretId: String, secretName: String, value: String) {
        countdownJob?.cancel()
        _state.value = CriticalSecretsState.Revealed(
            secretId = secretId,
            secretName = secretName,
            value = value,
            remainingSeconds = REVEAL_DURATION_SECONDS
        )

        countdownJob = viewModelScope.launch {
            for (remaining in REVEAL_DURATION_SECONDS - 1 downTo 0) {
                delay(1000)
                val current = _state.value
                if (current is CriticalSecretsState.Revealed && current.secretId == secretId) {
                    if (remaining == 0) {
                        onEvent(CriticalSecretsScreenEvent.TimerExpired)
                    } else {
                        _state.value = current.copy(remainingSeconds = remaining)
                    }
                } else {
                    break
                }
            }
        }
    }

    private fun parseCriticalSecretsMetadata(result: JsonObject): CriticalSecretsState.MetadataList {
        val secrets = mutableListOf<CriticalSecretItem>()
        val cryptoKeys = mutableListOf<CryptoKeyItem>()
        var credentialInfo: CredentialInfoItem? = null

        result.getAsJsonArray("secrets")?.forEach { element ->
            try {
                val obj = element.asJsonObject
                secrets.add(CriticalSecretItem(
                    id = obj.get("id").asString,
                    name = obj.get("name").asString,
                    category = obj.get("category")?.asString ?: "OTHER",
                    description = obj.get("description")?.asString,
                    owner = obj.get("owner")?.asString,
                    createdAt = obj.get("created_at")?.asString ?: ""
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse critical secret", e)
            }
        }

        result.getAsJsonArray("crypto_keys")?.forEach { element ->
            try {
                val obj = element.asJsonObject
                cryptoKeys.add(CryptoKeyItem(
                    id = obj.get("id").asString,
                    label = obj.get("label").asString,
                    type = obj.get("type").asString,
                    publicKey = obj.get("public_key")?.asString,
                    derivationPath = obj.get("derivation_path")?.asString,
                    createdAt = obj.get("created_at")?.asString ?: ""
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse crypto key", e)
            }
        }

        result.getAsJsonObject("credential")?.let { cred ->
            try {
                credentialInfo = CredentialInfoItem(
                    identityFingerprint = cred.get("identity_fingerprint")?.asString ?: "",
                    vaultId = cred.get("vault_id")?.asString,
                    boundAt = cred.get("bound_at")?.asString,
                    version = cred.get("version")?.asInt ?: 0,
                    createdAt = cred.get("created_at")?.asString ?: "",
                    lastModified = cred.get("last_modified")?.asString ?: ""
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse credential info", e)
            }
        }

        return CriticalSecretsState.MetadataList(
            secrets = secrets,
            cryptoKeys = cryptoKeys,
            credentialInfo = credentialInfo
        )
    }

    /**
     * Clear all in-memory secret data when ViewModel is destroyed.
     * No local caching - everything is zeroed.
     */
    override fun onCleared() {
        countdownJob?.cancel()
        cachedMetadataList = null
        pendingRevealSecretId = null
        _state.value = CriticalSecretsState.PasswordPrompt
        super.onCleared()
    }
}
