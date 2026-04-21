package com.vettid.app.features.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.network.VaultServiceClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lives only to warm the HTTP connection pool to the backend the
 * first time the Welcome screen is shown, so enrollment's first
 * HTTP request reuses an already-handshaked TLS connection instead
 * of paying the DNS + TLS round-trip cost.
 *
 * Fire-and-forget; no state exposed.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val vaultServiceClient: VaultServiceClient,
) : ViewModel() {
    init {
        viewModelScope.launch(Dispatchers.IO) {
            vaultServiceClient.warmUp()
        }
    }
}
