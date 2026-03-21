package com.vettid.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom navigation tabs.
 */
enum class BottomTab(
    val title: String,
    val icon: ImageVector
) {
    VAULT("Vault", Icons.Default.Lock),
    ACTIVITY("Activity", Icons.Default.DynamicFeed)
}

/**
 * Segments within the Vault tab.
 */
enum class VaultSegment(val title: String, val icon: ImageVector) {
    CONNECTIONS("Connections", Icons.Default.People),
    DATA("Data", Icons.Default.Person),
    SECRETS("Secrets", Icons.Default.Lock)
}

/**
 * Segments within the Activity tab.
 */
enum class ActivitySegment(val title: String, val icon: ImageVector) {
    FEED("Feed", Icons.Default.DynamicFeed),
    VOTING("Voting", Icons.Default.HowToVote),
    ARCHIVE("Archive", Icons.Default.Archive)
}

/**
 * Navigation state for bottom tabs + segments.
 */
data class NavigationState(
    val bottomTab: BottomTab = BottomTab.ACTIVITY,
    val vaultSegment: VaultSegment = VaultSegment.CONNECTIONS,
    val activitySegment: ActivitySegment = ActivitySegment.FEED,
    val isSettingsOpen: Boolean = false
) {
    /** Current page title for the header. */
    val title: String
        get() = when {
            isSettingsOpen -> "Settings"
            bottomTab == BottomTab.VAULT -> vaultSegment.title
            else -> activitySegment.title
        }
}

// Legacy compat — keep DrawerItem references compiling during transition
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
