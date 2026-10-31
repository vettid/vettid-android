package com.vettid.feature.votes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.checkCleared
import com.vettid.core.data.votes.BallotState
import com.vettid.core.data.votes.ResultsOutcome
import com.vettid.core.data.votes.VoteDetail
import com.vettid.core.data.votes.VoteHint
import com.vettid.core.data.votes.VoteRules
import com.vettid.core.data.votes.VoteSummary
import com.vettid.core.data.votes.VotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** A request of this screen that failed: said plainly, never silent. [code] is the vault's own code when it gave one. */
data class VoteProblem(val kind: FailureKind, val code: String?, val shownCode: String?)

/** Where the screen is in a cast (ANDROID-PLAN 0.1.102 screens 2 and 3). */
enum class CastStep { NONE, CONFIRM, PASSWORD, SENDING }

/** Immutable UI state of one vote (screens 2 to 7). */
data class VoteUiState(
    val connectionId: String = "",
    val proposalId: String = "",
    /** The vote as the list has it now (the pair identifies it). */
    val summary: VoteSummary? = null,
    /** The vote with its text and choices (`vote.get`); the member's choice is in it once cast. Memory only. */
    val detail: VoteDetail? = null,
    val serviceName: String = "",
    val hint: VoteHint = VoteHint(),
    /** The vault was asked at least once (a missing [summary] after that is a vote that is not there). */
    val loaded: Boolean = false,
    val loadError: VoteProblem? = null,
    val selected: String? = null,
    val cast: CastStep = CastStep.NONE,
    val password: String = "",
    val passwordError: VoteProblem? = null,
    /** The end of a password backoff (`retry_after`). */
    val retryUntil: Instant? = null,
    val castProblem: VoteProblem? = null,
    /** The lock of the unlock window this screen's cast opened failed (told once). */
    val windowNotClosed: Boolean = false,
    val checking: Boolean = false,
    val checkProblem: VoteProblem? = null,
    /** What the last **Check again** came to when it was not handed to the vault: the site had nothing, or could not be reached. */
    val checkOutcome: ResultsOutcome? = null,
    /** "Is this your vote?" is open. */
    val restoreDialog: Boolean = false,
    val restoreBusy: Boolean = false,
    val restoreProblem: VoteProblem? = null,
    val proofWarning: Boolean = false,
    val proofBusy: Boolean = false,
    val proofProblem: VoteProblem? = null,
    /** The vault's evidence waits for the member to choose where to save it ("Save to…"); never logged or kept longer. */
    val proofReady: Boolean = false,
    val proofSaved: Boolean = false,
    val proofSaveFailed: Boolean = false,
    /** The proof was written in this visit: **Release** is offered from then on (the vault checks it again). */
    val proofExported: Boolean = false,
    val releaseDialog: Boolean = false,
    val pin: String = "",
    val releaseBusy: Boolean = false,
    val releaseProblem: VoteProblem? = null,
    val released: Boolean = false,
) {
    /** Whether Cast your vote is enabled: a choice is selected, the vault says it may be cast, nothing waits on a restore. */
    val canCast: Boolean
        get() = summary != null && selected != null && detail?.choices?.any { it.id == selected } == true &&
            VoteRules.castAllowed(summary) && cast == CastStep.NONE

    /** The label of the member's own choice (once cast), from the choices as sent. */
    val choiceLabel: String?
        get() = detail?.choiceId?.let { id -> detail.choices.firstOrNull { it.id == id }?.label ?: id }

    /** The label of the ballot the service holds, while a restore asks "Is this your vote?". */
    val restoreLabel: String?
        get() = detail?.serviceBallotChoiceId?.let { id -> detail.choices.firstOrNull { it.id == id }?.label ?: id }

    /** The dialog is offered only when the vault named a ballot to ask about (any other unsigned ballot is a problem). */
    val restoreAsked: Boolean get() = summary?.confirmPending == true && restoreLabel != null
}

/**
 * One vote (ANDROID-PLAN 0.1.102 to 0.1.108 §4 "Votes", screens 2 to 7): its text, the choices, the cast with its confirm
 * sheet and the unlock window prompt, the states in words, the results, **Save the proof**, **Release** and "Is this your
 * vote?". Identified by the pair (connection, proposal). Fails secure: a request that fails leaves the screen as it was and
 * says so; a cast is never retried by the screen, only by the member.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class VoteViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: VotesRepository,
    connections: ConnectionsRepository,
    private val documents: ProofDocuments,
    ownerCheck: OwnerCheckRepository,
) : ViewModel() {
    private val connectionId: String = saved.pair().first
    private val proposalId: String = saved.pair().second
    private val local = MutableStateFlow(VoteUiState(connectionId = connectionId, proposalId = proposalId))

    /** The evidence the vault gave, until the member saves it or leaves; only in memory. */
    private var proof: String? = null
    private var loadJob: Job? = null
    private var lastSummary: VoteSummary? = null

    val uiState: StateFlow<VoteUiState> =
        combine(local, repo.votes, repo.hints, connections.connections, repo.windowNotClosed) { s, list, hints, cs, lock ->
        val summary = list.firstOrNull { it.connectionId == connectionId && it.proposalId == proposalId }
        s.copy(
            summary = summary,
            serviceName = serviceName(cs.firstOrNull { it.id == connectionId }),
            hint = hints.of(summary),
            windowNotClosed = lock,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VoteUiState(connectionId = connectionId, proposalId = proposalId))

    init {
        refresh()
        // A change of the vote (a ballot answered, a check made) reads it again: the choice and the tally appear then.
        viewModelScope.launch {
            repo.votes.collect { list ->
                val now = list.firstOrNull { it.connectionId == connectionId && it.proposalId == proposalId }
                if (now != null && lastSummary != null && now != lastSummary) loadDetail()
                if (now != null) lastSummary = now
                if (now?.check?.state == com.vettid.core.data.votes.CheckState.GAVE_UP) repo.diagnoseGaveUp(connectionId, proposalId)
            }
        }
        // The daily check passed (or the hold ended): a vote refused because it was due reads again by itself.
        viewModelScope.launch {
            ownerCheck.checkCleared().collect {
                if (local.value.loadError?.kind == FailureKind.OWNER_CHECK_REQUIRED) refresh()
            }
        }
    }

    /** On opening, on Retry, and on a return to the screen: the list, the vote, and the public site's copy of it. */
    fun refresh() {
        local.update { it.copy(loadError = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                repo.refresh()
            } catch (e: VaultFailure) {
                local.update { it.copy(loadError = problem(e), loaded = true) }
                return@launch
            }
            loadDetail()
            // The vote's own documents are fetched when its screen opens, so that the vault can compare it with the public copy.
            runCatching { repo.siteCheck(connectionId, proposalId) }
        }
    }

    private suspend fun loadDetail() {
        try {
            val d = repo.get(connectionId, proposalId)
            local.update {
                it.copy(
                    detail = d,
                    loaded = true,
                    loadError = null,
                    selected = it.selected?.takeIf { s -> d.choices.any { c -> c.id == s } },
                )
            }
        } catch (e: VaultFailure) {
            local.update { it.copy(loaded = true, loadError = if (it.detail == null) problem(e) else it.loadError) }
        }
    }

    fun select(choiceId: String) = local.update { if (it.cast == CastStep.NONE) it.copy(selected = choiceId, castProblem = null) else it }

    /** Cast your vote: the confirm sheet, with the fixed disclosure. Nothing is sent. */
    fun askCast() {
        if (local.value.cast != CastStep.NONE || !canCastNow(local.value)) return
        local.update { it.copy(cast = CastStep.CONFIRM, castProblem = null) }
    }

    fun dismissCast() = local.update { it.copy(cast = CastStep.NONE, password = "", passwordError = null) }

    /** **Cast vote** in the sheet: at once when the unlock window is open, else the credential password is asked for first. */
    fun confirmCast() {
        val s = local.value
        if (s.cast != CastStep.CONFIRM || !canCastNow(s)) return
        if (repo.windowOpen()) send(null) else local.update { it.copy(cast = CastStep.PASSWORD, passwordError = null) }
    }

    /** The vault's word as the list has it now (not as the screen last drew it), a choice in the list sent, and nothing waiting. */
    private fun canCastNow(s: VoteUiState): Boolean {
        val summary = repo.votes.value.firstOrNull { it.connectionId == connectionId && it.proposalId == proposalId } ?: return false
        return VoteRules.castAllowed(summary) && s.selected != null && s.detail?.choices?.any { it.id == s.selected } == true
    }

    fun setPassword(v: String) = local.update { it.copy(password = v, passwordError = null) }

    /** **Confirm** in the password prompt. */
    fun submitPassword() {
        val s = local.value
        if (s.cast != CastStep.PASSWORD || s.password.isEmpty()) return
        if (s.retryUntil?.isAfter(Instant.now()) == true) return
        send(s.password)
    }

    private fun send(password: String?) {
        val s = local.value
        val choice = s.selected ?: return
        local.update { it.copy(cast = CastStep.SENDING, castProblem = null, passwordError = null) }
        viewModelScope.launch {
            try {
                repo.cast(connectionId, proposalId, choice, password)
                // The password is held only while the request runs.
                local.update { it.copy(cast = CastStep.NONE, password = "", selected = null, retryUntil = null) }
                loadDetail()
            } catch (e: VaultFailure) {
                onCastFailure(e)
            }
        }
    }

    private fun onCastFailure(e: VaultFailure) {
        val p = problem(e)
        // Whatever failed, the vote is read again: a cast whose answer was lost may have been sent, and the list says so.
        refresh()
        when (e.kind) {
            // The window was closed after all: the prompt, then the same request once.
            FailureKind.CREDENTIAL_LOCKED -> local.update { it.copy(cast = CastStep.PASSWORD, password = "", passwordError = null) }
            FailureKind.BAD_PASSWORD, FailureKind.BACKOFF, FailureKind.CREDENTIAL_FROZEN, FailureKind.ROTATION_REQUIRED ->
                local.update {
                    it.copy(
                        cast = CastStep.PASSWORD, password = "", passwordError = p,
                        retryUntil = if (e.kind == FailureKind.BACKOFF) Instant.now().plusSeconds(e.retryAfterSeconds) else null,
                    )
                }
            else -> {
                // A refusal of the vault (closed, closing, already voted…) or no answer: the screen says so and reads the vote again.
                // `already_voted` is not a failure to tell: the re-read ballot says what was recorded.
                val shown = p.takeUnless { it.code == "already_voted" }
                local.update { it.copy(cast = CastStep.NONE, password = "", castProblem = shown) }
            }
        }
    }

    fun windowNoticeShown() = repo.windowNoticeShown()

    fun dismissCastProblem() = local.update { it.copy(castProblem = null) }

    /** Check with the service / Check again (`vote.check`, and the results for a closed vote). */
    fun checkWithService() {
        val s = repo.votes.value.firstOrNull { it.connectionId == connectionId && it.proposalId == proposalId } ?: return
        if (local.value.checking) return
        local.update { it.copy(checking = true, checkProblem = null, checkOutcome = null) }
        viewModelScope.launch {
            try {
                // A closed vote: the results from the site, handed to the vault; else the service is asked (`vote.check`).
                val out = if (s.closed) repo.checkResults(connectionId, proposalId) else { repo.check(connectionId, proposalId); null }
                local.update { it.copy(checking = false, checkOutcome = out) }
            } catch (e: VaultFailure) {
                local.update { it.copy(checking = false, checkProblem = problem(e)) }
            }
        }
    }

    fun dismissCheckProblem() = local.update { it.copy(checkProblem = null) }

    // ---- Restore ----

    fun answerRestore(mine: Boolean) {
        if (local.value.restoreBusy) return
        local.update { it.copy(restoreBusy = true, restoreProblem = null) }
        viewModelScope.launch {
            try {
                val state = repo.confirm(connectionId, proposalId, mine)
                local.update { it.copy(restoreBusy = false, restoreDialog = false) }
                if (state == BallotState.RECORDED || state == BallotState.DISPUTED) loadDetail()
            } catch (e: VaultFailure) {
                local.update { it.copy(restoreBusy = false, restoreProblem = problem(e)) }
            }
        }
    }

    // ---- The proof ----

    /** **Save the proof**: the warning first. */
    fun askProof() = local.update { it.copy(proofWarning = true, proofProblem = null, proofSaved = false, proofSaveFailed = false) }

    fun dismissProofWarning() = local.update { it.copy(proofWarning = false) }

    /** The warning was accepted: the vault's evidence is fetched, then the screen offers "Save to…". */
    fun fetchProof() {
        if (local.value.proofBusy) return
        local.update { it.copy(proofWarning = false, proofBusy = true, proofProblem = null) }
        viewModelScope.launch {
            try {
                proof = repo.evidence(connectionId, proposalId)
                local.update { it.copy(proofBusy = false, proofReady = true) }
            } catch (e: VaultFailure) {
                local.update { it.copy(proofBusy = false, proofProblem = problem(e)) }
            }
        }
    }

    /** The file name offered to "Save to…": the proposal's id, never the title or the choice. */
    fun proofFileName() = "vettid-vote-$proposalId.json"

    /** The member chose where to save the proof ([uri] null: they cancelled). The text is written unchanged, then forgotten. */
    fun saveProofTo(uri: String?) {
        val text = proof
        proof = null
        if (uri == null || text == null) {
            local.update { it.copy(proofReady = false) }
            return
        }
        viewModelScope.launch {
            try {
                documents.write(uri) { it.write(text.toByteArray(Charsets.UTF_8)) }
                local.update { it.copy(proofReady = false, proofSaved = true, proofSaveFailed = false, proofExported = true) }
            } catch (_: java.io.IOException) {
                documents.discard(uri)
                local.update { it.copy(proofReady = false, proofSaved = false, proofSaveFailed = true) }
            } catch (_: SecurityException) {
                documents.discard(uri)
                local.update { it.copy(proofReady = false, proofSaved = false, proofSaveFailed = true) }
            }
        }
    }

    fun proofShown() = local.update { it.copy(proofSaved = false, proofSaveFailed = false) }

    // ---- Release ----

    fun askRelease() = local.update { if (it.proofExported && !it.released) it.copy(releaseDialog = true) else it }

    fun dismissRelease() = local.update { it.copy(releaseDialog = false, pin = "", releaseProblem = null) }

    fun setPin(v: String) = local.update { it.copy(pin = v.filter { c -> c in '0'..'9' }, releaseProblem = null) }

    fun release() {
        val s = local.value
        if (s.releaseBusy || s.pin.isEmpty()) return
        val pin = s.pin
        local.update { it.copy(releaseBusy = true, releaseProblem = null) }
        viewModelScope.launch {
            try {
                repo.release(connectionId, proposalId, pin)
                local.update { it.copy(releaseBusy = false, releaseDialog = false, pin = "", released = true) }
            } catch (e: VaultFailure) {
                local.update { it.copy(releaseBusy = false, pin = "", releaseProblem = problem(e)) }
            }
        }
    }

    override fun onCleared() {
        // The evidence is the member's own: it goes with the screen (and the choice with the detail).
        proof = null
        super.onCleared()
    }

    private fun problem(e: VaultFailure) = VoteProblem(e.kind, e.code, e.shownCode)
}
