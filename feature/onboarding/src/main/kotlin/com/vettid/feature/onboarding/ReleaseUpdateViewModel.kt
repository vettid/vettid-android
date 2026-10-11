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
    /** What's new in the offered release (ANDROID-PLAN 0.1.31); null while it loads. Never gates the approval. */
    val notes: ReleaseNotes? = null,
) {
    val submitAllowed: Boolean get() = offer != null && !checkDue && pin.length >= MIN_PIN_LENGTH

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
    private val state = MutableStateFlow(ReleaseUpdateUiState(offer = updates.offer.value, loading = updates.offer.value == null))
    val uiState: StateFlow<ReleaseUpdateUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { updates.offer.collect { o -> state.update { it.copy(offer = o) } } }
        // What's new for the offered release (number and PCR0); a new target loads again. Each visit retries.
        viewModelScope.launch {
            updates.offer.map { it?.let { o -> o.target to o.between } }
                .distinctUntilChanged { a, b -> a?.first?.number == b?.first?.number && a?.first?.pcr0 == b?.first?.pcr0 }
                .collectLatest { t ->
                    state.update { it.copy(notes = null) }
                    if (t != null) {
                        val n = releaseNotes.whatsNew(t.first, t.second)
                        state.update { it.copy(notes = n) }
                    }
                }
        }
        viewModelScope.launch {
            updates.refreshOffer()
            state.update { it.copy(loading = false) }
        }
        viewModelScope.launch {
            combine(ownerCheck.ownerCheck, ownerCheck.lockedByOwnerCheck) { v, locked -> v?.gated(Instant.now()) == true || locked }
                .collect { due -> state.update { it.copy(checkDue = due) } }
        }
    }

    override fun setPin(v: String) = state.update { it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN)) }

    override fun approve() {
        val s = state.value
        val offer = s.offer ?: return
        if (!s.submitAllowed) return
        if (updates.start(s.pin, offer)) {
            state.update { it.copy(pin = "") }
        } else {
            state.update { it.copy(checkDue = updates.ownerCheckDue()) }
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

    init {
        viewModelScope.launch {
            var last: UpdateProgress? = null
            updates.progress.collect { p ->
                state.update { it.copy(progress = p) }
                // A new answer with a backoff (not the same one again): count it down.
                val before = last
                val changed = before == null || before.copy(starting = p?.starting ?: false) != p
                if (p != null && p.waitSeconds > 0 && changed) startWait(p.waitSeconds)
                last = p
            }
        }
    }

    override fun setPin(v: String) = state.update { it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN)) }

    override fun retry() {
        val s = state.value
        if (!s.retryAllowed) return
        state.update { it.copy(pin = "") }
        updates.retry(s.pin)
    }

    override fun askAbandon() = state.update { if (it.progress?.canAbandon == true) it.copy(abandonConfirm = true) else it }

    override fun dismissAbandon() = state.update { it.copy(abandonConfirm = false) }

    override fun confirmAbandon() {
        val s = state.value
        state.update { it.copy(abandonConfirm = false) }
        if (!s.retryAllowed || s.progress?.step != UpdateStep.NEW_RELEASE_FAILED) return
        state.update { it.copy(pin = "") }
        updates.abandon(s.pin)
    }

    override fun finish() {
        state.update { it.copy(pin = "", abandonConfirm = false) }
        updates.finish()
    }

    override fun onCleared() {
        state.update { it.copy(pin = "") }
    }

    private fun startWait(seconds: Long) {
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
        const val MAX_PIN = 32
        const val TICK_MS = 1000L
    }
}
