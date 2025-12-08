package com.vettid.app.ui.recovery

import android.os.Build
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
import java.util.UUID
import javax.inject.Inject

/**
 * State for the credential recovery screen.
 */
sealed class CredentialRecoveryState {
    data object EnteringPhrase : CredentialRecoveryState()
    data object Validating : CredentialRecoveryState()
    data object Downloading : CredentialRecoveryState()
    data object Decrypting : CredentialRecoveryState()
    data object Complete : CredentialRecoveryState()
    data class Error(val message: String) : CredentialRecoveryState()
}

/**
 * ViewModel for credential recovery using recovery phrase.
 */
@HiltViewModel
class CredentialRecoveryViewModel @Inject constructor(
    private val recoveryPhraseManager: RecoveryPhraseManager,
    private val credentialBackupApiClient: CredentialBackupApiClient,
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _state = MutableStateFlow<CredentialRecoveryState>(CredentialRecoveryState.EnteringPhrase)
    val state: StateFlow<CredentialRecoveryState> = _state.asStateFlow()

    private val _enteredWords = MutableStateFlow(List(24) { "" })
    val enteredWords: StateFlow<List<String>> = _enteredWords.asStateFlow()

    private val _wordValidation = MutableStateFlow(List(24) { true })
    val wordValidation: StateFlow<List<Boolean>> = _wordValidation.asStateFlow()

    private val _canSubmit = MutableStateFlow(false)
    val canSubmit: StateFlow<Boolean> = _canSubmit.asStateFlow()

    /**
     * Set a word at a specific index.
     */
    fun setWord(index: Int, word: String) {
        if (index !in 0 until 24) return

        val newWords = _enteredWords.value.toMutableList()
        newWords[index] = word.lowercase().trim()
        _enteredWords.value = newWords

        // Validate the word
        val newValidation = _wordValidation.value.toMutableList()
        newValidation[index] = word.isBlank() || recoveryPhraseManager.isValidWord(word)
        _wordValidation.value = newValidation

        // Check if we can submit
        updateCanSubmit()
    }

    /**
     * Set all words at once (e.g., from clipboard paste).
     */
    fun setAllWords(words: List<String>) {
        if (words.size != 24) return

        val normalizedWords = words.map { it.lowercase().trim() }
        _enteredWords.value = normalizedWords

        // Validate all words
        val validation = normalizedWords.map { word ->
            word.isBlank() || recoveryPhraseManager.isValidWord(word)
        }
        _wordValidation.value = validation

        updateCanSubmit()
    }

    /**
     * Parse and set words from a space-separated string.
     */
    fun parseAndSetWords(input: String) {
        val words = input.trim().split("\\s+".toRegex())
        if (words.size == 24) {
            setAllWords(words)
        }
    }

    /**
     * Get word suggestions for autocomplete.
     */
    fun getSuggestions(prefix: String): List<String> {
        return recoveryPhraseManager.getSuggestions(prefix)
    }

    /**
     * Validate the complete phrase.
     */
    fun validatePhrase(): Boolean {
        val words = _enteredWords.value
        return recoveryPhraseManager.validatePhrase(words)
    }

    /**
     * Recover credentials using the entered phrase.
     */
    fun recoverCredentials() {
        viewModelScope.launch {
            _state.value = CredentialRecoveryState.Validating

            val words = _enteredWords.value

            // Validate the phrase
            if (!recoveryPhraseManager.validatePhrase(words)) {
                _state.value = CredentialRecoveryState.Error("Invalid recovery phrase. Please check your words.")
                return@launch
            }

            _state.value = CredentialRecoveryState.Downloading

            // Download encrypted backup
            credentialBackupApiClient.downloadCredentialBackup().fold(
                onSuccess = { encryptedBackup ->
                    _state.value = CredentialRecoveryState.Decrypting

                    try {
                        // Decrypt the backup
                        val credentialBlob = recoveryPhraseManager.decryptCredentialBackup(
                            encryptedBackup = encryptedBackup,
                            phrase = words
                        )

                        // Store the recovered credentials
                        credentialStore.storeCredentialBlob(credentialBlob)

                        // Generate a new device ID for this recovery
                        val deviceId = generateDeviceId()

                        // Notify server of recovery
                        credentialBackupApiClient.recoverCredentials(
                            encryptedBlob = credentialBlob,
                            deviceId = deviceId
                        ).fold(
                            onSuccess = {
                                _state.value = CredentialRecoveryState.Complete
                            },
                            onFailure = { error ->
                                // Recovery succeeded locally but server notification failed
                                // Still mark as complete since credentials are restored
                                _state.value = CredentialRecoveryState.Complete
                            }
                        )
                    } catch (e: Exception) {
                        _state.value = CredentialRecoveryState.Error(
                            "Failed to decrypt backup. Please verify your recovery phrase is correct."
                        )
                    }
                },
                onFailure = { error ->
                    _state.value = CredentialRecoveryState.Error(
                        error.message ?: "Failed to download backup"
                    )
                }
            )
        }
    }

    /**
     * Reset to phrase entry state.
     */
    fun reset() {
        _enteredWords.value = List(24) { "" }
        _wordValidation.value = List(24) { true }
        _canSubmit.value = false
        _state.value = CredentialRecoveryState.EnteringPhrase
    }

    /**
     * Clear all entered words.
     */
    fun clearWords() {
        _enteredWords.value = List(24) { "" }
        _wordValidation.value = List(24) { true }
        _canSubmit.value = false
    }

    private fun updateCanSubmit() {
        val words = _enteredWords.value
        val allFilled = words.all { it.isNotBlank() }
        val allValid = _wordValidation.value.all { it }
        _canSubmit.value = allFilled && allValid
    }

    private fun generateDeviceId(): String {
        return "${Build.MANUFACTURER}_${Build.MODEL}_${UUID.randomUUID()}"
    }
}
