package com.vettid.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.lock.AppLockState
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.ReleaseUpdateRepository
import com.vettid.core.data.vault.UpdateProgress
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val prefs: PreferencesRepository,
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

    /**
     * (Release R, §11.14.10) This new phone received a transfer and has not finished the move yet: asked at every start,
     * in a banner, until an unlock from storage with its own secret ("Finish the move").
     */
    val finishMovePending: StateFlow<Boolean> = vault.finishMovePending

    /** "Finish the move" is locking and unlocking the vault: the root keeps the screen that shows it. */
    val finishingMove: StateFlow<Boolean> = vault.finishingMove

    /** (Release R) This old phone took the vault back after a move that did not finish: said once, until dismissed. */
    val movedBack: StateFlow<Boolean> = vault.movedBack

    fun acknowledgeMovedBack() = vault.acknowledgeMovedBack()

    /** (Release R) 7 days after a transfer's approval the vault confirmed this phone is still its app: told once. */
    val moveNotDone: StateFlow<Boolean> = vault.moveNotDone

    fun acknowledgeMoveNotDone() = vault.acknowledgeMoveNotDone()

    /** (Release R) This old phone removed its keys at the end of a kept transfer: told once on the welcome screen. */
    val movedAway: StateFlow<Boolean> = vault.movedAway

    fun acknowledgeMovedAway() = vault.acknowledgeMovedAway()

    /** A release update under way or its outcome (ANDROID-PLAN 0.1.19): shown over every phase until it is left. */
    val updateProgress: StateFlow<UpdateProgress?> = updates.progress

    /**
     * The preferences file was corrupt and was reset (A6 audit P4): told once, in a banner, until dismissed.
     */
    val preferencesReset: StateFlow<Boolean> = prefs.preferences.map { it.wasReset }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun acknowledgePreferencesReset() {
        viewModelScope.launch { prefs.acknowledgeReset() }
    }

    /** A read of the phase is under way ("Try again" shows it; the screen used to give no sign, A6 audit E7). */
    private val retryingFlow = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = retryingFlow.asStateFlow()

    fun retry() {
        if (retryingFlow.value) return
        retryingFlow.value = true
        viewModelScope.launch {
            try {
                accounts.refresh()
            } finally {
                retryingFlow.value = false
            }
        }
    }

    /**
     * "Start over", confirmed, on the two dead ends that have one (A6 audit N6, P4): the account service says the
     * account has no vault ([AccountRepository.forgetMissingVault], which asks it again first), or this phone's
     * stored keys cannot be read ([AccountRepository.eraseThisPhone]). Never without the member's confirmation.
     */
    fun startOver() {
        if (retryingFlow.value) return
        val kind = (phase.value as? AppPhase.Unreachable)?.failure
        retryingFlow.value = true
        viewModelScope.launch {
            try {
                when (kind) {
                    FailureKind.NO_VAULT -> accounts.forgetMissingVault()
                    FailureKind.LOCAL_STATE -> accounts.eraseThisPhone()
                    else -> Unit
                }
            } catch (_: VaultFailure) {
                accounts.refresh()
            } finally {
                retryingFlow.value = false
            }
        }
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
