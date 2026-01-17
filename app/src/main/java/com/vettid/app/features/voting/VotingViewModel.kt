package com.vettid.app.features.voting

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.NitroEnrollmentClient
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

private const val TAG = "VotingViewModel"

/**
 * ViewModel for the voting flow on a single proposal.
 *
 * Handles:
 * - Loading proposal details
 * - Password entry for vote authorization
 * - Vote submission via vault operation
 * - Storing vote receipts
 *
 * The voting flow:
 * 1. User selects a choice
 * 2. User enters password for authorization
 * 3. Password is encrypted using UTK
 * 4. Vote request sent via NATS to vault enclave
 * 5. Enclave signs vote with derived voting keypair
 * 6. Receipt stored locally for verification
 */
@HiltViewModel
class VotingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultServiceClient: VaultServiceClient,
    private val credentialStore: CredentialStore,
    private val cryptoManager: CryptoManager,
    private val nitroEnrollmentClient: NitroEnrollmentClient,
    private val votingRepository: VotingRepository
) : ViewModel() {

    private val proposalId: String = savedStateHandle.get<String>("proposalId") ?: ""

    private val _state = MutableStateFlow<VotingState>(VotingState.Idle)
    val state: StateFlow<VotingState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VotingEffect>()
    val effects: SharedFlow<VotingEffect> = _effects.asSharedFlow()

    private val _proposal = MutableStateFlow<Proposal?>(null)
    val proposal: StateFlow<Proposal?> = _proposal.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        if (proposalId.isNotBlank()) {
            loadProposal()
        }
    }

    fun setProposalId(proposalId: String) {
        loadProposal(proposalId)
    }

    fun onEvent(event: VotingEvent) {
        when (event) {
            is VotingEvent.SelectChoice -> selectChoice(event.choice)
            is VotingEvent.PasswordChanged -> updatePassword(event.password)
            is VotingEvent.SubmitVote -> submitVote()
            is VotingEvent.Cancel -> cancel()
            is VotingEvent.Retry -> retry()
            is VotingEvent.DismissSuccess -> dismissSuccess()
        }
    }

    private fun loadProposal(proposalIdOverride: String? = null) {
        val id = proposalIdOverride ?: proposalId
        if (id.isBlank()) return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            // First try cache
            votingRepository.getCachedProposal(id)?.let { cached ->
                _proposal.value = cached
            }

            // Then fetch from API
            val authToken = credentialStore.getAuthToken()
            if (authToken != null) {
                try {
                    val result = vaultServiceClient.getProposal(authToken, id)
                    result.onSuccess { proposal ->
                        _proposal.value = proposal
                    }.onFailure { e ->
                        Log.e(TAG, "Failed to load proposal", e)
                        if (_proposal.value == null) {
                            _error.value = e.message ?: "Failed to load proposal"
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception loading proposal", e)
                    if (_proposal.value == null) {
                        _error.value = e.message ?: "Failed to load proposal"
                    }
                }
            } else if (_proposal.value == null) {
                _error.value = "Not authenticated"
            }

            _isLoading.value = false
        }
    }

    private fun selectChoice(choice: VoteChoice) {
        val currentProposal = _proposal.value ?: return

        // Check if user already voted
        if (votingRepository.hasVotedOnProposal(currentProposal.id)) {
            viewModelScope.launch {
                _effects.emit(VotingEffect.ShowError("You have already voted on this proposal"))
            }
            return
        }

        // Check if voting is active
        if (currentProposal.status != ProposalStatus.ACTIVE) {
            viewModelScope.launch {
                _effects.emit(VotingEffect.ShowError("Voting is not currently active for this proposal"))
            }
            return
        }

        // Transition to password entry
        _state.value = VotingState.EnteringPassword(
            proposal = currentProposal,
            selectedChoice = choice
        )
    }

    private fun updatePassword(password: String) {
        val currentState = _state.value
        if (currentState is VotingState.EnteringPassword) {
            _state.value = currentState.copy(password = password, error = null)
        }
    }

    private fun submitVote() {
        val currentState = _state.value
        if (currentState !is VotingState.EnteringPassword) return

        val password = currentState.password
        if (password.isBlank()) {
            _state.value = currentState.copy(error = "Please enter your password")
            return
        }

        viewModelScope.launch {
            _state.value = VotingState.Casting(
                proposal = currentState.proposal,
                selectedChoice = currentState.selectedChoice
            )

            try {
                val receipt = castVote(
                    proposalId = currentState.proposal.id,
                    choiceId = currentState.selectedChoice.id,
                    password = password
                )

                if (receipt != null) {
                    // Store vote locally
                    val vote = Vote(
                        proposalId = currentState.proposal.id,
                        choiceId = currentState.selectedChoice.id,
                        castAt = Instant.now(),
                        receipt = receipt
                    )
                    votingRepository.storeVote(vote)

                    _state.value = VotingState.Success(
                        proposal = currentState.proposal,
                        receipt = receipt
                    )

                    _effects.emit(VotingEffect.ShowReceipt(receipt))
                } else {
                    _state.value = VotingState.Failed(
                        proposal = currentState.proposal,
                        error = "Failed to cast vote",
                        retryable = true
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cast vote", e)
                _state.value = VotingState.Failed(
                    proposal = currentState.proposal,
                    error = e.message ?: "Failed to cast vote",
                    retryable = true
                )
            }
        }
    }

    /**
     * Cast a vote via NATS to the vault enclave.
     *
     * The enclave will:
     * 1. Verify password against stored hash
     * 2. Derive voting keypair from identity + proposal_id
     * 3. Sign the vote with the voting private key
     * 4. Return receipt with voting public key and signature
     */
    private suspend fun castVote(
        proposalId: String,
        choiceId: String,
        password: String
    ): VoteReceipt? {
        // Get UTK for password encryption
        val utkPool = credentialStore.getUtkPool()
        if (utkPool.isEmpty()) {
            throw IllegalStateException("No transaction keys available")
        }

        val utk = utkPool.first()
        val passwordSalt = credentialStore.getPasswordSaltBytes()
            ?: throw IllegalStateException("No password salt")

        // Encrypt password for enclave
        val passwordEncryption = cryptoManager.encryptPasswordForEnclave(
            password = password,
            salt = passwordSalt,
            enclavePublicKeyBase64 = utk.publicKey
        )

        // Build vote request
        val voteRequest = mapOf(
            "operation" to "cast_vote",
            "proposal_id" to proposalId,
            "choice_id" to choiceId,
            "encrypted_password_hash" to passwordEncryption.encryptedPasswordHash,
            "password_ephemeral_key" to passwordEncryption.ephemeralPublicKey,
            "password_nonce" to passwordEncryption.nonce,
            "password_key_id" to utk.keyId,
            "timestamp" to Instant.now().toString()
        )

        // Send via NATS and wait for response
        // This uses the existing NATS connection established during enrollment
        val response = nitroEnrollmentClient.sendVaultOperation(voteRequest)

        // Parse response
        return response?.let { resp ->
            @Suppress("UNCHECKED_CAST")
            val receiptData = resp["receipt"] as? Map<String, Any>
            receiptData?.let {
                VoteReceipt(
                    voteId = it["vote_id"] as? String ?: "",
                    proposalId = proposalId,
                    choiceId = choiceId,
                    votingPublicKey = it["voting_public_key"] as? String ?: "",
                    signature = it["signature"] as? String ?: "",
                    timestamp = Instant.parse(it["timestamp"] as? String ?: Instant.now().toString()),
                    merkleIndex = (it["merkle_index"] as? Number)?.toInt(),
                    merkleProof = null
                )
            }
        }.also {
            // Remove used UTK after attempting vote
            credentialStore.removeUtk(utk.keyId)
        }
    }

    private fun cancel() {
        _state.value = VotingState.Idle
    }

    private fun retry() {
        val currentState = _state.value
        if (currentState is VotingState.Failed) {
            // Return to password entry with the same selection
            _state.value = VotingState.EnteringPassword(
                proposal = currentState.proposal,
                selectedChoice = VoteChoice(
                    id = "",
                    label = "Previous selection"
                )
            )
        }
    }

    private fun dismissSuccess() {
        viewModelScope.launch {
            _state.value = VotingState.Idle
            _effects.emit(VotingEffect.NavigateBack)
        }
    }
}
