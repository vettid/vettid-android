package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.HoldOff
import com.vettid.core.data.vault.OwnerCheckOutcome
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.OwnerCheckView
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

    data class Failed(val kind: FailureKind, val code: String?) : OwnerCheckMessage
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
) {
    val submitAllowed: Boolean get() = !busy && !locking && waitSeconds == 0L && pin.length >= MIN_PIN && password.isNotEmpty()

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
    private val state = MutableStateFlow(OwnerCheckUiState(view = repo.ownerCheck.value))
    val uiState: StateFlow<OwnerCheckUiState> = state.asStateFlow()
    private var ticker: Job? = null

    init {
        viewModelScope.launch { repo.ownerCheck.collect { v -> state.update { it.copy(view = v) } } }
    }

    /** Opens the screen for [mode], with nothing entered; a check sent right after an unlock says how it went. */
    fun start(mode: OwnerCheckMode) {
        ticker?.cancel()
        val after = repo.unlockCheckOutcome.value
        repo.consumeUnlockCheckOutcome()
        state.value = OwnerCheckUiState(mode = mode, view = repo.ownerCheck.value, message = after?.let { messageOf(it) })
        if (after is OwnerCheckOutcome.Backoff) startBackoff(after.retryAfterSeconds)
        viewModelScope.launch { runCatching { repo.refreshOwnerCheck() } }
    }

    /** Leaves the screen without a check (not while gated): forgets what was entered. */
    fun cancel() {
        ticker?.cancel()
        state.update { OwnerCheckUiState(mode = it.mode, view = it.view) }
    }

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
        viewModelScope.launch {
            val r = try {
                repo.check(s.pin, s.password, holdOff)
            } catch (e: VaultFailure) {
                OwnerCheckOutcome.Failed(e.kind, e.code)
            }
            // §3.6.5: neither entry is kept beyond the answer.
            state.update { it.copy(busy = false, pin = "", password = "", passed = r == OwnerCheckOutcome.Passed, message = messageOf(r)) }
            if (r is OwnerCheckOutcome.Backoff) startBackoff(r.retryAfterSeconds)
        }
    }

    override fun lockVault() {
        if (state.value.locking) return
        state.update { it.copy(locking = true, pin = "", password = "") }
        viewModelScope.launch {
            try {
                vault.lock()
            } catch (_: VaultFailure) {
                state.update { it.copy(locking = false) }
            }
        }
    }

    private fun messageOf(r: OwnerCheckOutcome): OwnerCheckMessage? = when (r) {
        OwnerCheckOutcome.Passed -> null
        is OwnerCheckOutcome.BadPin -> OwnerCheckMessage.BadPin(r.checksLeft)
        is OwnerCheckOutcome.BadPassword -> OwnerCheckMessage.BadPassword(r.checksLeft)
        is OwnerCheckOutcome.Backoff -> OwnerCheckMessage.Backoff
        is OwnerCheckOutcome.Failed -> OwnerCheckMessage.Failed(r.kind, r.code)
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
    }
}
