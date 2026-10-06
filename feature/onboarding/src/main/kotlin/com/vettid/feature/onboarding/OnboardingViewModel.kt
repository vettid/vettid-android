package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.altchan.RecoveryCode
import com.vettid.core.altchan.SetupCodes
import com.vettid.core.data.account.EmailFormat
import com.vettid.core.data.account.SetupLinkInbox
import com.vettid.core.data.policy.PasswordPolicy
import com.vettid.core.data.policy.PinPolicy
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.EnrollStep
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.SetupCodeInput
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.ui.components.StepState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The onboarding steps (ANDROID-PLAN §4 "Onboarding"). */
enum class OnboardingStep {
    WELCOME,

    /** Scan the setup QR from the account portal (VAULT-MESSAGING §11.12.1), the primary path. */
    SETUP_SCAN,

    /** Type the short code with the account's email. */
    SETUP_TYPE,

    /** The code was redeemed: "Setting up a vault for m***@example.com" (`email_hint`), before the PIN. */
    CONFIRM_ACCOUNT,
    VAULT_ELSEWHERE,
    PIN_CREATE,
    PIN_CONFIRM,
    PASSWORD,
    BACKUP,
    PROGRESS,
    DONE,

    /** Recovering the vault on this phone (§11.11): [RecoverViewModel] runs the steps. */
    RECOVER,

    /** Moving VettID here from the old phone (§6.7.1): [TransferInViewModel] runs the steps. */
    TRANSFER_IN,
}

/** Why a scanned code or an opened link was not redeemed. */
enum class ScanRefusal {
    /** Not a setup QR. */
    NOT_A_CODE,

    /** A recovery QR: it belongs to "I lost my phone". */
    RECOVERY_CODE,

    /** A setup QR (or link) made by another environment's portal ([OnboardingUiState.otherApi]). */
    OTHER_ENVIRONMENT,
}

/** Immutable UI state of the onboarding flow. Secrets live here only while the flow needs them. */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val email: String = "",
    val emailInvalid: Boolean = false,
    val codeInput: String = "",
    /** The typed code has a character a code never contains (0, 1, I, L, O) or is not 8 symbols long. */
    val codeInvalid: Boolean = false,
    val scanRefusal: ScanRefusal? = null,
    /** The other environment's `api` of a refused QR, shown so the member knows which portal made it. */
    val otherApi: String? = null,
    /** The account the redeemed code belongs to, masked (`email_hint`). */
    val emailHint: String = "",
    val pin: String = "",
    val pinConfirm: String = "",
    val pinProblem: PinPolicy.Problem? = null,
    val pinMismatch: Boolean = false,
    val password: String = "",
    val passwordConfirm: String = "",
    val passwordStrength: PasswordPolicy.Strength = PasswordPolicy.Strength.TOO_SHORT,
    val passwordProblem: PasswordPolicy.Problem? = null,
    val passwordMismatch: Boolean = false,
    val backup: Boolean = true,
    val backupOffAcknowledged: Boolean = false,
    /** Resuming after enrollment: only the credential steps remain (§3.5.7). */
    val credentialOnly: Boolean = false,
    val progress: List<Pair<EnrollStep, StepState>> = emptyList(),
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val errorCode: String? = null,
    val devHint: String? = null,
) {
    val canContinueBackup: Boolean get() = backup || backupOffAcknowledged
    val progressFailed: Boolean get() = progress.any { it.second == StepState.FAILED }

    override fun toString(): String = "OnboardingUiState(step=$step, busy=$busy, error=$error)"
}

/**
 * Drives onboarding (VAULT-MESSAGING 0.15.0 §11.12; the app never signs in): the setup code from the account
 * portal, scanned (or opened as its App Link) or typed with the account's email, redeemed with this phone's app
 * key; the account's masked email to confirm; then the vault: PIN, credential password, backup choice, enrollment
 * with device attestation, the first handshake, the credential, and the confirmation. The app-level phase
 * ([AccountRepository.phase]) decides where it starts.
 */
@Suppress("TooManyFunctions") // one action per form field and step
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val account: AccountRepository,
    private val vault: VaultRepository,
    private val inbox: SetupLinkInbox,
) : ViewModel(), OnboardingActions {
    private val state = MutableStateFlow(OnboardingUiState(devHint = account.devHint))
    val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

    /** Set once enrollment succeeded in this flow, so a retry resumes with the credential. */
    private var enrolled = false

    init {
        viewModelScope.launch { account.phase.collect { route(it) } }
        viewModelScope.launch { inbox.link.collect { raw -> if (raw != null) receiveLink(raw) } }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun route(phase: AppPhase) {
        val s = state.value
        val codeSteps = setOf(OnboardingStep.WELCOME, OnboardingStep.SETUP_SCAN, OnboardingStep.SETUP_TYPE)
        val vaultSteps = setOf(
            OnboardingStep.PIN_CREATE,
            OnboardingStep.PIN_CONFIRM,
            OnboardingStep.PASSWORD,
            OnboardingStep.BACKUP,
            OnboardingStep.PROGRESS,
        )
        val moving = s.step == OnboardingStep.RECOVER || s.step == OnboardingStep.TRANSFER_IN
        val accountSteps = setOf(OnboardingStep.VAULT_ELSEWHERE, OnboardingStep.CONFIRM_ACCOUNT)
        when (phase) {
            // Nothing set up, or a replaced phone that erased itself: the welcome screen, nothing of before kept. A
            // recovery or a transfer started from the welcome screen runs while nothing is set up yet.
            AppPhase.SignedOut -> if (s.step !in codeSteps && !moving) {
                enrolled = false
                state.value = OnboardingUiState(devHint = account.devHint)
            }
            is AppPhase.Setup -> when (phase.stage) {
                // A redeemed code: confirm the account first (also after a restart); a recovery that deleted the
                // vault says so first.
                SetupStage.NEW_VAULT -> if (s.step !in vaultSteps + accountSteps && !moving) {
                    state.update {
                        it.copy(
                            step = OnboardingStep.CONFIRM_ACCOUNT, busy = false, error = null, errorCode = null,
                            emailHint = it.emailHint.ifEmpty { account.account.value?.emailHint ?: "" },
                        )
                    }
                }
                SetupStage.VAULT_ELSEWHERE -> if (s.step != OnboardingStep.PROGRESS && !moving) go(OnboardingStep.VAULT_ELSEWHERE)
                SetupStage.RECOVERING -> if (s.step != OnboardingStep.RECOVER) {
                    state.update { it.copy(step = OnboardingStep.RECOVER, busy = false, error = null) }
                }
                SetupStage.NEEDS_CREDENTIAL -> if (s.step != OnboardingStep.PROGRESS && s.step != OnboardingStep.BACKUP) {
                    enrolled = true
                    state.update { it.copy(step = OnboardingStep.PASSWORD, credentialOnly = true, busy = false) }
                }
                // A recovery or a transfer shows its own "your vault is on this phone now".
                SetupStage.FINISHING -> if (!moving) go(OnboardingStep.DONE)
            }
            else -> Unit
        }
    }

    private fun go(step: OnboardingStep) = state.update { it.copy(step = step, busy = false, error = null, errorCode = null) }

    /** Back within the flow; false when there is nowhere to go back to (the system handles it). */
    @Suppress("ReturnCount")
    override fun back(): Boolean {
        val s = state.value
        val to = when (s.step) {
            OnboardingStep.SETUP_SCAN -> OnboardingStep.WELCOME
            OnboardingStep.SETUP_TYPE -> OnboardingStep.SETUP_SCAN
            OnboardingStep.PIN_CONFIRM -> OnboardingStep.PIN_CREATE
            OnboardingStep.PASSWORD -> if (s.credentialOnly) null else OnboardingStep.PIN_CONFIRM
            OnboardingStep.BACKUP -> OnboardingStep.PASSWORD
            else -> null
        } ?: return false
        if (s.busy) return true
        state.update {
            when (to) {
                OnboardingStep.PIN_CREATE -> it.copy(step = to, pinConfirm = "", pinMismatch = false, error = null)
                OnboardingStep.PIN_CONFIRM -> it.copy(step = to, password = "", passwordConfirm = "", passwordProblem = null, error = null)
                else -> it.copy(step = to, error = null, scanRefusal = null, codeInvalid = false)
            }
        }
        return true
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        state.update { it.copy(busy = true, error = null, errorCode = null) }
        try {
            block()
            state.update { it.copy(busy = false) }
        } catch (e: VaultFailure) {
            state.update { it.copy(busy = false, error = e.kind, errorCode = e.code) }
        }
    }

    // --- the welcome choices ---

    /** Welcome: set up a vault with the portal's setup code. */
    override fun start() = state.update { it.copy(step = OnboardingStep.SETUP_SCAN, scanRefusal = null, error = null) }

    /** Welcome: "I lost my phone": the recovery (§11.11), with the portal's recovery QR. */
    override fun startRecovery() = go(OnboardingStep.RECOVER)

    /** Welcome: "Move from my old phone": the direct transfer (§6.7.1). */
    override fun startTransfer() = go(OnboardingStep.TRANSFER_IN)

    /** Vault elsewhere: recover it on this phone. */
    override fun recover() = go(OnboardingStep.RECOVER)

    /** Vault elsewhere: move it here from the old phone. */
    override fun transfer() = go(OnboardingStep.TRANSFER_IN)

    /** Leaves the recovery or the transfer: back where it was started from. */
    override fun leaveMove() {
        go(if (account.phase.value is AppPhase.Setup) OnboardingStep.VAULT_ELSEWHERE else OnboardingStep.WELCOME)
        viewModelScope.launch { account.refresh() }
    }

    /** After a recovery deleted the vault: a new vault needs a new setup code. */
    override fun newVaultAfterMove() = start()

    // --- the setup code (§11.12.1) ---

    override fun typeCode() = state.update {
        it.copy(step = OnboardingStep.SETUP_TYPE, scanRefusal = null, codeInvalid = false, error = null)
    }

    /** A QR the camera read: only this environment's setup QR is redeemed; nothing is sent anywhere else. */
    override fun scanned(text: String) {
        val s = state.value
        if (s.busy || s.step != OnboardingStep.SETUP_SCAN) return
        when (val r = SetupCodes.parseScanned(text, account.apiOrigin)) {
            is SetupCodes.Scanned.Secret -> redeem(SetupCodeInput.Secret(r.secret))
            is SetupCodes.Scanned.OtherEnvironment -> state.update {
                it.copy(scanRefusal = ScanRefusal.OTHER_ENVIRONMENT, otherApi = r.api)
            }
            SetupCodes.Scanned.NotACode -> {
                val rc = RecoveryCode.parseScanned(text)
                state.update { it.copy(scanRefusal = if (rc != null) ScanRefusal.RECOVERY_CODE else ScanRefusal.NOT_A_CODE) }
            }
        }
    }

    /** The portal's same-device App Link: redeemed only while nothing is set up on this phone. */
    private fun receiveLink(raw: String) {
        inbox.consume()
        if (account.phase.value != AppPhase.SignedOut || state.value.busy) return
        val secret = SetupCodes.parseLink(raw, account.apiOrigin)
        if (secret == null) {
            state.update { it.copy(step = OnboardingStep.SETUP_SCAN, scanRefusal = ScanRefusal.NOT_A_CODE) }
            return
        }
        state.update { it.copy(step = OnboardingStep.SETUP_SCAN) }
        redeem(SetupCodeInput.Secret(secret))
    }

    override fun setEmail(v: String) = state.update { it.copy(email = v.take(MAX_EMAIL), emailInvalid = false) }

    override fun setCode(v: String) = state.update {
        it.copy(codeInput = v.take(MAX_CODE_INPUT), codeInvalid = SetupCodes.hasForeignCharacter(v), error = null)
    }

    override fun submitCode() {
        val s = state.value
        val email = s.email.trim()
        val code = SetupCodes.normalize(s.codeInput)
        val emailOk = EmailFormat.isPlausible(email)
        if (!emailOk || code == null) {
            state.update { it.copy(emailInvalid = !emailOk, codeInvalid = code == null) }
            return
        }
        redeem(SetupCodeInput.Typed(SetupCodes.normalizeEmail(email), code))
    }

    private fun redeem(code: SetupCodeInput) {
        viewModelScope.launch {
            attempt {
                val hint = account.redeemSetupCode(code)
                state.update { it.copy(emailHint = hint, codeInput = "", scanRefusal = null, step = OnboardingStep.CONFIRM_ACCOUNT) }
            }
        }
    }

    /** The account is the member's: on to the vault PIN. */
    override fun confirmAccount() = go(OnboardingStep.PIN_CREATE)

    /** "That is not my account" (or another code): the redeemed code is dropped; back to the welcome screen. */
    override fun useAnotherCode() {
        viewModelScope.launch {
            attempt { account.forgetSetupCode() }
            enrolled = false
            state.value = OnboardingUiState(devHint = account.devHint)
        }
    }

    /** Vault elsewhere: try enrolling anyway (a provisional vault may be replaced; the enclave decides, §11.3). */
    override fun enrollAnyway() = go(OnboardingStep.PIN_CREATE)

    // --- vault PIN ---

    override fun setPin(v: String) = state.update { it.copy(pin = v.take(PinPolicy.MAX_LENGTH), pinProblem = null) }

    override fun submitPin() {
        val p = PinPolicy.check(state.value.pin)
        if (p != null) {
            state.update { it.copy(pinProblem = p) }
            return
        }
        state.update { it.copy(step = OnboardingStep.PIN_CONFIRM, pinConfirm = "", pinMismatch = false) }
    }

    override fun setPinConfirm(v: String) = state.update { it.copy(pinConfirm = v.take(PinPolicy.MAX_LENGTH), pinMismatch = false) }

    override fun submitPinConfirm() {
        val s = state.value
        if (s.pinConfirm != s.pin) {
            state.update { it.copy(pinMismatch = true, pinConfirm = "") }
            return
        }
        state.update { it.copy(step = OnboardingStep.PASSWORD) }
    }

    // --- credential password ---

    override fun setPassword(v: String) = state.update {
        it.copy(password = v, passwordStrength = PasswordPolicy.strength(v), passwordProblem = null, passwordMismatch = false)
    }

    override fun setPasswordConfirm(v: String) = state.update { it.copy(passwordConfirm = v, passwordMismatch = false) }

    override fun submitPassword() {
        val s = state.value
        val p = PasswordPolicy.check(s.password, pin = s.pin.ifEmpty { null })
        when {
            p != null -> state.update { it.copy(passwordProblem = p) }
            s.passwordConfirm != s.password -> state.update { it.copy(passwordMismatch = true) }
            else -> state.update { it.copy(step = OnboardingStep.BACKUP) }
        }
    }

    // --- backup (§3.5.6) ---

    override fun setBackup(on: Boolean) = state.update { it.copy(backup = on, backupOffAcknowledged = if (on) false
        else it.backupOffAcknowledged) }

    override fun acknowledgeBackupOff(ack: Boolean) = state.update { it.copy(backupOffAcknowledged = ack) }

    override fun submitBackup() {
        if (!state.value.canContinueBackup) return
        run()
    }

    // --- enrollment ---

    private fun steps(credentialOnly: Boolean): List<EnrollStep> =
        if (credentialOnly) listOf(EnrollStep.CREATE_CREDENTIAL, EnrollStep.BACKUP, EnrollStep.CONFIRM) else EnrollStep.entries

    private fun mark(step: EnrollStep) = state.update { s ->
        val idx = s.progress.indexOfFirst { it.first == step }
        s.copy(progress = s.progress.mapIndexed { i, (st, _) -> st to if (i < idx) StepState.DONE else if (i == idx) StepState.ACTIVE
            else StepState.PENDING })
    }

    /** Runs (or resumes) enrollment and the credential steps. */
    override fun run() {
        val s = state.value
        val credentialOnly = s.credentialOnly || enrolled
        state.update {
            it.copy(step = OnboardingStep.PROGRESS, busy = true, error = null, errorCode = null,
                progress = steps(credentialOnly).map { st -> st to StepState.PENDING })
        }
        viewModelScope.launch {
            try {
                if (!enrolled) {
                    vault.enroll(s.pin) { mark(it) }
                    enrolled = true
                }
                vault.createCredential(s.password, s.backup) { mark(it) }
                state.update {
                    it.copy(
                        busy = false, pin = "", pinConfirm = "", password = "", passwordConfirm = "",
                        progress = it.progress.map { (st, _) -> st to StepState.DONE },
                    )
                }
                if (account.phase.value is AppPhase.Setup) go(OnboardingStep.DONE)
            } catch (e: VaultFailure) {
                state.update {
                    it.copy(
                        busy = false, error = e.kind, errorCode = e.code,
                        progress = it.progress.map { (st, ps) -> st to if (ps == StepState.ACTIVE) StepState.FAILED else ps },
                    )
                }
                if (e.kind == FailureKind.VAULT_EXISTS) {
                    state.update { it.copy(step = OnboardingStep.VAULT_ELSEWHERE, pin = "", pinConfirm = "") }
                }
            }
        }
    }

    /** Leaves the progress screen after a failure, back to the PIN (before enrollment) or the password. */
    override fun editAfterFailure() = state.update {
        it.copy(
            step = if (enrolled) OnboardingStep.PASSWORD else OnboardingStep.PIN_CREATE,
            credentialOnly = enrolled,
            error = null,
            progress = emptyList(),
        )
    }

    override fun finish() {
        viewModelScope.launch { vault.finishSetup() }
    }

    private companion object {
        const val MAX_EMAIL = 254
        const val MAX_CODE_INPUT = 16
    }
}
