package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.altchan.SignInStatus
import com.vettid.core.data.account.EmailFormat
import com.vettid.core.data.account.SignInLink
import com.vettid.core.data.account.SignInLinkInbox
import com.vettid.core.data.policy.PasswordPolicy
import com.vettid.core.data.policy.PinPolicy
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.EnrollStep
import com.vettid.core.data.vault.FailureKind
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
    EMAIL,
    CHECK_EMAIL,
    CONFIRM_SIGN_IN,
    ACCOUNT_PIN,
    TERMS,
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

/** What the member came to do on the welcome screen; decides where onboarding goes after sign-in. */
enum class OnboardingGoal { NEW_VAULT, RECOVER, TRANSFER }

/** Immutable UI state of the onboarding flow. Secrets live here only while the flow needs them. */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val email: String = "",
    val emailInvalid: Boolean = false,
    val linkInput: String = "",
    val linkInvalid: Boolean = false,
    val link: SignInLink? = null,
    val accountPin: String = "",
    val termsUpdated: Boolean = false,
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
    val goal: OnboardingGoal = OnboardingGoal.NEW_VAULT,
) {
    val canContinueBackup: Boolean get() = backup || backupOffAcknowledged
    val progressFailed: Boolean get() = progress.any { it.second == StepState.FAILED }

    override fun toString(): String = "OnboardingUiState(step=$step, busy=$busy, error=$error)"
}

/**
 * Drives onboarding: sign-in by magic link (or a pasted link) with the
 * optional account PIN, the membership and terms checks, then the vault:
 * PIN, credential password, backup choice, enrollment with device
 * attestation, the first handshake, the credential, and the confirmation.
 * The app-level phase ([AccountRepository.phase]) decides where it starts.
 */
@Suppress("TooManyFunctions") // one action per form field and step
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val account: AccountRepository,
    private val vault: VaultRepository,
    private val inbox: SignInLinkInbox,
) : ViewModel(), OnboardingActions {
    private val state = MutableStateFlow(OnboardingUiState(devHint = account.devHint, email = account.pendingEmail.value ?: ""))
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
        val signInSteps = setOf(
            OnboardingStep.WELCOME, OnboardingStep.EMAIL, OnboardingStep.CHECK_EMAIL, OnboardingStep.CONFIRM_SIGN_IN,
            OnboardingStep.ACCOUNT_PIN,
        )
        val vaultSteps = setOf(
            OnboardingStep.PIN_CREATE,
            OnboardingStep.PIN_CONFIRM,
            OnboardingStep.PASSWORD,
            OnboardingStep.BACKUP,
            OnboardingStep.PROGRESS,
        )
        val moving = s.step == OnboardingStep.RECOVER || s.step == OnboardingStep.TRANSFER_IN
        when (phase) {
            // Signed out, or a replaced phone that erased itself: the welcome screen, nothing of before kept.
            AppPhase.SignedOut -> if (s.step !in signInSteps) {
                enrolled = false
                val pending = account.pendingEmail.value
                state.value = OnboardingUiState(
                    step = if (pending != null) OnboardingStep.CHECK_EMAIL else OnboardingStep.WELCOME,
                    devHint = account.devHint,
                    email = pending ?: "",
                )
            }
            is AppPhase.TermsRequired -> state.update { it.copy(step = OnboardingStep.TERMS, termsUpdated = phase.updated, busy = false) }
            is AppPhase.Setup -> when (phase.stage) {
                // After a recovery that deleted the vault, the recovery screen says so first.
                SetupStage.NEW_VAULT -> if (s.step !in vaultSteps && s.step != OnboardingStep.RECOVER) go(OnboardingStep.PIN_CREATE)
                SetupStage.VAULT_ELSEWHERE -> if (s.step != OnboardingStep.PROGRESS && !moving) {
                    go(
                        when (s.goal) {
                            OnboardingGoal.RECOVER -> OnboardingStep.RECOVER
                            OnboardingGoal.TRANSFER -> OnboardingStep.TRANSFER_IN
                            OnboardingGoal.NEW_VAULT -> OnboardingStep.VAULT_ELSEWHERE
                        },
                    )
                }
                SetupStage.RECOVERING -> if (s.step != OnboardingStep.RECOVER) {
                    state.update { it.copy(step = OnboardingStep.RECOVER, goal = OnboardingGoal.RECOVER, busy = false, error = null) }
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
            OnboardingStep.EMAIL -> OnboardingStep.WELCOME
            OnboardingStep.CHECK_EMAIL -> OnboardingStep.EMAIL
            OnboardingStep.CONFIRM_SIGN_IN, OnboardingStep.ACCOUNT_PIN -> OnboardingStep.CHECK_EMAIL
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
                else -> it.copy(step = to, error = null)
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
            if (e.kind == FailureKind.TERMS_REQUIRED) account.refresh()
        }
    }

    // --- sign-in ---

    override fun start() {
        state.update { it.copy(goal = OnboardingGoal.NEW_VAULT) }
        go(OnboardingStep.EMAIL)
    }

    /** Welcome: "I lost my phone": sign in, then the recovery (§11.11). */
    override fun startRecovery() {
        state.update { it.copy(goal = OnboardingGoal.RECOVER) }
        go(OnboardingStep.EMAIL)
    }

    /** Welcome: "Move from my old phone": sign in, then the direct transfer (§6.7.1). */
    override fun startTransfer() {
        state.update { it.copy(goal = OnboardingGoal.TRANSFER) }
        go(OnboardingStep.EMAIL)
    }

    /** Vault elsewhere: recover it on this phone. */
    override fun recover() {
        state.update { it.copy(goal = OnboardingGoal.RECOVER) }
        go(OnboardingStep.RECOVER)
    }

    /** Vault elsewhere: move it here from the old phone. */
    override fun transfer() {
        state.update { it.copy(goal = OnboardingGoal.TRANSFER) }
        go(OnboardingStep.TRANSFER_IN)
    }

    /** Leaves the recovery or the transfer for the choice of what to do with this phone. */
    override fun leaveMove() {
        state.update { it.copy(goal = OnboardingGoal.NEW_VAULT) }
        go(OnboardingStep.VAULT_ELSEWHERE)
        viewModelScope.launch { account.refresh() }
    }

    /** After a recovery deleted the vault: set up a new one. */
    override fun newVaultAfterMove() {
        state.update { it.copy(goal = OnboardingGoal.NEW_VAULT) }
        go(OnboardingStep.PIN_CREATE)
    }

    override fun setEmail(v: String) = state.update { it.copy(email = v.take(MAX_EMAIL), emailInvalid = false) }

    override fun submitEmail() {
        val email = state.value.email.trim()
        if (!EmailFormat.isPlausible(email)) {
            state.update { it.copy(emailInvalid = true) }
            return
        }
        viewModelScope.launch {
            attempt {
                account.startSignIn(email)
                state.update { it.copy(step = OnboardingStep.CHECK_EMAIL, linkInput = "", linkInvalid = false) }
            }
        }
    }

    override fun resendLink() {
        viewModelScope.launch { attempt { account.startSignIn(state.value.email.trim()) } }
    }

    override fun setLinkInput(v: String) = state.update { it.copy(linkInput = v, linkInvalid = false) }

    override fun submitLink() {
        val link = SignInLink.parse(state.value.linkInput, account.signInHosts)
        if (link == null) {
            state.update { it.copy(linkInvalid = true) }
            return
        }
        confirmStep(link)
    }

    private fun receiveLink(raw: String) {
        inbox.consume()
        val link = SignInLink.parse(raw, account.signInHosts)
        if (link == null) {
            state.update { it.copy(step = OnboardingStep.CHECK_EMAIL, linkInvalid = true) }
            return
        }
        if (account.phase.value != AppPhase.SignedOut) return // already signed in: ignore a stray link
        confirmStep(link)
    }

    private fun confirmStep(link: SignInLink) {
        state.update {
            val email = link.email ?: it.email.ifBlank { account.pendingEmail.value ?: "" }
            it.copy(step = OnboardingStep.CONFIRM_SIGN_IN, link = link, email = email, linkInvalid = false, error = null)
        }
    }

    /** The member confirms which account to sign in to; only now is the token sent (MEMBER-API). */
    override fun confirmSignIn() {
        val s = state.value
        val link = s.link ?: return
        viewModelScope.launch {
            attempt {
                when (account.verifySignIn(s.email, link)) {
                    SignInStatus.SIGNED_IN -> {
                        state.update { it.copy(link = null) }
                        account.refresh()
                    }
                    SignInStatus.PIN_REQUIRED -> state.update { it.copy(step = OnboardingStep.ACCOUNT_PIN, link = null, accountPin = "") }
                }
            }
        }
    }

    override fun setAccountPin(v: String) = state.update { it.copy(accountPin = v.filter { c -> c.isDigit() }.take(MAX_ACCOUNT_PIN)) }

    override fun submitAccountPin() {
        val pin = state.value.accountPin
        if (pin.length < MIN_ACCOUNT_PIN) return
        viewModelScope.launch {
            attempt {
                account.signInPin(pin)
                state.update { it.copy(accountPin = "") }
                account.refresh()
            }
        }
    }

    // --- membership ---

    override fun checkAgain() {
        viewModelScope.launch { attempt { account.refresh() } }
    }

    override fun useAnotherAccount() {
        viewModelScope.launch {
            attempt { account.signOut() }
            state.update { OnboardingUiState(devHint = account.devHint) }
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
                when (e.kind) {
                    FailureKind.VAULT_EXISTS -> state.update { it.copy(step = OnboardingStep.VAULT_ELSEWHERE, pin = "", pinConfirm = "") }
                    FailureKind.TERMS_REQUIRED -> account.refresh()
                    else -> Unit
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
        const val MIN_ACCOUNT_PIN = 4
        const val MAX_ACCOUNT_PIN = 8
    }
}
