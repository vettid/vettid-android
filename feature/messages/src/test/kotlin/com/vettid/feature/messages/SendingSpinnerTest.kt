package com.vettid.feature.messages

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A6 audit A12: the spinner that replaces the send button while a message is on its way has the button's name. */
@RunWith(RobolectricTestRunner::class)
class SendingSpinnerTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theSendingSpinnerIsNamedLikeTheSendButton() {
        val c = ConnectionInfo("c2", "Alex", ConnectionState.ACTIVE, firstName = "Alex", lastName = "Doe")
        // The spinner is indeterminate: the clock is held so that the test never waits for it.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ConversationScreen(ConversationUiState("c2", c, emptyList(), loading = false, sending = true), ConversationActions())
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("sending", useUnmergedTree = true).assertContentDescriptionEquals("Send")
    }
}
