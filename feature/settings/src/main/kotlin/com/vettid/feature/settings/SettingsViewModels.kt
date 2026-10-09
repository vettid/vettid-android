package com.vettid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.lock.AppLockState
import com.vettid.core.data.policy.DeletePhrase
import com.vettid.core.data.policy.PinPolicy
import com.vettid.core.data.prefs.AppLockMethod
import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.prefs.ThemePreference
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.AttestationInfo
import com.vettid.core.data.vault.CanaryManifestRepository
import com.vettid.core.data.vault.CanaryManifestView
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.RecoveryView
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultOverview
import com.vettid.core.data.vault.ProfileRepository
import com.vettid.core.data.vault.ReleaseUpdateOffer
import com.vettid.core.data.vault.ReleaseUpdateRepository
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable UI state of the Settings screen. */
data class SettingsUiState(
    val account: AccountInfo? = null,
    val preferences: AppPreferences = AppPreferences(),
    val appLockOn: Boolean = false,
    val appLockInvalidated: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    /** The member's profile photo (§10.8) for the account card; null for none. */
    val photo: String? = null,
    /** How the app lock asks (0.1.19): the chosen method, also while the lock is off. */
    val appLockMethod: AppLockMethod = AppLockMethod.BIOMETRICS,
    /** The phone has a screen lock: "Phone screen lock" can be chosen (read by the screen, not the ViewModel). */
    val screenLockSet: Boolean = true,
    /** A release update to approve (ANDROID-PLAN 0.1.19): the "Update available" row. */
    val update: ReleaseUpdateOffer? = null,
)

/** Settings (ANDROID-PLAN §4): theme (DataStore), app lock and its timeout (D6), lock vault; the account read-only. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val account: AccountRepository,
    private val vault: VaultRepository,
    private val prefs: PreferencesRepository,
    private val appLock: AppLock,
    private val profiles: ProfileRepository,
    updates: ReleaseUpdateRepository,
) : ViewModel() {
    private val local = MutableStateFlow(SettingsUiState())

    init {
        viewModelScope.launch { updates.offer.collect { o -> local.update { it.copy(update = o) } } }
        viewModelScope.launch { appLock.method.collect { m -> local.update { it.copy(appLockMethod = m) } } }
        viewModelScope.launch { profiles.profile.collect { p -> local.update { it.copy(photo = p?.photo) } } }
        viewModelScope.launch { runCatching { profiles.refreshProfile() } }
    }
    val uiState: StateFlow<SettingsUiState> = combine(local, account.account, prefs.preferences, appLock.state, appLock.invalidated) {
        s, a, p, l, inv ->
        s.copy(account = a, preferences = p, appLockOn = l == AppLockState.UNLOCKED || l == AppLockState.LOCKED, appLockInvalidated = inv)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setTheme(t: ThemePreference) {
        viewModelScope.launch { prefs.setTheme(t) }
    }

    fun setAppLockTimeout(t: AppLockTimeout) {
        appLock.setTimeout(t)
        viewModelScope.launch { prefs.setAppLockTimeout(t) }
    }

    /** Turning the lock off needs no prompt (the app is open); turning it on goes through the activity's BiometricPrompt. */
    fun disableAppLock() {
        viewModelScope.launch { appLock.disable() }
    }

    fun acknowledgeInvalidated() = appLock.acknowledgeInvalidated()

    /** The method while the lock is off; with the lock on the activity's prompt changes it (a new key). */
    fun chooseAppLockMethod(m: AppLockMethod) {
        viewModelScope.launch { appLock.chooseMethod(m) }
    }

    fun lockVault() = act { vault.lock() }

    fun dismissError() = local.update { it.copy(error = null) }

    private fun act(block: suspend () -> Unit) {
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                local.update { it.copy(busy = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }
}

/** A screen that loads one value: loading, the value, or a failure. */
data class LoadState<T>(val loading: Boolean = true, val value: T? = null, val error: FailureKind? = null)

/** Vault status (advisory member API status + the vault's own `vault.status`). */
@HiltViewModel
class VaultStatusViewModel @Inject constructor(private val vault: VaultRepository) : ViewModel() {
    private val state = MutableStateFlow(LoadState<VaultOverview>())
    val uiState: StateFlow<LoadState<VaultOverview>> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            state.value = try {
                LoadState(false, vault.overview())
            } catch (e: VaultFailure) {
                LoadState(false, null, e.kind)
            }
        }
    }
}

/**
 * Attestation details (this phone's key, the enclave release last verified), and the canary manifest when one
 * is installed (VAULT-RELEASES §10.1 step 9: only on VettID's test phones).
 */
@HiltViewModel
class AttestationViewModel @Inject constructor(
    private val vault: VaultRepository,
    private val canaryManifests: CanaryManifestRepository,
) : ViewModel() {
    private val state = MutableStateFlow(LoadState<AttestationInfo>())
    val uiState: StateFlow<LoadState<AttestationInfo>> = state.asStateFlow()

    /** The installed canary manifest, or null (then nothing about it is shown). */
    val canary: StateFlow<CanaryManifestView?> = canaryManifests.canaryManifest

    /** Stops using the canary manifest: the published one is used again. */
    fun removeCanary() {
        viewModelScope.launch { canaryManifests.removeCanaryManifest() }
    }

    init {
        viewModelScope.launch {
            state.value = try {
                LoadState(false, vault.attestationInfo())
            } catch (e: VaultFailure) {
                LoadState(false, null, e.kind)
            }
        }
    }
}

/** Immutable UI state of the recovery screen. */
data class RecoveryUiState(
    val loading: Boolean = true,
    val recovery: RecoveryView? = null,
    val backupOff: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
)

/**
 * Recovery info (MEMBER-API "Vault recovery"): what a recovery does and one in progress (from the vault's status).
 * A recovery is started and cancelled on the account portal (MEMBER-API 2.0.0: those routes are the portal's).
 */
@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val vault: VaultRepository,
    private val credential: CredentialRepository,
) : ViewModel() {
    private val state = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val r = vault.recovery()
                val backupOff = try {
                    !credential.status().backup
                } catch (_: VaultFailure) {
                    false
                }
                state.update { it.copy(loading = false, recovery = r, backupOff = backupOff) }
            } catch (e: VaultFailure) {
                state.update { it.copy(loading = false, error = e.kind) }
            }
        }
    }
}

/** Immutable UI state of the PIN change. */
data class ChangePinUiState(
    val current: String = "",
    val pin: String = "",
    val confirm: String = "",
    val problem: PinPolicy.Problem? = null,
    val mismatch: Boolean = false,
    val same: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val done: Boolean = false,
) {
    override fun toString(): String = "ChangePinUiState(busy=$busy, error=$error, done=$done)"
}

/** `pin.change` (§10.6). */
@HiltViewModel
class ChangePinViewModel @Inject constructor(private val vault: VaultRepository) : ViewModel() {
    private val state = MutableStateFlow(ChangePinUiState())
    val uiState: StateFlow<ChangePinUiState> = state.asStateFlow()

    fun setCurrent(v: String) = state.update { it.copy(current = v.filter(Char::isDigit).take(PinPolicy.MAX_LENGTH), error = null) }

    fun setPin(v: String) = state.update { it.copy(pin = v.filter(Char::isDigit).take(PinPolicy.MAX_LENGTH), problem = null, same = false) }

    fun setConfirm(v: String) = state.update { it.copy(confirm = v.filter(Char::isDigit).take(PinPolicy.MAX_LENGTH), mismatch = false) }

    fun submit() {
        val s = state.value
        val p = PinPolicy.check(s.pin)
        when {
            s.current.isEmpty() -> return
            p != null -> state.update { it.copy(problem = p) }
            s.pin == s.current -> state.update { it.copy(same = true) }
            s.pin != s.confirm -> state.update { it.copy(mismatch = true, confirm = "") }
            else -> {
                state.update { it.copy(busy = true, error = null) }
                viewModelScope.launch {
                    try {
                        vault.changePin(s.current, s.pin)
                        state.update { ChangePinUiState(done = true) }
                    } catch (e: VaultFailure) {
                        state.update { it.copy(busy = false, error = e.kind, current = "") }
                    }
                }
            }
        }
    }
}

/** Immutable UI state of the vault deletion. */
data class DeleteVaultUiState(
    val phrase: String = "",
    val pin: String = "",
    val password: String = "",
    val acknowledged: Boolean = false,
    val confirming: Boolean = false,
    val phraseWrong: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
) {
    val ready: Boolean get() = DeletePhrase.matches(phrase) && pin.length >= MIN_PIN && password.isNotEmpty() && acknowledged

    override fun toString(): String = "DeleteVaultUiState(ready=$ready, busy=$busy, error=$error)"

    private companion object {
        const val MIN_PIN = 4
    }
}

/**
 * `vault.delete` (§12.5) by the holder: the exact phrase, the PIN and the
 * credential password, an acknowledgement, then a final confirmation.
 */
@HiltViewModel
class DeleteVaultViewModel @Inject constructor(private val vault: VaultRepository) : ViewModel() {
    private val state = MutableStateFlow(DeleteVaultUiState())
    val uiState: StateFlow<DeleteVaultUiState> = state.asStateFlow()

    fun setPhrase(v: String) = state.update { it.copy(phrase = v, phraseWrong = false) }

    fun setPin(v: String) = state.update { it.copy(pin = v.filter(Char::isDigit).take(PinPolicy.MAX_LENGTH), error = null) }

    fun setPassword(v: String) = state.update { it.copy(password = v, error = null) }

    fun setAcknowledged(v: Boolean) = state.update { it.copy(acknowledged = v) }

    /** First press: checks the phrase and asks for the final confirmation. */
    fun submit() {
        val s = state.value
        if (!DeletePhrase.matches(s.phrase)) {
            state.update { it.copy(phraseWrong = true) }
            return
        }
        if (s.ready) state.update { it.copy(confirming = true) }
    }

    fun cancelConfirm() = state.update { it.copy(confirming = false) }

    fun confirm() {
        val s = state.value
        if (!s.ready) return
        state.update { it.copy(confirming = false, busy = true, error = null) }
        viewModelScope.launch {
            try {
                vault.deleteVault(s.pin, s.password)
                state.update { DeleteVaultUiState() }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind, pin = "", password = "") }
            }
        }
    }
}
