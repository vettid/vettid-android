package com.vettid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.lock.AppLockState
import com.vettid.core.data.lock.ScreenshotAllowance
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
import com.vettid.core.data.vault.ChangeCheck
import com.vettid.core.data.vault.ChangeKind
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
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
    /** The member's profile photo (§10.8) for the account card; null for none. */
    val photo: String? = null,
    /** How the app lock asks (0.1.19): the chosen method, also while the lock is off. */
    val appLockMethod: AppLockMethod = AppLockMethod.BIOMETRICS,
    /** The phone has a screen lock: "Phone screen lock" can be chosen (read by the screen, not the ViewModel). */
    val screenLockSet: Boolean = true,
    /** A release update to approve (ANDROID-PLAN 0.1.19): the "Update available" row. */
    val update: ReleaseUpdateOffer? = null,
    /** "Allow screenshots" is on (A6 audit S1): the app is not kept out of screenshots, recordings and Recents. */
    val screenshotsAllowed: Boolean = false,
    /** A development build: the allowance does not end by itself (the row says so). */
    val screenshotsStayOn: Boolean = false,
    /** (Release R) The vault has a phone secret: Settings → Security → New phone secret is offered. */
    val phoneSecret: Boolean = false,
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
    private val screenshots: ScreenshotAllowance,
) : ViewModel() {
    private val local = MutableStateFlow(
        SettingsUiState(
            screenshotsAllowed = screenshots.allowed.value, screenshotsStayOn = screenshots.staysOn, phoneSecret = vault.usesPhoneSecret,
        ),
    )

    init {
        viewModelScope.launch { screenshots.allowed.collect { on -> local.update { it.copy(screenshotsAllowed = on) } } }
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

    /**
     * "Allow screenshots" (owner decision 2026-10-11): on after the screen's confirmation, until the vault locks or
     * an hour has passed; a local, in-memory choice that is never stored or sent to the vault.
     */
    fun setScreenshotsAllowed(on: Boolean) = if (on) screenshots.allow() else screenshots.disallow()

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
                local.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }
}

/** A screen that loads one value: loading, the value, or a failure. */
data class LoadState<T>(
    val loading: Boolean = true,
    val value: T? = null,
    val error: FailureKind? = null,
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
)

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
                LoadState(false, null, e.kind, e.shownCode)
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
                LoadState(false, null, e.kind, e.shownCode)
            }
        }
    }
}

/** Immutable UI state of the recovery screen. */
data class RecoveryUiState(
    val loading: Boolean = true,
    val recovery: RecoveryView? = null,
    val backupOff: Boolean = false,
    /** (0.34.0) The vault's release offers recovery (§11.10.9); false: recovery is hidden for this vault. */
    val offered: Boolean = true,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
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
    private val state = MutableStateFlow(RecoveryUiState(offered = vault.recoveryOffered))
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
                state.update { it.copy(loading = false, error = e.kind, errorCode = e.shownCode) }
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
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
    val done: Boolean = false,
    /**
     * (Release R, VAULT-MESSAGING 0.33.0 §11.14.9) `pin.change` is a credential operation: the password too, and the
     * phone's next secret. Its answer gives the recovery 10 new attempts.
     */
    val withPassword: Boolean = false,
    val password: String = "",
    /**
     * Opened from a notice about recovery attempts ("if that was not you"): the screen says first that changing only the
     * PIN gives whoever is guessing ten new tries at the same password (ANDROID-PLAN "Change PIN").
     */
    val fromAttempts: Boolean = false,
    /**
     * (VAULT-MESSAGING 0.44.0 §11.14.9) The change got no answer and VettID is deciding it ([ChangeCheck.Checking],
     * [ChangeCheck.Unreachable]), or decided that it did not apply ([ChangeCheck.NotApplied]). The entries are kept.
     */
    val check: ChangeCheck? = null,
) {
    /**
     * A change is being decided, or its check ended without a decision (L2: no new try that could spend an attempt with
     * the wrong secret): nothing is sent from this screen.
     */
    val checking: Boolean get() = check?.open == true || check is ChangeCheck.Ended

    override fun toString(): String = "ChangePinUiState(busy=$busy, error=$error, done=$done, withPassword=$withPassword, check=$check)"
}

/**
 * (VAULT-MESSAGING 0.44.0 §11.14.9 step 4) A refused change: the entry that was wrong is cleared, the new ones are kept
 * (a wrong PIN or password); any other refusal clears every entry.
 * [CHANGE_UNRESOLVED][FailureKind.CHANGE_UNRESOLVED] (an earlier change is undecided) keeps every entry.
 */
internal fun ChangePinUiState.afterRefusal(kind: FailureKind): ChangePinUiState = when (kind) {
    FailureKind.BAD_PIN -> copy(current = "")
    FailureKind.BAD_PASSWORD -> copy(password = "")
    // Refused with nothing sent: every entry stays.
    FailureKind.CHANGE_UNRESOLVED, FailureKind.CHANGE_CHECKING, FailureKind.CHANGE_UNDECIDED,
    FailureKind.CHANGE_UNDECIDED_PASSWORD, FailureKind.PIN_UNCERTAIN, FailureKind.CREDENTIAL_UNSETTLED,
    FailureKind.RENEW_UNCONFIRMED,
    -> this
    // Every other refusal (credential_required, "not the holder", an unmapped code): no PIN or password is kept, the new
    // PIN and its confirmation included (ANDROID-PLAN 0.1.57: the member sends again from this screen).
    else -> copy(current = "", pin = "", confirm = "", password = "")
}

/** `pin.change` (§10.6). */
@HiltViewModel
class ChangePinViewModel @Inject constructor(private val vault: VaultRepository) : ViewModel() {
    private val state = MutableStateFlow(ChangePinUiState(withPassword = vault.usesPhoneSecret))
    val uiState: StateFlow<ChangePinUiState> = state.asStateFlow()

    fun setCurrent(v: String) = state.update { it.copy(current = v.filter(Char::isDigit).take(PinPolicy.MAX_LENGTH), error = null) }

    fun setPin(v: String) = state.update { it.copy(pin = v.filter(Char::isDigit).take(PinPolicy.MAX_LENGTH), problem = null, same = false) }

    fun setConfirm(v: String) = state.update { it.copy(confirm = v.filter(Char::isDigit).take(PinPolicy.MAX_LENGTH), mismatch = false) }

    fun setPassword(v: String) = state.update { it.copy(password = v, error = null) }

    /**
     * This screen follows a change: one it sent, or one being decided when it opened. Only then is a decision shown here
     * (and acknowledged); an old decision never opens the screen as "done" (the shell's notice says it once).
     */
    private var following = false

    init {
        // §11.14.9: a PIN change whose answer was lost is decided by VettID; this screen shows where that stands.
        viewModelScope.launch {
            vault.changeCheck.collect { c -> if (c != null && c.kind == ChangeKind.PIN) onCheck(c) }
        }
    }

    private fun onCheck(c: ChangeCheck) {
        if (c.open) following = true
        // Not this screen's change, or its own answer is still on its way (handled where it returns).
        if (!following || !c.open && state.value.busy) return
        when (c) {
            is ChangeCheck.Checking, is ChangeCheck.Unreachable -> state.update { it.copy(busy = false, error = null, check = c) }
            is ChangeCheck.Applied -> state.update { ChangePinUiState(done = true, withPassword = it.withPassword) }
            // (ANDROID-PLAN 0.1.55) Moved: another phone became the holder; said here as the one-time notice.
            is ChangeCheck.NotApplied, is ChangeCheck.Ended, is ChangeCheck.Moved ->
                state.update { it.copy(busy = false, error = null, check = c) }
            is ChangeCheck.Refused -> state.update {
                it.copy(busy = false, check = null, error = c.failure, errorCode = c.shownCode).afterRefusal(c.failure)
            }
        }
        if (!c.open) {
            following = false
            vault.acknowledgeChangeCheck()
        }
    }

    /** "Lock and unlock now" (0.45.0 step 3): the unlock screen's two steps settle an uncertain PIN. */
    fun lockAndUnlock() {
        viewModelScope.launch {
            try {
                vault.lock()
            } catch (_: VaultFailure) {
                // The lock is pending and retried by the vault manager; the unlock screen follows.
            }
        }
    }

    fun submit() {
        val s = state.value
        val p = PinPolicy.check(s.pin)
        when {
            // (§11.14.9) Nothing is sent while a change is being decided.
            s.checking || s.current.isEmpty() -> return
            s.withPassword && s.password.isEmpty() -> return
            p != null -> state.update { it.copy(problem = p) }
            s.pin == s.current -> state.update { it.copy(same = true) }
            s.pin != s.confirm -> state.update { it.copy(mismatch = true, confirm = "") }
            else -> {
                state.update { it.copy(busy = true, error = null, check = null) }
                following = true
                viewModelScope.launch {
                    try {
                        if (s.withPassword) vault.changePin(s.current, s.pin, s.password) else vault.changePin(s.current, s.pin)
                        state.update { ChangePinUiState(done = true, withPassword = s.withPassword) }
                        following = false
                        vault.acknowledgeChangeCheck() // said here: not again by the shell
                    } catch (e: VaultFailure) {
                        // CHANGE_CHECKING: no answer is not a failure; the entries stay and the check's state follows.
                        if (e.kind == FailureKind.CHANGE_CHECKING) {
                            state.update { it.copy(busy = false, error = null) }
                            vault.changeCheck.value?.takeIf { it.kind == ChangeKind.PIN }?.let(::onCheck)
                        } else {
                            state.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode).afterRefusal(e.kind) }
                            following = false
                            vault.acknowledgeChangeCheck()
                        }
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
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
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
                state.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode, pin = "", password = "") }
            }
        }
    }
}
