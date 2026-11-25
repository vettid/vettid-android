package com.vettid.feature.messages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.ui.components.ApprovalsIndicator
import com.vettid.core.ui.components.LocalApprovalsIndicator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The approvals indicator is on a conversation, and not under its camera (ANDROID-PLAN §4 "The approvals indicator" 2). */
@RunWith(RobolectricTestRunner::class)
class ConversationIndicatorTest {
    @get:Rule
    val rule = createComposeRule()

    private val sam = ConnectionInfo("c1", "Sam", ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee")

    private fun show(capturing: Boolean) = rule.setContent {
        CompositionLocalProvider(LocalApprovalsIndicator provides ApprovalsIndicator(count = 1) {}) {
            ConversationScreen(
                ConversationUiState("c1", sam, emptyList(), loading = false, capturing = capturing),
                ConversationActions(),
            )
        }
    }

    @Test
    fun onAConversation() {
        show(capturing = false)
        rule.onNodeWithTag("top_bar_approvals").assertExists()
    }

    @Test
    fun notUnderTheCamera() {
        show(capturing = true)
        rule.onAllNodesWithTag("top_bar_approvals").assertCountEquals(0)
    }
}
