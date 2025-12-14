package com.vettid.app.ui.navigation

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ContextualBottomNav(
    section: AppSection,
    vaultTab: VaultTab,
    vaultServicesTab: VaultServicesTab,
    appSettingsTab: AppSettingsTab,
    onVaultTabSelected: (VaultTab) -> Unit,
    onVaultServicesTabSelected: (VaultServicesTab) -> Unit,
    onAppSettingsTabSelected: (AppSettingsTab) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (section) {
        AppSection.VAULT -> VaultBottomNav(
            selectedTab = vaultTab,
            onTabSelected = onVaultTabSelected,
            onMoreClick = onMoreClick,
            modifier = modifier
        )
        AppSection.VAULT_SERVICES -> VaultServicesBottomNav(
            selectedTab = vaultServicesTab,
            onTabSelected = onVaultServicesTabSelected,
            modifier = modifier
        )
        AppSection.APP_SETTINGS -> AppSettingsBottomNav(
            selectedTab = appSettingsTab,
            onTabSelected = onAppSettingsTabSelected,
            modifier = modifier
        )
    }
}

@Composable
private fun VaultBottomNav(
    selectedTab: VaultTab,
    onTabSelected: (VaultTab) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        NavigationBarItem(
            icon = { Icon(VaultTab.CONNECTIONS.icon, contentDescription = null) },
            label = { Text(VaultTab.CONNECTIONS.title) },
            selected = selectedTab == VaultTab.CONNECTIONS,
            onClick = { onTabSelected(VaultTab.CONNECTIONS) }
        )
        NavigationBarItem(
            icon = { Icon(VaultTab.FEED.icon, contentDescription = null) },
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

@Composable
private fun AppSettingsBottomNav(
    selectedTab: AppSettingsTab,
    onTabSelected: (AppSettingsTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        AppSettingsTab.entries.forEach { tab ->
            NavigationBarItem(
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.title) },
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) }
            )
        }
    }
}
