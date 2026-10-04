package com.vettid.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.lock.AppLockState
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the root of the UI follows: the vault phase, the app lock, the clone alarm and the account. */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val vault: VaultRepository,
    credential: CredentialRepository,
    appLock: AppLock,
) : ViewModel() {
    val phase: StateFlow<AppPhase> = accounts.phase
    val lock: StateFlow<AppLockState> = appLock.state
    val alarm: StateFlow<CredentialAlarm?> = credential.alarm
    val account: StateFlow<AccountInfo?> = accounts.account

    fun retry() {
        viewModelScope.launch { accounts.refresh() }
    }

    fun lockVault() {
        viewModelScope.launch {
            try {
                vault.lock()
            } catch (_: VaultFailure) {
                accounts.refresh()
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { accounts.signOut() }
    }
}
