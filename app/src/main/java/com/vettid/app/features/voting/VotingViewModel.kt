package com.vettid.app.features.voting

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.network.TransactionKeyInfo
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
    private val ownerSpaceClient: OwnerSpaceClient,
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
                    proposal = currentState.proposal,
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
                        retryable = true,
                        selectedChoice = currentState.selectedChoice
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cast vote", e)
                _state.value = VotingState.Failed(
                    proposal = currentState.proposal,
                    error = e.message ?: "Failed to cast vote",
                    retryable = true,
                    selectedChoice = currentState.selectedChoice
                )
            }
        }
    }

    /**
     * Cast a vote via NATS to the vault enclave.
     *
     * Uses OwnerSpaceClient.sendAndAwaitResponse() which handles E2E encryption,
     * correct NATS subject routing ({space}.forVault.vote.cast), and timeout.
     *
     * The enclave will:
     * 1. Verify password against stored hash
     * 2. Derive voting keypair from identity + proposal_id
     * 3. Sign the vote with the voting private key
     * 4. Return receipt with voting public key and signature
     */
    private suspend fun castVote(
        proposal: Proposal,
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

        // Build vote request payload as JsonObject for OwnerSpaceClient
        val payload = JsonObject().apply {
            addProperty("proposal_id", proposal.id)
            addProperty("vote", choiceId)
            // Send valid choices so vault can validate
            val choicesArray = JsonArray()
            proposal.choices.forEach { choicesArray.add(it.id) }
            add("valid_choices", choicesArray)
            // Password authorization (encrypted with UTK)
            addProperty("encrypted_password_hash", passwordEncryption.encryptedPasswordHash)
            addProperty("ephemeral_public_key", passwordEncryption.ephemeralPublicKey)
            addProperty("nonce", passwordEncryption.nonce)
            addProperty("password_key_id", utk.keyId)
        }

        // Send via OwnerSpaceClient — publishes to {space}.forVault.vote.cast
        val response = ownerSpaceClient.sendAndAwaitResponse("vote.cast", payload)

        // Remove used UTK regardless of outcome
        credentialStore.removeUtk(utk.keyId)

        // Parse response from VaultResponse
        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    val r = response.result

                    // Handle UTK replenishment (format: "ID:base64PublicKey")
                    r.getAsJsonArray("new_utks")?.let { utksArray ->
                        val newKeys = (0 until utksArray.size()).mapNotNull { i ->
                            val utkString = utksArray[i].asString
                            val parts = utkString.split(":", limit = 2)
                            if (parts.size == 2) {
                                TransactionKeyInfo(
                                    keyId = parts[0],
                                    publicKey = parts[1],
                                    algorithm = "X25519"
                                )
                            } else null
                        }
                        if (newKeys.isNotEmpty()) {
                            credentialStore.addUtks(newKeys)
                            Log.i(TAG, "Added ${newKeys.size} new UTKs from vote response")
                        }
                    }

                    val votingPubKey = r.get("voting_public_key")?.asString ?: ""
                    VoteReceipt(
                        voteId = "${votingPubKey.take(16)}-${proposal.id.take(8)}",
                        proposalId = proposal.id,
                        choiceId = choiceId,
                        votingPublicKey = votingPubKey,
                        signature = r.get("vote_signature")?.asString ?: "",
                        timestamp = try {
                            Instant.parse(r.get("voted_at")?.asString)
                        } catch (e: Exception) {
                            Instant.now()
                        }
                    )
                } else {
                    throw Exception(response.error ?: "Vote failed")
                }
            }
            is VaultResponse.Error -> throw Exception(response.message)
            null -> throw Exception("Vault response timeout")
            else -> throw Exception("Unexpected response type")
        }
    }

    private fun cancel() {
        _state.value = VotingState.Idle
    }

    private fun retry() {
        val currentState = _state.value
        if (currentState is VotingState.Failed) {
            val choice = currentState.selectedChoice
            if (choice != null) {
                // Return to password entry with the preserved selection
                _state.value = VotingState.EnteringPassword(
                    proposal = currentState.proposal,
                    selectedChoice = choice
                )
            } else {
                // Fallback: return to idle so user can re-select
                _state.value = VotingState.Idle
            }
        }
    }

    private fun dismissSuccess() {
        viewModelScope.launch {
            _state.value = VotingState.Idle
            _effects.emit(VotingEffect.NavigateBack)
        }
    }
}
