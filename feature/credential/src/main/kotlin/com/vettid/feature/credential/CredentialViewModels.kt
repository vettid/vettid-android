package com.vettid.feature.credential

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.policy.PasswordPolicy
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.CredentialStatus
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** A one-off confirmation shown after an action. */
enum class CredentialNotice { BACKUP_ON, BACKUP_OFF, PASSWORD_CHANGED, ROTATED }

/** Immutable UI state of the Credential screen. */
data class CredentialUiState(
    val loading: Boolean = true,
    val status: CredentialStatus? = null,
    val alarm: CredentialAlarm? = null,
    val windowUntil: Instant? = null,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val notice: CredentialNotice? = null,
    /** The unlock-window password dialog. */
    val windowDialog: Boolean = false,
    val windowPassword: String = "",
    val windowError: FailureKind? = null,
) {
    override fun toString(): String = "CredentialUiState(loading=$loading, busy=$busy, error=$error, alarm=${alarm?.state})"
}

/**
 * The Credential screen (ANDROID-PLAN §4): status, the unlock window
 * (§3.5.3), the window length setting, the sealed backup (§3.5.6), and the
 * clone alarm (§3.5.9), which links to [AlarmViewModel].
 */
@HiltViewModel
class CredentialViewModel @Inject constructor(private val repo: CredentialRepository) : ViewModel() {
    private val local = MutableStateFlow(CredentialUiState())
    val uiState: StateFlow<CredentialUiState> = combine(local, repo.alarm, repo.unlockWindow) { s, a, w ->
        s.copy(alarm = a, windowUntil = w)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CredentialUiState())

    init {
        refresh()
    }

    fun refresh() {
        local.update { it.copy(loading = it.status == null, error = null) }
        viewModelScope.launch {
            try {
                val st = repo.status()
                local.update { it.copy(loading = false, status = st) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind) }
            }
        }
    }

    fun showWindowDialog(show: Boolean) = local.update { it.copy(windowDialog = show, windowPassword = "", windowError = null) }

    fun setWindowPassword(v: String) = local.update { it.copy(windowPassword = v, windowError = null) }

    fun openWindow() {
        val pw = local.value.windowPassword
        if (pw.isEmpty()) return
        local.update { it.copy(busy = true, windowError = null) }
        viewModelScope.launch {
            try {
                repo.openUnlockWindow(pw)
                local.update { it.copy(busy = false, windowDialog = false, windowPassword = "") }
            } catch (e: VaultFailure) {
                local.update { it.copy(busy = false, windowError = e.kind, windowPassword = "") }
            }
        }
    }

    fun closeWindow() = act { repo.closeUnlockWindow() }

    fun setTtl(seconds: Int) = act {
        repo.setUnlockTtl(seconds)
        local.update { s -> s.copy(status = s.status?.copy(unlockTtlSeconds = seconds)) }
    }

    /** Turning the backup off is confirmed in the UI first (§3.5.6: warn and ask). */
    fun setBackup(on: Boolean) = act {
        repo.setBackup(on)
        local.update { s -> s.copy(status = s.status?.copy(backup = on), notice = if (on) CredentialNotice.BACKUP_ON
            else CredentialNotice.BACKUP_OFF) }
    }

    fun dismissNotice() = local.update { it.copy(notice = null, error = null) }

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

/** Immutable UI state of the password change. */
data class ChangePasswordUiState(
    val current: String = "",
    val password: String = "",
    val confirm: String = "",
    val strength: PasswordPolicy.Strength = PasswordPolicy.Strength.TOO_SHORT,
    val problem: PasswordPolicy.Problem? = null,
    val mismatch: Boolean = false,
    val same: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val done: Boolean = false,
) {
    override fun toString(): String = "ChangePasswordUiState(busy=$busy, error=$error, done=$done)"
}

/** `credential.password.change` (§10.6): the current password, a new one with the strength rules. */
@HiltViewModel
class ChangePasswordViewModel @Inject constructor(private val repo: CredentialRepository) : ViewModel() {
    private val state = MutableStateFlow(ChangePasswordUiState())
    val uiState: StateFlow<ChangePasswordUiState> = state.asStateFlow()

    fun setCurrent(v: String) = state.update { it.copy(current = v, error = null) }

    fun setPassword(v: String) = state.update { it.copy(password = v, strength = PasswordPolicy.strength(v), problem = null, mismatch =
        false, same = false) }

    fun setConfirm(v: String) = state.update { it.copy(confirm = v, mismatch = false) }

    fun submit() {
        val s = state.value
        val p = PasswordPolicy.check(s.password)
        when {
            s.current.isEmpty() -> return
            p != null -> state.update { it.copy(problem = p) }
            s.password == s.current -> state.update { it.copy(same = true) }
            s.confirm != s.password -> state.update { it.copy(mismatch = true) }
            else -> {
                state.update { it.copy(busy = true, error = null) }
                viewModelScope.launch {
                    try {
                        repo.changePassword(s.current, s.password)
                        state.update { ChangePasswordUiState(done = true) }
                    } catch (e: VaultFailure) {
                        state.update { it.copy(busy = false, error = e.kind, current = "") }
                    }
                }
            }
        }
    }
}

/** Immutable UI state of a password-only credential action (rotation). */
data class RotateUiState(
    val password: String = "",
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val done: Boolean = false,
) {
    override fun toString(): String = "RotateUiState(busy=$busy, error=$error, done=$done)"
}

/** `credential.rotate` (§3.5.5): a new credential key, every critical item re-keyed, the vault's ik and kem rotated. */
@HiltViewModel
class RotateViewModel @Inject constructor(private val repo: CredentialRepository) : ViewModel() {
    private val state = MutableStateFlow(RotateUiState())
    val uiState: StateFlow<RotateUiState> = state.asStateFlow()

    fun setPassword(v: String) = state.update { it.copy(password = v, error = null) }

    fun submit() {
        val pw = state.value.password
        if (pw.isEmpty()) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                repo.rotate(pw)
                state.update { RotateUiState(done = true) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind, password = "") }
            }
        }
    }
}

/** The steps of answering a clone alarm (§3.5.9). */
enum class AlarmStep { ASK, CONFIRM_MINE, CONFIRM_NOT_MINE, ROTATE, RESOLVED }

/** Immutable UI state of the clone-alarm screen. */
data class AlarmUiState(
    val alarm: CredentialAlarm? = null,
    val step: AlarmStep = AlarmStep.ASK,
    val notMine: Boolean = false,
    val password: String = "",
    val busy: Boolean = false,
    val error: FailureKind? = null,
) {
    override fun toString(): String = "AlarmUiState(step=$step, busy=$busy, error=$error)"
}

/**
 * The clone alarm (§3.5.9): "that was me" or "not me" (both move the alarm
 * to `rotation_required`), then the forced rotation with the password, which
 * resolves it. On "not me" the app suggests changing the password and the PIN.
 */
@HiltViewModel
class AlarmViewModel @Inject constructor(private val repo: CredentialRepository) : ViewModel() {
    private val state = MutableStateFlow(AlarmUiState(alarm = repo.alarm.value, step = stepFor(repo.alarm.value)))
    val uiState: StateFlow<AlarmUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.alarm.collect { a ->
                state.update { s ->
                    val step = when {
                        a == null && s.alarm != null -> AlarmStep.RESOLVED
                        a == null -> s.step
                        a.rotationRequired && s.step != AlarmStep.RESOLVED -> AlarmStep.ROTATE
                        else -> s.step
                    }
                    s.copy(alarm = a ?: s.alarm, step = step)
                }
            }
        }
    }

    private fun stepFor(a: CredentialAlarm?): AlarmStep = when {
        a == null -> AlarmStep.RESOLVED
        a.rotationRequired -> AlarmStep.ROTATE
        else -> AlarmStep.ASK
    }

    fun answer(mine: Boolean) = state.update { it.copy(step = if (mine) AlarmStep.CONFIRM_MINE else AlarmStep.CONFIRM_NOT_MINE, notMine =
        !mine) }

    fun cancelAnswer() = state.update { it.copy(step = AlarmStep.ASK) }

    fun confirm() {
        val mine = state.value.step == AlarmStep.CONFIRM_MINE
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                repo.confirmAlarm(mine)
                state.update { it.copy(busy = false, step = AlarmStep.ROTATE) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind, step = AlarmStep.ASK) }
            }
        }
    }

    fun setPassword(v: String) = state.update { it.copy(password = v, error = null) }

    fun rotate() {
        val pw = state.value.password
        if (pw.isEmpty()) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                repo.rotate(pw)
                state.update { it.copy(busy = false, password = "", step = AlarmStep.RESOLVED) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind, password = "") }
            }
        }
    }
}
