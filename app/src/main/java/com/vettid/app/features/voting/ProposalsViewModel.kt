package com.vettid.app.features.voting

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
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
 * Proposals are fetched via NATS through the vault (vault queries DynamoDB
 * via parent process). Vote casting also goes through NATS.
 */
@HiltViewModel
class ProposalsViewModel @Inject constructor(
    private val vaultServiceClient: VaultServiceClient,
    private val credentialStore: CredentialStore,
    private val votingRepository: VotingRepository,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector
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
        // VotingViewModel/ProposalDetailScreen casts votes through a
        // sibling VM. After it lands a vote, the repository tick
        // fires and we re-stamp `userHasVoted` on the matching item
        // so the list flips from "Vote Now" to "You voted" without
        // forcing the user to leave and return.
        viewModelScope.launch {
            votingRepository.votesDirtyTick.drop(1).collect {
                refreshVotedFlags()
            }
        }
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

            // Fetch proposals via NATS through the vault
            if (!natsAutoConnector.isConnected()) {
                Log.w(TAG, "NATS not connected, showing cached proposals")
                showCachedOrEmpty()
                return@launch
            }

            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    "vote.list", JsonObject(), 15000L
                )

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success && response.result != null) {
                            val proposalsArray = response.result.getAsJsonArray("proposals")
                            if (proposalsArray == null || proposalsArray.size() == 0) {
                                _state.value = ProposalsState.Empty
                                return@launch
                            }

                            val proposals = proposalsArray.mapNotNull { element ->
                                try {
                                    parseProposalFromJson(element.asJsonObject)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse proposal: ${e.message}")
                                    null
                                }
                            }.map { proposal ->
                                if (votingRepository.hasVotedOnProposal(proposal.id)) {
                                    proposal.copy(userHasVoted = true)
                                } else {
                                    proposal
                                }
                            }

                            if (proposals.isEmpty()) {
                                _state.value = ProposalsState.Empty
                            } else {
                                _state.value = ProposalsState.Loaded(
                                    proposals = sortProposals(proposals),
                                    hasMore = false,
                                    filter = currentFilter
                                )
                                votingRepository.cacheProposals(proposals)
                            }
                        } else {
                            Log.e(TAG, "Vault error: ${response.error}")
                            showCachedOrError(response.error ?: "Failed to load proposals")
                        }
                    }
                    is VaultResponse.Error -> {
                        if (response.code == "TIMEOUT") {
                            Log.w(TAG, "Proposals request timed out")
                            showCachedOrEmpty()
                        } else {
                            Log.e(TAG, "Vault error: ${response.code} - ${response.message}")
                            showCachedOrError(response.message ?: "Failed to load proposals")
                        }
                    }
                    else -> {
                        Log.e(TAG, "Unexpected response: $response")
                        showCachedOrError("Failed to load proposals")
                    }
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
        Log.d(TAG, "selectProposal called: $proposalId")
        viewModelScope.launch {
            Log.d(TAG, "Emitting NavigateToProposal effect for: $proposalId")
            _effects.emit(ProposalsEffect.NavigateToProposal(proposalId))
            Log.d(TAG, "Effect emitted for: $proposalId")
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
     * Sort proposals: active unvoted → active voted → upcoming → closed → cancelled.
     * Within each group, newest first.
     */
    private fun sortProposals(proposals: List<Proposal>): List<Proposal> {
        return proposals.sortedWith(
            compareBy<Proposal> {
                when {
                    it.status == ProposalStatus.ACTIVE && !it.userHasVoted -> 0
                    it.status == ProposalStatus.ACTIVE && it.userHasVoted -> 1
                    it.status == ProposalStatus.UPCOMING -> 2
                    it.status == ProposalStatus.CLOSED -> 3
                    else -> 4 // CANCELLED
                }
            }.thenByDescending { it.createdAt }
        )
    }

    /**
     * Re-check voted status from VotingRepository for all loaded proposals.
     * Called when the proposals list becomes visible again (e.g., after navigating
     * back from ProposalDetailScreen where a vote may have been cast).
     */
    fun refreshVotedFlags() {
        val currentState = _state.value
        if (currentState is ProposalsState.Loaded) {
            val updatedProposals = currentState.proposals.map { proposal ->
                if (!proposal.userHasVoted && votingRepository.hasVotedOnProposal(proposal.id)) {
                    proposal.copy(userHasVoted = true)
                } else {
                    proposal
                }
            }
            _state.value = currentState.copy(proposals = sortProposals(updatedProposals))
        }
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
