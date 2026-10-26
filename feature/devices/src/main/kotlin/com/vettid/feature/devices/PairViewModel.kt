package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.devices.PairingRepository
import com.vettid.core.data.devices.PairingStage
import com.vettid.core.data.devices.PairingState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.checkCleared
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The first access offered when a pairing is approved (ANDROID-PLAN 0.1.53 AA2 step 3, decision 5). */
@Suppress("MagicNumber") // the table of lengths is the point
enum class SessionChoice(val seconds: Int?) {
    NONE(null),
    MINUTES_15(15 * MINUTE),
    HOUR_1(HOUR),
    HOURS_4(4 * HOUR),
    HOURS_8(8 * HOUR),
    HOURS_24(24 * HOUR),
}

private const val MINUTE = 60
private const val HOUR = 60 * MINUTE

/** Immutable UI state of the pairing screens. */
data class PairUiState(
    val pairing: PairingState = PairingState(),
    /** Opened from the feed item "A device asks to pair": with no pairing held, the phone can't check it any more. */
    val held: Boolean = false,
    /** The sheet "Allow access now?" is open (only over the compare stage). */
    val sheet: Boolean = false,
    val session: SessionChoice = SessionChoice.HOUR_1,
    /** The code was copied: the button says so. */
    val copied: Boolean = false,
) {
    val stage: PairingStage get() = pairing.stage
}

/**
 * Pairing an agent (ANDROID-PLAN 0.1.53 AA2). The pairing itself lives in [PairingRepository] (memory only); this holds
 * what the screen adds. Leaving the screen with a pairing not approved rejects it: an abandoned pairing grants nothing.
 */
@HiltViewModel
class PairViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: PairingRepository,
    ownerCheck: OwnerCheckRepository,
) : ViewModel() {
    private val held: Boolean = saved.get<Boolean>(PairRoute.HELD) ?: false
    private val local = MutableStateFlow(PairUiState(held = held))

    val uiState: StateFlow<PairUiState> = combine(local, repo.state) { l, p ->
        l.copy(pairing = p, sheet = l.sheet && p.stage is PairingStage.Compare)
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PairUiState(held = held))

    init {
        // From the feed item the pairing is whatever is held; from the button, a new code (an unfinished one is kept).
        if (!held) {
            repo.acknowledge()
            viewModelScope.launch { repo.start() }
        }
        // The daily check passed: a refused approval may be sent again.
        viewModelScope.launch {
            ownerCheck.checkCleared().collect {
                if (repo.state.value.error == FailureKind.OWNER_CHECK_REQUIRED) repo.acknowledge()
            }
        }
    }

    /** "New code", or "Try again" after a code could not be made. */
    fun newCode() {
        local.update { it.copy(copied = false, sheet = false) }
        viewModelScope.launch {
            repo.acknowledge()
            repo.start()
        }
    }

    fun copied() = local.update { it.copy(copied = true) }

    /** Cancel on the code screen: the vault is told, nothing is held. */
    fun cancel() = repo.leave()

    fun codesMatch() = local.update { it.copy(sheet = true) }

    fun closeSheet() = local.update { it.copy(sheet = false) }

    fun choose(c: SessionChoice) = local.update { it.copy(session = c) }

    fun pair() {
        val s = local.value
        viewModelScope.launch {
            repo.approve(s.session.seconds)
            // Only a refused daily check keeps the comparison (and the sheet) open.
            if (repo.state.value.stage !is PairingStage.Compare) local.update { it.copy(sheet = false) }
        }
    }

    /** "They don't match", and Reject for a pairing that cannot be compared. */
    fun reject() {
        local.update { it.copy(sheet = false) }
        viewModelScope.launch { repo.reject() }
    }

    /** A final stage was read. */
    fun done() = repo.acknowledge()

    override fun onCleared() {
        // The screen is gone: a code not used or a comparison not answered is rejected (best effort), never kept.
        repo.leave()
        repo.acknowledge()
    }
}
