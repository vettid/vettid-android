package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.HoldOff
import com.vettid.core.data.vault.OwnerCheckOutcome
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.TimeScaleView
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
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** Why the check screen is open (VAULT-MESSAGING §3.6.5). */
enum class OwnerCheckMode {
    /** Past the deadline: the app is gated; never dismissible, only the check or locking the vault. */
    GATED,

    /** The member checks early ("check now"): resets the clock; may be cancelled. */
    VOLUNTARY,

    /** Turning the hold off, which only a check may do (§3.6.7); may be cancelled. */
    HOLD_OFF,
}

/** How long the hold stays off (§3.6.7: an optional end date at most 30 days ahead). */
@Suppress("MagicNumber") // the days are the choices
enum class HoldOffChoice(val days: Long?) {
    UNTIL_TURNED_ON(null),
    ONE_DAY(1),
    THREE_DAYS(3),
    ONE_WEEK(7),
    THIRTY_DAYS(30),
}

/** What the check screen says about the last answer. */
sealed interface OwnerCheckMessage {
    data class BadPin(val checksLeft: Int?) : OwnerCheckMessage

    data class BadPassword(val checksLeft: Int?) : OwnerCheckMessage

    data object Backoff : OwnerCheckMessage

    /** [shownCode]: (ANDROID-PLAN 0.1.57 (2)) the code `error_vault_code` names, or null. */
    data class Failed(val kind: FailureKind, val code: String?, val shownCode: String? = null) : OwnerCheckMessage
}

/** Immutable UI state of the owner-check screen. */
data class OwnerCheckUiState(
    val mode: OwnerCheckMode = OwnerCheckMode.GATED,
    val view: OwnerCheckView? = null,
    val pin: String = "",
    val password: String = "",
    val holdOff: HoldOffChoice = HoldOffChoice.ONE_WEEK,
    val busy: Boolean = false,
    val message: OwnerCheckMessage? = null,
    /** A running backoff's wait, when the vault said how long. */
    val waitSeconds: Long = 0,
    /** The check passed: the screen closes. */
    val passed: Boolean = false,
    val locking: Boolean = false,
    /**
     * (Release R, §11.14.5) The vault could not record that the member was here (`present_recorded: false`): the
     * presence notice, until a later answer or `vault.status` says the presence was recorded.
     */
    val presenceUnrecorded: Boolean = false,
    /** The check passed with the presence not recorded: the screen stays with the notice until the member goes on. */
    val passedUnrecorded: Boolean = false,
    /** (STAGING-TIME-SCALE) The vault's time scale: the interval as it runs, and the staging note. */
    val timeScale: TimeScaleView = TimeScaleView(),
    /**
     * (ANDROID-PLAN 0.1.93) The unlock's PIN was accepted and the check after it failed on the password (or backed off): the
     * PIN is kept in [pin] only while this screen is in front, and only the password is asked for, with "Check in".
     */
    val pinKept: Boolean = false,
    /** The tries left after the wrong password that followed the unlock ([pinKept]): the approved line under the counts. */
    val unlockedWrong: Int? = null,
    /** The check after the unlock ran into a backoff ([pinKept]): the vault is open, the password is asked for. */
    val unlockedOpen: Boolean = false,
    /** The vault locked after ten wrong checks (§3.6.4): the lock text, and no check is sent. */
    val locked: Boolean = false,
) {
    val submitAllowed: Boolean get() = !busy && !locking && !locked && waitSeconds == 0L && pin.length >= MIN_PIN && password.isNotEmpty()

    override fun toString(): String = "OwnerCheckUiState(mode=$mode, busy=$busy, message=$message, wait=$waitSeconds, passed=$passed)"

    companion object {
        /** A vault PIN is 6–32 digits (§3.6.1: `bad_request` otherwise). */
        const val MIN_PIN = 6
        const val MAX_PIN = 32
    }
}

/** What the check screen can ask for. */
interface OwnerCheckActions {
    fun setPin(v: String)
    fun setPassword(v: String)
    fun setHoldOff(choice: HoldOffChoice)
    fun submit()
    fun lockVault()


    /** After a check that passed with the presence notice: the member read it; the screen closes. */
    fun acknowledgePresence() {}
}

/**
 * The daily owner check (VAULT-MESSAGING §3.6.5): the PIN and the credential password on one screen, sent
 * together in one `vault.owner_check`, both dropped from memory once the vault answered. Shows which entry was
 * wrong, the checks left before the vault locks (10 − `failures`) and a running backoff; while the vault is
 * held, only the `vault.held` counts and the lock action besides. Turning the hold off rides on a check
 * (§3.6.7).
 */
@HiltViewModel
class OwnerCheckViewModel @Inject constructor(
    private val repo: OwnerCheckRepository,
    private val vault: VaultRepository,
) : ViewModel(), OwnerCheckActions {
    private val state = MutableStateFlow(OwnerCheckUiState(view = repo.ownerCheck.value, timeScale = vault.timeScale.value))
    val uiState: StateFlow<OwnerCheckUiState> = state.asStateFlow()
    private var ticker: Job? = null

    /** The mode [start] opened and [cancel] has not closed. */
    private var startedMode: OwnerCheckMode? = null

    init {
        viewModelScope.launch { repo.ownerCheck.collect { v -> state.update { it.copy(view = v) } } }
        // ANDROID-PLAN 0.1.93: the PIN kept for the password-only retry is forgotten when the app leaves the foreground (the
        // process, not this screen: navigation and rotation keep it); then the same screen asks for both.
        viewModelScope.launch {
            repo.inFront.collect { front ->
                if (!front) state.update { if (it.pinKept) it.copy(pin = "", password = "", pinKept = false, unlockedOpen = false) else it }
            }
        }
        viewModelScope.launch {
            repo.lockedByOwnerCheck.collect { l ->
                // Locked: only the lock text; the vault-is-open and tries lines, or a backoff's wait, would contradict it.
                if (l) ticker?.cancel()
                state.update {
                    if (l) {
                        it.copy(
                            locked = true, pin = "", password = "", pinKept = false, unlockedWrong = null, unlockedOpen = false,
                            waitSeconds = 0L, message = it.message.takeUnless { m -> m is OwnerCheckMessage.Backoff },
                        )
                    } else {
                        it.copy(locked = false)
                    }
                }
            }
        }
        viewModelScope.launch { repo.presenceUnrecorded.collect { p -> state.update { it.copy(presenceUnrecorded = p) } } }
        viewModelScope.launch { vault.timeScale.collect { t -> state.update { it.copy(timeScale = t) } } }
    }

    /** Opens the screen for [mode], with nothing entered; a check sent right after an unlock says how it went. */
    fun start(mode: OwnerCheckMode) {
        // A rotation recreates the screen, not this view model: the same mode already open keeps what it shows (the PIN kept in
        // memory, the failure line with the tries left), which a second start would take from the repository a second time.
        // A fresh outcome always wins over what is retained (a gate that cleared meanwhile, with no cancel).
        if (startedMode == mode && repo.unlockCheckOutcome.value == null) return
        startedMode = mode
        ticker?.cancel()
        val after = repo.unlockCheckOutcome.value
        repo.consumeUnlockCheckOutcome()
        // (ANDROID-PLAN 0.1.93) A check that failed on the password right after the unlock: the PIN is kept, the password asked.
        val retry = mode == OwnerCheckMode.GATED && (after is OwnerCheckOutcome.BadPassword || after is OwnerCheckOutcome.Backoff)
        val pin = if (retry) repo.takeUnlockPin() else null
        val wrong = (after as? OwnerCheckOutcome.BadPassword)?.checksLeft?.takeIf { pin != null && it > 0 }
        state.value = OwnerCheckUiState(
            mode = mode, view = repo.ownerCheck.value, message = after?.takeIf { wrong == null }?.let { messageOf(it) },
            presenceUnrecorded = repo.presenceUnrecorded.value, timeScale = vault.timeScale.value,
            pin = pin.orEmpty(), pinKept = pin != null, unlockedWrong = wrong,
            unlockedOpen = pin != null && after is OwnerCheckOutcome.Backoff,
        )
        if (after is OwnerCheckOutcome.Backoff) startBackoff(after.retryAfterSeconds)
        viewModelScope.launch { runCatching { repo.refreshOwnerCheck() } }
    }

    /** Leaves the screen without a check (not while gated): forgets what was entered. */
    fun cancel() {
        startedMode = null
        ticker?.cancel()
        state.update {
            OwnerCheckUiState(mode = it.mode, view = it.view, presenceUnrecorded = it.presenceUnrecorded, timeScale = it.timeScale)
        }
    }

    override fun acknowledgePresence() = state.update { if (it.passedUnrecorded) it.copy(passed = true) else it }

    override fun setPin(v: String) = state.update {
        it.copy(pin = v.filter { c -> c.isDigit() }.take(OwnerCheckUiState.MAX_PIN), message = null)
    }

    override fun setPassword(v: String) = state.update { it.copy(password = v, message = null) }

    override fun setHoldOff(choice: HoldOffChoice) = state.update { it.copy(holdOff = choice) }

    override fun submit() {
        val s = state.value
        if (!s.submitAllowed) return
        val holdOff = if (s.mode == OwnerCheckMode.HOLD_OFF) {
            HoldOff(s.holdOff.days?.let { Instant.now().plus(Duration.ofDays(it)) })
        } else {
            null
        }
        state.update { it.copy(busy = true, message = null) }
        // The app leaving and coming back while the request runs keeps no PIN (the generation counts the departures).
        val generation = repo.foregroundGeneration
        viewModelScope.launch {
            val r = try {
                repo.check(s.pin, s.password, holdOff)
            } catch (e: VaultFailure) {
                OwnerCheckOutcome.Failed(e.kind, e.code, e.shownCode)
            }
            // §3.6.5: neither entry is kept beyond the answer. §11.14.5: a check that passed without the presence recorded
            // keeps the screen, with the notice, until the member goes on.
            val passed = r == OwnerCheckOutcome.Passed
            val unrecorded = passed && repo.presenceUnrecorded.value
            state.update { it.answered(r, passed, unrecorded, repo.foregroundGeneration == generation) }
            if (r is OwnerCheckOutcome.Backoff) startBackoff(r.retryAfterSeconds)
        }
    }

    /** The state after the vault's answer [r]: neither entry is kept, except the unlock's PIN for another try of the password. */
    private fun OwnerCheckUiState.answered(
        r: OwnerCheckOutcome,
        passed: Boolean,
        unrecorded: Boolean,
        sameFront: Boolean,
    ): OwnerCheckUiState {
        val keep = pinKept && sameFront && keepsPin(r)
        val left = (r as? OwnerCheckOutcome.BadPassword)?.checksLeft?.takeIf { keep && it > 0 }
        return copy(
            busy = false, pin = if (keep) pin else "", password = "", passed = passed && !unrecorded, passedUnrecorded = unrecorded,
            presenceUnrecorded = if (passed) repo.presenceUnrecorded.value else presenceUnrecorded,
            message = if (left != null) null else messageOf(r),
            pinKept = keep, unlockedWrong = left, unlockedOpen = keep && r is OwnerCheckOutcome.Backoff,
        )
    }

    /**
     * Whether the kept PIN may serve another password-only try after [r]. No tries left: the tenth failure locks the vault, and
     * its `vault.locking` may come late or not at all, so the PIN goes now. A failure keeps it only when sending again can help
     * (the vault or the way to it did not answer).
     */
    private fun keepsPin(r: OwnerCheckOutcome): Boolean = when (r) {
        is OwnerCheckOutcome.BadPassword -> (r.checksLeft ?: 0) > 0
        is OwnerCheckOutcome.Backoff -> true
        is OwnerCheckOutcome.Failed -> r.kind in TRANSIENT
        OwnerCheckOutcome.Passed, is OwnerCheckOutcome.BadPin -> false
    }

    override fun lockVault() {
        if (state.value.locking) return
        // The kept PIN goes with the fields: a lock that fails leaves the ordinary check (both fields), not a hidden PIN field.
        state.update {
            it.copy(locking = true, pin = "", password = "", pinKept = false, unlockedWrong = null, unlockedOpen = false)
        }
        viewModelScope.launch {
            try {
                vault.lock()
            } catch (e: VaultFailure) {
                // Not expected (an unconfirmed lock stays pending and is retried), but never silent.
                state.update { it.copy(locking = false, message = OwnerCheckMessage.Failed(e.kind, e.code, e.shownCode)) }
            }
        }
    }

    private fun messageOf(r: OwnerCheckOutcome): OwnerCheckMessage? = when (r) {
        OwnerCheckOutcome.Passed -> null
        is OwnerCheckOutcome.BadPin -> OwnerCheckMessage.BadPin(r.checksLeft)
        is OwnerCheckOutcome.BadPassword -> OwnerCheckMessage.BadPassword(r.checksLeft)
        is OwnerCheckOutcome.Backoff -> OwnerCheckMessage.Backoff
        is OwnerCheckOutcome.Failed -> OwnerCheckMessage.Failed(r.kind, r.code, r.shownCode)
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
        const val TICK_MS = 1000L

        /** Failures after which the same check may be sent again with the kept PIN; any other drops it (fail secure). */
        val TRANSIENT = setOf(
            FailureKind.NETWORK, FailureKind.NO_RESPONSE, FailureKind.RATE_LIMITED, FailureKind.VAULT_UNAVAILABLE,
            FailureKind.SERVICE_PAUSED,
        )
    }
}
