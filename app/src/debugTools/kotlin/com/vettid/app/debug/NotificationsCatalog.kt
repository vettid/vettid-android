// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.GrantEntry
import com.vettid.core.ui.components.NotificationBell
import com.vettid.core.ui.components.VettIdTopAppBar
import com.vettid.core.vault.FeedItem
import com.vettid.feature.notifications.DetailSheetContent
import com.vettid.feature.notifications.NotificationsActions
import com.vettid.feature.notifications.NotificationsScreen
import com.vettid.feature.notifications.NotificationsUiState
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The Notifications screen (ANDROID-PLAN 0.1.23): the vault's feed with Needs attention, the day groups, the four
 * priorities, a batch, an unknown kind, the Archived view, the empty states, the delete confirmation and the sheet.
 */
internal object NotificationsCatalog {
    private val now = Instant.now()

    private fun at(minutesAgo: Long) = now.minus(minutesAgo, ChronoUnit.MINUTES).toString()

    private val connections = listOf(
        ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Alice", lastName = "Moreau"),
        ConnectionInfo("c2", "Dr Lee", ConnectionState.ACTIVE, favorite = true, firstName = "Dana", lastName = "Lee"),
        ConnectionInfo("c3", "", ConnectionState.ACTIVE, firstName = "Bob", lastName = "Okafor"),
    )

    private val grant = Approval.GrantRequest(
        "g1", "c2", listOf(GrantEntry("item", "i1", "Passport", true, name = "Passport")), 1, null, null, now, null, "Dana Lee",
    )

    private val names = FeedNames(
        connections = connections.associateBy { it.id },
        items = mapOf("i1" to "Passport", "i2" to "Bank login"),
        approvals = mapOf(grant.key to grant),
    )

    private val items = listOf(
        FeedItem("f01", 31, "credential.alarm", at(3), "active", "urgent", ref = "a1"),
        FeedItem("f02", 30, "message.received", at(8), "active", connectionId = "c1", ref = "m1"),
        FeedItem("f03", 29, "grant.request", at(20), "active", connectionId = "c2", ref = "g1"),
        FeedItem("f04", 28, "grant.request", at(45), "active", connectionId = "c3", ref = "g2", count = 3),
        FeedItem("f05", 27, "owner_check.failed", at(90), "read", "high", ref = "password"),
        FeedItem("f06", 26, "connection.added", at(60 * 26), "read", connectionId = "c3"),
        FeedItem("f07", 25, "item.revealed", at(60 * 27), "read", ref = "i2"),
        FeedItem("f08", 24, "guide", at(60 * 28), "read", "low", title = "Welcome to VettID", body = "Your vault keeps your data and talks to your connections for you."),
        FeedItem("f09", 23, "vault.something_new", at(60 * 50), "active"),
        FeedItem("f10", 22, "connection.removed", at(60 * 24 * 4), "read", connectionId = "c9"),
        FeedItem("f11", 21, "location.request", at(60 * 24 * 5), "read", connectionId = "c1", ref = "l1"),
        FeedItem("f12", 20, "message.received", at(60 * 24 * 6), "archived", connectionId = "c1", ref = "m0"),
        FeedItem("f13", 19, "grant.request", at(60 * 24 * 7), "archived", connectionId = "c2", ref = "g0", count = 2),
    )

    private val loaded = NotificationsUiState(items = items, names = names, load = ListLoad.LOADED)

    @Composable
    private fun Screen(state: NotificationsUiState) = NotificationsScreen(state, NotificationsActions())

    @Composable
    private fun Bars() = Column {
        listOf(0 to false, 1 to false, 7 to true, 140 to false).forEach { (n, urgent) ->
            VettIdTopAppBar("Messages", onMenuClick = {}, accountName = "Sam Rivera", onAvatarClick = {}, onSearchClick = {}, bell = NotificationBell(n, urgent) {})
        }
    }

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "notifications" to { Screen(loaded) },
        "notifications.unread" to { Screen(loaded.copy(unreadOnly = true)) },
        "notifications.archived" to { Screen(loaded.copy(archived = true)) },
        "notifications.empty" to { Screen(NotificationsUiState(load = ListLoad.LOADED)) },
        "notifications.empty_unread" to { Screen(loaded.copy(items = items.filter { it.status != "active" }, unreadOnly = true)) },
        "notifications.empty_archived" to { Screen(NotificationsUiState(load = ListLoad.LOADED, archived = true)) },
        "notifications.loading" to { Screen(NotificationsUiState(load = ListLoad.LOADING)) },
        "notifications.failed" to { Screen(NotificationsUiState(load = ListLoad.FAILED)) },
        "notifications.delete_confirm" to { Screen(loaded.copy(archived = true, confirmDelete = items.last())) },
        "notifications.sheet_gone" to { DetailSheetContent(items[3], names, gone = true) },
        "notifications.sheet_removed" to { DetailSheetContent(items[9], names, gone = false) },
        "notifications.sheet_unknown" to { DetailSheetContent(items[8], names, gone = false) },
        "notifications.sheet_not_available" to { DetailSheetContent(items[10], names, gone = false) },
        "notifications.sheet_guide" to { DetailSheetContent(items[7], names, gone = false) },
        "shell.bell" to { Bars() },
    )
}
