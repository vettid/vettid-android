package com.vettid.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

/**
 * Contextual bottom navigation that changes based on the current section.
 * Per mobile-ui-plan.md Section 2.3
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextualBottomNav(
    section: AppSection,
    vaultTab: VaultTab,
    vaultServicesTab: VaultServicesTab,
    onVaultTabSelected: (VaultTab) -> Unit,
    onVaultServicesTabSelected: (VaultServicesTab) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Badge counts
    pendingConnectionsCount: Int = 0,
    unreadFeedCount: Int = 0
) {
    when (section) {
        AppSection.VAULT -> VaultBottomNav(
            selectedTab = vaultTab,
            onTabSelected = onVaultTabSelected,
            onMoreClick = onMoreClick,
            modifier = modifier,
            pendingConnectionsCount = pendingConnectionsCount,
            unreadFeedCount = unreadFeedCount
        )
        AppSection.VAULT_SERVICES -> VaultServicesBottomNav(
            selectedTab = vaultServicesTab,
            onTabSelected = onVaultServicesTabSelected,
            modifier = modifier
        )
        AppSection.APP_SETTINGS -> {
            // App settings uses VaultPreferencesScreen directly, no bottom nav needed
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultBottomNav(
    selectedTab: VaultTab,
    onTabSelected: (VaultTab) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    pendingConnectionsCount: Int = 0,
    unreadFeedCount: Int = 0
) {
    NavigationBar(modifier = modifier) {
        NavigationBarItem(
            icon = {
                if (pendingConnectionsCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge {
                                Text(
                                    text = if (pendingConnectionsCount > 99) "99+" else pendingConnectionsCount.toString(),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    ) {
                        Icon(VaultTab.CONNECTIONS.icon, contentDescription = null)
                    }
                } else {
                    Icon(VaultTab.CONNECTIONS.icon, contentDescription = null)
                }
            },
            label = { Text(VaultTab.CONNECTIONS.title) },
            selected = selectedTab == VaultTab.CONNECTIONS,
            onClick = { onTabSelected(VaultTab.CONNECTIONS) }
        )
        NavigationBarItem(
            icon = {
                if (unreadFeedCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge {
                                Text(
                                    text = if (unreadFeedCount > 99) "99+" else unreadFeedCount.toString(),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    ) {
                        Icon(VaultTab.FEED.icon, contentDescription = null)
                    }
                } else {
                    Icon(VaultTab.FEED.icon, contentDescription = null)
                }
            },
            label = { Text(VaultTab.FEED.title) },
            selected = selectedTab == VaultTab.FEED,
            onClick = { onTabSelected(VaultTab.FEED) }
        )
        NavigationBarItem(
            icon = { Icon(VaultTab.MORE.icon, contentDescription = null) },
            label = { Text(VaultTab.MORE.title) },
            selected = selectedTab == VaultTab.MORE,
            onClick = onMoreClick
        )
    }
}

@Composable
private fun VaultServicesBottomNav(
    selectedTab: VaultServicesTab,
    onTabSelected: (VaultServicesTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        VaultServicesTab.entries.forEach { tab ->
            NavigationBarItem(
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.title) },
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) }
            )
        }
    }
}

