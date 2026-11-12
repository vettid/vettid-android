package com.vettid.feature.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** (C2, owner decision 2026-11-12) Calls off: the conversation's call button is disabled, with the reason shown without a tap. */
@RunWith(RobolectricTestRunner::class)
class ConversationCallsOffScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val alex = ConnectionInfo("c2", "Alex", ConnectionState.ACTIVE, firstName = "Alex", lastName = "Doe")

    private fun show(off: Boolean) = rule.setContent {
        ConversationScreen(
            ConversationUiState("c2", alex, emptyList(), loading = false, call = CallAvailability.READY, callsOff = off),
            ConversationActions(),
        )
    }

    @Test
    fun offDisablesTheButtonAndSaysWhereToTurnThemOn() {
        show(off = true)
        rule.onNodeWithTag("conversation_call").assertIsNotEnabled()
        rule.onNodeWithTag("conversation_calls_off").assertIsDisplayed()
        rule.onNodeWithText("Calls with ⁨Alex⁩ are off. You can turn them on in ⁨Alex⁩'s details.").assertIsDisplayed()
    }

    @Test
    fun onKeepsTheButtonAndSaysNothing() {
        show(off = false)
        rule.onNodeWithTag("conversation_call").assertIsEnabled()
        rule.onNodeWithTag("conversation_calls_off").assertDoesNotExist()
    }
}
