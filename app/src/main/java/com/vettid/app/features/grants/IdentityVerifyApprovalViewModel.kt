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

private const val TAG = "IdentityVerifyApprovalVM"

/**
 * Backs the full-screen identity-verify approval prompt. A peer issued
 * a connection.authenticate challenge; the vault parked the request
 * and notified us. Approval requires fresh password entry every time —
 * no TTL cache for the Ed25519 identity key. The vault decrypts the
 * credential blob, signs the challenge nonce, then wipes the key from
 * memory before responding to the peer.
 */
@HiltViewModel
class IdentityVerifyApprovalViewModel @Inject constructor(
    private val grants: GrantsRepository,
    private val cryptoManager: CryptoManager,
    private val credentialStore: CredentialStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val requestId: String = savedStateHandle["requestId"] ?: ""
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

                val result = grants.approveVerify(
                    requestId = requestId,
                    encryptedCredential = cred,
                    encryptedPasswordHash = enc.encryptedPasswordHash,
                    ephemeralPublicKey = enc.ephemeralPublicKey,
                    nonce = enc.nonce,
                    keyId = utk.keyId,
                )
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
            grants.denyVerify(requestId)
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
