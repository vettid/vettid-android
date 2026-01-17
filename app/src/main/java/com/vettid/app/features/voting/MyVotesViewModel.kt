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

private const val TAG = "MyVotesViewModel"

/**
 * ViewModel for the My Votes screen.
 *
 * Handles:
 * - Loading stored votes from local storage
 * - Syncing with server for Merkle proofs
 * - Verifying individual votes
 */
@HiltViewModel
class MyVotesViewModel @Inject constructor(
    private val vaultServiceClient: VaultServiceClient,
    private val credentialStore: CredentialStore,
    private val votingRepository: VotingRepository,
    private val merkleVerifier: MerkleProofVerifier
) : ViewModel() {

    private val _state = MutableStateFlow<MyVotesState>(MyVotesState.Loading)
    val state: StateFlow<MyVotesState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<MyVotesEffect>()
    val effects: SharedFlow<MyVotesEffect> = _effects.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadVotes()
    }

    fun onEvent(event: MyVotesEvent) {
        when (event) {
            is MyVotesEvent.LoadVotes -> loadVotes()
            is MyVotesEvent.VerifyVote -> verifyVote(event.voteId)
            is MyVotesEvent.ViewReceipt -> viewReceipt(event.voteId)
            is MyVotesEvent.Refresh -> refresh()
        }
    }

    private fun loadVotes() {
        viewModelScope.launch {
            _state.value = MyVotesState.Loading

            try {
                // Load from local storage first
                val storedVotes = votingRepository.getStoredVotes()

                if (storedVotes.isEmpty()) {
                    _state.value = MyVotesState.Empty
                    return@launch
                }

                // Initial state with stored votes
                val verificationStatus = storedVotes.associate { vote ->
                    vote.receipt.voteId to if (vote.receipt.merkleProof != null) {
                        VerificationStatus.PENDING
                    } else {
                        VerificationStatus.PENDING
                    }
                }

                _state.value = MyVotesState.Loaded(
                    votes = storedVotes,
                    verificationStatus = verificationStatus
                )

                // Try to sync with server for Merkle proofs
                syncMerkleProofs(storedVotes)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load votes", e)
                _state.value = MyVotesState.Error(e.message ?: "Failed to load votes")
            }
        }
    }

    private suspend fun syncMerkleProofs(votes: List<Vote>) {
        val authToken = credentialStore.getAuthToken() ?: return

        // Get votes that need Merkle proof updates
        val votesNeedingProofs = votes.filter { it.receipt.merkleProof == null }

        for (vote in votesNeedingProofs) {
            try {
                // Try to get updated receipt with Merkle proof from server
                val result = vaultServiceClient.getMyVotes(authToken)
                result.onSuccess { response ->
                    val serverVote = response.votes.find { it.receipt.voteId == vote.receipt.voteId }
                    serverVote?.receipt?.merkleProof?.let { proof ->
                        // Update local storage with Merkle proof
                        votingRepository.updateVoteReceipt(
                            proposalId = vote.proposalId,
                            updatedReceipt = vote.receipt.copy(merkleProof = proof)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync Merkle proof for vote ${vote.receipt.voteId}", e)
            }
        }

        // Reload to reflect updates
        val updatedVotes = votingRepository.getStoredVotes()
        val currentState = _state.value
        if (currentState is MyVotesState.Loaded) {
            _state.value = currentState.copy(votes = updatedVotes)
        }
    }

    private fun verifyVote(voteId: String) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MyVotesState.Loaded) return@launch

            val vote = currentState.votes.find { it.receipt.voteId == voteId } ?: return@launch

            // Update status to verifying
            _state.value = currentState.copy(
                verificationStatus = currentState.verificationStatus + (voteId to VerificationStatus.VERIFYING)
            )

            try {
                val isValid = verifyVoteReceipt(vote)

                _state.value = currentState.copy(
                    verificationStatus = currentState.verificationStatus + (
                        voteId to if (isValid) VerificationStatus.VERIFIED else VerificationStatus.FAILED
                    )
                )

                _effects.emit(MyVotesEffect.ShowVerificationResult(voteId, isValid))

            } catch (e: Exception) {
                Log.e(TAG, "Vote verification failed", e)
                _state.value = currentState.copy(
                    verificationStatus = currentState.verificationStatus + (voteId to VerificationStatus.FAILED)
                )
                _effects.emit(MyVotesEffect.ShowError("Verification failed: ${e.message}"))
            }
        }
    }

    private suspend fun verifyVoteReceipt(vote: Vote): Boolean {
        val receipt = vote.receipt

        // 1. Verify signature locally
        val signatureValid = merkleVerifier.verifySignature(
            votingPublicKey = receipt.votingPublicKey,
            proposalId = receipt.proposalId,
            choiceId = receipt.choiceId,
            timestamp = receipt.timestamp,
            signature = receipt.signature
        )

        if (!signatureValid) {
            Log.w(TAG, "Signature verification failed for vote ${receipt.voteId}")
            return false
        }

        // 2. Verify Merkle inclusion if proof is available
        val merkleProof = receipt.merkleProof
        if (merkleProof != null) {
            val merkleValid = merkleVerifier.verifyMerkleProof(
                receipt = receipt,
                proof = merkleProof
            )
            if (!merkleValid) {
                Log.w(TAG, "Merkle proof verification failed for vote ${receipt.voteId}")
                return false
            }
        }

        // 3. Optionally verify with server
        val authToken = credentialStore.getAuthToken()
        if (authToken != null) {
            try {
                val result = vaultServiceClient.verifyVote(authToken, receipt)
                result.onSuccess { response ->
                    if (!response.valid) {
                        Log.w(TAG, "Server verification failed: ${response.details}")
                        return false
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server verification unavailable, using local verification", e)
                // Continue with local verification only
            }
        }

        return true
    }

    private fun viewReceipt(voteId: String) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is MyVotesState.Loaded) return@launch

            val vote = currentState.votes.find { it.receipt.voteId == voteId } ?: return@launch
            _effects.emit(MyVotesEffect.ShowReceiptDetail(vote))
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                loadVotes()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
