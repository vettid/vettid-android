package com.vettid.feature.notifications

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.ui.theme.VettIdTheme
import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The Notifications screen (ANDROID-PLAN 0.1.23, 11 Compose UI): sections, unread styles, TalkBack actions, empty states. */
@RunWith(RobolectricTestRunner::class)
class NotificationsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.now()
    private val names = FeedNames(mapOf("c1" to ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Alice", lastName = "Moreau")))
    private val alarm = FeedItem("a", 2, "credential.alarm", now.minusSeconds(60).toString(), "active", "urgent")
    private val message = FeedItem("m", 1, "message.received", now.minusSeconds(120).toString(), "read", connectionId = "c1")

    @Test
    fun needsAttentionUnreadAndActions() {
        val done = mutableListOf<String>()
        val actions = NotificationsActions(
            onArchive = { done += "archive:${it.itemId}" },
            onToggleRead = { done += "toggle:${it.itemId}" },
            onOpen = { done += "open:${it.itemId}" },
        )
        rule.setContent {
            VettIdTheme { NotificationsScreen(NotificationsUiState(listOf(alarm, message), names, ListLoad.LOADED), actions) }
        }
        rule.onNodeWithText("Needs attention").assertExists()
        rule.onNodeWithText("Your credential was used somewhere else").assertExists()
        rule.onNodeWithText("New message from Alice Moreau").assertExists()
        rule.onNodeWithTag("feed_row_a").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val custom = rule.onNodeWithTag("feed_row_a").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Archive", "Mark as read"), custom.map { it.label })
        custom.first().action?.invoke()
        rule.onNodeWithTag("feed_row_m").performClick()
        assertEquals(listOf("archive:a", "open:m"), done)
    }

    @Test
    fun emptyStates() {
        var unreadOnly = true
        rule.setContent {
            VettIdTheme {
                NotificationsScreen(
                    NotificationsUiState(listOf(message), names, ListLoad.LOADED, unreadOnly = unreadOnly),
                    NotificationsActions(onUnreadOnly = { unreadOnly = it }),
                )
            }
        }
        rule.onNodeWithTag("notifications_empty_unread").assertExists()
        rule.onNodeWithTag("clear_filters").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(false, unreadOnly)
    }
}
