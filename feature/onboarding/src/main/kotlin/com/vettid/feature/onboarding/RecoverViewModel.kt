package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.altchan.RecoveryCode
import com.vettid.core.data.policy.PasswordPolicy
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.RecoverOutcome
import com.vettid.core.data.vault.RecoveryRegistration
import com.vettid.core.data.vault.RecoveryStage
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

/** The steps of a recovery on this phone (VAULT-MESSAGING §11.11; ANDROID-PLAN §4 "Onboarding"). */
enum class RecoverStep {
    LOADING,

    /** What is needed, and where the code comes from (the account portal; the app never signs in). */
    INTRO,
    SCAN,

    /** `vault.recovery.register` (§11.11.3). */
    REGISTERING,

    /** The PIN (§11.11.5 step 1), after the release check. */
    PIN,

    /** The credential password (step 3). */
    PASSWORD,

    /** Backup off: the credential is lost (step 4); a new credential or deleting the vault. */
    LOST,
    NEW_PASSWORD,
    DELETE,

    /** The vault is on this phone now. */
    DONE,
    DELETED,
}

/** Why the code was refused, for the member (§11.11.3 codes, the API's 409). */
enum class CodeRefusal {
    BAD_CODE,
    VOIDED,
    EXPIRED,
    TOO_EARLY,
    GONE,
    ATTESTATION,
    RETRY,
    NOT_A_CODE,

    /** A recovery QR of another environment's portal (its `api`, §11.11.2, 0.15.0). */
    OTHER_ENVIRONMENT,

    /** A recovery QR without `api` (an account site from before 0.15.0): apps of 0.15.0 require it. */
    NO_API,

    /** The member API's `409 recovery_not_available` at the claim or the register. */
    NOT_AVAILABLE,
}

/** Immutable UI state of the recovery flow. The PIN and passwords live here only while the flow needs them. */
data class RecoverUiState(
    val step: RecoverStep = RecoverStep.LOADING,
    val refusal: CodeRefusal? = null,
    /** The other environment's `api` of a refused QR. */
    val otherApi: String? = null,
    /** The account the vault belongs to, masked (`email_hint` from the claim, §11.11.7). */
    val emailHint: String = "",
    /** Wrong codes sent from this phone for the current recovery (the vault voids it at 5). */
    val wrongCodes: Int = 0,
    val preflight: PreflightInfo? = null,
    val preflightError: FailureKind? = null,
    val approveOffer: Boolean = false,
    val pin: String = "",
    val pinWrong: Boolean = false,
    val waitSeconds: Long = 0,
    val password: String = "",
    val passwordConfirm: String = "",
    val passwordStrength: PasswordPolicy.Strength = PasswordPolicy.Strength.TOO_SHORT,
    val passwordProblem: PasswordPolicy.Problem? = null,
    val passwordMismatch: Boolean = false,
    val confirming: Boolean = false,
    /** Done by `credential.reset` (backup off) rather than `credential.recover`. */
    val reset: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val errorCode: String? = null,
) {
    /** The PIN may be entered and sent now (§11.10.6: never to an older release; the enclave's backoff). */
    val pinAllowed: Boolean
        get() = preflight != null && !preflight.rollback && preflightError != FailureKind.RELEASE_ENDED && waitSeconds == 0L && !busy

    override fun toString(): String = "RecoverUiState(step=$step, busy=$busy, refusal=$refusal, error=$error)"

    companion object {
        const val MAX_WRONG_CODES = 5
    }
}

/** What the recovery screens can ask for. */
@Suppress("TooManyFunctions")
interface RecoverActions {
    fun scan()
    fun back(): Boolean
    fun scanned(text: String)
    fun retryPreflight()
    fun setApproveOffer(approve: Boolean)
    fun setPin(v: String)
    fun submitPin()
    fun setPassword(v: String)
    fun setPasswordConfirm(v: String)
    fun submitPassword()
    fun chooseNewCredential()
    fun chooseDelete()
    fun submitNewPassword()
    fun submitDelete()
    fun confirm()
    fun dismissConfirm()
    fun finish()
}

/**
 * Recovery on a new phone (VAULT-MESSAGING §11.11), as vettid-vault's
 * reference client runs it: the portal's QR is claimed with this phone's app
 * key (§11.11.7, 0.15.0: no sign-in; a typed code would lack the vault and
 * recovery ids the app can no longer look up) and registered over the alternate
 * channel with this phone's device attestation; then the PIN unlocks the
 * vault and the first handshake makes this phone a restricted app; then
 * `credential.recover` with the password hands the credential over and the
 * old app is removed. With the backup off (`credential_lost`) only a new
 * credential or deleting the vault remain (§11.11.5 step 4). Resumes where
 * it stopped after a restart ([MoveRepository.recoveryStage]).
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class RecoverViewModel @Inject constructor(
    private val move: MoveRepository,
    private val vault: VaultRepository,
    private val account: AccountRepository,
) : ViewModel(), RecoverActions {
    private val state = MutableStateFlow(RecoverUiState())
    val uiState: StateFlow<RecoverUiState> = state.asStateFlow()
    private var ticker: Job? = null
    private var countedFor: String? = null

    init {
        viewModelScope.launch {
            val stage = try {
                move.recoveryStage()
            } catch (_: VaultFailure) {
                RecoveryStage.CODE
            }
            val hint = account.account.value?.emailHint ?: ""
            state.update { it.copy(emailHint = hint) }
            when (stage) {
                RecoveryStage.CODE -> state.update { it.copy(step = RecoverStep.INTRO) }
                RecoveryStage.PIN -> toPin()
                RecoveryStage.PASSWORD -> afterUnlock()
            }
        }
    }

    // --- the code ---

    override fun scan() = state.update { it.copy(step = RecoverStep.SCAN, refusal = null, error = null) }

    /** Back within the flow; false when the member leaves it (the onboarding flow takes over). */
    override fun back(): Boolean {
        val s = state.value
        if (s.busy) return true
        return when (s.step) {
            RecoverStep.SCAN -> {
                state.update { it.copy(step = RecoverStep.INTRO, refusal = null) }
                true
            }
            RecoverStep.NEW_PASSWORD, RecoverStep.DELETE -> {
                state.update { it.copy(step = RecoverStep.LOST, pin = "", password = "", passwordConfirm = "", error = null) }
                true
            }
            else -> false
        }
    }

    /**
     * A QR the camera read: only a recovery QR of this app's environment is claimed (its `api` compared exactly with
     * the app's own member API origin, never contacted, §11.11.2).
     */
    override fun scanned(text: String) {
        val s = state.value
        if (s.busy || s.step != RecoverStep.SCAN) return
        val code = RecoveryCode.parseScanned(text)
        val api = code?.api
        when {
            code == null -> state.update { it.copy(refusal = CodeRefusal.NOT_A_CODE) }
            api == null -> state.update { it.copy(refusal = CodeRefusal.NO_API) }
            api.trimEnd('/') != account.apiOrigin -> state.update { it.copy(refusal = CodeRefusal.OTHER_ENVIRONMENT, otherApi = api) }
            else -> register(code)
        }
    }

    private fun register(code: RecoveryCode) {
        val from = state.value.step
        val recoveryId = code.recoveryId
        state.update { it.copy(step = RecoverStep.REGISTERING, busy = true, refusal = null, error = null) }
        viewModelScope.launch {
            try {
                when (val r = move.registerRecovery(code)) {
                    is RecoveryRegistration.Registered -> {
                        state.update { it.copy(busy = false, emailHint = r.emailHint) }
                        toPin()
                    }
                    is RecoveryRegistration.Refused -> refused(from, recoveryId, r.code)
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(step = from, busy = false, error = e.kind, errorCode = e.code) }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // one branch per §11.11.3 code
    private fun refused(from: RecoverStep, recoveryId: String, code: String) {
        var refusal = when (code) {
            CODE_BAD -> CodeRefusal.BAD_CODE
            CODE_EXPIRED -> CodeRefusal.EXPIRED
            CODE_EARLY -> CodeRefusal.TOO_EARLY
            CODE_ATTESTATION -> CodeRefusal.ATTESTATION
            CODE_NONE, CODE_USED -> CodeRefusal.GONE
            CODE_NOT_AVAILABLE -> CodeRefusal.NOT_AVAILABLE
            else -> CodeRefusal.RETRY
        }
        var wrong = state.value.wrongCodes
        if (refusal == CodeRefusal.BAD_CODE) {
            if (countedFor != recoveryId) wrong = 0
            countedFor = recoveryId
            wrong++
            if (wrong >= RecoverUiState.MAX_WRONG_CODES) refusal = CodeRefusal.VOIDED
        }
        state.update { it.copy(step = from, busy = false, refusal = refusal, wrongCodes = wrong) }
    }

    // --- the PIN (§11.11.5 step 1) ---

    private fun toPin() {
        state.update { it.copy(step = RecoverStep.PIN, preflight = null, preflightError = null) }
        retryPreflight()
    }

    override fun retryPreflight() {
        state.update { it.copy(busy = true, preflightError = null, error = null) }
        viewModelScope.launch {
            try {
                val p = move.recoveryPreflight()
                state.update { it.copy(busy = false, preflight = p) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, preflightError = e.kind) }
            }
        }
    }

    override fun setApproveOffer(approve: Boolean) = state.update { it.copy(approveOffer = approve) }

    override fun setPin(v: String) = state.update {
        it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN), pinWrong = false, error = null)
    }

    override fun submitPin() {
        val s = state.value
        if (!s.pinAllowed || s.pin.length < MIN_PIN) return
        state.update { it.copy(busy = true, pinWrong = false, error = null) }
        viewModelScope.launch {
            val offer = if (s.approveOffer) s.preflight?.offer else null
            when (val r = move.recoveryUnlock(s.pin, offer)) {
                UnlockAttempt.Success -> {
                    state.update { it.copy(busy = false, pin = "") }
                    afterUnlock()
                }
                is UnlockAttempt.BadPin -> {
                    state.update { it.copy(busy = false, pin = "", pinWrong = true) }
                    startBackoff(r.retryAfterSeconds)
                }
                is UnlockAttempt.Backoff -> {
                    state.update { it.copy(busy = false, pin = "") }
                    startBackoff(r.retryAfterSeconds)
                }
                // The registered key was removed: the recovery was cancelled or voided (§11.11.4).
                is UnlockAttempt.Failed -> state.update {
                    it.copy(busy = false, pin = "", error = r.kind, errorCode = r.code)
                }
                is UnlockAttempt.UpdateRefused -> state.update {
                    it.copy(busy = false, pin = "", error = FailureKind.OTHER, errorCode = r.code)
                }
                UnlockAttempt.RecoveryPending, UnlockAttempt.StateRollback ->
                    state.update { it.copy(busy = false, pin = "", error = FailureKind.OTHER) }
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

    /**
     * After the unlock (§11.11.5 step 1, 0.10.6): the password only when the vault keeps a copy of the credential
     * (`credential_backup` true) or did not say (an older vault, which answers `credential_lost` to the password);
     * with the backup off, straight to the choice of step 4 without asking for a password that cannot succeed.
     */
    private suspend fun afterUnlock() {
        val backup = try {
            move.recoveryCredentialBackup()
        } catch (_: VaultFailure) {
            null
        }
        state.update { it.copy(step = if (backup == false) RecoverStep.LOST else RecoverStep.PASSWORD) }
    }

    // --- the password (step 3) ---

    override fun setPassword(v: String) = state.update {
        it.copy(password = v, passwordStrength = PasswordPolicy.strength(v), passwordProblem = null, passwordMismatch = false, error = null)
    }

    override fun setPasswordConfirm(v: String) = state.update { it.copy(passwordConfirm = v, passwordMismatch = false) }

    override fun submitPassword() {
        val s = state.value
        if (s.password.isEmpty() || s.busy || s.waitSeconds > 0) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (move.recoverCredential(s.password)) {
                    RecoverOutcome.RECOVERED -> state.update { it.copy(step = RecoverStep.DONE, busy = false, password = "") }
                    RecoverOutcome.CREDENTIAL_LOST -> state.update { it.copy(step = RecoverStep.LOST, busy = false, password = "") }
                    RecoverOutcome.CREDENTIAL_REQUIRED -> state.update {
                        it.copy(busy = false, password = "", error = FailureKind.OTHER, errorCode = CODE_REQUIRED)
                    }
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, password = "", error = e.kind, errorCode = e.code) }
                if (e.kind == FailureKind.BACKOFF || e.kind == FailureKind.BAD_PASSWORD) startBackoff(e.retryAfterSeconds)
            }
        }
    }

    // --- backup off (step 4) ---

    override fun chooseNewCredential() = state.update {
        it.copy(step = RecoverStep.NEW_PASSWORD, password = "", passwordConfirm = "", passwordProblem = null, error = null)
    }

    override fun chooseDelete() = state.update { it.copy(step = RecoverStep.DELETE, pin = "", error = null) }

    override fun submitNewPassword() {
        val s = state.value
        val p = PasswordPolicy.check(s.password)
        when {
            p != null -> state.update { it.copy(passwordProblem = p) }
            s.passwordConfirm != s.password -> state.update { it.copy(passwordMismatch = true) }
            else -> state.update { it.copy(confirming = true) }
        }
    }

    override fun submitDelete() {
        if (state.value.pin.length < MIN_PIN) return
        state.update { it.copy(confirming = true) }
    }

    override fun dismissConfirm() = state.update { it.copy(confirming = false) }

    /** The confirmed destructive step: a new credential, or the deletion. */
    override fun confirm() {
        val s = state.value
        state.update { it.copy(confirming = false, busy = true, error = null) }
        viewModelScope.launch {
            try {
                if (s.step == RecoverStep.NEW_PASSWORD) {
                    move.resetCredential(s.password)
                    state.update { it.copy(step = RecoverStep.DONE, reset = true, busy = false, password = "", passwordConfirm = "") }
                } else {
                    move.deleteRecoveredVault(s.pin)
                    state.update { it.copy(step = RecoverStep.DELETED, busy = false, pin = "") }
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, pin = "", error = e.kind, errorCode = e.code) }
                if (e.kind == FailureKind.BACKOFF || e.kind == FailureKind.BAD_PIN) startBackoff(e.retryAfterSeconds)
            }
        }
    }

    override fun finish() {
        viewModelScope.launch { vault.finishSetup() }
    }

    companion object {
        const val CODE_BAD = "bad_code"
        const val CODE_EXPIRED = "expired"
        const val CODE_EARLY = "too_early"
        const val CODE_ATTESTATION = "attestation"
        const val CODE_NONE = "no_recovery"
        const val CODE_USED = "used"
        const val CODE_NOT_AVAILABLE = "not_available"
        const val CODE_UNKNOWN_DEVICE = "unknown_device"
        const val CODE_REQUIRED = "credential_required"
        private const val MIN_PIN = 6
        private const val MAX_PIN = 32
        private const val TICK_MS = 1000L
    }
}
