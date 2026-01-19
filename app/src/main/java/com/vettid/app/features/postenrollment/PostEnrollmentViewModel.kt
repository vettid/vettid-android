package com.vettid.app.features.postenrollment

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.PostEnrollmentAuthClient
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the post-enrollment authentication flow.
 *
 * Manages state transitions:
 * Initial -> PasswordEntry -> Authenticating -> Success/Error
 *
 * Uses [PostEnrollmentAuthClient] to verify the credential works after enrollment.
 */
@HiltViewModel
class PostEnrollmentViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val authClient: PostEnrollmentAuthClient,
    private val credentialStore: CredentialStore
) : ViewModel() {

    companion object {
        private const val TAG = "PostEnrollmentVM"
    }

    private val _state = MutableStateFlow<PostEnrollmentState>(PostEnrollmentState.Initial)
    val state: StateFlow<PostEnrollmentState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PostEnrollmentEffect>()
    val effects: SharedFlow<PostEnrollmentEffect> = _effects.asSharedFlow()

    // Track if we should skip to personal data or go to main
    private var hasCompletedPersonalData = false

    init {
        // Start in password entry state
        _state.value = PostEnrollmentState.PasswordEntry()
    }

    /**
     * Process events from the UI.
     */
    fun onEvent(event: PostEnrollmentEvent) {
        viewModelScope.launch {
            when (event) {
                is PostEnrollmentEvent.StartAuthentication -> startAuthentication()
                is PostEnrollmentEvent.PasswordChanged -> updatePassword(event.password)
                is PostEnrollmentEvent.TogglePasswordVisibility -> togglePasswordVisibility()
                is PostEnrollmentEvent.SubmitPassword -> submitPassword()
                is PostEnrollmentEvent.Retry -> retry()
                is PostEnrollmentEvent.Skip -> skip()
                is PostEnrollmentEvent.Continue -> continueToNext()
            }
        }
    }

    private fun startAuthentication() {
        _state.value = PostEnrollmentState.PasswordEntry()
    }

    private fun updatePassword(password: String) {
        val currentState = _state.value
        if (currentState is PostEnrollmentState.PasswordEntry) {
            _state.value = currentState.copy(password = password, error = null)
        }
    }

    private fun togglePasswordVisibility() {
        val currentState = _state.value
        if (currentState is PostEnrollmentState.PasswordEntry) {
            _state.value = currentState.copy(isPasswordVisible = !currentState.isPasswordVisible)
        }
    }

    private suspend fun submitPassword() {
        val currentState = _state.value
        if (currentState !is PostEnrollmentState.PasswordEntry) return

        val password = currentState.password
        if (password.isBlank()) {
            _state.value = currentState.copy(error = "Please enter your password")
            return
        }

        _state.value = currentState.copy(isSubmitting = true, error = null)
        _state.value = PostEnrollmentState.Authenticating(
            progress = 0.1f,
            statusMessage = "Connecting to vault..."
        )

        try {
            // Update progress as we authenticate
            _state.value = PostEnrollmentState.Authenticating(
                progress = 0.3f,
                statusMessage = "Verifying credential..."
            )

            val result = authClient.authenticate(password)

            _state.value = PostEnrollmentState.Authenticating(
                progress = 0.8f,
                statusMessage = "Processing response..."
            )

            result.fold(
                onSuccess = { authResult ->
                    Log.i(TAG, "Authentication successful for user: ${authResult.userGuid}")

                    // Store any new credentials if provided
                    if (authResult.natsCredentials != null) {
                        credentialStore.storeFullNatsCredentials(
                            credentials = authResult.natsCredentials,
                            ownerSpace = authResult.ownerSpace,
                            messageSpace = authResult.messageSpace
                        )
                        Log.d(TAG, "Updated NATS credentials from auth response")
                    }

                    _state.value = PostEnrollmentState.Authenticating(
                        progress = 1.0f,
                        statusMessage = "Credential verified!"
                    )

                    delay(500) // Brief pause to show success

                    _state.value = PostEnrollmentState.Success(
                        userGuid = authResult.userGuid,
                        message = "Your credential has been verified successfully"
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Authentication failed", error)

                    val errorMessage = when {
                        error.message?.contains("password", ignoreCase = true) == true ||
                        error.message?.contains("invalid", ignoreCase = true) == true ->
                            "Incorrect password. Please try again."
                        error.message?.contains("timeout", ignoreCase = true) == true ->
                            "Connection timed out. Please check your network and try again."
                        error.message?.contains("connect", ignoreCase = true) == true ->
                            "Could not connect to vault. Please check your network."
                        else -> error.message ?: "Authentication failed"
                    }

                    _state.value = PostEnrollmentState.Error(
                        message = errorMessage,
                        retryable = true
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Authentication error", e)
            _state.value = PostEnrollmentState.Error(
                message = "An unexpected error occurred: ${e.message}",
                retryable = true
            )
        }
    }

    private fun retry() {
        _state.value = PostEnrollmentState.PasswordEntry()
    }

    private suspend fun skip() {
        // Skip authentication and go directly to main
        // This might be used if the user doesn't want to verify now
        Log.i(TAG, "User skipped post-enrollment verification")
        _effects.emit(PostEnrollmentEffect.NavigateToMain)
    }

    private suspend fun continueToNext() {
        val currentState = _state.value
        if (currentState is PostEnrollmentState.Success) {
            // Navigate to personal data collection if not completed, otherwise main
            if (!hasCompletedPersonalData) {
                _effects.emit(PostEnrollmentEffect.NavigateToPersonalData)
            } else {
                _effects.emit(PostEnrollmentEffect.NavigateToMain)
            }
        }
    }

    /**
     * Set whether the user should skip personal data and go directly to main.
     */
    fun setSkipPersonalData(skip: Boolean) {
        hasCompletedPersonalData = skip
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            authClient.disconnect()
        }
    }
}
