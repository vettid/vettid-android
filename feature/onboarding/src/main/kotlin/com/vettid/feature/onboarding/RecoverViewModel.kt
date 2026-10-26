package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.altchan.RecoveryCode
import com.vettid.core.altchan.RecoveryCodes
import com.vettid.core.altchan.TypedRecoveryCode
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.CouldNotTell
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.ReleaseNotesRepository
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
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject

/** The steps of a recovery on this phone (VAULT-MESSAGING §11.11; ANDROID-PLAN §4 "Onboarding"). */
enum class RecoverStep {
    LOADING,

    /** What is needed, and where the code comes from (the account portal; the app never signs in). */
    INTRO,
    SCAN,

    /** The code typed instead of scanned (VAULT-MESSAGING 0.25.0 §11.11.10; ANDROID-PLAN 0.1.33). */
    TYPE,

    /** `vault.recovery.register` (§11.11.3). */
    REGISTERING,

    /** The PIN (§11.11.5 step 1), after the release check. */
    PIN,

    /** The credential password (step 3). */
    PASSWORD,

    /**
     * The vault keeps no backup copy of its credential (`no_backup`, `409 recovery_unavailable`; VAULT-MESSAGING
     * 0.16.0): it cannot be recovered, only deleted on the account site and replaced by a new one.
     */
    NO_BACKUP,

    /**
     * The vault no longer takes this phone for the recovery it registered (A6 audit E1): the recovery was cancelled,
     * expired, was completed elsewhere or replaced by a newer one (§11.11.4), or the vault is gone. Reached from the
     * PIN step by itself; offers "Start over" and another try.
     */
    ENDED,

    /** The vault is connected to the credential on this phone now. */
    DONE,
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

    /** (ANDROID-PLAN 0.1.80) A recovery code for another vault than the one of this phone's earlier open; nothing was registered. */
    OTHER_VAULT,

    /** A recovery QR without `api` (an account site from before 0.15.0): apps of 0.15.0 require it. */
    NO_API,

    /** The member API's `409 recovery_not_available` at the claim or the register. */
    NOT_AVAILABLE,

    /**
     * (VAULT-MESSAGING 0.34.0 §11.10.9) The vault's release implements release R without `recovery_offered`: nothing is
     * sent ("This vault's release does not offer recovery").
     */
    NOT_OFFERED,
}

/**
 * Why the typed entry is not (yet) a code this app sends (§11.11.10 steps 1 and 2). None of these is a wrong code:
 * nothing reaches the member API or the vault, and nothing is counted.
 */
enum class TypedProblem {
    /** A `U` was typed: the alphabet has none, and it is never guessed to be a `V`. */
    LETTER_U,

    /** A character that is neither a digit nor a letter. */
    OTHER_CHARACTER,

    /** 48 characters whose check characters do not match this app's account site: a typing mistake, or another site's code. */
    BAD_CHECK,

    /** Exactly the 32 characters an account page showed before MEMBER-API 2.4.0. */
    SHORT_CODE,

    /** Fewer than 12 groups. */
    INCOMPLETE,
}

/** Immutable UI state of the recovery flow. The PIN and passwords live here only while the flow needs them. */
data class RecoverUiState(
    val step: RecoverStep = RecoverStep.LOADING,
    /** (Release R) This phone holds a phone secret for the vault: the recovery opens with `vault.recovery.open`. */
    val phoneSecret: Boolean = false,
    val refusal: CodeRefusal? = null,
    /** The other environment's `api` of a refused QR. */
    val otherApi: String? = null,
    /** The account the vault belongs to, masked (`email_hint` from the claim, §11.11.7). */
    val emailHint: String = "",
    /**
     * The typed entry in canonical form (at most 48 characters). Held in memory only: never in saved state, on disk,
     * in a log or on the clipboard, and left out of [toString].
     */
    val typed: String = "",
    val typedProblem: TypedProblem? = null,
    /** The group (1 to 12) a refused character would have gone into, for [TypedProblem.LETTER_U] and [TypedProblem.OTHER_CHARACTER]. */
    val typedGroup: Int = 0,
    /** The host of this app's account site, for [TypedProblem.BAD_CHECK]'s text. */
    val accountHost: String = "",
    /** Seconds until the member API takes another claim from this network (`429`'s `Retry-After`); 0 when unknown or over. */
    val retrySeconds: Long = 0,
    /** Wrong codes sent from this phone for the current recovery (the vault voids it at 5). */
    val wrongCodes: Int = 0,
    val preflight: PreflightInfo? = null,
    val preflightError: FailureKind? = null,
    val approveOffer: Boolean = false,
    /** What's new in the offered release (ANDROID-PLAN 0.1.31); null while it loads. Never gates the PIN. */
    val notes: ReleaseNotes? = null,
    val pin: String = "",
    val pinWrong: Boolean = false,
    val waitSeconds: Long = 0,
    val password: String = "",
    /** The account site's "Delete my vault and start over" page (MEMBER-API 2.1.0), for [RecoverStep.NO_BACKUP]. */
    val startOverUrl: String = "",
    /** "Start over" was asked for on the PIN, password or ended step: the confirmation shows. */
    val confirmStartOver: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorShown: String? = null,
    val errorCode: String? = null,
    /**
     * (ANDROID-PLAN 0.1.78) An open's answer is unknown: K is never forgotten and "Start over" is not offered
     * ([MoveRepository.recoveryUndecided]).
     */
    val undecided: Boolean = false,
    /** (0.1.78) What the look at status with K found on [RecoverStep.ENDED]; null while it runs or when none applies. */
    val couldNotTell: CouldNotTell? = null,
) {
    /** The PIN may be entered and sent now (§11.10.6: never to an older release; the enclave's backoff). */
    val pinAllowed: Boolean
        get() = preflight != null && !preflight.rollback && preflightError != FailureKind.RELEASE_ENDED && waitSeconds == 0L && !busy

    /** Complete groups of four typed so far (of [RecoveryCodes.TYPED_GROUPS]). */
    val typedGroups: Int get() = typed.length / RecoveryCodes.GROUP

    /** All 48 characters are there: **Continue** checks them. */
    val typedComplete: Boolean get() = typed.length == RecoveryCodes.TYPED_LENGTH

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

    /** "Type the code instead". */
    fun type()

    /** The typed field changed (typing or a paste). */
    fun setTyped(v: String)

    /** **Continue** on the typed entry. */
    fun submitTyped()
    fun retryPreflight()
    fun setApproveOffer(approve: Boolean)
    fun setPin(v: String)
    fun submitPin()
    fun setPassword(v: String)
    fun submitPassword()

    /** "Start over" on the PIN, password and ended steps: asks first ([confirmStartOver]). */
    fun askStartOver()
    fun dismissStartOver()

    /** Confirmed: this phone forgets the recovery it registered (the recovery itself is not cancelled by that). */
    fun confirmStartOver()

    /** The ended step: back to the PIN for another try (what ended it there is not proof). */
    fun tryPinAgain()

    /** (0.1.78) The ended step after an open whose answer was lost: look at status with K again (not before 5 minutes). */
    fun checkAgain()

    /** (0.1.78) The member started a new recovery on the account site: its code is scanned or typed and claimed with the same K. */
    fun useNewCode()
    fun finish()
}

/**
 * Recovery on a new phone (VAULT-MESSAGING §11.11), as vettid-vault's
 * reference client runs it: the portal's QR is claimed with this phone's app
 * key (§11.11.7, 0.15.0: no sign-in) and registered over the alternate
 * channel with this phone's device attestation; then the PIN unlocks the
 * vault and the first handshake makes this phone a restricted app; then
 * `credential.recover` with the password hands the credential over and the
 * old app is removed. The code can be typed instead (0.25.0 §11.11.10): the
 * 48 characters the portal shows carry a locator that stands for the vault and
 * the recovery at the claim, the code, and check characters this app verifies
 * with its own member API origin before it sends anything; after the claim it is
 * the scanned path. A vault without a backup copy of its credential cannot be
 * recovered at all (0.16.0, `no_backup`): the app says so and links to the
 * account site's start-over. Resumes where it stopped after a restart
 * ([MoveRepository.recoveryStage]).
 *
 * A registered recovery can be left (A6 audit E1): "Start over" on the PIN and password steps, after a confirmation,
 * makes this phone forget its registration ([MoveRepository.abandonRecovery]). The app has no call that cancels the
 * recovery itself: the spec gives that to the account site, the email link and an owner app's unlock (§11.11.4), so
 * the recovery stays registered until one of them or its expiry ends it. When the vault no longer takes this phone
 * for the recovery (an unlock result that cannot be read, which is how the enclave answers a key it does not hold,
 * §11.4; or the member API's `401` for a recovering key whose recovery ended, §11.11.7), the PIN step is left for
 * [RecoverStep.ENDED] by itself. Neither answer is authenticated, so nothing is forgotten until the member confirms.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class RecoverViewModel @Inject constructor(
    private val move: MoveRepository,
    private val vault: VaultRepository,
    private val account: AccountRepository,
    private val releaseNotes: ReleaseNotesRepository,
) : ViewModel(), RecoverActions {
    private val state = MutableStateFlow(
        RecoverUiState(startOverUrl = account.apiOrigin.trimEnd('/') + START_OVER_PATH, accountHost = hostOf(account.apiOrigin)),
    )
    val uiState: StateFlow<RecoverUiState> = state.asStateFlow()
    private var ticker: Job? = null
    private var retryTicker: Job? = null
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

    override fun scan() {
        if (state.value.busy) return
        state.update { it.copy(step = RecoverStep.SCAN, refusal = null, error = null, typedProblem = null) }
    }

    override fun type() {
        if (state.value.busy) return
        state.update { it.copy(step = RecoverStep.TYPE, refusal = null, error = null, typedProblem = null) }
    }

    /** Back within the flow; false when the member leaves it (the onboarding flow takes over). */
    override fun back(): Boolean {
        val s = state.value
        if (s.busy) return true
        return when (s.step) {
            RecoverStep.SCAN, RecoverStep.TYPE -> {
                state.update { it.copy(step = RecoverStep.INTRO, refusal = null, typedProblem = null) }
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
            else -> register(code.recoveryId) { move.registerRecovery(code) }
        }
    }

    /**
     * The typed field (§11.11.10 step 1): separators dropped, upper case, I and L read as 1 and O as 0; a `U` or
     * any other character is refused in the field (dropped and named, never guessed); a forty-ninth is ignored.
     */
    override fun setTyped(v: String) {
        val s = state.value
        if (s.busy || s.step != RecoverStep.TYPE) return
        val input = RecoveryCodes.typedInput(v)
        val pasted = input.text.length - s.typed.length > 1
        val problem = when {
            input.refused == RecoveryCodes.Refused.LETTER_U -> TypedProblem.LETTER_U
            input.refused == RecoveryCodes.Refused.OTHER -> TypedProblem.OTHER_CHARACTER
            // The bare code of an account page from before MEMBER-API 2.4.0, pasted whole.
            pasted && input.text.length == RecoveryCodes.LENGTH -> TypedProblem.SHORT_CODE
            else -> null
        }
        state.update { it.copy(typed = input.text, typedProblem = problem, typedGroup = input.refusedGroup) }
    }

    /**
     * **Continue** (§11.11.10 step 2): the check characters are recomputed with this app's own member API origin.
     * On a mismatch nothing is sent and nothing is counted; the entry stays for correction.
     */
    override fun submitTyped() {
        val s = state.value
        if (s.busy || s.step != RecoverStep.TYPE) return
        val problem = when (val p = TypedRecoveryCode.parse(s.typed, account.apiOrigin)) {
            is TypedRecoveryCode.Parsed.Ok -> {
                val code = p.code
                state.update { it.copy(typedProblem = null) }
                register(code.locator) { move.registerTypedRecovery(code) }
                return
            }
            TypedRecoveryCode.Parsed.BadCheck -> TypedProblem.BAD_CHECK
            TypedRecoveryCode.Parsed.BareCode -> TypedProblem.SHORT_CODE
            TypedRecoveryCode.Parsed.NotTyped -> TypedProblem.INCOMPLETE
        }
        state.update { it.copy(typedProblem = problem, typedGroup = 0, refusal = null, error = null) }
    }

    /**
     * The claim and the register, however the code was read. Wrong codes are counted per recovery: the one the
     * answer names (a typed code's claim), else [countKey] (a scanned QR's `recovery_id`).
     */
    private fun register(countKey: String, attempt: suspend () -> RecoveryRegistration) {
        val from = state.value.step
        retryTicker?.cancel()
        state.update { it.copy(step = RecoverStep.REGISTERING, busy = true, refusal = null, error = null, retrySeconds = 0) }
        viewModelScope.launch {
            try {
                when (val r = attempt()) {
                    is RecoveryRegistration.Registered -> {
                        state.update { it.copy(busy = false, emailHint = r.emailHint, typed = "", typedProblem = null) }
                        toPin()
                    }
                    is RecoveryRegistration.Refused -> refused(from, r.recoveryId ?: countKey, r.code)
                    // (0.1.78) `409 key_in_use`, or the earlier open's readable `ok`: this phone is the vault's; the PIN finishes it.
                    RecoveryRegistration.KeyInUse, RecoveryRegistration.EarlierOpened -> {
                        state.update { it.copy(busy = false, typed = "", typedProblem = null) }
                        toPin()
                    }
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(step = from, busy = false, error = e.kind, errorCode = e.code, errorShown = e.shownCode) }
                if (e.kind == FailureKind.RATE_LIMITED) startRetryWait(e.retryAfterSeconds)
            }
        }
    }

    /** Counts down a `429`'s wait, for the text only: the member may try again at any time (another network is not limited). */
    private fun startRetryWait(seconds: Long) {
        if (seconds <= 0) return
        retryTicker?.cancel()
        state.update { it.copy(retrySeconds = seconds) }
        retryTicker = viewModelScope.launch {
            while (state.value.retrySeconds > 0) {
                delay(TICK_MS)
                state.update { it.copy(retrySeconds = (it.retrySeconds - 1).coerceAtLeast(0)) }
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
            CODE_NOT_OFFERED -> CodeRefusal.NOT_OFFERED
            CODE_OTHER_VAULT -> CodeRefusal.OTHER_VAULT
            CODE_NO_BACKUP -> {
                state.update { it.copy(step = RecoverStep.NO_BACKUP, busy = false, refusal = null, typed = "", typedProblem = null) }
                noBackup()
                return
            }
            else -> CodeRefusal.RETRY
        }
        var wrong = state.value.wrongCodes
        if (refusal == CodeRefusal.BAD_CODE) {
            if (countedFor != recoveryId) wrong = 0
            countedFor = recoveryId
            wrong++
            if (wrong >= RecoverUiState.MAX_WRONG_CODES) refusal = CodeRefusal.VOIDED
        }
        // The typed characters stay for correction, and go when the recovery has ended (ANDROID-PLAN 0.1.33).
        val ended = refusal == CodeRefusal.VOIDED || code == CODE_EXPIRED || code == CODE_USED
        state.update {
            val typed = if (ended) "" else it.typed
            it.copy(step = from, busy = false, refusal = refusal, wrongCodes = wrong, typed = typed, typedProblem = null)
        }
    }

    // --- the PIN (§11.11.5 step 1) ---

    private fun toPin() {
        state.update {
            it.copy(
                step = RecoverStep.PIN, preflight = null, preflightError = null, phoneSecret = vault.usesPhoneSecret,
                undecided = move.recoveryUndecided,
            )
        }
        retryPreflight()
    }

    override fun retryPreflight() {
        state.update { it.copy(busy = true, preflightError = null, error = null) }
        viewModelScope.launch {
            try {
                val p = move.recoveryPreflight()
                state.update { it.copy(busy = false, preflight = p, notes = null) }
                p.offer?.takeIf { !p.rollback }?.let { offer ->
                    val n = releaseNotes.whatsNew(offer, p.between)
                    state.update { if (it.preflight?.offer == offer) it.copy(notes = n) else it }
                }
            } catch (e: VaultFailure) {
                // §11.11.7: the member API takes a recovering key only until its recovery ends.
                if (e.kind == FailureKind.UNAUTHORIZED) ended() else state.update { it.copy(busy = false, preflightError = e.kind) }
            }
        }
    }

    /** The vault or the member API no longer takes this phone for the recovery: say so, and offer the way out. */
    private fun ended() {
        ticker?.cancel()
        state.update {
            it.copy(
                step = RecoverStep.ENDED, busy = false, pin = "", pinWrong = false, waitSeconds = 0, error = null, errorCode = null,
                undecided = move.recoveryUndecided, couldNotTell = null,
            )
        }
        lookAtStatus()
    }

    /**
     * (0.1.78) After an open whose answer was never readable: status with K (twice, 5 minutes apart). `200` leads to the
     * PIN (the lost-ok unlock, or an open again); two ended answers offer a new recovery. Nothing is deleted by any answer.
     */
    private fun lookAtStatus() {
        viewModelScope.launch {
            val c = try {
                move.couldNotTell()
            } catch (_: VaultFailure) {
                CouldNotTell(CouldNotTell.Kind.UNKNOWN)
            }
            when (c.kind) {
                CouldNotTell.Kind.LOST_OK, CouldNotTell.Kind.OPEN_AGAIN -> toPin()
                else -> state.update { it.copy(couldNotTell = c, undecided = move.recoveryUndecided) }
            }
        }
    }

    override fun checkAgain() {
        val s = state.value
        if (s.step != RecoverStep.ENDED || s.busy) return
        state.update { it.copy(couldNotTell = null) }
        lookAtStatus()
    }

    override fun useNewCode() {
        val s = state.value
        val fromEnded = s.step == RecoverStep.ENDED && s.couldNotTell?.kind == CouldNotTell.Kind.START_NEW
        // Also from the lost-ok step once the rule allowed a new code that reuses the second identity (0.1.80).
        val fromPin = s.step == RecoverStep.PIN && move.newCodeAllowed()
        if (!fromEnded && !fromPin) return
        state.update { it.copy(step = RecoverStep.SCAN, refusal = null, error = null, typedProblem = null) }
    }

    private fun UnlockAttempt.Failed.recoveryEnded(): Boolean =
        kind == FailureKind.UNAUTHORIZED || code == UnlockViewModel.CODE_UNREADABLE || code == CODE_UNKNOWN_DEVICE

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
                // 0.16.0: a vault without a backup copy refuses the registered app's unlock.
                is UnlockAttempt.Failed -> when {
                    r.code == CODE_NO_BACKUP -> {
                        state.update { it.copy(step = RecoverStep.NO_BACKUP, busy = false, pin = "") }
                        noBackup()
                    }
                    // The registered key was removed: the recovery was cancelled, expired or replaced (§11.11.4).
                    r.recoveryEnded() -> ended()
                    else -> state.update { it.copy(busy = false, pin = "", error = r.kind, errorCode = r.code) }
                }
                is UnlockAttempt.UpdateRefused -> state.update {
                    it.copy(busy = false, pin = "", error = FailureKind.OTHER, errorCode = r.code, errorShown = null)
                }
                // NewPinNeeded: never on a recovering phone (no PIN change of its own to decide).
                UnlockAttempt.RecoveryPending, UnlockAttempt.StateRollback, UnlockAttempt.NewPinNeeded ->
                    state.update { it.copy(busy = false, pin = "", error = FailureKind.OTHER, errorShown = null) }
                // §11.7.1 (0.27.0): the release does not pin this installation's signer; not a wrong PIN.
                UnlockAttempt.SignerRefused -> state.update {
                    it.copy(busy = false, pin = "", error = FailureKind.SIGNER_REFUSED, errorCode = SIGNER_REFUSED)
                }
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
     * (`credential_backup` true) or did not say; without a copy the vault cannot be recovered (0.16.0).
     */
    private suspend fun afterUnlock() {
        val backup = try {
            move.recoveryCredentialBackup()
        } catch (_: VaultFailure) {
            null
        }
        state.update { it.copy(step = if (backup == false) RecoverStep.NO_BACKUP else RecoverStep.PASSWORD) }
        if (backup == false) noBackup()
    }

    // --- the password (step 3) ---

    override fun setPassword(v: String) = state.update { it.copy(password = v, error = null) }

    override fun submitPassword() {
        val s = state.value
        if (s.password.isEmpty() || s.busy || s.waitSeconds > 0) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (move.recoverCredential(s.password)) {
                    RecoverOutcome.RECOVERED -> state.update { it.copy(step = RecoverStep.DONE, busy = false, password = "") }
                    RecoverOutcome.NO_BACKUP -> {
                        state.update { it.copy(step = RecoverStep.NO_BACKUP, busy = false, password = "") }
                        noBackup()
                    }
                    RecoverOutcome.CREDENTIAL_REQUIRED -> state.update {
                        it.copy(busy = false, password = "", error = FailureKind.OTHER, errorCode = CODE_REQUIRED, errorShown = null)
                    }
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, password = "", error = e.kind, errorCode = e.code, errorShown = e.shownCode) }
                if (e.kind == FailureKind.BACKOFF || e.kind == FailureKind.BAD_PASSWORD) startBackoff(e.retryAfterSeconds)
            }
        }
    }

    /** 0.16.0: nothing more to do with this vault on this phone; forget the registration (quietly). */
    private fun noBackup() {
        viewModelScope.launch { runCatching { move.abandonRecovery() } }
    }

    // --- leaving a registered recovery (A6 audit E1) ---

    override fun askStartOver() {
        val s = state.value
        val undecided = s.undecided || move.recoveryUndecided
        if (s.busy || s.step !in LEAVABLE || undecided) return
        state.update { it.copy(confirmStartOver = true) }
    }

    override fun dismissStartOver() = state.update { it.copy(confirmStartOver = false) }

    /**
     * This phone forgets the recovery (its registration, its keys for it, the PIN or password typed so far) and is
     * back at the start of the flow. Nothing is sent: only the account site, the email link, an owner app's unlock
     * or the expiry end the recovery itself (§11.11.4).
     */
    override fun confirmStartOver() {
        val s = state.value
        if (s.busy || !s.confirmStartOver) return
        ticker?.cancel()
        state.update { it.copy(busy = true, confirmStartOver = false, pin = "", password = "", error = null, errorCode = null) }
        viewModelScope.launch {
            try {
                move.abandonRecovery()
                countedFor = null
                state.value = RecoverUiState(step = RecoverStep.INTRO, startOverUrl = s.startOverUrl, accountHost = s.accountHost)
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind, errorCode = e.code, errorShown = e.shownCode) }
            }
        }
    }

    override fun tryPinAgain() {
        val s = state.value
        if (s.busy || s.step != RecoverStep.ENDED) return
        toPin()
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

        /** (0.34.0) The release does not offer recovery (§11.10.9, §11.14.13). */
        const val CODE_NOT_OFFERED = "not_offered"
        const val CODE_UNKNOWN_DEVICE = "unknown_device"
        const val CODE_OTHER_VAULT = "other_vault"
        const val CODE_REQUIRED = "credential_required"
        const val CODE_NO_BACKUP = "no_backup"
        private const val SIGNER_REFUSED = "signer_refused"

        /** The steps a registered recovery can be left from. */
        private val LEAVABLE = setOf(RecoverStep.PIN, RecoverStep.PASSWORD, RecoverStep.ENDED)

        /** MEMBER-API 2.1.0: the account site's "Delete my vault and start over". */
        const val START_OVER_PATH = "/account/vault/deletion/"
        private const val MIN_PIN = 6
        private const val MAX_PIN = 32
        private const val TICK_MS = 1000L

        /** The host of [origin] (`account.vettid.org`), or [origin] itself when it is not a URL. */
        internal fun hostOf(origin: String): String = try {
            URI(origin).host ?: origin
        } catch (_: URISyntaxException) {
            origin
        }
    }
}
