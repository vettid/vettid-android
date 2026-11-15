package com.vettid.feature.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.calls.CallHistoryEntry
import com.vettid.core.data.calls.CallOutcome
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

/** "Calls may be out of date. VettID couldn't check them just now." (owner-approved 2026-11-15): shown only when stale. */
@RunWith(RobolectricTestRunner::class)
class CallsStaleTest {
    @get:Rule
    val rule = createComposeRule()

    private val c = ConnectionInfo("c1", "Alex", ConnectionState.ACTIVE, firstName = "Alex", lastName = "Doe")
    private val call = CallHistoryEntry("k1", "c1", false, CallOutcome.MISSED, Instant.parse("2026-11-15T10:00:00Z"))

    @Test
    fun theOwnersWordsWordForWord() {
        assertEquals(
            "Calls may be out of date. VettID couldn't check them just now.",
            RuntimeEnvironment.getApplication().getString(R.string.messages_calls_stale),
        )
    }

    @Test
    fun theNoteWithTheCallsKept() {
        rule.setContent {
            ConversationScreen(
                ConversationUiState("c1", c, emptyList(), loading = false, calls = listOf(call), callsStale = true),
                ConversationActions(),
            )
        }
        rule.onNodeWithTag("calls_stale").assertIsDisplayed()
        rule.onNodeWithText("Calls may be out of date. VettID couldn't check them just now.").assertIsDisplayed()
    }

    @Test
    fun noNoteWhenNotStale() {
        rule.setContent {
            ConversationScreen(ConversationUiState("c1", c, emptyList(), loading = false, calls = listOf(call)), ConversationActions())
        }
        rule.onNodeWithTag("calls_stale").assertDoesNotExist()
    }
}
