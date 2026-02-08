package com.vettid.app.features.voting

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ProposalsViewModel"

/**
 * ViewModel for the Proposals list screen.
 *
 * Handles:
 * - Loading proposals from API
 * - Filtering by status/organization
 * - Pagination
 * - Navigation to proposal detail/voting
 */
@HiltViewModel
class ProposalsViewModel @Inject constructor(
    private val vaultServiceClient: VaultServiceClient,
    private val credentialStore: CredentialStore,
    private val votingRepository: VotingRepository
) : ViewModel() {

    private val _state = MutableStateFlow<ProposalsState>(ProposalsState.Loading)
    val state: StateFlow<ProposalsState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ProposalsEffect>()
    val effects: SharedFlow<ProposalsEffect> = _effects.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var currentPage = 1
    private var hasMorePages = false
    private var currentFilter = ProposalFilter()

    init {
        loadProposals()
    }

    fun onEvent(event: ProposalsEvent) {
        when (event) {
            is ProposalsEvent.LoadProposals -> {
                currentFilter = event.filter
                currentPage = 1
                loadProposals()
            }
            is ProposalsEvent.LoadMore -> loadMoreProposals()
            is ProposalsEvent.SelectProposal -> selectProposal(event.proposalId)
            is ProposalsEvent.UpdateFilter -> updateFilter(event.filter)
            is ProposalsEvent.Refresh -> refresh()
        }
    }

    private fun loadProposals() {
        viewModelScope.launch {
            _state.value = ProposalsState.Loading

            val authToken = credentialStore.getAuthToken()
            if (authToken == null) {
                Log.w(TAG, "No auth token, showing cached proposals if available")
                showCachedOrEmpty()
                return@launch
            }

            try {
                val statusList = currentFilter.status.toList().takeIf { it.isNotEmpty() }
                val result = vaultServiceClient.getProposals(
                    authToken = authToken,
                    organizationId = currentFilter.organizationId,
                    status = statusList,
                    page = 1,
                    perPage = 20
                )

                result.onSuccess { response ->
                    val proposalsWithVoteStatus = response.proposals.map { proposal ->
                        // Check local storage if we've voted
                        if (votingRepository.hasVotedOnProposal(proposal.id)) {
                            proposal.copy(userHasVoted = true)
                        } else {
                            proposal
                        }
                    }

                    if (proposalsWithVoteStatus.isEmpty()) {
                        _state.value = ProposalsState.Empty
                    } else {
                        hasMorePages = response.page < response.totalPages
                        currentPage = response.page
                        _state.value = ProposalsState.Loaded(
                            proposals = sortProposals(proposalsWithVoteStatus),
                            hasMore = hasMorePages,
                            filter = currentFilter
                        )
                        // Cache for offline use
                        votingRepository.cacheProposals(proposalsWithVoteStatus)
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Failed to load proposals", e)
                    showCachedOrError(e.message ?: "Failed to load proposals")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading proposals", e)
                showCachedOrError(e.message ?: "Failed to load proposals")
            }
        }
    }

    private fun loadMoreProposals() {
        if (!hasMorePages) return

        val currentState = _state.value
        if (currentState !is ProposalsState.Loaded) return

        viewModelScope.launch {
            val authToken = credentialStore.getAuthToken() ?: return@launch

            try {
                val statusList = currentFilter.status.toList().takeIf { it.isNotEmpty() }
                val result = vaultServiceClient.getProposals(
                    authToken = authToken,
                    organizationId = currentFilter.organizationId,
                    status = statusList,
                    page = currentPage + 1,
                    perPage = 20
                )

                result.onSuccess { response ->
                    val newProposals = response.proposals.map { proposal ->
                        if (votingRepository.hasVotedOnProposal(proposal.id)) {
                            proposal.copy(userHasVoted = true)
                        } else {
                            proposal
                        }
                    }

                    hasMorePages = response.page < response.totalPages
                    currentPage = response.page

                    _state.value = currentState.copy(
                        proposals = sortProposals(currentState.proposals + newProposals),
                        hasMore = hasMorePages
                    )
                }.onFailure { e ->
                    Log.e(TAG, "Failed to load more proposals", e)
                    _effects.emit(ProposalsEffect.ShowError(e.message ?: "Failed to load more"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading more proposals", e)
                _effects.emit(ProposalsEffect.ShowError(e.message ?: "Failed to load more"))
            }
        }
    }

    private fun selectProposal(proposalId: String) {
        viewModelScope.launch {
            _effects.emit(ProposalsEffect.NavigateToProposal(proposalId))
        }
    }

    private fun updateFilter(filter: ProposalFilter) {
        currentFilter = filter
        currentPage = 1
        loadProposals()
    }

    private fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                currentPage = 1
                loadProposals()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private fun showCachedOrEmpty() {
        val cached = votingRepository.getCachedProposals()
        if (cached != null && cached.isNotEmpty()) {
            _state.value = ProposalsState.Loaded(
                proposals = cached,
                hasMore = false,
                filter = currentFilter
            )
        } else {
            _state.value = ProposalsState.Empty
        }
    }

    private suspend fun showCachedOrError(errorMessage: String) {
        val cached = votingRepository.getCachedProposals()
        if (cached != null && cached.isNotEmpty()) {
            _state.value = ProposalsState.Loaded(
                proposals = cached,
                hasMore = false,
                filter = currentFilter
            )
            _effects.emit(ProposalsEffect.ShowError("Using cached data: $errorMessage"))
        } else {
            _state.value = ProposalsState.Error(errorMessage)
        }
    }

    /**
     * Sort proposals by priority: active unvoted first, then active voted,
     * then upcoming, then ended/finalized/cancelled. Within each group,
     * sort newest first.
     */
    private fun sortProposals(proposals: List<Proposal>): List<Proposal> {
        return proposals.sortedWith(
            compareBy<Proposal> {
                when {
                    it.status == ProposalStatus.ACTIVE && !it.userHasVoted -> 0
                    it.status == ProposalStatus.ACTIVE && it.userHasVoted -> 1
                    it.status == ProposalStatus.UPCOMING -> 2
                    else -> 3 // ENDED, FINALIZED, CANCELLED
                }
            }.thenByDescending { it.createdAt }
        )
    }

    /**
     * Mark a proposal as voted locally.
     * Called after successful vote casting.
     */
    fun markProposalAsVoted(proposalId: String) {
        val currentState = _state.value
        if (currentState is ProposalsState.Loaded) {
            val updatedProposals = currentState.proposals.map { proposal ->
                if (proposal.id == proposalId) {
                    proposal.copy(userHasVoted = true)
                } else {
                    proposal
                }
            }
            _state.value = currentState.copy(proposals = updatedProposals)
        }
    }
}
