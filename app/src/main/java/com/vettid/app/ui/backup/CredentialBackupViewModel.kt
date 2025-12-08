package com.vettid.app.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.RecoveryPhraseManager
import com.vettid.app.core.network.CredentialBackupApiClient
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the credential backup screen.
 */
sealed class CredentialBackupState {
    data object Initial : CredentialBackupState()
    data object GeneratingPhrase : CredentialBackupState()
    data class ShowingPhrase(val words: List<String>) : CredentialBackupState()
    data class VerifyingPhrase(
        val words: List<String>,
        val verifyIndices: List<Int>,
        val currentVerifyIndex: Int = 0
    ) : CredentialBackupState()
    data object Uploading : CredentialBackupState()
    data object Complete : CredentialBackupState()
    data class Error(val message: String) : CredentialBackupState()
}

/**
 * ViewModel for credential backup with recovery phrase.
 */
@HiltViewModel
class CredentialBackupViewModel @Inject constructor(
    private val recoveryPhraseManager: RecoveryPhraseManager,
    private val credentialBackupApiClient: CredentialBackupApiClient,
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _state = MutableStateFlow<CredentialBackupState>(CredentialBackupState.Initial)
    val state: StateFlow<CredentialBackupState> = _state.asStateFlow()

    private var generatedPhrase: List<String> = emptyList()

    /**
     * Generate a new recovery phrase and encrypted backup.
     */
    fun generateBackup() {
        viewModelScope.launch {
            _state.value = CredentialBackupState.GeneratingPhrase

            try {
                // Generate 24-word recovery phrase
                generatedPhrase = recoveryPhraseManager.generateRecoveryPhrase()
                _state.value = CredentialBackupState.ShowingPhrase(generatedPhrase)
            } catch (e: Exception) {
                _state.value = CredentialBackupState.Error(e.message ?: "Failed to generate recovery phrase")
            }
        }
    }

    /**
     * User confirms they've written down the phrase.
     * Move to verification step.
     */
    fun confirmWrittenDown() {
        if (generatedPhrase.isEmpty()) return

        // Select 3 random word indices to verify
        val verifyIndices = (0 until 24).shuffled().take(3).sorted()

        _state.value = CredentialBackupState.VerifyingPhrase(
            words = generatedPhrase,
            verifyIndices = verifyIndices,
            currentVerifyIndex = 0
        )
    }

    /**
     * Verify a word at the current verification index.
     * Returns true if correct.
     */
    fun verifyWord(word: String): Boolean {
        val currentState = _state.value
        if (currentState !is CredentialBackupState.VerifyingPhrase) return false

        val currentIndex = currentState.verifyIndices[currentState.currentVerifyIndex]
        val expectedWord = generatedPhrase[currentIndex]

        if (word.lowercase().trim() == expectedWord.lowercase()) {
            // Correct! Move to next or complete
            if (currentState.currentVerifyIndex < currentState.verifyIndices.size - 1) {
                _state.value = currentState.copy(
                    currentVerifyIndex = currentState.currentVerifyIndex + 1
                )
            } else {
                // All words verified, complete the backup
                completeBackup()
            }
            return true
        }

        return false
    }

    /**
     * Get the current word number being verified (1-indexed for display).
     */
    fun getCurrentVerifyWordNumber(): Int {
        val currentState = _state.value
        if (currentState !is CredentialBackupState.VerifyingPhrase) return 0
        return currentState.verifyIndices[currentState.currentVerifyIndex] + 1
    }

    /**
     * Get word suggestions for autocomplete.
     */
    fun getSuggestions(prefix: String): List<String> {
        return recoveryPhraseManager.getSuggestions(prefix)
    }

    /**
     * Complete the backup by encrypting and uploading.
     */
    private fun completeBackup() {
        viewModelScope.launch {
            _state.value = CredentialBackupState.Uploading

            try {
                // Get credential blob from store
                val credentialBlob = credentialStore.getCredentialBlob()
                if (credentialBlob == null) {
                    _state.value = CredentialBackupState.Error("No credentials to backup")
                    return@launch
                }

                // Encrypt with recovery phrase
                val encryptedBackup = recoveryPhraseManager.encryptCredentialBackup(
                    credentialBlob = credentialBlob,
                    phrase = generatedPhrase
                )

                // Upload to server
                credentialBackupApiClient.createCredentialBackup(encryptedBackup).fold(
                    onSuccess = {
                        _state.value = CredentialBackupState.Complete
                    },
                    onFailure = { error ->
                        _state.value = CredentialBackupState.Error(error.message ?: "Failed to upload backup")
                    }
                )
            } catch (e: Exception) {
                _state.value = CredentialBackupState.Error(e.message ?: "Failed to create backup")
            }
        }
    }

    /**
     * Reset to initial state.
     */
    fun reset() {
        generatedPhrase = emptyList()
        _state.value = CredentialBackupState.Initial
    }
}
