package com.vettid.feature.messages

import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallHistoryEntry
import com.vettid.core.data.calls.CallOutcome
import com.vettid.core.testing.FakeCalls
import java.time.Duration
import java.time.Instant
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val social = FakeSocial().apply {
        seed(
            listOf(FakeSocial.connection("c1", "Sam", favorite = true), FakeSocial.connection("c2", "Alex")),
            mapOf(
                "c1" to listOf(FakeSocial.message("c1", "m1", "hi", outgoing = false, read = true, minute = 1)),
                "c2" to listOf(FakeSocial.message("c2", "m2", "unread", outgoing = false, read = false, minute = 2)),
            ),
        )
    }

    @Test
    fun listsConversationsAndFiltersUnread() = runTest {
        val vm = MessagesViewModel(social)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertFalse(s.noConnections)
        assertEquals(listOf("c2", "c1"), s.conversations.map { it.connection.id })
        vm.setUnreadOnly(true)
        advanceUntilIdle()
        assertEquals(listOf("c2"), vm.uiState.value.conversations.map { it.connection.id })
        assertTrue("refreshConversations" in social.calls)
    }

    @Test
    fun theSearchFiltersByNameAndTheLatestMessage() = runTest {
        // Owner request 2026-10-08: a local filter, by the connection's name and the latest message's text.
        val vm = MessagesViewModel(social)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.searchable)
        vm.setQuery("sam")
        advanceUntilIdle()
        assertEquals(listOf("c1"), vm.uiState.value.conversations.map { it.connection.id })
        vm.setQuery("UNREAD")
        advanceUntilIdle()
        assertEquals(listOf("c2"), vm.uiState.value.conversations.map { it.connection.id })
        vm.setQuery("nobody")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.conversations.isEmpty())
        assertTrue(vm.uiState.value.searchable)
        vm.setQuery("")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.conversations.size)
    }

    @Test
    fun aFailedRefreshShowsTheError() = runTest {
        social.fail["refreshConversations"] = VaultFailure(FailureKind.NO_RESPONSE)
        val vm = MessagesViewModel(social)
        advanceUntilIdle()
        assertEquals(FailureKind.NO_RESPONSE, vm.uiState.value.error)
    }

    @Test
    fun conversationMarksReadSendsAndDeletes() = runTest {
        val vm = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c2")), social, social)
        advanceUntilIdle()
        assertEquals("Alex", vm.uiState.value.connection?.name)
        // Open: the unread incoming message is marked read.
        assertTrue("markRead" in social.calls)
        assertTrue(vm.uiState.value.messages.all { it.read })

        assertFalse(vm.uiState.value.canSend)
        vm.setDraft("  hello there  ")
        vm.send()
        advanceUntilIdle()
        assertEquals("hello there", social.lastText)
        assertEquals("", vm.uiState.value.draft)
        assertEquals(2, vm.uiState.value.messages.size)

        val sent = vm.uiState.value.messages.last()
        vm.askDelete(sent)
        advanceUntilIdle()
        assertEquals(sent, vm.uiState.value.deleting)
        vm.confirmDelete()
        advanceUntilIdle()
        assertNull(vm.uiState.value.deleting)
        assertEquals(1, vm.uiState.value.messages.size)
    }

    @Test
    fun aFailedSendKeepsTheDraftAndTooLongTextCannotBeSent() = runTest {
        val vm = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c1")), social, social)
        advanceUntilIdle()
        social.fail["send"] = VaultFailure(FailureKind.CONNECTION_UNAVAILABLE, "connection_unavailable")
        vm.setDraft("hello")
        vm.send()
        advanceUntilIdle()
        assertEquals("hello", vm.uiState.value.draft)
        assertEquals(FailureKind.CONNECTION_UNAVAILABLE, vm.uiState.value.error)
        vm.setDraft("x".repeat(16 * 1024 + 1))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.tooLong)
        assertFalse(vm.uiState.value.canSend)
    }

    @Test
    fun aStaleConnectionCannotBeWrittenTo() = runTest {
        social.seed(listOf(FakeSocial.connection("c3", "Jo", state = ConnectionState.STALE)))
        val vm = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c3")), social, social)
        advanceUntilIdle()
        vm.setDraft("hi")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.active)
        assertFalse(vm.uiState.value.canSend)
    }

    @Test
    fun newMessageListsActiveConnectionsFavouritesFirst() = runTest {
        social.seed(
            listOf(
                FakeSocial.connection("c1", "Zed", favorite = true),
                FakeSocial.connection("c2", "Amy"),
                FakeSocial.connection("c3", "Bo", state = ConnectionState.PENDING),
            ),
        )
        val vm = NewMessageViewModel(social)
        advanceUntilIdle()
        assertEquals(listOf("c1", "c2"), vm.uiState.value.connections.map { it.id })
    }

    // --- calls (CALLS-PLAN C1) ---

    private fun conversation(calls: FakeCalls, id: String = "c2") =
        ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to id)), social, social, calls)

    @Test
    fun theCallButtonFollowsWhatTheBuildAndTheVaultHave() = runTest {
        // A build without calls (every production build): no button, and the vault is never asked for its calls.
        val plain = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c2")), social, social)
        advanceUntilIdle()
        assertFalse(plain.uiState.value.callShown)
        plain.call()

        val calls = FakeCalls(CallAvailability.UNKNOWN)
        val vm = conversation(calls)
        advanceUntilIdle()
        assertFalse("not before the vault was asked", vm.uiState.value.callShown)
        assertEquals(0, calls.historyReads)

        calls.availability.value = CallAvailability.READY
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callShown && vm.uiState.value.callEnabled)
        vm.call()
        assertEquals(listOf("c2"), calls.called)

        // During a call the button is there and disabled; a tap does nothing.
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callShown && !vm.uiState.value.callEnabled)
        vm.call()
        assertEquals(1, calls.called.size)

        // A vault whose release has no calls: the tap says so and calls nobody.
        calls.availability.value = CallAvailability.NOT_IN_VAULT
        advanceUntilIdle()
        vm.call()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callsNotAvailable)
        assertEquals(1, calls.called.size)
        vm.dismissCallNotice()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.callsNotAvailable)
    }

    @Test
    fun theConversationListsItsCallsAndReadsThemAgainAfterACall() = runTest {
        val t = Instant.parse("2026-10-12T10:00:00Z")
        val calls = FakeCalls()
        calls.entries = listOf(
            CallHistoryEntry("k1", "c2", true, CallOutcome.TALKED, t, Duration.ofSeconds(252)),
            CallHistoryEntry("k2", "c1", false, CallOutcome.MISSED, t),
            CallHistoryEntry("k3", "c2", false, CallOutcome.ANSWERED_ELSEWHERE, t.plusSeconds(60)),
        )
        val vm = conversation(calls)
        advanceUntilIdle()
        assertEquals("this connection's calls only", listOf("k1", "k3"), vm.uiState.value.calls.map { it.callId })
        val rows = CallEntries.rows(vm.uiState.value.messages, vm.uiState.value.calls)
        assertEquals("a call answered elsewhere leaves no entry", 1, rows.count { it is ConversationRow.Call })
        assertEquals(rows.sortedBy { it.at }, rows)

        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        val unanswered = CallHistoryEntry("k4", "c2", true, CallOutcome.NOT_ANSWERED, t.plusSeconds(120))
        calls.entries = calls.entries + unanswered
        calls.availability.value = CallAvailability.READY
        advanceUntilIdle()
        assertEquals(listOf("k1", "k3", "k4"), vm.uiState.value.calls.map { it.callId })
    }

    @Test
    fun aCallsLineAndItsDuration() {
        assertEquals(CallEntries.Parts(0, 4, 12), CallEntries.parts(Duration.ofSeconds(252)))
        assertEquals(R.string.messages_call_duration_min_s, CallEntries.durationFormat(CallEntries.parts(Duration.ofSeconds(252))))
        assertEquals(R.string.messages_call_duration_s, CallEntries.durationFormat(CallEntries.parts(Duration.ofSeconds(12))))
        assertEquals(R.string.messages_call_duration_h_min, CallEntries.durationFormat(CallEntries.parts(Duration.ofSeconds(3852))))
        for (o in CallOutcome.entries) {
            val none = o == CallOutcome.ANSWERED_ELSEWHERE || o == CallOutcome.ONGOING
            assertEquals(o.name, none, CallEntries.text(o) == null)
        }
    }
}
