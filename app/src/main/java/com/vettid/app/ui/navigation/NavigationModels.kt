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
    ACTIVITY("Connections", Icons.Default.People)
}

/**
 * Segments within the Vault tab.
 */
enum class VaultSegment(val title: String, val icon: ImageVector) {
    DATA("Data", Icons.Default.Person),
    SECRETS("Secrets", Icons.Default.Lock),
    WALLETS("Wallets", Icons.Default.AccountBalance)
}

/**
 * Navigation state for bottom tabs + segments.
 */
data class NavigationState(
    val bottomTab: BottomTab = BottomTab.ACTIVITY,
    val vaultSegment: VaultSegment = VaultSegment.DATA,
    val isSettingsOpen: Boolean = false
) {
    /** Current page title for the header. */
    val title: String
        get() = when {
            isSettingsOpen -> "Settings"
            bottomTab == BottomTab.VAULT -> vaultSegment.title
            else -> "Connections"
        }
}

// Legacy compat — keep DrawerItem references compiling during transition
enum class DrawerItem(
    val title: String,
    val icon: ImageVector
) {
    FEED("Connections", Icons.Default.People),
    PERSONAL_DATA("Personal Data", Icons.Default.Person),
    SECRETS("Secrets", Icons.Default.Lock),
    WALLETS("Wallets", Icons.Default.AccountBalance),
    AUDIT_LOG("Audit Log", Icons.Default.Security)
}
