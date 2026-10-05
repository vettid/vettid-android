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
    /**
     * The last unlock's result was unreadable, as a phone the vault no longer knows sees it (§11.4: random
     * bytes): "Erase VettID from this phone" is offered. Cleared by any answer that shows the vault knows
     * this phone (a sealed result); a network failure leaves it as it was.
     */
    val notRecognised: Boolean = false,
    /**
     * The open app was sent here because the relay kept refusing this phone's messages to the vault
     * ([VaultRepository.refusedByVault]): a phone replaced while the vault stayed unlocked on the new one never
     * sees this screen otherwise. Not proof: the PIN asks the enclave, and an unreadable result then sets
     * [notRecognised]. Nothing is erased without the member's confirmation.
     */
    val refused: Boolean = false,
    /** The erase confirmation dialog is open. */
    val eraseConfirm: Boolean = false,
    /** The erase runs. */
    val erasing: Boolean = false,
) {
    /** Whether the PIN may be entered and sent now. */
    val pinAllowed: Boolean
        get() {
            val p = preflight ?: return false
            return !p.rollback && (!p.softwareUpdated || updateAcknowledged) && waitSeconds == 0L && !busy
        }

    override fun toString(): String =
        "UnlockUiState(loading=$loading, busy=$busy, message=$message, wait=$waitSeconds, refused=$refused, " +
            "notRecognised=$notRecognised, erasing=$erasing)"
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

    /** Opens the "Erase VettID from this phone" confirmation (only when [UnlockUiState.notRecognised]). */
    fun askErase()

    /** Closes it; nothing is erased. */
    fun dismissErase()

    /** The member confirmed: erases this phone ([AccountRepository.eraseThisPhone]), then the welcome screen. */
    fun confirmErase()
}

/**
 * The vault unlock (§11.4): first the release check (§11.10.6: refuse an
 * older release, announce a newer one before the PIN, offer the newest
 * active release), then the PIN, the enclave's answer and its backoff, the
 * recovery-pending refusal (§11.11.4) and the state-rollback warning. An
 * unlock the vault did not recognise (an unreadable result: a phone replaced
 * while it was offline longer than the relay keeps its `device.unlinked`)
 * offers "Erase VettID from this phone" (owner decision, 2026-10-05). So does
 * one after the open app was sent here because the relay kept refusing this
 * phone's messages to the vault ([UnlockUiState.refused]); the refusals alone
 * offer nothing and never erase.
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
        viewModelScope.launch { vault.refusedByVault.collect { r -> state.update { it.copy(refused = r) } } }
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

    override fun askErase() = state.update { if (it.notRecognised && !it.erasing) it.copy(eraseConfirm = true) else it }

    override fun dismissErase() = state.update { it.copy(eraseConfirm = false) }

    override fun confirmErase() {
        val s = state.value
        if (!s.eraseConfirm || !s.notRecognised || s.erasing) return
        state.update { it.copy(eraseConfirm = false, erasing = true, pin = "") }
        // The phase becomes SignedOut at the end: the root shows the welcome screen and drops this screen.
        viewModelScope.launch { account.eraseThisPhone() }
    }

    private fun attempt(cancelRecovery: Boolean) {
        val s = state.value
        if (!s.pinAllowed || s.pin.length < MIN_PIN || s.erasing) return
        state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val offer = if (s.approveOffer) s.preflight?.offer else null
            val r = vault.unlock(s.pin, offer, cancelRecovery)
            state.update { it.copy(notRecognised = notRecognisedAfter(r, it.notRecognised)) }
            when (r) {
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

    /**
     * An unreadable answer: the vault may not know this phone. A wrong PIN, a backoff, a success and the other
     * sealed answers show that it does; other failures (network, refusals) say nothing either way.
     */
    private fun notRecognisedAfter(r: UnlockAttempt, before: Boolean): Boolean =
        if (r is UnlockAttempt.Failed) r.code == CODE_UNREADABLE || before else false

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

    companion object {
        /** The unlock result this phone could not read ([com.vettid.core.data.vault.VaultFailure] code). */
        const val CODE_UNREADABLE = "unreadable_result"

        private const val MIN_PIN = 4
        private const val MAX_PIN = 32
        private const val TICK_MS = 1000L
    }
}
