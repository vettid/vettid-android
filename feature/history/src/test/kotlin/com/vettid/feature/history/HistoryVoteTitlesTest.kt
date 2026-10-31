// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.history

import android.content.Context
import com.vettid.core.data.vault.AuditCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** History's vote titles (ANDROID-PLAN 0.1.102 "Votes"): the vote's title from the list in memory, "a vote" without it, never a choice. */
@RunWith(RobolectricTestRunner::class)
class HistoryVoteTitlesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun title(kind: String, ref: String? = "P1", names: EntryNames = EntryNames("Example", null, null, "Adopt the budget")) =
        HistoryText.title(context, kind, ref, names, isolate = false)

    @Test
    fun theTitlesReadAsApproved() {
        assertEquals("You cast a vote: “Adopt the budget”", title("vote.cast"))
        assertEquals("Example recorded your vote: “Adopt the budget”", title("vote.recorded"))
        assertEquals("Example holds a different vote under your code: “Adopt the budget”", title("vote.conflict"))
        assertEquals("A receipt for your vote could not be checked: “Adopt the budget”", title("vote.receipt_rejected"))
        assertEquals("The closing time of “Adopt the budget” was extended", title("vote.extended", "P1:1"))
        assertEquals("A change of closing time was not accepted: “Adopt the budget”", title("vote.extension_rejected", "P1:late"))
        assertEquals("A vote from Example could not be checked and was set aside", title("vote.proposal_rejected", "P1:record"))
        assertEquals("The list of services was not accepted", title("vote.list_rejected", null))
        assertEquals("The published results include your vote: “Adopt the budget”", title("vote.check_ok"))
        assertEquals("Your vote could not be confirmed in the published results: “Adopt the budget”", title("vote.check_unconfirmed"))
        assertEquals("The published results do not match your vote: “Adopt the budget”", title("vote.check_failed", "P1:row_mismatch"))
        assertEquals("The results of “Adopt the budget” could not be checked", title("vote.check_gave_up"))
        assertEquals("Vote record released: “Adopt the budget”", title("vote.released"))
    }

    @Test
    fun aRefusalNamesTheReasonInWords() {
        assertEquals("Example did not accept your vote: “Adopt the budget” (the vote had closed)", title("vote.refused", "P1:closed"))
        assertEquals("Example did not accept your vote: “Adopt the budget” (you were not on the list)", title("vote.refused", "P1:not_eligible"))
        assertEquals("Example did not accept your vote: “Adopt the budget” (not accepted)", title("vote.refused", "P1:something_new"))
    }

    @Test
    fun theProposalConflictsReadBySuffix() {
        assertEquals("The vote from Example differs from the one published on the public site", title("vote.proposal_conflict", "P1:mismatch"))
        assertEquals("A vote from Example is not on the public site yet", title("vote.proposal_conflict", "P1:not_listed"))
        assertEquals("A vote from Example was never published on the public site", title("vote.proposal_conflict", "P1:unpublished"))
        assertEquals("Example sent two different versions of one vote", title("vote.proposal_conflict", "P1"))
    }

    @Test
    fun withoutTheVotesTitleOrTheServiceTheTitleSaysAVoteOrAService() {
        val none = EntryNames()
        assertEquals("You cast a vote", title("vote.cast", names = none))
        assertEquals("A service recorded your vote", title("vote.recorded", names = none))
        assertEquals("The results of a vote could not be checked", title("vote.check_gave_up", names = none))
        assertEquals("A vote could not be checked and was set aside", title("vote.proposal_rejected", names = none))
        assertEquals("A service did not accept your vote (the vote had closed)", title("vote.refused", "P1:closed", none))
    }

    @Test
    fun aVoteMessageTheVaultIgnoredIsOneTitle() {
        for (k in listOf("drop.vote_malformed", "drop.vote_limit", "drop.vote_not_service")) assertEquals("A vote message was ignored", title(k, null))
    }

    @Test
    fun theProposalIdComesFromTheRefAndVoteEntriesAreTheConnectionsCategory() {
        assertEquals("P1", AuditKinds.voteOf("vote.refused", "P1:closed"))
        assertEquals("P1", AuditKinds.voteOf("vote.cast", "P1"))
        assertNull(AuditKinds.voteOf("item.added", "P1"))
        assertNull(AuditKinds.voteOf("vote.list_rejected", ""))
        for (k in listOf("vote.cast", "vote.recorded", "vote.check_ok", "vote.proposal_conflict", "vote.list_rejected")) {
            assertEquals(k, AuditCategory.CONNECTIONS, AuditCategory.of(k))
        }
        assertEquals(AuditCategory.DROPPED, AuditCategory.of("drop.vote_limit"))
    }

    @Test
    fun noVoteTitleCarriesAChoiceAndAnUnknownVoteKindIsShownAsItIs() {
        // The history entries hold no choice (the vault never audits it): the title is built from names only.
        assertFalse(title("vote.cast").contains("yes"))
        assertEquals("vote.disputed", AuditKinds.title("vote.disputed", "P1").let { (it as AuditKinds.Title.Unknown).kind })
    }
}
