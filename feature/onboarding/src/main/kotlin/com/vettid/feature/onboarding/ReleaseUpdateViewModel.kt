package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.ReleaseNotesRepository
import com.vettid.core.data.vault.ReleaseUpdateOffer
import com.vettid.core.data.vault.ReleaseUpdateRepository
import com.vettid.core.data.vault.UpdateProgress
import com.vettid.core.data.vault.UpdateStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Immutable UI state of the update screen ("Update now"). */
data class ReleaseUpdateUiState(
    val loading: Boolean = true,
    val offer: ReleaseUpdateOffer? = null,
    val pin: String = "",
    /** The owner check is due or held (§3.6.5): it comes first; the shell's gate shows it. */
    val checkDue: Boolean = false,
    /**
     * (VAULT-MESSAGING 0.45.0 §11.14.9) Why no update can start now: a PIN or password change being decided, or an
     * uncertain PIN, in the gate's words; null when it can.
     */
    val changeHold: com.vettid.core.data.vault.FailureKind? = null,
    /** What's new in the offered release (ANDROID-PLAN 0.1.31); null while it loads. Never gates the approval. */
    val notes: ReleaseNotes? = null,
) {
    val changeUnresolved: Boolean get() = changeHold != null

    /** The PIN field can be used: an offer, and neither the owner check nor a change comes first. */
    val pinAllowed: Boolean get() = offer != null && !checkDue && !changeUnresolved

    val submitAllowed: Boolean get() = pinAllowed && pin.length >= MIN_PIN_LENGTH

    override fun toString(): String = "ReleaseUpdateUiState(loading=$loading, offer=${offer?.target?.number}, checkDue=$checkDue)"

    private companion object {
        const val MIN_PIN_LENGTH = 4
    }
}

/** What the update screen can ask for. */
interface ReleaseUpdateActions {
    fun setPin(v: String)

    /** "Approve and update": the member's explicit approval of the release shown, with the vault PIN. */
    fun approve()
}

/**
 * The update screen (owner decision 2026-10-09): the release offered, its fingerprint and notes, and "Approve and
 * update" with the vault PIN. The approval leaves this screen at once for the update's own screens at the root
 * ([ReleaseUpdateFlowViewModel]); the PIN is handed over and dropped here.
 */
@HiltViewModel
class ReleaseUpdateViewModel @Inject constructor(
    private val updates: ReleaseUpdateRepository,
    ownerCheck: OwnerCheckRepository,
    private val releaseNotes: ReleaseNotesRepository,
) : ViewModel(), ReleaseUpdateActions {
    private val state = MutableStateFlow(
        ReleaseUpdateUiState(
            offer = updates.offer.value,
            loading = updates.offer.value == null,
            changeHold = updates.changeHold.value,
        ),
    )
    val uiState: StateFlow<ReleaseUpdateUiState> = state.asStateFlow()

    /** (§11.14.9 step 2; chosen) Which PIN the vault expects, read when the PIN field becomes usable. */
    private val keypad = KeypadReading { updates.pinGeneration }

    /** Every change of the state, with the keypad's reading following whether the PIN field can be used. */
    private fun set(f: (ReleaseUpdateUiState) -> ReleaseUpdateUiState) {
        val wasEmpty = state.value.pin.isEmpty()
        state.update { s -> f(s).let { n -> if (!n.pinAllowed && n.pin.isNotEmpty()) n.copy(pin = "") else n } }
        keypad.follow(state.value.pinAllowed, wasEmpty)
    }

    init {
        keypad.follow(state.value.pinAllowed)
        viewModelScope.launch { updates.offer.collect { o -> set { it.copy(offer = o) } } }
        // What's new for the offered release (number and PCR0); a new target loads again. Each visit retries.
        viewModelScope.launch {
            updates.offer.map { it?.let { o -> o.target to o.between } }
                .distinctUntilChanged { a, b -> a?.first?.number == b?.first?.number && a?.first?.pcr0 == b?.first?.pcr0 }
                .collectLatest { t ->
                    set { it.copy(notes = null) }
                    if (t != null) {
                        val n = releaseNotes.whatsNew(t.first, t.second)
                        set { it.copy(notes = n) }
                    }
                }
        }
        // (N6) Followed, so that the PIN field opens again once the change is decided, and the text is the gate's.
        viewModelScope.launch { updates.changeHold.collect { h -> set { it.copy(changeHold = h) } } }
        viewModelScope.launch {
            updates.refreshOffer()
            set { it.copy(loading = false) }
        }
        viewModelScope.launch {
            combine(ownerCheck.ownerCheck, ownerCheck.lockedByOwnerCheck, ownerCheck.lockCauseUnknown) { v, locked, unknown ->
                v?.gated(Instant.now()) == true || (locked && !unknown) // a lock of unknown cause asks for no password
            }
                .collect { due -> set { it.copy(checkDue = due) } }
        }
    }

    override fun setPin(v: String) = set { it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN)) }

    override fun approve() {
        val s = state.value
        val offer = s.offer ?: return
        if (!s.submitAllowed) return
        if (updates.start(s.pin, offer, keypad.take(s.pinAllowed))) {
            set { it.copy(pin = "") }
        } else {
            set { it.copy(checkDue = updates.ownerCheckDue(), changeHold = updates.changeHold.value) }
        }
    }

    private companion object {
        const val MAX_PIN = 32
    }
}

/** Immutable UI state of the update's own screens (progress, outcome, the way back). */
data class ReleaseUpdateFlowUiState(
    val progress: UpdateProgress? = null,
    val pin: String = "",
    /** The enclave's backoff, counted down here (§11.8). */
    val waitSeconds: Long = 0,
    /** "Return to release N" asks first. */
    val abandonConfirm: Boolean = false,
) {
    val pinAllowed: Boolean get() = waitSeconds == 0L && progress?.waiting == true

    val retryAllowed: Boolean get() = pinAllowed && pin.length >= MIN_PIN_LENGTH

    override fun toString(): String = "ReleaseUpdateFlowUiState(step=${progress?.step}, wait=$waitSeconds)"

    private companion object {
        const val MIN_PIN_LENGTH = 4
    }
}

/** What the update's screens can ask for. */
interface ReleaseUpdateFlowActions {
    fun setPin(v: String)

    fun retry()

    fun askAbandon()

    fun dismissAbandon()

    fun confirmAbandon()

    fun finish()
}

/** The update under way, shown by the root of the UI over every phase ([ReleaseUpdateRepository.progress]). */
@HiltViewModel
class ReleaseUpdateFlowViewModel @Inject constructor(private val updates: ReleaseUpdateRepository) :
    ViewModel(), ReleaseUpdateFlowActions {
    private val state = MutableStateFlow(ReleaseUpdateFlowUiState(progress = updates.progress.value))
    val uiState: StateFlow<ReleaseUpdateFlowUiState> = state.asStateFlow()
    private var ticker: Job? = null

    /** (§11.14.9 step 2; chosen) Which PIN the vault expects, read when a step waits for the PIN. */
    private val keypad = KeypadReading { updates.pinGeneration }

    /** Every change of the state, with the keypad's reading following whether the PIN can be typed. */
    private fun set(f: (ReleaseUpdateFlowUiState) -> ReleaseUpdateFlowUiState) {
        val wasEmpty = state.value.pin.isEmpty()
        state.update { s -> f(s).let { n -> if (!n.pinAllowed && n.pin.isNotEmpty()) n.copy(pin = "") else n } }
        keypad.follow(state.value.pinAllowed, wasEmpty)
    }

    init {
        keypad.follow(state.value.pinAllowed)
        viewModelScope.launch {
            var last: UpdateProgress? = null
            updates.progress.collect { p ->
                set { it.copy(progress = p) }
                // A new answer with a backoff (not the same one again): count it down.
                val before = last
                val changed = before == null || before.copy(starting = p?.starting ?: false) != p
                if (p != null && p.waitSeconds > 0 && changed) startWait(p.waitSeconds)
                last = p
            }
        }
    }

    override fun setPin(v: String) = set { it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN)) }

    override fun retry() {
        val s = state.value
        if (!s.retryAllowed) return
        val taken = keypad.take(s.pinAllowed)
        set { it.copy(pin = "") }
        updates.retry(s.pin, taken)
    }

    override fun askAbandon() = set { if (it.progress?.canAbandon == true) it.copy(abandonConfirm = true) else it }

    override fun dismissAbandon() = set { it.copy(abandonConfirm = false) }

    override fun confirmAbandon() {
        val s = state.value
        set { it.copy(abandonConfirm = false) }
        if (!s.retryAllowed || s.progress?.step != UpdateStep.NEW_RELEASE_FAILED) return
        val taken = keypad.take(s.pinAllowed)
        set { it.copy(pin = "") }
        updates.abandon(s.pin, taken)
    }

    override fun finish() {
        set { it.copy(pin = "", abandonConfirm = false) }
        updates.finish()
    }

    override fun onCleared() {
        set { it.copy(pin = "") }
    }

    private fun startWait(seconds: Long) {
        ticker?.cancel()
        set { it.copy(waitSeconds = seconds) }
        ticker = viewModelScope.launch {
            while (state.value.waitSeconds > 0) {
                delay(TICK_MS)
                set { it.copy(waitSeconds = (it.waitSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    private companion object {
        const val MAX_PIN = 32
        const val TICK_MS = 1000L
    }
}

/**
 * (VAULT-MESSAGING §11.14.9 step 2; chosen, not in the spec) Which PIN the vault expects ([read]: the repository's
 * `pinGeneration`), read when the keypad becomes usable: a change sent or decided while the member types moves it, and
 * the PIN is then not sent but asked for again. Dropped while the keypad cannot be used; read again each time it can.
 * Used once: handing a PIN over empties the keypad and takes a fresh reading for the next PIN at once (a step refused
 * without the keypad ever closing, as a PIN taken before a change is, then goes with a new one).
 */
internal class KeypadReading(private val read: () -> Int) {
    var generation: Int? = null
        private set

    /** The reading for the PIN handed over now, and a fresh one for the next PIN when the keypad stays usable ([allowed]). */
    fun take(allowed: Boolean): Int? {
        val taken = generation
        generation = null
        follow(allowed)
        return taken
    }

    /**
     * The keypad can be used ([allowed]) or not. [wasEmpty]: no digit was typed before this change, so the reading is
     * taken afresh (the PIN is typed from here on): a change between the keypad's opening and the first digit is then
     * not counted against the PIN.
     */
    fun follow(allowed: Boolean, wasEmpty: Boolean = false) {
        if (!allowed) {
            generation = null
        } else if (generation == null || wasEmpty) {
            generation = read()
        }
    }
}
