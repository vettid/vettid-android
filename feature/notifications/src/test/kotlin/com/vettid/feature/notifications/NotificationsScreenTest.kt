package com.vettid.feature.notifications

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        rule.onNodeWithText("New message from \u2068Alice Moreau\u2069").assertExists()
        rule.onNodeWithTag("feed_row_a").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val custom = rule.onNodeWithTag("feed_row_a").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Archive", "Mark as read"), custom.map { it.label })
        custom.first().action?.invoke()
        rule.onNodeWithTag("feed_row_m").performClick()
        assertEquals(listOf("archive:a", "open:m"), done)
    }

    /** "Service" where a row names a service connection (ANDROID-PLAN 0.1.90); never for a person. */
    @Test
    fun aServiceRowCarriesTheServiceLabelAndAPersonRowDoesNot() {
        val svc = ConnectionInfo("s1", "", ConnectionState.ACTIVE, firstName = "Acme", lastName = "Co-op", service = true)
        val withService = FeedNames(names.connections + ("s1" to svc))
        val serviceItem = FeedItem("s", 6, "message.received", now.minusSeconds(60).toString(), "read", connectionId = "s1")
        rule.setContent {
            VettIdTheme {
                val state = NotificationsUiState(listOf(serviceItem, message), withService, ListLoad.LOADED)
                NotificationsScreen(state, NotificationsActions())
            }
        }
        rule.onNodeWithTag("feed_row_s").assertExists()
        assertEquals(1, rule.onAllNodesWithText("Service", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun secondLineLengthAndSheetShowsItInFull() {
        val rollback = FeedItem("r", 3, "recovery.generation_closed", now.toString(), "active", "urgent", ref = "g1/rollback")
        val rotation = FeedItem("i", 4, "identity.rotation_failed", now.toString(), "active", "high")
        assertEquals(3, secondLineMaxLines(rollback))
        assertEquals(3, secondLineMaxLines(rollback.copy(status = "read")))
        assertEquals(1, secondLineMaxLines(rotation))
        assertEquals(1, secondLineMaxLines(message))
        rule.setContent { VettIdTheme { DetailSheetContent(rollback, names, gone = false) } }
        rule.onNodeWithTag("feed_sheet_second_line").assertTextEquals(
            "A host may have served an older copy of your vault's recovery record, one on which recovery attempts had " +
                "already been used. Your vault moved past it. If you did not try to recover your vault, change your PIN " +
                "and your password now, and contact VettID.",
        )
    }

    @Test
    fun sheetWithoutSecondLine() {
        rule.setContent { VettIdTheme { DetailSheetContent(alarm, names, gone = false) } }
        rule.onNodeWithTag("feed_sheet_second_line").assertDoesNotExist()
    }

    /** A guide's second line is its body's first line: the sheet shows the body alone, in full. */
    @Test
    fun aGuideShowsItsBodyWithoutTheSecondLine() {
        val guide = FeedItem("g", 5, "guide", now.toString(), "active", title = "Welcome", body = "First line\nSecond line")
        rule.setContent { VettIdTheme { DetailSheetContent(guide, names, gone = false) } }
        rule.onNodeWithTag("feed_sheet_second_line").assertDoesNotExist()
        rule.onNodeWithText("First line\nSecond line").assertExists()
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

    // AA6: the detail sheet of an agent's use of a secret: title, History's detail line and "Open the item" while the item exists.
    private val itemNames = FeedNames(items = mapOf("it1" to "GitHub token"))

    private fun agentUse(kind: String, ref: String) =
        FeedItem("u", 4, kind, now.toString(), "active", "normal", deviceId = "dev", ref = ref)

    @Test
    fun agentUseSheetsShowTheTitleTheDetailLineAndOpenTheKnownItem() {
        val run = "The secret went to your VettID connector for one command on your computer. The agent was not given it."
        val proxy = "The secret went to your VettID connector, which added it to the agent's requests to a site you allowed. " +
            "The agent was not given it."
        val read = "The agent was given the item's values. Its app may keep them in its history."
        val cases = listOf("leash.item.run" to run, "leash.item.proxy" to proxy, "leash.item.read" to read)
        var opened = 0
        var current by mutableStateOf(agentUse(cases[0].first, "it1"))
        rule.setContent { VettIdTheme { DetailSheetContent(current, itemNames, gone = false, onOpenItem = { opened++ }) } }
        for ((n, case) in cases.withIndex()) {
            val (kind, line) = case
            current = agentUse(kind, "it1")
            rule.waitForIdle()
            val verb = if (kind == "leash.item.read") "read" else "used"
            rule.onNodeWithText("An agent $verb \u2068GitHub token\u2069").assertExists()
            rule.onNodeWithTag("feed_sheet_detail").assertTextEquals(line)
            rule.onNodeWithTag("feed_sheet_open_item").assertTextEquals("Open the item").performClick()
            assertEquals(n + 1, opened)
        }
    }

    @Test
    fun agentUseSheetOfADeletedItemHasTheDetailLineButNoAction() {
        rule.setContent { VettIdTheme { DetailSheetContent(agentUse("leash.item.run", "gone"), itemNames, gone = false) } }
        rule.onNodeWithText("An agent used an item").assertExists()
        rule.onNodeWithTag("feed_sheet_detail").assertExists()
        rule.onNodeWithTag("feed_sheet_open_item").assertDoesNotExist()
    }
}
