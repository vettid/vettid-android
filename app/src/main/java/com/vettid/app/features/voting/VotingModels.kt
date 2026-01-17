package com.vettid.app.features.voting

import com.google.gson.annotations.SerializedName
import java.time.Instant

/**
 * Voting data models for Issue #50: Vault-Based Voting.
 *
 * Privacy model:
 * - Voting keypairs are derived per-proposal using HKDF(identity_key, proposal_id)
 * - Voting public key is unlinkable to user identity
 * - Votes are signed by the voting keypair, not identity key
 * - Bulletin board shows only voting_public_key + signature
 */

// MARK: - Proposal Models

/**
 * A proposal that can be voted on.
 * Proposals are signed by VettID to prevent tampering.
 */
data class Proposal(
    /** Unique identifier */
    val id: String,

    /** Organization that created the proposal */
    @SerializedName("organization_id")
    val organizationId: String,

    /** Human-readable title */
    val title: String,

    /** Full description (may contain markdown) */
    val description: String,

    /** Available choices for voting */
    val choices: List<VoteChoice>,

    /** When voting opens */
    @SerializedName("voting_starts_at")
    val votingStartsAt: Instant,

    /** When voting closes */
    @SerializedName("voting_ends_at")
    val votingEndsAt: Instant,

    /** Current status */
    val status: ProposalStatus,

    /** VettID signature over proposal content (Ed25519) */
    val signature: String,

    /** Signing key ID for verification */
    @SerializedName("signature_key_id")
    val signatureKeyId: String,

    /** When the proposal was created */
    @SerializedName("created_at")
    val createdAt: Instant,

    /** Whether the user has already voted */
    @SerializedName("user_has_voted")
    val userHasVoted: Boolean = false,

    /** Results if voting has ended and results are released */
    val results: VoteResults? = null
)

/**
 * A voting choice within a proposal.
 */
data class VoteChoice(
    /** Choice identifier (e.g., "A", "B", "C" or UUIDs) */
    val id: String,

    /** Human-readable label */
    val label: String,

    /** Optional description */
    val description: String? = null
)

/**
 * Proposal status.
 */
enum class ProposalStatus {
    /** Voting has not yet started */
    @SerializedName("upcoming")
    UPCOMING,

    /** Voting is currently active */
    @SerializedName("active")
    ACTIVE,

    /** Voting has ended, results pending */
    @SerializedName("ended")
    ENDED,

    /** Results have been published */
    @SerializedName("finalized")
    FINALIZED,

    /** Proposal was cancelled */
    @SerializedName("cancelled")
    CANCELLED
}

/**
 * Vote results for a finalized proposal.
 */
data class VoteResults(
    /** Total number of votes cast */
    @SerializedName("total_votes")
    val totalVotes: Int,

    /** Votes per choice */
    @SerializedName("choice_counts")
    val choiceCounts: Map<String, Int>,

    /** Merkle root of the bulletin board */
    @SerializedName("merkle_root")
    val merkleRoot: String,

    /** When results were finalized */
    @SerializedName("finalized_at")
    val finalizedAt: Instant
)

// MARK: - Vote Models

/**
 * A vote cast by the user.
 * Contains encrypted choice sent to enclave and public receipt.
 */
data class Vote(
    /** Proposal being voted on */
    @SerializedName("proposal_id")
    val proposalId: String,

    /** Selected choice ID */
    @SerializedName("choice_id")
    val choiceId: String,

    /** When the vote was cast */
    @SerializedName("cast_at")
    val castAt: Instant,

    /** Receipt for verification */
    val receipt: VoteReceipt
)

/**
 * Vote receipt for public verification.
 * This is what appears on the anonymized bulletin board.
 */
data class VoteReceipt(
    /** Unique vote identifier (derived from voting public key + proposal) */
    @SerializedName("vote_id")
    val voteId: String,

    /** Proposal this vote is for */
    @SerializedName("proposal_id")
    val proposalId: String,

    /** Choice that was voted for */
    @SerializedName("choice_id")
    val choiceId: String,

    /** Voting public key (unlinkable to identity) */
    @SerializedName("voting_public_key")
    val votingPublicKey: String,

    /** Ed25519 signature over (proposal_id || choice_id || timestamp) */
    val signature: String,

    /** When the vote was recorded */
    val timestamp: Instant,

    /** Position in the bulletin board Merkle tree */
    @SerializedName("merkle_index")
    val merkleIndex: Int? = null,

    /** Merkle proof for verification (populated after finalization) */
    @SerializedName("merkle_proof")
    val merkleProof: MerkleProof? = null
)

/**
 * Merkle proof for vote inclusion verification.
 */
data class MerkleProof(
    /** Leaf hash (hash of the vote receipt) */
    @SerializedName("leaf_hash")
    val leafHash: String,

    /** Path from leaf to root */
    val path: List<MerkleProofNode>,

    /** Expected root hash */
    @SerializedName("root_hash")
    val rootHash: String
)

/**
 * A node in the Merkle proof path.
 */
data class MerkleProofNode(
    /** Hash at this level */
    val hash: String,

    /** Whether this hash is on the left (true) or right (false) */
    @SerializedName("is_left")
    val isLeft: Boolean
)

// MARK: - API Request/Response Models

/**
 * Request to cast a vote via vault operation.
 */
data class CastVoteRequest(
    /** Proposal being voted on */
    @SerializedName("proposal_id")
    val proposalId: String,

    /** Encrypted choice (encrypted to enclave public key) */
    @SerializedName("encrypted_choice")
    val encryptedChoice: String,

    /** Ephemeral public key for encryption */
    @SerializedName("ephemeral_public_key")
    val ephemeralPublicKey: String,

    /** Nonce used for encryption */
    val nonce: String,

    /** Password authorization (encrypted password hash) */
    @SerializedName("encrypted_password_hash")
    val encryptedPasswordHash: String,

    /** Key ID used for password encryption */
    @SerializedName("password_key_id")
    val passwordKeyId: String,

    /** Nonce for password encryption */
    @SerializedName("password_nonce")
    val passwordNonce: String,

    /** Ephemeral key for password encryption */
    @SerializedName("password_ephemeral_key")
    val passwordEphemeralKey: String
)

/**
 * Response from casting a vote.
 */
data class CastVoteResponse(
    /** Whether the vote was successfully recorded */
    val success: Boolean,

    /** Vote receipt for verification */
    val receipt: VoteReceipt,

    /** Error message if failed */
    val error: String? = null
)

/**
 * Request to get proposals list.
 */
data class GetProposalsRequest(
    /** Filter by organization (optional) */
    @SerializedName("organization_id")
    val organizationId: String? = null,

    /** Filter by status (optional) */
    val status: List<ProposalStatus>? = null,

    /** Page number for pagination */
    val page: Int = 1,

    /** Items per page */
    @SerializedName("per_page")
    val perPage: Int = 20
)

/**
 * Response containing list of proposals.
 */
data class GetProposalsResponse(
    /** List of proposals */
    val proposals: List<Proposal>,

    /** Total count for pagination */
    @SerializedName("total_count")
    val totalCount: Int,

    /** Current page */
    val page: Int,

    /** Total pages */
    @SerializedName("total_pages")
    val totalPages: Int
)

/**
 * Response from verifying a vote.
 */
data class VerifyVoteResponse(
    /** Whether the vote is valid */
    val valid: Boolean,

    /** Verification details */
    val details: VerificationDetails
)

/**
 * Details of vote verification.
 */
data class VerificationDetails(
    /** Signature verification passed */
    @SerializedName("signature_valid")
    val signatureValid: Boolean,

    /** Vote is included in Merkle tree */
    @SerializedName("merkle_inclusion_valid")
    val merkleInclusionValid: Boolean,

    /** Vote timestamp is within voting period */
    @SerializedName("timestamp_valid")
    val timestampValid: Boolean,

    /** Additional notes */
    val notes: String? = null
)

// MARK: - State Models

/**
 * State for the proposals list screen.
 */
sealed class ProposalsState {
    object Loading : ProposalsState()

    data class Loaded(
        val proposals: List<Proposal>,
        val hasMore: Boolean = false,
        val filter: ProposalFilter = ProposalFilter()
    ) : ProposalsState()

    data class Error(val message: String) : ProposalsState()

    object Empty : ProposalsState()
}

/**
 * Filter options for proposals.
 */
data class ProposalFilter(
    val status: Set<ProposalStatus> = setOf(ProposalStatus.ACTIVE),
    val organizationId: String? = null
)

/**
 * State for voting on a proposal.
 */
sealed class VotingState {
    object Idle : VotingState()

    data class EnteringPassword(
        val proposal: Proposal,
        val selectedChoice: VoteChoice,
        val password: String = "",
        val error: String? = null
    ) : VotingState()

    data class Casting(
        val proposal: Proposal,
        val selectedChoice: VoteChoice
    ) : VotingState()

    data class Success(
        val proposal: Proposal,
        val receipt: VoteReceipt
    ) : VotingState()

    data class Failed(
        val proposal: Proposal,
        val error: String,
        val retryable: Boolean = true
    ) : VotingState()
}

/**
 * State for the my votes screen.
 */
sealed class MyVotesState {
    object Loading : MyVotesState()

    data class Loaded(
        val votes: List<Vote>,
        val verificationStatus: Map<String, VerificationStatus> = emptyMap()
    ) : MyVotesState()

    data class Error(val message: String) : MyVotesState()

    object Empty : MyVotesState()
}

/**
 * Verification status for a vote.
 */
enum class VerificationStatus {
    /** Not yet verified */
    PENDING,

    /** Verification in progress */
    VERIFYING,

    /** Successfully verified */
    VERIFIED,

    /** Verification failed */
    FAILED
}

// MARK: - UI Events

/**
 * Events from the proposals screen.
 */
sealed class ProposalsEvent {
    data class LoadProposals(val filter: ProposalFilter = ProposalFilter()) : ProposalsEvent()
    object LoadMore : ProposalsEvent()
    data class SelectProposal(val proposalId: String) : ProposalsEvent()
    data class UpdateFilter(val filter: ProposalFilter) : ProposalsEvent()
    object Refresh : ProposalsEvent()
}

/**
 * Events from voting flow.
 */
sealed class VotingEvent {
    data class SelectChoice(val choice: VoteChoice) : VotingEvent()
    data class PasswordChanged(val password: String) : VotingEvent()
    object SubmitVote : VotingEvent()
    object Cancel : VotingEvent()
    object Retry : VotingEvent()
    object DismissSuccess : VotingEvent()
}

/**
 * Events from my votes screen.
 */
sealed class MyVotesEvent {
    object LoadVotes : MyVotesEvent()
    data class VerifyVote(val voteId: String) : MyVotesEvent()
    data class ViewReceipt(val voteId: String) : MyVotesEvent()
    object Refresh : MyVotesEvent()
}

// MARK: - Effects

/**
 * Side effects from proposals screen.
 */
sealed class ProposalsEffect {
    data class NavigateToProposal(val proposalId: String) : ProposalsEffect()
    data class ShowError(val message: String) : ProposalsEffect()
}

/**
 * Side effects from voting.
 */
sealed class VotingEffect {
    data class ShowReceipt(val receipt: VoteReceipt) : VotingEffect()
    data class ShowError(val message: String) : VotingEffect()
    object NavigateBack : VotingEffect()
}

/**
 * Side effects from my votes.
 */
sealed class MyVotesEffect {
    data class ShowReceiptDetail(val vote: Vote) : MyVotesEffect()
    data class ShowVerificationResult(val voteId: String, val valid: Boolean) : MyVotesEffect()
    data class ShowError(val message: String) : MyVotesEffect()
}
