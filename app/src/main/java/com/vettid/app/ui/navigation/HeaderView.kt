package com.vettid.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeaderView(
    title: String,
    onProfileClick: () -> Unit,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null,
    showSearch: Boolean = false,
    onSearchClick: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    TopAppBar(
        navigationIcon = {
            // Profile avatar (opens drawer)
            IconButton(onClick = onProfileClick) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Profile",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        actions = {
            if (showSearch && onSearchClick != null) {
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                }
            }
            if (actionIcon != null && onActionClick != null) {
                IconButton(onClick = onActionClick) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = "Action"
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

/**
 * Returns the appropriate header configuration for each screen
 */
data class HeaderConfig(
    val title: String,
    val actionIcon: ImageVector? = null,
    val showSearch: Boolean = false
)

fun getHeaderConfig(
    section: AppSection,
    vaultTab: VaultTab,
    vaultServicesTab: VaultServicesTab,
    appSettingsTab: AppSettingsTab
): HeaderConfig {
    return when (section) {
        AppSection.VAULT -> when (vaultTab) {
            VaultTab.CONNECTIONS -> HeaderConfig(
                title = "Connections",
                actionIcon = Icons.Default.PersonAdd,
                showSearch = true
            )
            VaultTab.FEED -> HeaderConfig(
                title = "Feed",
                showSearch = false
            )
            VaultTab.MORE -> HeaderConfig(
                title = "More",
                showSearch = false
            )
        }
        AppSection.VAULT_SERVICES -> when (vaultServicesTab) {
            VaultServicesTab.STATUS -> HeaderConfig(
                title = "Vault Status",
                actionIcon = Icons.Default.Refresh
            )
            VaultServicesTab.BACKUPS -> HeaderConfig(
                title = "Backups",
                actionIcon = null
            )
            VaultServicesTab.MANAGE -> HeaderConfig(
                title = "Manage Vault",
                actionIcon = null
            )
        }
        AppSection.APP_SETTINGS -> when (appSettingsTab) {
            AppSettingsTab.GENERAL -> HeaderConfig(
                title = "General Settings"
            )
            AppSettingsTab.SECURITY -> HeaderConfig(
                title = "Security"
            )
            AppSettingsTab.BACKUP -> HeaderConfig(
                title = "Backup & Recovery"
            )
        }
    }
}
