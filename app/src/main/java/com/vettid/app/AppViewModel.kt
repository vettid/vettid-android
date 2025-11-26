package com.vettid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppState(
    val hasCredential: Boolean = false,
    val isAuthenticated: Boolean = false,
    val vaultStatus: VaultStatus? = null
)

enum class VaultStatus {
    PENDING_ENROLLMENT,
    PROVISIONING,
    RUNNING,
    STOPPED,
    TERMINATED
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    init {
        refreshCredentialStatus()
    }

    fun refreshCredentialStatus() {
        viewModelScope.launch {
            val hasCredential = credentialStore.hasStoredCredential()
            _appState.update { it.copy(hasCredential = hasCredential) }
        }
    }

    fun setAuthenticated(authenticated: Boolean) {
        _appState.update { it.copy(isAuthenticated = authenticated) }
    }
}
