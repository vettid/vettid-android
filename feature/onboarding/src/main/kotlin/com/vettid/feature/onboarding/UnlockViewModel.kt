package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A message under the PIN field. */
sealed interface UnlockMessage {
    data object BadPin : UnlockMessage

    data class UpdateRefused(val code: String) : UnlockMessage

    data class Failed(val kind: FailureKind, val code: String?) : UnlockMessage
}

/** Immutable UI state of the unlock screen. */
data class UnlockUiState(
    val loading: Boolean = true,
    val email: String? = null,
    val preflight: PreflightInfo? = null,
    val preflightError: FailureKind? = null,
    /** The member read the "vault software was updated" notice (§11.10.6). */
    val updateAcknowledged: Boolean = false,
    /** The member approves the offered release with this unlock (§11.10.3). */
    val approveOffer: Boolean = false,
    val pin: String = "",
    val busy: Boolean = false,
    val message: UnlockMessage? = null,
    /** Seconds until the enclave's backoff allows another try (§11.8). */
    val waitSeconds: Long = 0,
    val recoveryPending: Boolean = false,
    val stateRollback: Boolean = false,
) {
    /** Whether the PIN may be entered and sent now. */
    val pinAllowed: Boolean
        get() {
            val p = preflight ?: return false
            return !p.rollback && (!p.softwareUpdated || updateAcknowledged) && waitSeconds == 0L && !busy
        }

    override fun toString(): String = "UnlockUiState(loading=$loading, busy=$busy, message=$message, wait=$waitSeconds)"
}

/** What the unlock screen can ask for. */
interface UnlockActions {
    fun retryPreflight()
    fun acknowledgeUpdate()
    fun setApproveOffer(approve: Boolean)
    fun setPin(v: String)
    fun submit()
    fun cancelRecoveryAndUnlock()
    fun signOut()
}

/**
 * The vault unlock (§11.4): first the release check (§11.10.6: refuse an
 * older release, announce a newer one before the PIN, offer the newest
 * active release), then the PIN, the enclave's answer and its backoff, the
 * recovery-pending refusal (§11.11.4) and the state-rollback warning.
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val vault: VaultRepository,
    private val account: AccountRepository,
) : ViewModel(), UnlockActions {
    private val state = MutableStateFlow(UnlockUiState(email = account.account.value?.email))
    val uiState: StateFlow<UnlockUiState> = state.asStateFlow()
    private var ticker: Job? = null

    init {
        retryPreflight()
    }

    override fun retryPreflight() {
        state.update { it.copy(loading = true, preflightError = null) }
        viewModelScope.launch {
            try {
                val p = vault.preflight()
                state.update { it.copy(loading = false, preflight = p) }
            } catch (e: VaultFailure) {
                state.update { it.copy(loading = false, preflightError = e.kind) }
            }
        }
    }

    override fun acknowledgeUpdate() = state.update { it.copy(updateAcknowledged = true) }

    override fun setApproveOffer(approve: Boolean) = state.update { it.copy(approveOffer = approve) }

    override fun setPin(v: String) = state.update { it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN), message = null) }

    override fun submit() = attempt(cancelRecovery = false)

    override fun cancelRecoveryAndUnlock() = attempt(cancelRecovery = true)

    override fun signOut() {
        viewModelScope.launch { account.signOut() }
    }

    private fun attempt(cancelRecovery: Boolean) {
        val s = state.value
        if (!s.pinAllowed || s.pin.length < MIN_PIN) return
        state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val offer = if (s.approveOffer) s.preflight?.offer else null
            when (val r = vault.unlock(s.pin, offer, cancelRecovery)) {
                UnlockAttempt.Success -> state.update { it.copy(busy = false, pin = "", recoveryPending = false) }
                is UnlockAttempt.BadPin -> {
                    state.update { it.copy(busy = false, pin = "", message = UnlockMessage.BadPin) }
                    startBackoff(r.retryAfterSeconds)
                }
                is UnlockAttempt.Backoff -> {
                    state.update { it.copy(busy = false, pin = "") }
                    startBackoff(r.retryAfterSeconds)
                }
                // The PIN stays in memory so that "cancel the recovery and unlock" can resend it.
                UnlockAttempt.RecoveryPending -> state.update { it.copy(busy = false, recoveryPending = true) }
                UnlockAttempt.StateRollback -> state.update { it.copy(busy = false, pin = "", stateRollback = true) }
                is UnlockAttempt.UpdateRefused -> state.update { it.copy(busy = false, pin = "", message =
                    UnlockMessage.UpdateRefused(r.code)) }
                is UnlockAttempt.Failed -> state.update { it.copy(busy = false, pin = "", message = UnlockMessage.Failed(r.kind, r.code)) }
            }
        }
    }

    private fun startBackoff(seconds: Long) {
        if (seconds <= 0) return
        ticker?.cancel()
        state.update { it.copy(waitSeconds = seconds) }
        ticker = viewModelScope.launch {
            while (state.value.waitSeconds > 0) {
                delay(TICK_MS)
                state.update { it.copy(waitSeconds = (it.waitSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    private companion object {
        const val MIN_PIN = 4
        const val MAX_PIN = 32
        const val TICK_MS = 1000L
    }
}
