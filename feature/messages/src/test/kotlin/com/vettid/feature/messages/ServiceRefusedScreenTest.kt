package com.vettid.feature.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** `forbidden` to a message (ANDROID-PLAN 0.1.90): "Services can't do that." alone, and no composer. */
@RunWith(RobolectricTestRunner::class)
class ServiceRefusedScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theRefusalStandsAloneAndTheComposerIsGone() {
        val c = ConnectionInfo("c2", "Alex", ConnectionState.ACTIVE, firstName = "Alex", lastName = "Doe")
        rule.setContent {
            ConversationScreen(
                ConversationUiState("c2", c, emptyList(), loading = false, error = FailureKind.SERVICE_FORBIDDEN, serviceRefused = true),
                ConversationActions(),
            )
        }
        rule.onNodeWithText("Services can't do that.").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Could not load", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("composer").assertDoesNotExist()
    }
}
