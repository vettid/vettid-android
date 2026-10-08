// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vettid.core.data.social.ConversationSummary
import com.vettid.core.testing.FakeSocial
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Messages' search behind the top bar's icon (owner request 2026-10-08): hidden, opens focused, filters, closes. */
@RunWith(RobolectricTestRunner::class)
class MessagesSearchTest {
    @get:Rule
    val rule = createComposeRule()

    private val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
    private val all = listOf(
        ConversationSummary(FakeSocial.connection("c1", "Sam"), FakeSocial.message("c1", "m1", "lunch?", outgoing = false, read = true, minute = 1), 0),
        ConversationSummary(FakeSocial.connection("c2", "Alex"), FakeSocial.message("c2", "m2", "the keys", outgoing = false, read = true, minute = 2), 0),
    )

    @Test
    fun theSearchIsHiddenThenOpensFiltersAndCloses() {
        var query by mutableStateOf("")
        rule.setContent {
            MessagesScreen(
                MessagesUiState(loading = false, conversations = all.filter { it.matches(query) }, noConnections = false, query = query, searchable = true),
                chrome,
                onQuery = { query = it },
            )
        }
        rule.onNodeWithTag("top_bar_search_field").assertDoesNotExist()
        rule.onNodeWithContentDescription("Search messages").performClick()
        rule.onNodeWithTag("top_bar_search_field").assertIsFocused()
        rule.onNodeWithTag("top_bar_search_field").performTextInput("keys")
        rule.onNodeWithTag("conversation_c2").assertExists()
        rule.onNodeWithTag("conversation_c1").assertDoesNotExist()
        rule.onNodeWithTag("top_bar_search_back").performClick()
        assertEquals("", query)
        rule.onNodeWithTag("top_bar_search_field").assertDoesNotExist()
        rule.onNodeWithTag("conversation_c1").assertExists()
    }

    @Test
    fun aQueryKeepsTheSearchShownAndNoMatchSaysSo() {
        rule.setContent {
            MessagesScreen(MessagesUiState(loading = false, noConnections = false, query = "zz", searchable = true), chrome)
        }
        rule.onNodeWithTag("top_bar_search_field").assertExists()
        rule.onNodeWithTag("messages_no_match").assertExists()
    }

    @Test
    fun withoutConversationsThereIsNoSearch() {
        rule.setContent { MessagesScreen(MessagesUiState(loading = false), chrome) }
        rule.onNodeWithContentDescription("Search messages").assertDoesNotExist()
    }
}
