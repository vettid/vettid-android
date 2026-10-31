// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.feed

import com.vettid.core.data.R
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vote feed items (ANDROID-PLAN 0.1.102 "Votes", Notifications; VAULT-MESSAGING §10.19.7). */
class FeedVoteItemsTest {
    private fun item(kind: String, connectionId: String? = "c1", ref: String? = "P1", priority: String = "normal") =
        FeedItem("i", 1, kind, "2026-10-30T12:00:00Z", "active", priority, connectionId = connectionId, ref = ref)

    private val names = FeedNames(
        connections = mapOf("c1" to ConnectionInfo("c1", "Example Cooperative", ConnectionState.ACTIVE, firstName = "Example", lastName = "Cooperative")),
        votes = mapOf(VoteTitles.key("c1", "P1") to "⁨Adopt the budget⁩"),
    )

    @Test
    fun theFourKindsAreKnownAndTitledAsApproved() {
        assertEquals(FeedText(R.string.data_feed_vote_open), FeedKinds.title(item("vote.open"), names))
        assertEquals(FeedText(R.string.data_feed_vote_closing), FeedKinds.title(item("vote.closing"), names))
        assertEquals(FeedText(R.string.data_feed_vote_result), FeedKinds.title(item("vote.result"), names))
        assertEquals(FeedText(R.string.data_feed_vote_problem), FeedKinds.title(item("vote.problem", priority = "high"), names))
        for (k in listOf("vote.open", "vote.closing", "vote.result", "vote.problem")) assertTrue(k, FeedKinds.known(k))
    }

    @Test
    fun theSecondLineIsTheServiceAndTheProposalTitleFromTheListInMemory() {
        val line = FeedKinds.supporting(item("vote.open"), names)!!
        assertEquals(R.string.data_feed_vote_line, line.res)
        assertEquals(listOf("⁨Adopt the budget⁩"), line.args.drop(1))
        // Without the title in memory, the service alone.
        assertEquals(FeedKinds.connectionName(item("vote.open"), names), FeedKinds.supporting(item("vote.open"), FeedNames(names.connections)))
        // A vote problem says only that something needs attention; it carries no name or title.
        assertEquals(FeedText(R.string.data_feed_vote_problem_body), FeedKinds.supporting(item("vote.problem"), names))
    }

    @Test
    fun theTitleNeverNamesAVoteInTheNoNamesMode() {
        // Names off: the title only (the notification builds the second line only with names on).
        val t = FeedKinds.title(item("vote.open"), names, details = false)
        assertEquals(emptyList<Any>(), t.args)
    }

    @Test
    fun aVoteItemOpensItsVoteByThePairOrTheList() {
        assertEquals(FeedTarget.Vote("c1", "P1"), FeedKinds.target(item("vote.open")))
        assertEquals(FeedTarget.Vote("c1", "P1"), FeedKinds.target(item("vote.problem", priority = "high")))
        // Without the pair that names a vote, the list.
        assertEquals(FeedTarget.VoteList, FeedKinds.target(item("vote.result", connectionId = null)))
        assertEquals(FeedTarget.VoteList, FeedKinds.target(item("vote.closing", ref = null)))
    }

    @Test
    fun noVoteItemIsAnAskOrHistoryOnlyOrNotAvailable() {
        for (k in listOf("vote.open", "vote.closing", "vote.result", "vote.problem")) {
            assertFalse(FeedKinds.isAsk(k))
            assertFalse(FeedKinds.historyOnly(k))
            assertFalse(FeedKinds.notAvailable(k))
        }
        assertNull(FeedAsks.approvalKey("vote.open", "P1"))
    }
}
