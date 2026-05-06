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

    private val _userVote = MutableStateFlow<Vote?>(null)
    val userVote: StateFlow<Vote?> = _userVote.asStateFlow()

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()

    private val _verification = MutableStateFlow<VoteVerification?>(null)
    val verification: StateFlow<VoteVerification?> = _verification.asStateFlow()

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

            // Look up the user's stored vote for this proposal
            _userVote.value = votingRepository.getVoteForProposal(id)

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

        val pwString = currentState.password
        if (pwString.isBlank()) {
            _state.value = currentState.copy(error = "Please enter your password")
            return
        }

        // Move the password out of state into a SecurePassword and
        // drop the String reference. State transitions to Casting (no
        // password field) so the only live reference is the
        // SecurePassword carried into castVote, which wipes on completion.
        val securePw = com.vettid.app.core.security.SecurePassword.fromString(pwString)
        _state.value = currentState.copy(password = "")

        viewModelScope.launch {
            _state.value = VotingState.Casting(
                proposal = currentState.proposal,
                selectedChoice = currentState.selectedChoice
            )

            try {
                val receipt = castVote(
                    proposal = currentState.proposal,
                    choiceId = currentState.selectedChoice.id,
                    password = securePw
                )

                if (receipt != null) {
                    // Store vote locally
                    val vote = Vote(
                        proposalId = currentState.proposal.id,
                        choiceId = currentState.selectedChoice.id,
                        castAt = Instant.now().toString(),
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
        password: com.vettid.app.core.security.SecurePassword
    ): VoteReceipt? {
        return try {
        // Get UTK for password encryption
        val utkPool = credentialStore.getUtkPool()
        if (utkPool.isEmpty()) {
            throw IllegalStateException("No transaction keys available")
        }

        val utk = utkPool.first()
        val passwordSalt = credentialStore.getPasswordSaltBytes()
            ?: throw IllegalStateException("No password salt")

        // Encrypt password with UTK using domain-separated XChaCha20-Poly1305
        val passwordEncryption = cryptoManager.encryptPasswordForServer(
            password = password,
            salt = passwordSalt,
            utkPublicKeyBase64 = utk.publicKey
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
            // Phase D: include the encrypted credential blob so the
            // vault verifies the password by decrypting in-flight
            // rather than reading vaultState.credential.
            credentialStore.getEncryptedBlob()?.let {
                addProperty("encrypted_credential", it)
            }
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
                        timestamp = r.get("voted_at")?.asString
                            ?: Instant.now().toString()
                    )
                } else {
                    throw Exception(response.error ?: "Vote failed")
                }
            }
            is VaultResponse.Error -> throw Exception(response.message)
            null -> throw Exception("Vault response timeout")
            else -> throw Exception("Unexpected response type")
        }
        } finally {
            // Vote signing always wipes the password (every vote requires
            // fresh password — see vote_handler.HandleCastVote on the
            // vault side, which always password-verifies).
            password.wipe()
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

    /**
     * Ask the vault to verify our recorded vote on this proposal against the
     * published Merkle tree. The vault re-derives the voting public key from
     * the user's identity, fetches the inclusion proof through the parent,
     * and re-walks the tree inside the enclave. The app just renders the
     * verified flag — the bool is the truth.
     */
    fun verifyVote(proposalId: String) {
        viewModelScope.launch {
            _verifying.value = true
            _verification.value = null
            try {
                val payload = JsonObject().apply { addProperty("proposal_id", proposalId) }
                val response = ownerSpaceClient.sendAndAwaitResponse("vote.verify", payload, 15000L)
                _verification.value = when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success && response.result != null) {
                            val r = response.result
                            VoteVerification(
                                verified = r.get("verified")?.asBoolean ?: false,
                                proposalId = r.get("proposal_id")?.asString ?: proposalId,
                                votingPublicKey = r.get("voting_public_key")?.asString,
                                leafIndex = r.get("leaf_index")?.asInt ?: 0,
                                total = r.get("total")?.asInt ?: 0,
                                merkleRoot = r.get("merkle_root")?.asString ?: "",
                                vote = r.get("vote")?.asString ?: "",
                                error = r.get("error")?.asString
                            )
                        } else {
                            VoteVerification(
                                verified = false, proposalId = proposalId,
                                error = response.error ?: "verification failed"
                            )
                        }
                    }
                    is VaultResponse.Error -> VoteVerification(
                        verified = false, proposalId = proposalId,
                        error = response.message ?: "verification error"
                    )
                    null -> VoteVerification(
                        verified = false, proposalId = proposalId,
                        error = "vault timeout"
                    )
                    else -> VoteVerification(
                        verified = false, proposalId = proposalId,
                        error = "unexpected response"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "verifyVote failed", e)
                _verification.value = VoteVerification(
                    verified = false, proposalId = proposalId,
                    error = e.message ?: "verification exception"
                )
            } finally {
                _verifying.value = false
            }
        }
    }

    fun clearVerification() {
        _verification.value = null
    }

    /**
     * Drain the vault's pending-vote queue. Used by the recovery flow when
     * the app comes back online and there might be receipts that were
     * signed but not yet shipped to DynamoDB.
     */
    fun resubmitPendingVotes() {
        viewModelScope.launch {
            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    "vote.resubmit-pending", JsonObject(), 30000L
                )
                Log.i(TAG, "resubmit-pending response: $response")
            } catch (e: Exception) {
                Log.e(TAG, "resubmitPendingVotes failed", e)
            }
        }
    }
}
