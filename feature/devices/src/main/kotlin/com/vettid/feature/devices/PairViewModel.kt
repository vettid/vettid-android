package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.devices.PairRole
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
    /** The sheet "Allow access now?" is open (only over the very comparison it was opened for). */
    val sheet: Boolean = false,
    /** A pairing was shown and the state returned to nothing by itself (a lock, a hold): it was interrupted, nothing paired. */
    val interrupted: Boolean = false,
    val session: SessionChoice = SessionChoice.HOUR_1,
    /** The code was copied: the button says so. */
    val copied: Boolean = false,
) {
    val stage: PairingStage get() = pairing.stage
}

/**
 * Pairing an agent (ANDROID-PLAN 0.1.53 AA2) or a desktop (owner decision 2026-11-05). The pairing itself lives in
 * [PairingRepository] (memory only); this holds
 * what the screen adds. Leaving the screen with a pairing not approved rejects it: an abandoned pairing grants nothing.
 */
@HiltViewModel
class PairViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: PairingRepository,
    ownerCheck: OwnerCheckRepository,
) : ViewModel() {
    private val held: Boolean = saved.get<Boolean>(PairRoute.HELD) ?: false
    private val role: PairRole = if (saved.get<Boolean>(PairRoute.DESKTOP) == true) PairRole.DESKTOP else PairRole.AGENT
    private val local = MutableStateFlow(PairUiState(held = held))

    /** This screen as the repository knows it (GC #7370): a token, so that a cleared screen is never held by the repository. */
    private val token = Any()

    /** The pairing the sheet was opened for. */
    private val sheetFor = MutableStateFlow<String?>(null)

    /**
     * The role of the last pairing seen that was not nothing: a held screen (opened from a feed item) has no route role, and
     * a cleared state (a lock, a hold) says AGENT whatever was held, so "New code" never takes its role from the repository.
     */
    private var seenRole: PairRole = role

    /** What a new code is made for: the button's role, or for a held screen the role of what it last showed. */
    private fun codeRole(): PairRole = if (held) seenRole else role

    /** A stage other than nothing was seen, and the screen is not being closed. */
    private var sawStage = false
    private val closing = MutableStateFlow(false)

    val uiState: StateFlow<PairUiState> = combine(local, repo.state, sheetFor, closing) { l, p, forId, leaving ->
        val st = p.stage
        if (st !is PairingStage.Idle) {
            sawStage = true
            seenRole = p.role
        }
        l.copy(
            // The screens say the role of what was shown, also once it was taken away (the lock's "This pairing has ended").
            pairing = if (st is PairingStage.Idle) p.copy(role = codeRole()) else p,
            sheet = st is PairingStage.Compare && forId == st.pending.pairingId,
            interrupted = sawStage && !leaving && st is PairingStage.Idle && p.error == null && !p.busy,
        )
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PairUiState(held = held))

    init {
        // From the feed item the pairing is whatever is held; from the button, a new code (an unfinished one is kept).
        if (!held) {
            // An earlier final stage or failure is cleared; (iOS #255) a refused approval another screen kept is not: only
            // its owner clears it.
            repo.acknowledge()
            // A pairing held for the other role is rejected and replaced by the repository in this one call. From here the
            // pairing is this screen's (GC #7370): an earlier screen cleared late touches none of it.
            viewModelScope.launch {
                repo.start(role, token)
                // (iOS #255) Now this screen owns a kept comparison: its refusal for the daily check goes once the check is
                // known to have passed. Not known: it stays, and the collector below clears it when the check passes later; a
                // view that only goes from unknown to passed fires no `checkCleared`, so the refusal's notice then stays until
                // the next approval (which ignores it). Only a comparison's refusal: a create that failed stays as it is.
                val s = repo.state.value
                if (checkPassed(ownerCheck) && s.stage is PairingStage.Compare && s.error == FailureKind.OWNER_CHECK_REQUIRED) {
                    repo.acknowledge(token)
                }
            }
        } else {
            repo.claim(token)
        }
        // The daily check passed: a refused approval may be sent again.
        viewModelScope.launch {
            ownerCheck.checkCleared().collect {
                if (repo.state.value.error == FailureKind.OWNER_CHECK_REQUIRED) repo.acknowledge(token)
            }
        }
    }

    /** "New code", or "Try again" after a code could not be made. */
    fun newCode() {
        local.update { it.copy(copied = false) }
        sheetFor.value = null
        sawStage = false
        viewModelScope.launch {
            repo.acknowledge(token)
            repo.start(codeRole(), token)
        }
    }

    fun copied() = local.update { it.copy(copied = true) }

    /** Cancel on the code screen: the vault is told, nothing is held. */
    fun cancel() {
        closing.value = true
        repo.leave(token)
    }

    /**
     * "The codes match" on the comparison [pairingId] shown (GC #7440): the sheet shows only over that very comparison (the
     * state's `sheet`), and its approval is bound to that id ([pair]).
     */
    fun codesMatch(pairingId: String) {
        sheetFor.value = pairingId
    }

    fun closeSheet() {
        sheetFor.value = null
    }

    fun choose(c: SessionChoice) = local.update { it.copy(session = c) }

    fun pair() {
        val s = local.value
        // Bound to the comparison the sheet showed (GC #7370).
        val id = sheetFor.value ?: return
        viewModelScope.launch {
            repo.approve(s.session.seconds, id)
        }
    }

    /** "They don't match", and Reject for a pairing that cannot be compared. */
    fun reject(pairingId: String) {
        sheetFor.value = null
        viewModelScope.launch { repo.reject(pairingId) }
    }

    /** A final stage was read. */
    fun done() {
        closing.value = true
        repo.acknowledge(token)
    }

    override fun onCleared() {
        // The screen is gone: a code not used, a create still out or a comparison not answered is rejected (best effort), never
        // kept, also after Done (Back on a comparison or while creating calls it). Only this screen's pairing (GC #7370): a
        // later screen's is left alone by the owner check.
        repo.leave(token)
        repo.acknowledge(token)
    }
}

/** (iOS #255) The daily check is known to be clear now: not due or held. Not known (no view yet): false. */
internal fun checkPassed(ownerCheck: OwnerCheckRepository, now: java.time.Instant = java.time.Instant.now()): Boolean =
    ownerCheck.ownerCheck.value?.let { !it.gated(now) } ?: false
