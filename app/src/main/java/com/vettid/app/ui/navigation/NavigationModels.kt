package com.vettid.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Main navigation items in the drawer
 */
enum class DrawerItem(
    val title: String,
    val icon: ImageVector
) {
    FEED("Feed", Icons.Default.DynamicFeed),
    CONNECTIONS("Connections", Icons.Default.People),
    PERSONAL_DATA("Personal Data", Icons.Default.Person),
    SECRETS("Secrets", Icons.Default.Lock),
    ARCHIVE("Archive", Icons.Default.Archive),
    VOTING("Voting", Icons.Default.HowToVote),
    AUDIT_LOG("Audit Log", Icons.Default.Security)
}

/**
 * Navigation state holder (simplified)
 */
data class NavigationState(
    val currentItem: DrawerItem = DrawerItem.FEED,
    val isDrawerOpen: Boolean = false,
    val isSettingsOpen: Boolean = false
)

// Keep these for backwards compatibility during transition
enum class AppSection(
    val title: String,
    val icon: ImageVector
) {
    VAULT("Vault", Icons.Default.AccountBalance),
    VAULT_SERVICES("Vault Services", Icons.Default.Cloud),
    APP_SETTINGS("App Settings", Icons.Default.Settings)
}

enum class VaultTab(
    val title: String,
    val icon: ImageVector
) {
    CONNECTIONS("Connections", Icons.Default.People),
    FEED("Feed", Icons.Default.DynamicFeed),
    MORE("More", Icons.Default.MoreHoriz)
}

enum class VaultServicesTab(
    val title: String,
    val icon: ImageVector
) {
    STATUS("Status", Icons.Default.Dashboard),
    BACKUPS("Backups", Icons.Default.Backup),
    MANAGE("Manage", Icons.Default.Settings)
}


enum class VaultMoreItem(
    val title: String,
    val icon: ImageVector
) {
    PERSONAL_DATA("Personal Data", Icons.Default.Person),
    SECRETS("Secrets", Icons.Default.Lock),
    ARCHIVE("Archive", Icons.Default.Archive),
    VOTING("Voting", Icons.Default.HowToVote),
    PREFERENCES("Preferences", Icons.Default.Tune)
}
