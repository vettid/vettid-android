package com.vettid.app.features.grants

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.security.SecurePassword
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "CriticalUseApprovalVM"

/**
 * Backs the full-screen critical-secret use approval prompt.
 * Lifecycle: pending request prefills the view (operation + item +
 * context); user enters password → encryptPasswordForServer → call
 * grants.approveCriticalUse with the password fields. The vault
 * decrypts the credential blob, finds the secret, runs the op, and
 * ships the result back through the existing critical_secret.use-response
 * peer broadcast. UI dismisses on success.
 *
 * No TTL on the password gate — every approve re-prompts. That's
 * the deliberate friction for "you are about to sign / decrypt with
 * a credential-bound secret."
 */
@HiltViewModel
class CriticalUseApprovalViewModel @Inject constructor(
    private val grants: GrantsRepository,
    private val cryptoManager: CryptoManager,
    private val credentialStore: CredentialStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val requestId: String = savedStateHandle["requestId"] ?: ""
    val itemLabel: String = savedStateHandle["itemLabel"] ?: ""
    val operation: String = savedStateHandle["operation"] ?: ""
    val context: String = savedStateHandle["context"] ?: ""

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun approve(password: SecurePassword) {
        if (requestId.isEmpty()) {
            _state.value = State.Error("Missing request_id")
            return
        }
        viewModelScope.launch {
            _state.value = State.Authenticating
            try {
                val salt = credentialStore.getPasswordSaltBytes()
                    ?: return@launch fail("Password salt missing")
                val utkPool = credentialStore.getUtkPool()
                if (utkPool.isEmpty()) return@launch fail("No UTKs available")
                val utk = utkPool.first()
                val enc = cryptoManager.encryptPasswordForServer(password, salt, utk.publicKey)
                val cred = credentialStore.getEncryptedBlob()
                    ?: return@launch fail("Credential blob missing")

                val result = grants.approveCriticalUse(
                    requestId = requestId,
                    encryptedCredential = cred,
                    encryptedPasswordHash = enc.encryptedPasswordHash,
                    ephemeralPublicKey = enc.ephemeralPublicKey,
                    nonce = enc.nonce,
                    keyId = utk.keyId,
                )
                // Consume the UTK whether we succeeded or not — the
                // vault treated it as consumed on first sight, and
                // reusing it would fail anyway.
                credentialStore.removeUtk(utk.keyId)

                result
                    .onSuccess { _state.value = State.Approved }
                    .onFailure { fail(it.message ?: "Approve failed") }
            } catch (e: Exception) {
                Log.e(TAG, "approve", e)
                fail(e.message ?: "Approve failed")
            }
        }
    }

    fun deny() {
        if (requestId.isEmpty()) return
        viewModelScope.launch {
            grants.denyCriticalUse(requestId)
                .onSuccess { _state.value = State.Denied }
                .onFailure { fail(it.message ?: "Deny failed") }
        }
    }

    private fun fail(message: String) {
        _state.value = State.Error(message)
    }

    sealed class State {
        object Idle : State()
        object Authenticating : State()
        object Approved : State()
        object Denied : State()
        data class Error(val message: String) : State()
    }
}
