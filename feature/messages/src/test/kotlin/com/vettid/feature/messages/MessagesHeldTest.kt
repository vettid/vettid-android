// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vettid.core.data.social.ConversationSummary
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.testing.FakeSocial
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The daily owner check is due (owner-approved 2026-11-03, as vettid-ios #201): the list says it is on hold, not failed. */
@RunWith(RobolectricTestRunner::class)
class MessagesHeldTest {
    @get:Rule
    val rule = createComposeRule()

    private val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
    private val rows = listOf(
        ConversationSummary(FakeSocial.connection("c1", "Sam"), FakeSocial.message("c1", "m1", "lunch?", outgoing = false, read = true, minute = 1), 0),
    )
    private val title = "Messages are on hold"
    private val body = "Your daily check is due. They'll show again once it's done."

    @Test
    fun theTextsAreTheOwnersWordForWord() {
        val r = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        assertEquals(title, r.getString(R.string.messages_held_title))
        assertEquals(body, r.getString(R.string.messages_held_body))
    }

    @Test
    fun aHeldListWithoutRowsShowsTheInfoNoticeAndNoRetry() {
        rule.setContent { MessagesScreen(MessagesUiState(loading = false, error = FailureKind.OWNER_CHECK_REQUIRED), chrome) }
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText(body).assertIsDisplayed()
        rule.onNodeWithText("Try again").assertDoesNotExist()
        rule.onNodeWithText("Could not load messages").assertDoesNotExist()
    }

    @Test
    fun aHeldListWithRowsShowsTheNoticeAboveThem() {
        rule.setContent {
            MessagesScreen(MessagesUiState(loading = false, conversations = rows, noConnections = false, error = FailureKind.OWNER_CHECK_REQUIRED), chrome)
        }
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithTag("conversation_c1").assertIsDisplayed()
        rule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun anotherFailureStillShowsTheErrorWithTryAgain() {
        rule.setContent { MessagesScreen(MessagesUiState(loading = false, error = FailureKind.NO_RESPONSE), chrome) }
        rule.onNodeWithText("Could not load messages").assertIsDisplayed()
        rule.onNodeWithText("Try again").assertIsDisplayed()
        rule.onNodeWithText(title).assertDoesNotExist()
    }
}
