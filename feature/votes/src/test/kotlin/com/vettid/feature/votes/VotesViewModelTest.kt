package com.vettid.feature.votes

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.votes.DroppedNotice
import com.vettid.core.data.votes.ReconnectEntry
import com.vettid.core.data.votes.VoteState
import com.vettid.core.data.votes.VoteSummary
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import com.vettid.core.testing.FakeVotes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** The Votes list (ANDROID-PLAN 0.1.102 §4 "Votes", screen 1). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VotesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val votes = FakeVotes()
    private val social = FakeSocial().apply {
        connections.value = listOf(ConnectionInfo("c1", "Example Cooperative", ConnectionState.ACTIVE, service = true))
    }

    private fun vote(conn: String, id: String, state: VoteState = VoteState.OPEN) = VoteSummary(
        proposalId = id, connectionId = conn, title = "T$id", state = state,
    )

    private fun vm(only: String? = null) = VotesViewModel(
        SavedStateHandle(if (only == null) emptyMap() else mapOf(ConnectionVotesRoute.ARG to only)),
        votes,
        social,
        FakeVault(),
    )

    @Test
    fun theListHoldsOpenAndClosedVotesAndTheServiceNames() = runTest {
        votes.votes.value = listOf(vote("c1", "A"), vote("c1", "B", VoteState.UPCOMING), vote("c1", "C", VoteState.CLOSED))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.uiState.value.open.map { it.proposalId })
        assertEquals(listOf("C"), vm.uiState.value.closed.map { it.proposalId })
        assertEquals("Example Cooperative", vm.uiState.value.services["c1"])
        assertFalse(vm.uiState.value.empty)
    }

    @Test
    fun twoServicesWithTheSameProposalIdAreTwoRows() = runTest {
        votes.votes.value = listOf(vote("c1", "A"), vote("c2", "A"))
        val vm = vm()
        assertEquals(listOf("c1/A", "c2/A"), vm.uiState.value.votes.map { it.key })
    }

    @Test
    fun aConnectionsVotesAreOnlyItsOwnWithItsOwnNotices() = runTest {
        votes.votes.value = listOf(vote("c1", "A"), vote("c2", "B"))
        votes.dropped.value = listOf(DroppedNotice("c1", 1), DroppedNotice("c2", 2))
        votes.reconnect.value = listOf(ReconnectEntry("c2", "s", "Other"))
        val vm = vm(only = "c1")
        assertEquals(listOf("c1/A"), vm.uiState.value.votes.map { it.key })
        assertEquals(listOf("c1"), vm.uiState.value.dropped.map { it.connectionId })
        assertTrue(vm.uiState.value.reconnect.isEmpty())
    }

    @Test
    fun withNothingToShowTheStateSaysEmptyOnlyOnceTheListWasRead() = runTest {
        votes.load.value = ListLoad.NOT_LOADED
        val vm = vm()
        assertFalse(vm.uiState.value.empty)
        votes.load.value = ListLoad.LOADED
        assertTrue(vm.uiState.value.empty)
        // A connection to connect again is something to show.
        votes.reconnect.value = listOf(ReconnectEntry("c2", "s", "Other"))
        assertFalse(vm.uiState.value.empty)
    }

    @Test
    fun aFailedReadIsShownWithItsKind() = runTest {
        votes.fail["refresh"] = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, vm.uiState.value.error)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun aShownDroppedNoticeIsAcknowledgedToTheRepository() = runTest {
        votes.dropped.value = listOf(DroppedNotice("c1", 3))
        val vm = vm()
        vm.noticesShown(listOf("c1"))
        assertEquals(listOf(listOf("c1")), votes.acknowledged)
        assertTrue(votes.dropped.value.isEmpty())
    }
}
