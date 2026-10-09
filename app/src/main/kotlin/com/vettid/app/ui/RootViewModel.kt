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
import com.vettid.core.data.vault.ReleaseUpdateRepository
import com.vettid.core.data.vault.UpdateProgress
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the root of the UI follows: the vault phase, the app lock, the clone alarm, the account and the service pause. */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val vault: VaultRepository,
    credential: CredentialRepository,
    appLock: AppLock,
    updates: ReleaseUpdateRepository,
) : ViewModel() {
    val phase: StateFlow<AppPhase> = accounts.phase
    val lock: StateFlow<AppLockState> = appLock.state

    /** How the app lock asks (its screen's text). */
    val lockMethod: StateFlow<com.vettid.core.data.prefs.AppLockMethod> = appLock.method
    val alarm: StateFlow<CredentialAlarm?> = credential.alarm
    val account: StateFlow<AccountInfo?> = accounts.account

    /** The account portal of this build's environment (the member API origin), opened in the browser. */
    val portalUrl: String = accounts.apiOrigin

    /** The vault service is paused for maintenance (MEMBER-API 1.2.0): a banner, nothing blocked. */
    val servicePaused: StateFlow<Boolean> = accounts.servicePaused

    /** A release update under way or its outcome (ANDROID-PLAN 0.1.19): shown over every phase until it is left. */
    val updateProgress: StateFlow<UpdateProgress?> = updates.progress

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
}
