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
import com.vettid.core.data.vault.VaultProbe
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * (ANDROID-PLAN 0.1.95, VAULT-MESSAGING 0.73.0) A check got no answer and may have counted, and the re-read of the vault's
     * state got none either: [atLeast] tries are left, the fewest that may remain.
     */
    data class MaybeCounted(val atLeast: Int) : OwnerCheckMessage

    /** [shownCode]: (ANDROID-PLAN 0.1.57 (2)) the code `error_vault_code` names, or null. */
    data class Failed(val kind: FailureKind, val code: String?, val shownCode: String? = null) : OwnerCheckMessage
}

/**
 * (ANDROID-PLAN 0.1.95, VAULT-MESSAGING 0.73.0) The vault has probably locked itself (a failed or unanswered check left the
 * estimate of tries at 0) but the app does not know: no check is offered while the vault's state is re-read.
 */
enum class LockWatch {
    /** The ordinary screen. */
    NONE,

    /** "Probably locked, checking": no fields, Check in off, Lock vault on; the reads run. */
    CHECKING,

    /** The reads ended with no answer: "isn't answering", with Check again and Lock vault. */
    SILENT,
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
    /** (ANDROID-PLAN 0.1.95) [locked] was found by a read, and the cause is not known: the locked screen, not the ten-checks text. */
    val lockCauseUnknown: Boolean = false,
    /** (ANDROID-PLAN 0.1.95) The vault has probably locked itself and the app is checking. */
    val watch: LockWatch = LockWatch.NONE,
) {
    val submitAllowed: Boolean
        get() = !busy && !locking && !locked && watch == LockWatch.NONE && waitSeconds == 0L && pin.length >= MIN_PIN &&
            password.isNotEmpty()

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

    /** (ANDROID-PLAN 0.1.95) "Check again" after "isn't answering": the same reads, no check sent. */
    fun checkAgain() {}

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

    /** The schedule of reads while the vault is probably locked (ANDROID-PLAN 0.1.95), and the lock that keeps them one at a time. */
    private var watchJob: Job? = null
    private val readLock = Mutex()

    /** The mode [start] opened and [cancel] has not closed. */
    private var startedMode: OwnerCheckMode? = null

    init {
        viewModelScope.launch { repo.ownerCheck.collect { v -> state.update { it.copy(view = v) } } }
        // ANDROID-PLAN 0.1.93: the PIN kept for the password-only retry is forgotten when the app leaves the foreground (the
        // process, not this screen: navigation and rotation keep it); then the same screen asks for both.
        viewModelScope.launch {
            repo.inFront.collect { front ->
                if (!front) state.update { if (it.pinKept) it.copy(pin = "", password = "", pinKept = false, unlockedOpen = false) else it }
                // (ANDROID-PLAN 0.1.95) A return to the foreground reads the vault's state again while it is probably locked.
                if (front) readNowIfWatching()
            }
        }
        viewModelScope.launch { repo.relayReconnects.collect { readNowIfWatching() } }
        viewModelScope.launch { repo.lockCauseUnknown.collect { u -> state.update { it.copy(lockCauseUnknown = u) } } }
        viewModelScope.launch {
            repo.lockedByOwnerCheck.collect { l ->
                // Locked: only the lock text; the vault-is-open and tries lines, or a backoff's wait, would contradict it.
                if (l) {
                    ticker?.cancel()
                    watchJob?.cancel()
                }
                state.update {
                    if (l) {
                        it.copy(
                            locked = true, watch = LockWatch.NONE, pin = "", password = "", pinKept = false, unlockedWrong = null,
                            unlockedOpen = false,
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
        if (mode == OwnerCheckMode.GATED && after != null) followUp(after)
        viewModelScope.launch { runCatching { repo.refreshOwnerCheck() } }
    }

    /** Leaves the screen without a check (not while gated): forgets what was entered. */
    fun cancel() {
        startedMode = null
        ticker?.cancel()
        watchJob?.cancel()
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
            // (ANDROID-PLAN 0.1.95) A check with no answer, tries still estimated to be left: one re-read of the vault's state.
            val probe = if (needsReread(r)) repo.probeVault(lockCauseKnown = false) else null
            // §3.6.5: neither entry is kept beyond the answer. §11.14.5: a check that passed without the presence recorded
            // keeps the screen, with the notice, until the member goes on.
            val passed = r == OwnerCheckOutcome.Passed
            val unrecorded = passed && repo.presenceUnrecorded.value
            state.update { it.answered(r, passed, unrecorded, repo.foregroundGeneration == generation, probe) }
            if (r is OwnerCheckOutcome.Backoff) startBackoff(r.retryAfterSeconds)
            if (!repo.lockedByOwnerCheck.value && needsWatch(r)) beginWatch()
        }
    }

    // --- probably locked (ANDROID-PLAN 0.1.95, VAULT-MESSAGING 0.73.0) ---

    /** An answer that leaves the app unable to tell whether the vault locked: the estimate of tries left is 0 or less. */
    private fun needsWatch(r: OwnerCheckOutcome): Boolean = when (r) {
        is OwnerCheckOutcome.BadPassword -> r.estimated && (r.checksLeft ?: 0) <= 0
        is OwnerCheckOutcome.BadPin -> r.estimated && (r.checksLeft ?: 0) <= 0
        is OwnerCheckOutcome.Failed -> r.unanswered && (r.estimatedLeft ?: 1) <= 0
        else -> false
    }

    /** The check was not answered and tries are still estimated to be left: the vault's state is read once, with no check. */
    private fun needsReread(r: OwnerCheckOutcome): Boolean =
        r is OwnerCheckOutcome.Failed && r.unanswered && (r.estimatedLeft ?: 0) > 0

    /** What an outcome from before the screen opened (the unlock's check) leaves to do. */
    private fun followUp(r: OwnerCheckOutcome) {
        if (repo.lockedByOwnerCheck.value) return
        if (needsWatch(r)) {
            beginWatch()
        } else if (needsReread(r)) {
            viewModelScope.launch {
                val probe = repo.probeVault(lockCauseKnown = false) // a lock found here has no known cause
                state.update { it.copy(message = messageAfter(r, probe)) }
            }
        }
    }

    /** The message under the fields after [r] and the re-read [probe] that followed it (null: none was made). */
    private fun messageAfter(r: OwnerCheckOutcome, probe: VaultProbe?): OwnerCheckMessage? {
        val left = (r as? OwnerCheckOutcome.Failed)?.estimatedLeft
        return when {
            probe == VaultProbe.OPEN || probe == VaultProbe.LOCKED -> null
            probe == VaultProbe.NO_ANSWER && left != null -> OwnerCheckMessage.MaybeCounted(left)
            else -> messageOf(r)
        }
    }

    /** The first text, no fields and no check: the reads start at once. */
    private fun beginWatch() {
        ticker?.cancel()
        state.update {
            it.copy(
                pin = "", password = "", pinKept = false, unlockedWrong = null, unlockedOpen = false, waitSeconds = 0L,
                message = null, busy = false,
            )
        }
        runReads()
    }

    override fun checkAgain() {
        if (state.value.watch == LockWatch.SILENT) runReads()
    }

    /** The reads at once, then after about 5 s, 15 s, 1 min and 3 min (counted from the start), one at a time. */
    private fun runReads() {
        watchJob?.cancel()
        state.update { it.copy(watch = LockWatch.CHECKING) }
        watchJob = viewModelScope.launch {
            for (gap in READ_GAPS_MS) {
                delay(gap)
                if (readOnce()) return@launch
            }
            state.update { if (it.watch == LockWatch.CHECKING) it.copy(watch = LockWatch.SILENT) else it }
        }
    }

    private fun readNowIfWatching() {
        if (state.value.watch != LockWatch.NONE) viewModelScope.launch { readOnce() }
    }

    /**
     * One read while probably locked (never a check): `vault.status` answering means the vault is open, and the ordinary check
     * returns with the count from the answer; a lock found shows the lock text (the flag comes from the manager). True: settled.
     */
    private suspend fun readOnce(): Boolean = readLock.withLock {
        if (state.value.watch == LockWatch.NONE) return@withLock true
        when (repo.probeVault(lockCauseKnown = true)) {
            VaultProbe.OPEN, VaultProbe.LOCKED -> {
                state.update { it.copy(watch = LockWatch.NONE, message = null) }
                true
            }
            VaultProbe.NO_ANSWER -> false
        }
    }

    /** The state after the vault's answer [r]: neither entry is kept, except the unlock's PIN for another try of the password. */
    private fun OwnerCheckUiState.answered(
        r: OwnerCheckOutcome,
        passed: Boolean,
        unrecorded: Boolean,
        sameFront: Boolean,
        probe: VaultProbe? = null,
    ): OwnerCheckUiState {
        val keep = pinKept && sameFront && keepsPin(r)
        val left = (r as? OwnerCheckOutcome.BadPassword)?.checksLeft?.takeIf { keep && it > 0 }
        return copy(
            busy = false, pin = if (keep) pin else "", password = "", passed = passed && !unrecorded, passedUnrecorded = unrecorded,
            presenceUnrecorded = if (passed) repo.presenceUnrecorded.value else presenceUnrecorded,
            message = if (left != null) null else messageAfter(r, probe),
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
        // No answer keeps it unless the tries are estimated at 0 (then the vault has probably locked, and the PIN goes).
        is OwnerCheckOutcome.Failed -> r.unanswered && (r.estimatedLeft ?: 1) > 0
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

        /** The waits before each read while probably locked: at once, 5 s, 15 s, 1 min, 3 min from the start (VAULT-MESSAGING 0.73.0). */
        val READ_GAPS_MS = listOf(0L, 5_000L, 10_000L, 45_000L, 120_000L)
    }
}
