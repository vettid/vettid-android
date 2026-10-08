package com.vettid.feature.messages

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
}
