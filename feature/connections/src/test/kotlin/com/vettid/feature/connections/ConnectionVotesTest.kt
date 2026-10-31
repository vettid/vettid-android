// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.votes.BallotState
import com.vettid.core.data.votes.ReconnectEntry
import com.vettid.core.data.votes.VoteBallot
import com.vettid.core.data.votes.VoteState
import com.vettid.core.data.votes.VoteSummary
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVotes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A connection's detail and Votes (ANDROID-PLAN 0.1.102 "Votes", screens 8 and 9). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class ConnectionVotesTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val social = FakeSocial()
    private val votes = FakeVotes()

    private fun vote(conn: String, id: String, state: VoteState = VoteState.OPEN, ballot: VoteBallot? = null) =
        VoteSummary(proposalId = id, connectionId = conn, title = "T$id", state = state, ballot = ballot)

    private fun service(id: String = "c1") = ConnectionInfo(id, "Example Cooperative", ConnectionState.ACTIVE, firstName = "Example", lastName = "Cooperative", service = true)

    private fun vm(id: String = "c1") = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to id)), social, FakeSharing(), votes = votes)

    @Test
    fun aServiceConnectionKnowsItsVotesAndWhetherOneIsUnfinished() = runTest {
        social.seed(listOf(service()))
        votes.votes.value = listOf(vote("c1", "A"), vote("c2", "B"))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.votes.count)
        // An open vote is unfinished.
        assertTrue(vm.uiState.value.votes.unfinished)
        votes.votes.value = listOf(vote("c1", "A", VoteState.CLOSED, VoteBallot(BallotState.RECORDED)))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.votes.unfinished)
        // A ballot still being sent is unfinished too.
        votes.votes.value = listOf(vote("c1", "A", VoteState.CLOSED, VoteBallot(BallotState.SENDING)))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.votes.unfinished)
    }

    @Test
    fun aPersonConnectionTheServiceListNamesIsToBeConnectedAgain() = runTest {
        social.seed(listOf(FakeSocial.connection("c9", "Old Cooperative")))
        votes.reconnect.value = listOf(ReconnectEntry("c9", "svc", "Old Cooperative"))
        val vm = vm("c9")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.votes.reconnect)
        // The app changes nothing about the connection on its own.
        assertFalse(vm.uiState.value.connection!!.service)
        assertEquals(listOf("connection"), social.calls)
    }

    private fun detail(votes: ConnectionVotes, service: Boolean = true, onOpenVotes: () -> Unit = {}, confirm: DetailConfirm? = null) {
        val c = service().copy(service = service)
        rule.setContent {
            ConnectionDetailScreen(
                ConnectionDetailUiState(c.id, c, null, loading = false, votes = votes, confirm = confirm),
                DetailActions(onOpenVotes = onOpenVotes),
            )
        }
    }

    @Test
    fun aServiceWithVotesHasTheRowVotesFromTheService() {
        var opened = 0
        detail(ConnectionVotes(count = 2), onOpenVotes = { opened++ })
        rule.onNodeWithTag("detail_votes").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Votes from", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("detail_votes").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun noRowWithoutVotesOrForAPerson() {
        detail(ConnectionVotes(count = 0))
        assertEquals(0, rule.onAllNodesWithText("Votes from", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aPersonConnectionToConnectAgainShowsTheCardWithTheApprovedWords() {
        detail(ConnectionVotes(reconnect = true), service = false)
        rule.onNodeWithTag("vote_reconnect").assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("is registered with VettID as a service. This connection was made before it said it was a service, so it can't take part in votes.", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun removingAServiceWithAnUnfinishedVoteWarnsFirst() {
        detail(ConnectionVotes(count = 1, unfinished = true), confirm = DetailConfirm.REMOVE)
        assertTrue(
            rule.onAllNodesWithText("that isn't finished. If you remove this connection you can't vote in it.", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun removingAnyOtherConnectionDoesNotMentionVotes() {
        detail(ConnectionVotes(), confirm = DetailConfirm.REMOVE)
        assertEquals(0, rule.onAllNodesWithText("can't vote in it", substring = true).fetchSemanticsNodes().size)
    }
}
