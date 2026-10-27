package com.vettid.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.RecoveryOpenOutcome
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.excludeFromAutofill
import com.vettid.core.ui.components.SecureWindow
import com.vettid.core.ui.format.LocalTimeScale
import com.vettid.core.ui.format.durationText
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.format.stagingNoteSuffix
import com.vettid.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** Where the recovery open stands (ANDROID-PLAN 0.1.40 "Recovery on the new phone"). */
enum class OpenStep {
    /** Both fields on one screen. */
    ENTER,

    /** "This is what will be sent": a step that cannot be skipped. */
    CHECK,

    SENDING,

    /** The vault answered (anything but `ok`). */
    ANSWERED,

    /**
     * The vault answered `ok` earlier but the phone could not finish opening it (the handshake or the credential did
     * not complete, or the app stopped): the PIN alone finishes it, and no new open is sent.
     */
    FINISH,

    /** Recovered: the `ok` text, then the app opens. */
    DONE,
}

/**
 * The recovery open's state. The PIN and the password are held in memory only and never written anywhere; they are
 * left out of [toString].
 */
data class RecoveryOpenUiState(
    val step: OpenStep = OpenStep.ENTER,
    val pin: String = "",
    val password: String = "",
    val showPin: Boolean = false,
    val showPassword: Boolean = false,
    /** The last answer, with the attempts the counter reported. */
    val outcome: RecoveryOpenOutcome? = null,
    /** A failure before the vault answered (network, the service). */
    val failure: FailureKind? = null,
    /** [failure]'s code (`unreadable_result`, …). */
    val failureCode: String? = null,
    /** The member asked to erase VettID from this phone ([canErase]); the confirmation is shown. */
    val confirmErase: Boolean = false,
    /**
     * (0.34.0 §11.14.6 "A lost ok") [OpenStep.FINISH] after an open whose answer never arrived or could not be read: the
     * PIN unlocks with this phone's secret, and a result with the vault's bundle finishes the recovery.
     */
    val lostOk: Boolean = false,
    /** Attempts used, as last known (null before the counter ever answered for this recovery, also after a restart). */
    val used: Int? = null,
    /**
     * An open after one that got no answer was answered `wait` or `attempt_lost` (§11.14.6 "A right entry that could
     * not be completed").
     */
    val rightButIncomplete: Boolean = false,
    /**
     * The member continued after `ok` and the vault opened on this phone ([RecoveryOpenViewModel.openApp]): the
     * recovery's own screen hands over to the end of setup ([RecoveryOpenPane]'s `onOpened`).
     */
    val opened: Boolean = false,
    /** (ANDROID-PLAN 0.1.78) An open's answer is unknown: K is never forgotten, so "Start over" is not offered. */
    val undecided: Boolean = false,
    /** (0.1.78) The lost-ok unlock found the recovery still registered: the PIN and the password open it again. */
    val openAgain: Boolean = false,
    /** (0.1.80) A new recovery code may be used with this phone (it reuses the second identity). */
    val newCodeAllowed: Boolean = false,
) {
    val canContinue: Boolean get() = pinValid && password.isNotEmpty()

    /**
     * While finishing an `ok` recovery, the enclave itself answered that this phone's key no longer opens the vault
     * (`device_secret` at both epochs, a sealed answer nobody else can make): the way out is to erase VettID from this
     * phone. Answers the host or the member API can produce (random bytes, a 401) never offer it.
     */
    val canErase: Boolean
        get() = step == OpenStep.FINISH && !lostOk && outcome?.opened == true && outcome.code == CODE_DEVICE_SECRET

    /** The PIN alone, for [OpenStep.FINISH]. */
    val pinValid: Boolean get() = pin.length in PIN_MIN..PIN_MAX && pin.all { it.isDigit() }

    /** Before the second attempt, and from three or fewer left: a warning above the check. */
    val warning: OpenWarning?
        get() = when {
            used == 1 -> OpenWarning.SECOND
            used != null && RecoveryOpenOutcome.ATTEMPTS - used <= LOW -> OpenWarning.FEW_LEFT
            else -> null
        }

    override fun toString(): String = "RecoveryOpenUiState(step=$step, outcome=${outcome?.code}, failure=$failure, used=$used)"

    companion object {
        private const val PIN_MIN = 6
        private const val PIN_MAX = 32
        private const val LOW = 3
        private const val CODE_DEVICE_SECRET = "device_secret"
        const val CODE_UNREADABLE = "unreadable_result"
    }
}

private const val CODE_OPEN_AGAIN = "open_again"
private const val CODE_START_NEW = "start_new"

enum class OpenWarning { SECOND, FEW_LEFT }

/**
 * `vault.recovery.open` (VAULT-MESSAGING 0.33.0 §11.14.6): the PIN and the password together, checked by the
 * member before they are sent (a wrong entry uses one of ten attempts), then the vault's answer.
 */
@Suppress("TooManyFunctions") // one action per field and step
@HiltViewModel
class RecoveryOpenViewModel @Inject constructor(
    private val move: MoveRepository,
    private val account: AccountRepository,
) : ViewModel() {
    private val state = MutableStateFlow(RecoveryOpenUiState(used = move.recoveryAttemptsUsed))
    val uiState: StateFlow<RecoveryOpenUiState> = state.asStateFlow()

    init {
        // A recovery the vault already answered `ok` (before a restart, or whose handshake failed): finished, never
        // opened again.
        viewModelScope.launch {
            val opened = try {
                move.recoveryOpened()
            } catch (_: VaultFailure) {
                false
            }
            if (opened) {
                state.update { it.copy(step = OpenStep.FINISH) }
            } else if (move.lostOkDue()) {
                // A `200` on status, or `409 key_in_use` at a claim: this phone may be the vault's app (0.1.78).
                finishLostOk()
            }
            state.update { it.copy(undecided = move.recoveryUndecided, newCodeAllowed = move.newCodeAllowed()) }
        }
    }

    fun setPin(v: String) = state.update { it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN)) }

    fun setPassword(v: String) = state.update { it.copy(password = v) }

    fun toggleShowPin() = state.update { it.copy(showPin = !it.showPin) }

    fun toggleShowPassword() = state.update { it.copy(showPassword = !it.showPassword) }

    /** Continue: the check-before-sending step (never skipped). */
    fun review() = state.update { if (it.canContinue) it.copy(step = OpenStep.CHECK) else it }

    /** "Change": back to the fields, the entries kept. */
    fun change() = state.update { it.copy(step = OpenStep.ENTER) }

    fun send() {
        val s = state.value
        val finishing = s.step == OpenStep.FINISH
        val ready = if (finishing) s.pinValid else s.step == OpenStep.CHECK && s.canContinue
        if (!ready) return
        state.update { it.copy(step = OpenStep.SENDING, failure = null, failureCode = null) }
        viewModelScope.launch {
            val out = try {
                if (finishing && s.lostOk) move.resumeLostOk(s.pin) else move.recoveryOpen(s.pin, s.password)
            } catch (e: VaultFailure) {
                // No answer, or the vault said `ok` and the rest failed: then the PIN finishes it, nothing is opened again.
                val opened = runCatching { move.recoveryOpened() }.getOrDefault(finishing)
                state.update {
                    it.copy(
                        step = if (opened) OpenStep.FINISH else OpenStep.ANSWERED, failure = e.kind, failureCode = e.code, outcome = null,
                        undecided = runCatching { move.recoveryUndecided }.getOrDefault(it.undecided),
                    )
                }
                return@launch
            }
            answered(out)
            state.update { it.copy(undecided = move.recoveryUndecided, newCodeAllowed = move.newCodeAllowed()) }
        }
    }

    /** The vault's answer (or this phone's own unlock while finishing an `ok` recovery). */
    private fun answered(out: RecoveryOpenOutcome) {
        when {
            out.ok -> state.update { RecoveryOpenUiState(step = OpenStep.DONE, outcome = out, used = out.used ?: it.used) }
            // (0.1.78) The recovery still shows `registered`: the entries again, the open repeats it (no attempt is spent for it).
            out.code == CODE_OPEN_AGAIN -> state.update {
                it.copy(
                    step = OpenStep.ENTER, outcome = null, failure = null, failureCode = null, lostOk = false, openAgain = true, pin = "",
                )
            }
            // (0.1.78) The state is still under DEK_0 and the recovery has ended: a new recovery with K (approved text).
            out.code == CODE_START_NEW -> state.update {
                it.copy(step = OpenStep.ANSWERED, outcome = out, failure = null, pin = "", lostOk = false)
            }
            // This phone's own unlock while finishing an `ok` recovery (a wrong PIN, a wait): nothing of the recovery used.
            out.opened -> state.update {
                // Resuming a lost `ok`, the vault's `device_secret`: this phone is not in its unlock keys, the recovery
                // did not complete onto it (as without a bundle): said so, with the way back.
                val shown = if (it.lostOk && out.code == CODE_DEVICE_SECRET_ANSWER) out.copy(code = CODE_NO_BUNDLE_ANSWER) else out
                it.copy(step = OpenStep.FINISH, outcome = shown, failure = null, pin = "")
            }
            else -> state.update {
                it.copy(
                    step = OpenStep.ANSWERED,
                    outcome = out,
                    used = out.used ?: it.used,
                    rightButIncomplete = out.afterUnanswered && (out.code == CODE_WAIT || out.code == CODE_LOST),
                    // A wrong pair is changed before the next try; anything that used nothing keeps the entries.
                    password = if (out.code == CODE_WRONG || out.code == CODE_WRONG_AGAIN) "" else it.password,
                )
            }
        }
    }

    /** From an answer: back to the fields for another try (the PIN kept). */
    fun again() = state.update { it.copy(step = OpenStep.ENTER, outcome = null, failure = null, failureCode = null, lostOk = false) }

    /**
     * (0.34.0) The open's answer was lost or unreadable, or the recovery ended after such an open: the PIN may finish a
     * recovery that completed on the way (an unlock with this phone's secret; no attempt is used).
     */
    fun finishLostOk() = state.update {
        it.copy(step = OpenStep.FINISH, lostOk = true, outcome = null, failure = null, failureCode = null, password = "")
    }

    fun askErase() = state.update { if (it.canErase) it.copy(confirmErase = true) else it }

    fun dismissErase() = state.update { it.copy(confirmErase = false) }

    /** Confirmed: VettID is erased from this phone; the phase becomes SignedOut and the welcome screen follows. */
    fun erase() {
        if (!state.value.confirmErase) return
        state.update { it.copy(confirmErase = false, pin = "", password = "") }
        viewModelScope.launch { account.eraseThisPhone() }
    }

    /**
     * After the `ok` text: the app opens. Then [RecoveryOpenUiState.opened], for the screen to finish setup if the app
     * is not open yet (the `ok` text is the recovered phone's last setup screen). Continue may be pressed again after a
     * failure; it opens nothing twice. A failure never opens anything: a lock asked for meanwhile
     * ([MoveRepository.CODE_LOCK_PENDING]) keeps the phone locked and is said as the unlock screen says it.
     */
    fun openApp() {
        if (state.value.step != OpenStep.DONE) return
        viewModelScope.launch {
            val failure = try {
                move.finishRecoveryOpen()
                null
            } catch (e: VaultFailure) {
                e
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // Anything else (the account record's write): not opened, said as a failure, and the screen stays.
                VaultFailure(FailureKind.OTHER, MoveRepository.CODE_NOT_OPENED, cause = e)
            }
            state.update {
                if (failure == null) {
                    it.copy(failure = null, failureCode = null, opened = true)
                } else {
                    it.copy(failure = failure.kind, failureCode = failure.code, opened = false)
                }
            }
        }
    }

    companion object {
        private const val MAX_PIN = 32
        const val CODE_WRONG = "wrong"
        const val CODE_WRONG_AGAIN = "wrong_again"
        const val CODE_LOST = "attempt_lost"
        const val CODE_WAIT = "wait"
        private const val CODE_DEVICE_SECRET_ANSWER = "device_secret"
        private const val CODE_NO_BUNDLE_ANSWER = "no_bundle"
    }
}

/**
 * The text of an answer (ANDROID-PLAN 0.1.41's table): a string and its arguments, then, when set, `retry_at`
 * ([withRetryAt]). The date the app was last used is not shown: nothing the app reads carries the vault's quiet period.
 * [attemptsLeft]: a line under it with the attempts left, for the answers whose text does not say whether this request
 * used one.
 */
data class OpenAnswerText(
    @StringRes val res: Int,
    val args: List<Any> = emptyList(),
    val withRetryAt: Boolean = false,
    val attemptsLeft: Int? = null,
)

/**
 * Every answer of the plan's table; [left] is 10 less the attempts used, when known. [rightButIncomplete]: the open
 * before this one got no answer. `quiet` and `wait` say nothing about whether this request used an attempt (the result
 * does not tell), only how many are left.
 */
fun openAnswerText(o: RecoveryOpenOutcome, rightButIncomplete: Boolean): OpenAnswerText {
    val left = o.left ?: RecoveryOpenOutcome.ATTEMPTS
    return when {
        // (0.1.78) Approved texts: the lost-ok unlock refused for a day, and a new recovery with this phone.
        o.contactVettid -> OpenAnswerText(R.string.onboarding_open_took_phone)
        // DRAFT (ANDROID-PLAN 0.1.80): both identities stand and the vault does not take this phone.
        o.contactCouldNotRecover -> OpenAnswerText(R.string.recover_could_not_recover)
        o.code == CODE_START_NEW -> OpenAnswerText(R.string.recover_could_not_tell)
        rightButIncomplete && o.retryAt != null ->
            OpenAnswerText(R.string.onboarding_open_right_incomplete, listOf(left), withRetryAt = true)
        o.opened -> OpenAnswerText(openedText(o.code))
        else -> tableText(o, left)
    }
}

@Suppress("CyclomaticComplexMethod") // one line per answer of the table
private fun tableText(o: RecoveryOpenOutcome, left: Int): OpenAnswerText {
    return when (o.code) {
        "wrong" -> if (o.retryAt != null) {
            OpenAnswerText(R.string.onboarding_open_wrong_wait, listOf(left), withRetryAt = true)
        } else {
            OpenAnswerText(R.string.onboarding_open_wrong, listOf(left))
        }
        "wrong_again" -> OpenAnswerText(R.string.onboarding_open_wrong_again)
        "attempt_lost" -> OpenAnswerText(R.string.onboarding_open_attempt_lost, listOf(left))
        "quiet" -> quietText(o, left)
        "wait" -> waitText(o, left)
        "busy" -> OpenAnswerText(R.string.onboarding_open_busy)
        // (0.34.0) A retry after the spend used an attempt (the entry may have been right).
        "retry" -> if (o.attemptUsed == true) {
            OpenAnswerText(R.string.onboarding_open_retry_used, listOf(left))
        } else {
            OpenAnswerText(R.string.onboarding_open_retry)
        }
        // Before the spend nothing was used; after it, the right attempt was (attempt_used; an older vault: attempts).
        "stored_mismatch", "vault_missing" -> if (o.attemptUsed ?: (o.used != null)) {
            OpenAnswerText(R.string.onboarding_open_stored_mismatch_right)
        } else {
            OpenAnswerText(R.string.onboarding_open_stored_mismatch)
        }
        "exhausted" -> OpenAnswerText(R.string.onboarding_open_exhausted)
        "closed" -> OpenAnswerText(R.string.onboarding_open_closed)
        "not_armed" -> OpenAnswerText(R.string.onboarding_open_not_armed)
        "counter_unavailable" -> OpenAnswerText(R.string.onboarding_open_counter_unavailable)
        "ok" -> o.used?.let { OpenAnswerText(R.string.onboarding_open_ok, listOf(it)) } ?: OpenAnswerText(R.string.onboarding_open_ok_plain)
        // Refusals before anything is counted (§11.14.6 step 1).
        // After an open that got no answer the recovery may have completed on the way; this phone cannot finish it.
        "no_recovery" -> if (o.afterUnanswered) {
            OpenAnswerText(R.string.onboarding_open_no_recovery_unanswered)
        } else {
            OpenAnswerText(R.string.onboarding_open_no_recovery)
        }
        "expired" -> OpenAnswerText(R.string.onboarding_open_expired)
        "attestation" -> OpenAnswerText(R.string.onboarding_open_attestation)
        "manifest" -> OpenAnswerText(R.string.onboarding_open_manifest)
        "wrong_release" -> OpenAnswerText(R.string.onboarding_open_wrong_release)
        "bad_request" -> OpenAnswerText(R.string.onboarding_open_bad_request)
        else -> OpenAnswerText(R.string.onboarding_open_other, listOf(o.code))
    }
}

/**
 * `quiet` (§11.14.6, ANDROID-PLAN 0.1.41): whether this request used an attempt comes only from `attempt_used`; a vault
 * that does not say gets the neutral text with the attempts left.
 */
private fun quietText(o: RecoveryOpenOutcome, left: Int): OpenAnswerText = when {
    o.attemptUsed == true -> OpenAnswerText(R.string.onboarding_open_quiet_used, listOf(left))
    o.retryAt == null -> OpenAnswerText(R.string.onboarding_open_quiet_nodate, attemptsLeft = o.left.takeIf { o.attemptUsed == null })
    o.attemptUsed == false -> OpenAnswerText(R.string.onboarding_open_quiet_nothing, withRetryAt = true)
    else -> OpenAnswerText(R.string.onboarding_open_quiet, withRetryAt = true, attemptsLeft = o.left)
}

/** `wait`: with an attempt used, as `attempt_lost`; with none, "Nothing was used"; unknown, neutral. */
private fun waitText(o: RecoveryOpenOutcome, left: Int): OpenAnswerText = when {
    o.attemptUsed == true -> OpenAnswerText(R.string.onboarding_open_attempt_lost, listOf(left))
    o.retryAt == null -> OpenAnswerText(R.string.onboarding_open_wait_nodate, attemptsLeft = o.left.takeIf { o.attemptUsed == null })
    o.attemptUsed == false -> OpenAnswerText(R.string.onboarding_open_wait_nothing, withRetryAt = true)
    else -> OpenAnswerText(R.string.onboarding_open_wait, withRetryAt = true, attemptsLeft = o.left)
}

/** This phone's own unlock while it finishes a recovery the vault answered `ok` (nothing of the recovery is used). */
@StringRes
private fun openedText(code: String): Int = when (code) {
    "bad_pin" -> R.string.onboarding_open_finish_bad_pin
    "backoff" -> R.string.onboarding_open_finish_backoff
    CODE_NO_BUNDLE -> R.string.onboarding_open_lost_not_here
    else -> R.string.onboarding_open_finish_failed
}

/** Answers after which this recovery cannot go on: a try again cannot help. */
private val ENDED = setOf(
    "start_new", "exhausted", "closed", "not_armed", "stored_mismatch", "no_recovery", "expired", "attestation", "bad_request",
)

/** Answers that end this recovery but leave a new one possible: "Start a new recovery". */
private val NEW_RECOVERY = setOf("no_recovery", "expired")

/** Whether an answer leaves room for another try now or later (the fields come back). */
fun openCanTryAgain(code: String?): Boolean = code !in ENDED

/** Whether an answer leads to starting a new recovery (this phone forgets this one; a new code on the account site). */
fun openNeedsNewRecovery(code: String?): Boolean = code in NEW_RECOVERY

private val whenFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** The arguments of [t] for [o]: its own, then the date the app was last used, then `retry_at`, in the phone's zone. */
internal fun openAnswerArgs(t: OpenAnswerText, o: RecoveryOpenOutcome, zone: ZoneId = ZoneId.systemDefault()): List<Any> {
    val retryAt = o.retryAt
    var args = t.args
    if (t.withRetryAt) args = args + (retryAt?.atZone(zone)?.format(whenFormat).orEmpty())
    return args
}

/**
 * The recovery's PIN and password (ANDROID-PLAN 0.1.40 "Recovery on the new phone"): one screen with both fields, the
 * check before sending, and the answer, with a countdown to the next possible attempt during a wait. The screen is
 * kept out of screenshots and the recent-apps preview whatever the app-wide setting says.
 */
@Composable
fun RecoveryOpenPane(
    state: RecoveryOpenUiState,
    vm: RecoveryOpenViewModel,
    modifier: Modifier = Modifier,
    /** "Start over": this phone forgets the registered recovery (after a confirmation). Never offered after `ok`. */
    onStartOver: (() -> Unit)? = null,
    /**
     * The vault opened on this phone after `ok` ([RecoveryOpenUiState.opened]): the end of setup (the recovery flow's
     * `finish`), so that the screen never stays when the app has not opened by itself.
     */
    onOpened: () -> Unit = {},
    /** (0.1.78) "Use a new recovery code": a new recovery, claimed with the same K. */
    onNewCode: (() -> Unit)? = null,
) {
    SecureWindow()
    // K is never forgotten while an open's answer is unknown (0.1.78): no start over then.
    val startOver = onStartOver.takeUnless { state.undecided }
    LaunchedEffect(state.opened) { if (state.opened) onOpened() }
    Column(
        modifier.fillMaxWidth().padding(Spacing.gutter).testTag("recovery_open"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        when (state.step) {
            OpenStep.ENTER -> OpenEntry(state, vm, startOver)
            OpenStep.CHECK -> OpenCheck(state, vm)
            OpenStep.SENDING -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            OpenStep.ANSWERED -> OpenAnswer(state, vm, startOver, onNewCode)
            OpenStep.FINISH -> OpenFinish(state, vm, onNewCode)
            OpenStep.DONE -> OpenDone(state, vm)
        }
    }
}

/** The vault answered `ok` earlier: the PIN alone finishes opening it. No new open, no start over. */
@Composable
private fun OpenFinish(state: RecoveryOpenUiState, vm: RecoveryOpenViewModel, onNewCode: (() -> Unit)?) {
    Text(
        stringResource(if (state.lostOk) R.string.onboarding_open_lost_title else R.string.onboarding_open_finish_title),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        stringResource(if (state.lostOk) R.string.onboarding_open_lost_body else R.string.onboarding_open_finish_body),
        style = MaterialTheme.typography.bodyLarge,
    )
    OutlinedTextField(
        value = state.pin,
        onValueChange = vm::setPin,
        label = { Text(stringResource(R.string.onboarding_open_pin)) },
        visualTransformation = if (state.showPin) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        trailingIcon = { ShowHide(state.showPin, vm::toggleShowPin) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("open_finish_pin"),
    )
    val o = state.outcome
    val failure = state.failure
    if (o != null && o.opened) {
        NoticeCard(NoticeKind.WARNING, stringResource(R.string.onboarding_open_answer_title), stringResource(openAnswerText(o, false).res))
        if (o.contactVettid || o.contactCouldNotRecover) ContactSupport()
    } else if (failure != null) {
        NoticeCard(NoticeKind.WARNING, stringResource(R.string.onboarding_open_answer_title), failureText(failure.messageRes()))
    }
    // (2026-10-27) Beside the PIN whenever the rule allows a new code (both recoveries ended), with no PIN attempt first: the
    // member usually has a new code in hand, and the lost-ok unlock stays free on this screen. A new code reuses the second
    // identity; if the vault did take the phone, claiming it answers `409 key_in_use` and the app returns here for the PIN.
    if (state.newCodeAllowed && onNewCode != null) {
        OutlinedButton(onClick = onNewCode, modifier = Modifier.testTag("open_new_code_after_lost_ok")) {
            Text(stringResource(R.string.recover_undecided_new_code))
        }
    }
    Button(onClick = vm::send, enabled = state.pinValid, modifier = Modifier.testTag("open_finish")) {
        Text(stringResource(R.string.onboarding_open_finish_go))
    }
    if (state.canErase) OpenFinishErase(state.confirmErase, vm)
    // A lost `ok` that may not have happened: back to the open (only an `ok` the vault confirmed has no way back).
    if (state.lostOk) {
        TextButton(onClick = vm::again, modifier = Modifier.testTag("open_lost_back")) {
            Text(stringResource(R.string.onboarding_open_back))
        }
    }
}

/** Recovery is ruled out for now: the contact line, and (not terminal) a new code that reuses the second identity. */
@Composable
private fun RuledOut(newCodeAllowed: Boolean, onNewCode: (() -> Unit)?) {
    ContactSupport()
    if (newCodeAllowed && onNewCode != null) {
        OutlinedButton(onClick = onNewCode, modifier = Modifier.testTag("open_new_code_after_ruled_out")) {
            Text(stringResource(R.string.recover_undecided_new_code))
        }
    }
}

/** "Contact VettID": the support address, as a mail button (the address is shown when no mail app takes it). */
@Composable
private fun ContactSupport() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var noMail by remember { androidx.compose.runtime.mutableStateOf(false) }
    // The button label is a DRAFT (pending the owner's wording).
    OutlinedButton(onClick = { noMail = !SupportMail.open(context) }, modifier = Modifier.testTag("open_contact_support")) {
        Text(stringResource(R.string.onboarding_open_contact_support, SupportMail.ADDRESS))
    }
    if (noMail) Text(SupportMail.ADDRESS, style = MaterialTheme.typography.bodyMedium)
}

/** The vault no longer has this phone: "Erase VettID from this phone", after a confirmation. */
@Composable
private fun OpenFinishErase(confirm: Boolean, vm: RecoveryOpenViewModel) {
    Text(stringResource(R.string.onboarding_open_finish_gone), style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = vm::askErase, modifier = Modifier.testTag("open_finish_erase")) {
        Text(stringResource(R.string.unlock_erase))
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.unlock_erase_confirm_title),
            text = stringResource(R.string.onboarding_open_finish_erase_body),
            confirmLabel = stringResource(R.string.unlock_erase_confirm),
            onConfirm = vm::erase,
            onDismiss = vm::dismissErase,
            destructive = true,
        )
    }
}

/** Recovered: the plan's `ok` text, read before the app opens. */
@Composable
private fun OpenDone(state: RecoveryOpenUiState, vm: RecoveryOpenViewModel) {
    val o = state.outcome ?: RecoveryOpenOutcome("ok", state.used)
    val t = openAnswerText(o, false)
    @Suppress("SpreadOperator") // a string's arguments
    val body = stringResource(t.res, *t.args.toTypedArray())
    NoticeCard(NoticeKind.INFO, stringResource(R.string.onboarding_open_answer_title), body, Modifier.testTag("open_answer_ok"))
    state.failure?.let {
        // A lock asked for meanwhile is pending: said as the unlock screen says it (the phone stays locked).
        val text = if (state.failureCode == MoveRepository.CODE_LOCK_PENDING) {
            stringResource(R.string.unlock_lock_unconfirmed_body)
        } else {
            failureText(it.messageRes())
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("open_app_failure"))
    }
    Button(onClick = vm::openApp, modifier = Modifier.testTag("open_app")) { Text(stringResource(R.string.onboarding_open_continue)) }
}

@Composable
private fun OpenEntry(state: RecoveryOpenUiState, vm: RecoveryOpenViewModel, onStartOver: (() -> Unit)?) {
    Text(stringResource(R.string.onboarding_open_title), style = MaterialTheme.typography.titleLarge)
    if (state.openAgain) {
        Text(
            stringResource(R.string.onboarding_open_again),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("open_again_note"),
        )
    }
    OutlinedTextField(
        value = state.pin,
        onValueChange = vm::setPin,
        label = { Text(stringResource(R.string.onboarding_open_pin)) },
        visualTransformation = if (state.showPin) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        trailingIcon = { ShowHide(state.showPin, vm::toggleShowPin) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("open_pin"),
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = vm::setPassword,
        label = { Text(stringResource(R.string.onboarding_open_password)) },
        visualTransformation = if (state.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { ShowHide(state.showPassword, vm::toggleShowPassword) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("open_password"),
    )
    Text(stringResource(R.string.onboarding_open_ten), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = vm::review, enabled = state.canContinue, modifier = Modifier.testTag("open_continue")) {
        Text(stringResource(R.string.onboarding_open_continue))
    }
    onStartOver?.let {
        TextButton(onClick = it, modifier = Modifier.testTag("open_start_over")) { Text(stringResource(R.string.recover_start_over)) }
    }
}

@Composable
private fun ShowHide(shown: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle) { Text(stringResource(if (shown) R.string.onboarding_open_hide else R.string.onboarding_open_show)) }
}

/**
 * The warning before a send. The second attempt's: in real time (production, and every vault at scale 1) the approved
 * words ("you can try again in 24 hours", the wait after a wrong second attempt, W(2) = one wait unit of 86,400 s);
 * on a vault with a time scale (staging) the wait as it runs there, [RECOVERY_WAIT_UNIT_SCALED_S], with the staging
 * note (STAGING-TIME-SCALE §5.2: no text hard-codes "24 hours" for a wait a staging vault shortens).
 */
@Composable
internal fun openWarningText(w: OpenWarning, used: Int?): String = when (w) {
    OpenWarning.SECOND -> if (LocalTimeScale.current.scale <= 1) {
        stringResource(R.string.onboarding_open_second)
    } else {
        stringResource(R.string.onboarding_open_second_scaled, durationText(RECOVERY_WAIT_UNIT_SCALED_S)) + stagingNoteSuffix()
    }
    OpenWarning.FEW_LEFT -> stringResource(R.string.onboarding_open_few_left, RecoveryOpenOutcome.ATTEMPTS - (used ?: 0))
}

/**
 * The recovery's wait unit on a vault with a time scale (STAGING-TIME-SCALE §2.1 and T6: `recovery_wait_unit_seconds` is
 * 600 on staging, and the time scale does not divide it again; 86,400 in production). The vault does not tell the app
 * its unit; a vault with a time scale other than 1 is a staging vault, so this is its unit.
 */
internal const val RECOVERY_WAIT_UNIT_SCALED_S = 600L

@Composable
private fun OpenCheck(state: RecoveryOpenUiState, vm: RecoveryOpenViewModel) {
    state.warning?.let { w ->
        NoticeCard(
            kind = NoticeKind.WARNING,
            title = stringResource(R.string.onboarding_open_warning_title),
            body = openWarningText(w, state.used),
        )
    }
    Text(stringResource(R.string.onboarding_open_check, state.pin, state.password), style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        OutlinedButton(onClick = vm::change, modifier = Modifier.testTag("open_change")) {
            Text(stringResource(R.string.onboarding_open_change))
        }
        Button(onClick = vm::send, modifier = Modifier.testTag("open_send")) { Text(stringResource(R.string.onboarding_open_send)) }
    }
}

@Composable
@Suppress("SpreadOperator", "CyclomaticComplexMethod") // a string's arguments; one branch per kind of answer
private fun OpenAnswer(state: RecoveryOpenUiState, vm: RecoveryOpenViewModel, onStartOver: (() -> Unit)?, onNewCode: (() -> Unit)?) {
    val o = state.outcome
    if (o == null) {
        OpenNoAnswer(state, vm, onStartOver)
        return
    }
    val t = openAnswerText(o, state.rightButIncomplete)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val retryAt = o.retryAt
    LaunchedEffect(retryAt) {
        while (retryAt != null && now < retryAt.toEpochMilli()) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val args = openAnswerArgs(t, o)
    NoticeCard(
        kind = if (o.code == "exhausted" || o.code == "stored_mismatch") NoticeKind.URGENT else NoticeKind.WARNING,
        title = stringResource(R.string.onboarding_open_answer_title),
        body = stringResource(t.res, *args.toTypedArray()),
        modifier = Modifier.testTag("open_answer_${o.code}"),
    )
    t.attemptsLeft?.let { Text(stringResource(R.string.onboarding_open_attempts_left, it), style = MaterialTheme.typography.bodyMedium) }
    if (retryAt != null && now < retryAt.toEpochMilli()) {
        Text(
            stringResource(R.string.onboarding_open_countdown, countdown(retryAt, Instant.ofEpochMilli(now))),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (o.code == "no_recovery" && o.afterUnanswered) {
        Button(onClick = vm::finishLostOk, modifier = Modifier.testTag("open_lost_ok")) {
            Text(stringResource(R.string.onboarding_open_lost_title))
        }
    }
    if (o.contactCouldNotRecover) RuledOut(state.newCodeAllowed, onNewCode)
    if (o.code == CODE_START_NEW && onNewCode != null) {
        Button(onClick = onNewCode, modifier = Modifier.testTag("open_new_code")) {
            Text(stringResource(R.string.recover_undecided_new_code))
        }
    }
    OpenAnswerActions(o.code, waiting = retryAt != null && now < retryAt.toEpochMilli(), vm, onStartOver)
}

/** No readable answer (a failure before the vault answered, or random bytes): the text, and the ways on. */
@Composable
private fun OpenNoAnswer(state: RecoveryOpenUiState, vm: RecoveryOpenViewModel, onStartOver: (() -> Unit)?) {
    val failure = state.failure
    val unreadable = state.failureCode == RecoveryOpenUiState.CODE_UNREADABLE
    NoticeCard(
        kind = NoticeKind.WARNING,
        title = stringResource(R.string.onboarding_open_answer_title),
        body = failureText(if (unreadable) R.string.onboarding_open_unreadable else (failure ?: FailureKind.OTHER).messageRes()),
    )
    // The answer may have been a lost `ok` (§11.14.6): the PIN finishes it, without an attempt.
    if (unreadable || failure == FailureKind.NO_RESPONSE) {
        OutlinedButton(onClick = vm::finishLostOk, modifier = Modifier.testTag("open_lost_ok")) {
            Text(stringResource(R.string.onboarding_open_lost_title))
        }
    }
    TextButton(onClick = vm::again) { Text(stringResource(R.string.onboarding_open_try_again)) }
    onStartOver?.let { TextButton(onClick = it) { Text(stringResource(R.string.recover_start_over)) } }
}

/** Try again (when it can help and no wait runs), and Start over or "Start a new recovery". */
@Composable
private fun OpenAnswerActions(code: String, waiting: Boolean, vm: RecoveryOpenViewModel, onStartOver: (() -> Unit)?) {
    if (openCanTryAgain(code) && !waiting) {
        Button(onClick = vm::again, modifier = Modifier.testTag("open_again")) { Text(stringResource(R.string.onboarding_open_try_again)) }
    }
    if (onStartOver != null) {
        if (openNeedsNewRecovery(code)) {
            Button(onClick = onStartOver, modifier = Modifier.testTag("open_new_recovery")) {
                Text(stringResource(R.string.onboarding_open_new_recovery))
            }
        } else {
            TextButton(onClick = onStartOver) { Text(stringResource(R.string.recover_start_over)) }
        }
    }
}

/** "2 d 03:04:05" until [at]. */
internal fun countdown(at: Instant, now: Instant): String {
    val s = maxOf(0L, at.epochSecond - now.epochSecond)
    val d = s / DAY
    val rest = s % DAY
    val hms = "%02d:%02d:%02d".format(java.util.Locale.ROOT, rest / HOUR, rest % HOUR / MINUTE, rest % MINUTE)
    return if (d > 0) "$d d $hms" else hms
}

private const val TICK_MS = 1_000L
private const val DAY = 86_400L
private const val HOUR = 3_600L
private const val MINUTE = 60L

/** The resume of a lost `ok` found no recovery completed onto this phone (the unlock carried no `vault_bundle`). */
private const val CODE_NO_BUNDLE = "no_bundle"
