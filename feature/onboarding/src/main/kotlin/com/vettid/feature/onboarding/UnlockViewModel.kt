package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.ReleaseNotesRepository
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
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
    /** What's new in the offered release (ANDROID-PLAN 0.1.31); null while it loads. Never gates the unlock. */
    val notes: ReleaseNotes? = null,
    val pin: String = "",
    val busy: Boolean = false,
    val message: UnlockMessage? = null,
    /** Seconds until the enclave's backoff allows another try (§11.8). */
    val waitSeconds: Long = 0,
    /**
     * Seconds until the paused vault service asked to be tried again (`Retry-After` of `503 vault_unavailable`,
     * MEMBER-API 1.2.0). Nothing retries by itself: the member's next try (PIN or "Try again") waits for it.
     */
    val serviceWaitSeconds: Long = 0,
    val recoveryPending: Boolean = false,
    val stateRollback: Boolean = false,
    /**
     * The vault answered `signer_refused` (VAULT-MESSAGING 0.27.0 §11.7.1): its release does not accept this
     * installation of VettID. A state of its own, not a wrong PIN: no count, no wait, and the same answer
     * whatever PIN is sent. Cleared by any other answer of the vault.
     */
    val signerRefused: Boolean = false,
    /** The member opened "How to recover your vault" under that notice. */
    val signerHelp: Boolean = false,
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
    /**
     * The vault is past its owner-check deadline, as this phone last learnt (VAULT-MESSAGING §3.6.5): the
     * credential password is asked for with the PIN, and the check follows the unlock.
     */
    val checkDue: Boolean = false,
    val password: String = "",
    /** The vault locked after ten failed checks (§3.6.4). */
    val lockedByOwnerCheck: Boolean = false,
) {
    /** Whether "Try again" (after a failed release check) may be pressed now. */
    val retryAllowed: Boolean get() = serviceWaitSeconds == 0L && !loading

    /** Whether the PIN may be entered and sent now. */
    val pinAllowed: Boolean
        get() {
            val p = preflight ?: return false
            // Never a PIN while the last check of the vault's software failed (an earlier check does not count).
            if (preflightError != null) return false
            return !p.rollback && (!p.softwareUpdated || updateAcknowledged) && waitSeconds == 0L && serviceWaitSeconds == 0L && !busy
        }

    /** Whether the unlock may be sent: a PIN, and with a check due, the password too. */
    val submitAllowed: Boolean get() = pinAllowed && pin.length >= MIN_PIN_LENGTH && (!checkDue || password.isNotEmpty())

    override fun toString(): String =
        "UnlockUiState(loading=$loading, busy=$busy, message=$message, wait=$waitSeconds, serviceWait=$serviceWaitSeconds, " +
            "refused=$refused, signerRefused=$signerRefused, " +
            "notRecognised=$notRecognised, erasing=$erasing, checkDue=$checkDue)"

    private companion object {
        const val MIN_PIN_LENGTH = 4
    }
}

/** What the unlock screen can ask for. */
@Suppress("TooManyFunctions")
interface UnlockActions {
    fun retryPreflight()
    fun acknowledgeUpdate()
    fun setApproveOffer(approve: Boolean)
    fun setPin(v: String)

    /** The credential password, asked for with the PIN when the owner check is due (§3.6.5). */
    fun setPassword(v: String)
    fun submit()
    fun cancelRecoveryAndUnlock()

    /** Shows or hides the recovery and transfer help under the `signer_refused` notice. */
    fun showSignerHelp(show: Boolean)

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
@Suppress("TooManyFunctions")
@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val vault: VaultRepository,
    private val account: AccountRepository,
    ownerCheck: OwnerCheckRepository,
    private val releaseNotes: ReleaseNotesRepository,
) : ViewModel(), UnlockActions {
    private val state = MutableStateFlow(UnlockUiState(email = account.account.value?.emailHint?.takeIf { it.isNotEmpty() }))
    val uiState: StateFlow<UnlockUiState> = state.asStateFlow()
    private var ticker: Job? = null
    private var serviceTicker: Job? = null
    private var notesJob: Job? = null

    init {
        retryPreflight()
        viewModelScope.launch { vault.refusedByVault.collect { r -> state.update { it.copy(refused = r) } } }
        viewModelScope.launch {
            combine(ownerCheck.ownerCheck, ownerCheck.lockedByOwnerCheck) { v, locked -> (v?.gated(Instant.now()) == true) to locked }
                .collect { (due, locked) -> state.update { it.copy(checkDue = due || locked, lockedByOwnerCheck = locked) } }
        }
    }

    override fun retryPreflight() {
        if (state.value.serviceWaitSeconds > 0) return
        // The result of an earlier check goes too: no PIN is asked for until this one has verified the vault's software.
        state.update { it.copy(loading = true, preflightError = null, preflight = null) }
        viewModelScope.launch {
            try {
                val p = vault.preflight()
                state.update { it.copy(loading = false, preflight = p, notes = null) }
                loadNotes(p)
            } catch (e: VaultFailure) {
                state.update { it.copy(loading = false, preflightError = e.kind) }
                if (e.kind == FailureKind.SERVICE_PAUSED) startServiceWait(e.retryAfterSeconds)
            }
        }
    }

    /** What's new for the offer (ANDROID-PLAN 0.1.31): information only, it never holds the unlock up. */
    private fun loadNotes(p: PreflightInfo) {
        val offer = p.offer?.takeIf { !p.rollback } ?: return
        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            val n = releaseNotes.whatsNew(offer, p.between)
            state.update { if (it.preflight?.offer == offer) it.copy(notes = n) else it }
        }
    }

    override fun acknowledgeUpdate() = state.update { it.copy(updateAcknowledged = true) }

    override fun setApproveOffer(approve: Boolean) = state.update { it.copy(approveOffer = approve) }

    override fun setPin(v: String) = state.update { it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN), message = null) }

    override fun setPassword(v: String) = state.update { it.copy(password = v, message = null) }

    override fun submit() = attempt(cancelRecovery = false)

    override fun cancelRecoveryAndUnlock() = attempt(cancelRecovery = true)

    override fun showSignerHelp(show: Boolean) = state.update { it.copy(signerHelp = show && it.signerRefused) }

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
        if (!s.submitAllowed || s.erasing) return
        state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val offer = if (s.approveOffer) s.preflight?.offer else null
            val r = if (s.checkDue) {
                vault.unlockWithCheck(s.pin, s.password, offer, cancelRecovery)
            } else {
                vault.unlock(s.pin, offer, cancelRecovery)
            }
            // §3.6.5: the password is not kept beyond the unlock and the check that follows it.
            state.update {
                it.copy(
                    password = "", notRecognised = notRecognisedAfter(r, it.notRecognised),
                    signerRefused = signerRefusedAfter(r, it.signerRefused),
                )
            }
            when (r) {
                // §11.7.1: not a PIN failure: no "wrong PIN" text, no count, no backoff. The notice says what to do.
                UnlockAttempt.SignerRefused -> state.update { it.copy(busy = false, pin = "", message = null) }
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
                is UnlockAttempt.Failed -> {
                    state.update { it.copy(busy = false, pin = "", message = UnlockMessage.Failed(r.kind, r.code)) }
                    // A paused service (503, never proof of anything, never a wipe): wait as it asked, no automatic retry.
                    if (r.kind == FailureKind.SERVICE_PAUSED) startServiceWait(r.retryAfterSeconds)
                }
            }
        }
    }

    /**
     * An unreadable answer: the vault may not know this phone. A wrong PIN, a backoff, a success and the other
     * sealed answers show that it does; other failures (network, refusals) say nothing either way. The member API
     * refusing this phone's app key (`401`, MEMBER-API 2.0.0: another phone's key replaced it at a transfer or a
     * recovery) is no proof either, but the member may then erase VettID here too.
     */
    private fun notRecognisedAfter(r: UnlockAttempt, before: Boolean): Boolean =
        if (r is UnlockAttempt.Failed) r.code == CODE_UNREADABLE || r.kind == FailureKind.UNAUTHORIZED || before else false

    /**
     * Whether the `signer_refused` notice stays: set by that answer, cleared by any other sealed answer (the
     * vault accepted this installation's signer after all, e.g. on another release), kept through failures that
     * never reached the enclave (network, a paused service).
     */
    private fun signerRefusedAfter(r: UnlockAttempt, before: Boolean): Boolean = when (r) {
        UnlockAttempt.SignerRefused -> true
        is UnlockAttempt.Failed -> before
        else -> false
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

    /** Counts [UnlockUiState.serviceWaitSeconds] down; the member tries again when it reaches 0. */
    private fun startServiceWait(seconds: Long) {
        if (seconds <= 0) return
        serviceTicker?.cancel()
        state.update { it.copy(serviceWaitSeconds = seconds) }
        serviceTicker = viewModelScope.launch {
            while (state.value.serviceWaitSeconds > 0) {
                delay(TICK_MS)
                state.update { it.copy(serviceWaitSeconds = (it.serviceWaitSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    companion object {
        /** The unlock result this phone could not read ([com.vettid.core.data.vault.VaultFailure] code). */
        const val CODE_UNREADABLE = "unreadable_result"

        private const val MAX_PIN = 32
        private const val TICK_MS = 1000L
    }
}
