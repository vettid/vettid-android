package com.vettid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vettid.app.R
import com.vettid.core.data.lock.AppLockState
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.format.failureText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.vettid.core.data.R as DataR

/**
 * (ANDROID-PLAN 0.1.57 (1), 0.1.58 (3)) What the root follows of "This phone can't use your vault": shown over the open
 * app only. The vault manager takes it away on a lock, a sign-out or wipe, or a deletion; one raised by the owner check
 * an unlock sends before the app opens shows once it opens.
 */
@HiltViewModel
class CredentialRequiredViewModel @Inject constructor(
    accounts: AccountRepository,
    private val vault: VaultRepository,
) : ViewModel() {
    val shown: StateFlow<Boolean> = combine(vault.credentialRequired, accounts.phase) { r, p ->
        r != null && p is AppPhase.Unlocked
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val retryingFlow = MutableStateFlow(false)

    /** **Try again** is sending the request again. */
    val retrying: StateFlow<Boolean> = retryingFlow.asStateFlow()

    /**
     * **Try again**: the screen closes, back to the screen that sent the request (its PIN and password fields empty: the
     * member sends again from there); an idempotent read is sent again, and a new `credential_required` shows the screen
     * again.
     */
    fun tryAgain() {
        if (retryingFlow.value) return
        retryingFlow.value = true
        viewModelScope.launch {
            try {
                vault.retryCredentialRequired()
            } finally {
                retryingFlow.value = false
            }
        }
    }

    /** **Lock vault**: `vault.lock` (allowed without a holder); the unlock screen follows. Nothing is erased. */
    fun lockVault() {
        vault.clearCredentialRequired()
        viewModelScope.launch {
            try {
                vault.lock()
            } catch (_: VaultFailure) {
                // The lock is pending and retried by the vault manager; the unlock screen follows.
            }
        }
    }
}

/** The screen, over the open app, never over the app lock ([lock]) or before it is read. */
@Composable
internal fun CredentialRequiredOverlay(lock: AppLockState, viewModel: CredentialRequiredViewModel = hiltViewModel()) {
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val retrying by viewModel.retrying.collectAsStateWithLifecycle()
    if (!shown || lock == AppLockState.LOCKED || lock == AppLockState.PENDING) return
    CredentialRequiredScreen(busy = retrying, onTryAgain = viewModel::tryAgain, onLockVault = viewModel::lockVault)
}

/**
 * (ANDROID-PLAN 0.1.57 (1), 0.1.58 (3)) "This phone can't use your vault": the vault answered `credential_required`
 * (no credential, or `{"reason": "no_holder"}`) before testing any PIN or password. A full screen over the open app,
 * from whatever screen sent the request. Nothing is erased. **Try again** and **Lock vault**
 * ([CredentialRequiredViewModel]). The body's "one to three days" is computed on a vault with a time scale
 * ([failureText]).
 */
@Composable
internal fun CredentialRequiredScreen(busy: Boolean, onTryAgain: () -> Unit, onLockVault: () -> Unit) {
    FormScaffold(
        title = stringResource(DataR.string.vault_credential_required_title),
        body = failureText(DataR.string.vault_credential_required_body),
        primaryLabel = stringResource(R.string.root_retry),
        onPrimary = onTryAgain,
        busy = busy,
        secondaryLabel = stringResource(R.string.account_lock_vault),
        onSecondary = onLockVault,
        modifier = Modifier.testTag("credential_required"),
    ) {}
}
