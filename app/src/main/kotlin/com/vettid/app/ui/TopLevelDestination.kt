package com.vettid.app.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.ui.graphics.vector.ImageVector
import com.vettid.app.R
import com.vettid.feature.approvals.ApprovalsRoute
import com.vettid.feature.connections.ConnectionsRoute
import com.vettid.feature.connections.InviteRoute
import com.vettid.feature.credential.CredentialRoute
import com.vettid.feature.items.ItemsRoute
import com.vettid.feature.messages.MessagesRoute
import com.vettid.feature.settings.HelpRoute
import com.vettid.feature.settings.SettingsRoute
import kotlin.reflect.KClass

/** Drawer destinations of v1 (ANDROID-PLAN §4), in drawer order and groups. */
enum class TopLevelDestination(
    val route: Any,
    val icon: ImageVector,
    @param:StringRes val label: Int,
    val group: Int,
) {
    Messages(MessagesRoute, Icons.Outlined.ChatBubbleOutline, R.string.nav_messages, group = 0),
    Connections(ConnectionsRoute, Icons.Outlined.People, R.string.nav_connections, group = 0),
    Approvals(ApprovalsRoute, Icons.Outlined.TaskAlt, R.string.nav_approvals, group = 0),
    Items(ItemsRoute, Icons.Outlined.Inventory2, R.string.nav_items, group = 1),
    Credential(CredentialRoute, Icons.Outlined.Shield, R.string.nav_credential, group = 1),
    // The "create" group (ANDROID-PLAN §4): New item joins it with A5.
    Invite(InviteRoute, Icons.Outlined.PersonAdd, R.string.nav_invite, group = 2),
    Settings(SettingsRoute, Icons.Outlined.Settings, R.string.nav_settings, group = 3),
    Help(HelpRoute, Icons.AutoMirrored.Outlined.HelpOutline, R.string.nav_help, group = 3),
    ;

    val routeClass: KClass<*> get() = route::class

    /** Stable drawer key. */
    val key: String get() = name

    companion object {
        fun fromKey(key: String): TopLevelDestination? = entries.firstOrNull { it.key == key }
    }
}
