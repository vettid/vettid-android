package com.vettid.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Main app sections accessible via drawer
 */
enum class AppSection(
    val title: String,
    val icon: ImageVector
) {
    VAULT("Vault", Icons.Default.AccountBalance),
    VAULT_SERVICES("Vault Services", Icons.Default.Cloud),
    APP_SETTINGS("App Settings", Icons.Default.Settings)
}

/**
 * Bottom nav items for Vault section
 */
enum class VaultTab(
    val title: String,
    val icon: ImageVector
) {
    CONNECTIONS("Connections", Icons.Default.People),
    FEED("Feed", Icons.Default.DynamicFeed),
    MORE("More", Icons.Default.MoreHoriz)
}

/**
 * Bottom nav items for Vault Services section
 */
enum class VaultServicesTab(
    val title: String,
    val icon: ImageVector
) {
    STATUS("Status", Icons.Default.Dashboard),
    HANDLERS("Handlers", Icons.Default.Extension),
    LOGS("Logs", Icons.Default.Article)
}

/**
 * Bottom nav items for App Settings section
 */
enum class AppSettingsTab(
    val title: String,
    val icon: ImageVector
) {
    GENERAL("General", Icons.Default.Tune),
    SECURITY("Security", Icons.Default.Security),
    BACKUP("Backup", Icons.Default.Backup)
}

/**
 * Items shown in "More" bottom sheet for Vault section
 */
enum class VaultMoreItem(
    val title: String,
    val icon: ImageVector
) {
    PERSONAL_DATA("Personal Data", Icons.Default.Person),
    SECRETS("Secrets", Icons.Default.Lock),
    DOCUMENTS("Documents", Icons.Default.Description),
    CREDENTIALS("Credentials", Icons.Default.Key)
}

/**
 * Navigation state holder
 */
data class NavigationState(
    val currentSection: AppSection = AppSection.VAULT,
    val isDrawerOpen: Boolean = false,
    val isMoreSheetOpen: Boolean = false,
    val vaultTab: VaultTab = VaultTab.CONNECTIONS,
    val vaultServicesTab: VaultServicesTab = VaultServicesTab.STATUS,
    val appSettingsTab: AppSettingsTab = AppSettingsTab.GENERAL
)
