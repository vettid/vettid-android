// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.presence.PresenceReading
import com.vettid.core.data.presence.PresenceState
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.testing.FakeAttachments
import com.vettid.core.testing.FakePresence
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Presence under a conversation's title (ANDROID-PLAN §4 "Presence", P1): asked when it opens, persons only. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PresenceConversationTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val social = FakeSocial().apply {
        seed(
            listOf(
                FakeSocial.connection("c1", "Sam"),
                ConnectionInfo("s1", "Acme", ConnectionState.ACTIVE, firstName = "Acme", lastName = "Co-op", service = true),
            ),
        )
    }
    private val presence = FakePresence()

    private fun vm(id: String, p: FakePresence = presence) =
        ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to id)), social, social, FakeAttachments(), presence = p)

    @Test
    fun aPersonIsAskedOnceWhenTheConversationOpens() = runTest {
        val vm = vm("c1")
        advanceUntilIdle()
        assertEquals(listOf("c1"), presence.queries)
        assertEquals(PresenceReading.Checking, vm.uiState.value.presence)
        presence.readings.value = mapOf("c1" to PresenceReading.Known(PresenceState.AWAY, null))
        advanceUntilIdle()
        assertEquals(PresenceReading.Known(PresenceState.AWAY, null), vm.uiState.value.presence)
        // A new message or anything else does not ask again.
        social.seed(social.connections.value)
        advanceUntilIdle()
        assertEquals(1, presence.queries.size)
    }

    @Test
    fun aHoldDuringTheConversationAsksAgainWhenPresenceIsBack() = runTest {
        val vm = vm("c1")
        advanceUntilIdle()
        presence.availability.value = com.vettid.core.data.presence.PresenceAvailability.UNKNOWN
        presence.readings.value = emptyMap()
        advanceUntilIdle()
        assertNull(vm.uiState.value.presence)
        presence.availability.value = com.vettid.core.data.presence.PresenceAvailability.AVAILABLE
        advanceUntilIdle()
        assertEquals(listOf("c1", "c1"), presence.queries)
        assertEquals(PresenceReading.Checking, vm.uiState.value.presence)
        // A lost reading is "Status unknown", never "Checking…" for good.
        presence.readings.value = emptyMap()
        advanceUntilIdle()
        assertEquals(PresenceReading.Unknown, vm.uiState.value.presence)
    }

    @Test
    fun aServiceConversationHasNoLineAndNothingIsAsked() = runTest {
        val vm = vm("s1")
        advanceUntilIdle()
        assertNull(vm.uiState.value.presence)
        assertTrue(presence.queries.isEmpty())
    }

    @Test
    fun withoutPresenceInTheVaultThereIsNoLineAndNoQuestion() = runTest {
        val none = FakePresence(available = false)
        val vm = vm("c1", none)
        advanceUntilIdle()
        assertNull(vm.uiState.value.presence)
        assertTrue(none.queries.isEmpty())
    }

    private fun screen(presence: PresenceReading?) {
        val c = FakeSocial.connection("c1", "Sam")
        rule.setContent { ConversationScreen(ConversationUiState("c1", c, emptyList(), loading = false, presence = presence), ConversationActions()) }
    }

    @Test
    fun theLineIsUnderTheTitle() {
        screen(PresenceReading.Known(PresenceState.BUSY, null))
        rule.onNodeWithText("Busy").assertIsDisplayed()
    }

    @Test
    fun noLineWithoutPresence() {
        screen(null)
        assertEquals(0, rule.onAllNodesWithTag("conversation_presence").fetchSemanticsNodes().size)
    }
}
