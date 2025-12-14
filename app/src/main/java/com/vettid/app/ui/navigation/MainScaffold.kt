package com.vettid.app.ui.navigation

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Main scaffold with drawer, contextual header, and bottom navigation.
 * This is the primary navigation shell for the authenticated app experience.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    navigationState: NavigationState,
    onNavigationStateChange: (NavigationState) -> Unit,
    userName: String = "VettID User",
    userEmail: String = "",
    vaultStatus: VaultStatus = VaultStatus.ACTIVE,
    onSignOutVaultOnly: () -> Unit,
    onSignOutVaultServices: () -> Unit,
    onHeaderAction: () -> Unit,
    onSearchClick: () -> Unit,
    // Navigation callbacks for more sheet
    onNavigateToPersonalData: () -> Unit,
    onNavigateToSecrets: () -> Unit,
    onNavigateToArchive: () -> Unit,
    onNavigateToPreferences: () -> Unit,
    // Badge counts
    pendingConnectionsCount: Int = 0,
    unreadFeedCount: Int = 0,
    // Content slots for each tab
    vaultConnectionsContent: @Composable () -> Unit,
    vaultFeedContent: @Composable () -> Unit,
    vaultServicesStatusContent: @Composable () -> Unit,
    vaultServicesBackupsContent: @Composable () -> Unit,
    vaultServicesManageContent: @Composable () -> Unit,
    appSettingsGeneralContent: @Composable () -> Unit,
    appSettingsSecurityContent: @Composable () -> Unit,
    appSettingsBackupContent: @Composable () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val density = LocalDensity.current

    // Sign out bottom sheet state
    var showSignOutSheet by remember { mutableStateOf(false) }

    // Get header config for current screen
    val headerConfig = getHeaderConfig(
        section = navigationState.currentSection,
        vaultTab = navigationState.vaultTab,
        vaultServicesTab = navigationState.vaultServicesTab,
        appSettingsTab = navigationState.appSettingsTab
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    val threshold = with(density) { 50.dp.toPx() }
                    if (dragAmount > threshold && !navigationState.isDrawerOpen) {
                        onNavigationStateChange(navigationState.copy(isDrawerOpen = true))
                    }
                }
            }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                HeaderView(
                    title = headerConfig.title,
                    onProfileClick = {
                        onNavigationStateChange(navigationState.copy(isDrawerOpen = true))
                    },
                    actionIcon = headerConfig.actionIcon,
                    onActionClick = onHeaderAction,
                    showSearch = headerConfig.showSearch,
                    onSearchClick = onSearchClick,
                    scrollBehavior = scrollBehavior
                )
            },
            bottomBar = {
                ContextualBottomNav(
                    section = navigationState.currentSection,
                    vaultTab = navigationState.vaultTab,
                    vaultServicesTab = navigationState.vaultServicesTab,
                    appSettingsTab = navigationState.appSettingsTab,
                    onVaultTabSelected = { tab ->
                        onNavigationStateChange(navigationState.copy(vaultTab = tab))
                    },
                    onVaultServicesTabSelected = { tab ->
                        onNavigationStateChange(navigationState.copy(vaultServicesTab = tab))
                    },
                    onAppSettingsTabSelected = { tab ->
                        onNavigationStateChange(navigationState.copy(appSettingsTab = tab))
                    },
                    onMoreClick = {
                        onNavigationStateChange(navigationState.copy(isMoreSheetOpen = true))
                    },
                    pendingConnectionsCount = pendingConnectionsCount,
                    unreadFeedCount = unreadFeedCount
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Render content based on current section and tab
                when (navigationState.currentSection) {
                    AppSection.VAULT -> {
                        when (navigationState.vaultTab) {
                            VaultTab.CONNECTIONS -> vaultConnectionsContent()
                            VaultTab.FEED -> vaultFeedContent()
                            VaultTab.MORE -> {
                                // More tab just opens the sheet, show connections by default
                                vaultConnectionsContent()
                            }
                        }
                    }
                    AppSection.VAULT_SERVICES -> {
                        when (navigationState.vaultServicesTab) {
                            VaultServicesTab.STATUS -> vaultServicesStatusContent()
                            VaultServicesTab.BACKUPS -> vaultServicesBackupsContent()
                            VaultServicesTab.MANAGE -> vaultServicesManageContent()
                        }
                    }
                    AppSection.APP_SETTINGS -> {
                        when (navigationState.appSettingsTab) {
                            AppSettingsTab.GENERAL -> appSettingsGeneralContent()
                            AppSettingsTab.SECURITY -> appSettingsSecurityContent()
                            AppSettingsTab.BACKUP -> appSettingsBackupContent()
                        }
                    }
                }
            }
        }

        // Drawer overlay
        DrawerView(
            isOpen = navigationState.isDrawerOpen,
            onClose = {
                onNavigationStateChange(navigationState.copy(isDrawerOpen = false))
            },
            currentSection = navigationState.currentSection,
            onSectionChange = { section ->
                onNavigationStateChange(
                    navigationState.copy(
                        currentSection = section,
                        isDrawerOpen = false
                    )
                )
            },
            userName = userName,
            userEmail = userEmail,
            vaultStatus = vaultStatus,
            onSignOut = {
                onNavigationStateChange(navigationState.copy(isDrawerOpen = false))
                showSignOutSheet = true
            }
        )

        // More bottom sheet
        MoreBottomSheet(
            isOpen = navigationState.isMoreSheetOpen,
            onDismiss = {
                onNavigationStateChange(navigationState.copy(isMoreSheetOpen = false))
            },
            onItemClick = { item ->
                when (item) {
                    VaultMoreItem.PERSONAL_DATA -> onNavigateToPersonalData()
                    VaultMoreItem.SECRETS -> onNavigateToSecrets()
                    VaultMoreItem.ARCHIVE -> onNavigateToArchive()
                    VaultMoreItem.PREFERENCES -> onNavigateToPreferences()
                }
            }
        )

        // Sign out bottom sheet
        SignOutBottomSheet(
            isOpen = showSignOutSheet,
            onDismiss = { showSignOutSheet = false },
            onSignOutVaultOnly = onSignOutVaultOnly,
            onSignOutVaultServices = onSignOutVaultServices,
            isVaultActive = vaultStatus == VaultStatus.ACTIVE
        )
    }
}
