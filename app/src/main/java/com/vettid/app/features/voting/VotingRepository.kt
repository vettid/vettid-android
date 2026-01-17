package com.vettid.app.features.voting

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for storing and retrieving voting data.
 *
 * Stores:
 * - Vote receipts (for My Votes screen and verification)
 * - Proposal cache (optional, for offline viewing)
 *
 * Uses EncryptedSharedPreferences for secure storage.
 */
@Singleton
class VotingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "vettid_voting",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_VOTES = "votes"
        private const val KEY_PROPOSALS_CACHE = "proposals_cache"
        private const val KEY_PROPOSALS_CACHE_TIME = "proposals_cache_time"
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    }

    // MARK: - Vote Storage

    /**
     * Store a vote after successful casting.
     */
    fun storeVote(vote: Vote) {
        val votes = getStoredVotes().toMutableList()
        // Remove existing vote for same proposal (should not happen, but safety check)
        votes.removeAll { it.proposalId == vote.proposalId }
        votes.add(vote)
        encryptedPrefs.edit()
            .putString(KEY_VOTES, gson.toJson(votes))
            .apply()
    }

    /**
     * Get all stored votes.
     */
    fun getStoredVotes(): List<Vote> {
        val json = encryptedPrefs.getString(KEY_VOTES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Vote>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            android.util.Log.e("VotingRepository", "Failed to parse stored votes", e)
            emptyList()
        }
    }

    /**
     * Get a specific vote by proposal ID.
     */
    fun getVoteForProposal(proposalId: String): Vote? {
        return getStoredVotes().find { it.proposalId == proposalId }
    }

    /**
     * Get a specific vote by vote ID.
     */
    fun getVoteById(voteId: String): Vote? {
        return getStoredVotes().find { it.receipt.voteId == voteId }
    }

    /**
     * Check if user has voted on a proposal.
     */
    fun hasVotedOnProposal(proposalId: String): Boolean {
        return getVoteForProposal(proposalId) != null
    }

    /**
     * Get vote receipt for a proposal.
     */
    fun getVoteReceipt(proposalId: String): VoteReceipt? {
        return getVoteForProposal(proposalId)?.receipt
    }

    /**
     * Update vote receipt with Merkle proof after finalization.
     */
    fun updateVoteReceipt(proposalId: String, updatedReceipt: VoteReceipt) {
        val votes = getStoredVotes().toMutableList()
        val index = votes.indexOfFirst { it.proposalId == proposalId }
        if (index >= 0) {
            val vote = votes[index]
            votes[index] = vote.copy(receipt = updatedReceipt)
            encryptedPrefs.edit()
                .putString(KEY_VOTES, gson.toJson(votes))
                .apply()
        }
    }

    /**
     * Delete a vote (for testing or admin purposes).
     */
    fun deleteVote(proposalId: String) {
        val votes = getStoredVotes().toMutableList()
        votes.removeAll { it.proposalId == proposalId }
        encryptedPrefs.edit()
            .putString(KEY_VOTES, gson.toJson(votes))
            .apply()
    }

    /**
     * Get count of stored votes.
     */
    fun getVoteCount(): Int {
        return getStoredVotes().size
    }

    // MARK: - Proposal Cache

    /**
     * Cache proposals for offline viewing.
     */
    fun cacheProposals(proposals: List<Proposal>) {
        encryptedPrefs.edit()
            .putString(KEY_PROPOSALS_CACHE, gson.toJson(proposals))
            .putLong(KEY_PROPOSALS_CACHE_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Get cached proposals.
     * Returns null if cache is expired or empty.
     */
    fun getCachedProposals(): List<Proposal>? {
        val cacheTime = encryptedPrefs.getLong(KEY_PROPOSALS_CACHE_TIME, 0)
        if (System.currentTimeMillis() - cacheTime > CACHE_DURATION_MS) {
            return null // Cache expired
        }

        val json = encryptedPrefs.getString(KEY_PROPOSALS_CACHE, null) ?: return null
        return try {
            val type = object : TypeToken<List<Proposal>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            android.util.Log.e("VotingRepository", "Failed to parse cached proposals", e)
            null
        }
    }

    /**
     * Get a cached proposal by ID.
     */
    fun getCachedProposal(proposalId: String): Proposal? {
        return getCachedProposals()?.find { it.id == proposalId }
    }

    /**
     * Clear the proposals cache.
     */
    fun clearProposalsCache() {
        encryptedPrefs.edit()
            .remove(KEY_PROPOSALS_CACHE)
            .remove(KEY_PROPOSALS_CACHE_TIME)
            .apply()
    }

    /**
     * Check if proposals cache is valid.
     */
    fun isProposalsCacheValid(): Boolean {
        val cacheTime = encryptedPrefs.getLong(KEY_PROPOSALS_CACHE_TIME, 0)
        return System.currentTimeMillis() - cacheTime < CACHE_DURATION_MS
    }

    // MARK: - Vote Verification Helpers

    /**
     * Get votes that need Merkle proof updates.
     * Returns votes where merkleProof is null but merkleIndex is set.
     */
    fun getVotesNeedingProofUpdate(): List<Vote> {
        return getStoredVotes().filter { vote ->
            vote.receipt.merkleIndex != null && vote.receipt.merkleProof == null
        }
    }

    /**
     * Get votes for finalized proposals.
     * These are votes that should have Merkle proofs available.
     */
    fun getVotesForFinalizedProposals(finalizedProposalIds: Set<String>): List<Vote> {
        return getStoredVotes().filter { it.proposalId in finalizedProposalIds }
    }

    // MARK: - Cleanup

    /**
     * Clear all voting data.
     */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
    }

    /**
     * Clear votes older than a given date.
     * Useful for cleaning up old vote history.
     */
    fun clearVotesOlderThan(instant: Instant) {
        val votes = getStoredVotes().filter { it.castAt.isAfter(instant) }
        encryptedPrefs.edit()
            .putString(KEY_VOTES, gson.toJson(votes))
            .apply()
    }
}
