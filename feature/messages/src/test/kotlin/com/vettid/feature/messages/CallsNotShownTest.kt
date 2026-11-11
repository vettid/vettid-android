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

/** (GC #7718) "Some calls couldn't be shown." (owner-approved 2026-11-10, GC #7716): shown with the calls that read, only then. */
@RunWith(RobolectricTestRunner::class)
class CallsNotShownTest {
    @get:Rule
    val rule = createComposeRule()

    private val c = ConnectionInfo("c1", "Alex", ConnectionState.ACTIVE, firstName = "Alex", lastName = "Doe")
    private val call = CallHistoryEntry("k1", "c1", false, CallOutcome.MISSED, Instant.parse("2026-11-10T10:00:00Z"))

    @Test
    fun theOwnersWordsWordForWord() {
        assertEquals("Some calls couldn't be shown.", RuntimeEnvironment.getApplication().getString(R.string.messages_calls_not_shown))
    }

    @Test
    fun theLineWithTheCallsThatRead() {
        rule.setContent {
            ConversationScreen(
                ConversationUiState("c1", c, emptyList(), loading = false, calls = listOf(call), callsNotShown = true),
                ConversationActions(),
            )
        }
        rule.onNodeWithTag("calls_not_shown").assertIsDisplayed()
        rule.onNodeWithText("Some calls couldn't be shown.").assertIsDisplayed()
    }

    @Test
    fun noLineWhenNoneWasSkipped() {
        rule.setContent {
            ConversationScreen(ConversationUiState("c1", c, emptyList(), loading = false, calls = listOf(call)), ConversationActions())
        }
        rule.onNodeWithTag("calls_not_shown").assertDoesNotExist()
    }
}
