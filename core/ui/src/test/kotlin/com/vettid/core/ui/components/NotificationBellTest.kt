package com.vettid.core.ui.components

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.ui.theme.VettIdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Notifications bell (ANDROID-PLAN 0.1.23): no badge at 0, the count, "99+", red while an item is urgent. */
@RunWith(RobolectricTestRunner::class)
class NotificationBellTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(unread: Int, urgent: Boolean, onClick: () -> Unit = {}) = rule.setContent {
        VettIdTheme { NotificationBellButton(NotificationBell(unread, urgent, onClick)) }
    }

    @Test
    fun noBadgeAtZero() {
        show(0, false)
        rule.onAllNodesWithTag("bell_badge", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("top_bar_bell").assertContentDescriptionEquals("Notifications")
    }

    @Test
    fun oneUnread() {
        var clicks = 0
        show(1, false) { clicks++ }
        rule.onNodeWithTag("bell_badge", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("top_bar_bell").assertContentDescriptionEquals("Notifications, 1 unread").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun manyUnreadAndUrgent() {
        show(140, true)
        rule.onNodeWithTag("bell_badge_urgent", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("99+", useUnmergedTree = true).assertExists()
    }
}
