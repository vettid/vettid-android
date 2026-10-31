package com.vettid.feature.votes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.checkCleared
import com.vettid.core.data.votes.DroppedNotice
import com.vettid.core.data.votes.ProblemsNotice
import com.vettid.core.data.votes.ReconnectEntry
import com.vettid.core.data.votes.VoteHint
import com.vettid.core.data.votes.VoteRules
import com.vettid.core.data.votes.VoteSummary
import com.vettid.core.data.votes.VotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The name a service connection is shown by: the name it gave (cleaned), else the connection's title; empty when neither shows. */
internal fun serviceName(c: ConnectionInfo?): String = AccountNames.cleaned(c?.name) ?: AccountNames.cleaned(c?.displayName).orEmpty()

/** Immutable UI state of the Votes list (ANDROID-PLAN 0.1.102 §4 "Votes", screen 1). */
data class VotesUiState(
    val votes: List<VoteSummary> = emptyList(),
    val reconnect: List<ReconnectEntry> = emptyList(),
    val dropped: List<DroppedNotice> = emptyList(),
    val problems: List<ProblemsNotice> = emptyList(),
    /** connection id to the service's name as the connection shows it. */
    val services: Map<String, String> = emptyMap(),
    val load: ListLoad = ListLoad.NOT_LOADED,
    val error: FailureKind? = null,
    val errorCode: String? = null,
) {
    val open: List<VoteSummary> get() = votes.filter { VoteRules.inOpenSection(it) }
    val closed: List<VoteSummary> get() = votes.filterNot { VoteRules.inOpenSection(it) }

    val loading: Boolean get() = votes.isEmpty() && reconnect.isEmpty() && error == null && load != ListLoad.LOADED

    /** Nothing at all to show: the list is read and holds no vote, no connection to connect again, no notice. */
    val empty: Boolean
        get() = load == ListLoad.LOADED && votes.isEmpty() && reconnect.isEmpty() && dropped.isEmpty() && problems.isEmpty()
}

/**
 * The Votes list (ANDROID-PLAN 0.1.102): the vault's `vote.list`, read when the screen opens and on every return; a
 * `vote.event` re-reads it in the repository. Held in memory only. A shown `dropped` notice is acknowledged to the vault
 * ([noticesShown]) so that it does not come back.
 */
@HiltViewModel
class VotesViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: VotesRepository,
    connections: ConnectionsRepository,
    ownerCheck: OwnerCheckRepository,
) : ViewModel() {
    private val local = MutableStateFlow(VotesUiState())

    /** Set for "Votes from <name>" (a connection's detail): only that service's votes and notices. */
    private val only: String? = saved.get<String>(ConnectionVotesRoute.ARG)

    private fun <T> List<T>.mine(id: (T) -> String) = if (only == null) this else filter { id(it) == only }

    val uiState: StateFlow<VotesUiState> = combine(
        local,
        repo.votes,
        combine(repo.reconnect, repo.dropped, repo.problems) { r, d, p -> Triple(r, d, p) },
        connections.connections,
        repo.load,
    ) { s, votes, notices, cs, load ->
        s.copy(
            votes = votes.mine { it.connectionId },
            reconnect = notices.first.mine { it.connectionId },
            dropped = notices.second.mine { it.connectionId },
            problems = notices.third.mine { it.connectionId },
            services = cs.associate { c -> c.id to serviceName(c) },
            load = load,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VotesUiState())

    init {
        // The daily check passed (or the hold ended): a list refused because it was due reads again by itself.
        viewModelScope.launch {
            ownerCheck.checkCleared().collect {
                if (local.value.error == FailureKind.OWNER_CHECK_REQUIRED) refresh()
            }
        }
    }

    /** On opening and on every return to the screen. */
    fun refresh() {
        local.update { it.copy(error = null, errorCode = null) }
        viewModelScope.launch {
            try {
                repo.refresh()
            } catch (e: VaultFailure) {
                local.update { it.copy(error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    /** The `dropped` notices of these connections were shown: sent to the vault as `ack_dropped` with the next read. */
    fun noticesShown(connectionIds: List<String>) = repo.acknowledgeDropped(connectionIds)
}

/** Hints (what the app knows beyond the list) for a vote, by its key. */
internal fun Map<String, VoteHint>.of(s: VoteSummary?): VoteHint = s?.let { this[it.key] } ?: VoteHint()

/** The route's arguments (the pair, 0.81.2). */
internal fun SavedStateHandle.pair(): Pair<String, String> =
    get<String>(VoteRoute.CONNECTION).orEmpty() to get<String>(VoteRoute.PROPOSAL).orEmpty()
