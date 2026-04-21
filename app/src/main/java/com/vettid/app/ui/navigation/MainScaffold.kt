package com.vettid.app.ui.navigation

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.vettid.app.NatsConnectionState

private const val TAG = "MainScaffold"

/**
 * Main scaffold for the Connections screen (home).
 *
 * Renamed from "Activity" with Feed | Voting | Archive tabs to a single
 * "Connections" list. Everything that used to be a standalone feed event
 * (guides, migration prompts, vote notifications, security alerts) now
 * lives in the VettID system connection's audit trail, reached by
 * tapping that connection's card. See
 * plans/luminous-unifying-manatee.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActivityScaffold(
    isSettingsOpen: Boolean,
    onSettingsToggle: () -> Unit,
    profilePhotoBase64: String? = null,
    natsConnectionState: NatsConnectionState = NatsConnectionState.Idle,
    onAvatarClick: () -> Unit = {},
    // Connections content (formerly the "Feed" tab)
    feedContent: @Composable (searchQuery: String) -> Unit,
    // Settings overlay
    settingsContent: @Composable () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(isSettingsOpen) {
        if (isSearchActive) { isSearchActive = false; searchQuery = "" }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HeaderView(
                title = if (isSettingsOpen) "Settings" else "Connections",
                onProfileClick = onAvatarClick,
                natsConnectionState = natsConnectionState,
                onNatsStatusClick = onSettingsToggle,
                scrollBehavior = scrollBehavior,
                profilePhotoBase64 = profilePhotoBase64,
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onSearchToggle = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) searchQuery = ""
                },
                showSearchIcon = !isSettingsOpen
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isSettingsOpen) {
                settingsContent()
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    feedContent(searchQuery)
                }
            }
        }
    }
}

/**
 * Vault scaffold — shown when user taps their avatar.
 * Shows Data | Secrets | Wallets tabs at the top.
 * Back arrow returns to Activity screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScaffold(
    vaultSegment: VaultSegment,
    onVaultSegmentChange: (VaultSegment) -> Unit,
    profilePhotoBase64: String? = null,
    natsConnectionState: NatsConnectionState = NatsConnectionState.Idle,
    onBack: () -> Unit = {},
    onSettingsToggle: () -> Unit = {},
    isSettingsOpen: Boolean = false,
    // Persistent profile strip rendered above the tab selector. Shows
    // avatar + name + "profile needs publishing" banner, visible on
    // every Vault tab so signals live in one place.
    profileSection: @Composable () -> Unit = {},
    // Vault content slots
    personalDataContent: @Composable (searchQuery: String) -> Unit,
    secretsContent: @Composable (searchQuery: String) -> Unit,
    walletsContent: @Composable (searchQuery: String) -> Unit,
    // Settings overlay
    settingsContent: @Composable () -> Unit = {},
    // FAB
    onFabClick: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(vaultSegment, isSettingsOpen) {
        if (isSearchActive) { isSearchActive = false; searchQuery = "" }
    }

    // All tabs manage their own FABs internally
    val showFab = false
    val fabIcon = when (vaultSegment) {
        VaultSegment.DATA -> Icons.Default.Add
        VaultSegment.SECRETS -> Icons.Default.Add
        VaultSegment.WALLETS -> Icons.Default.AccountBalance
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HeaderView(
                title = if (isSettingsOpen) "Settings" else "Vault",
                onProfileClick = onBack,
                natsConnectionState = natsConnectionState,
                onNatsStatusClick = onSettingsToggle,
                scrollBehavior = scrollBehavior,
                profilePhotoBase64 = profilePhotoBase64,
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onSearchToggle = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) searchQuery = ""
                },
                showSearchIcon = true
            )
        },
        floatingActionButton = {
            if (showFab) {
                FloatingActionButton(onClick = onFabClick) {
                    Icon(fabIcon, contentDescription = "Add")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isSettingsOpen) {
                settingsContent()
            } else {
                profileSection()
                IconTabSelector(
                    segments = VaultSegment.entries,
                    selectedIndex = VaultSegment.entries.indexOf(vaultSegment),
                    onSegmentSelected = { onVaultSegmentChange(VaultSegment.entries[it]) }
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    when (vaultSegment) {
                        VaultSegment.DATA -> personalDataContent(searchQuery)
                        VaultSegment.SECRETS -> secretsContent(searchQuery)
                        VaultSegment.WALLETS -> walletsContent(searchQuery)
                    }
                }
            }
        }
    }
}

/**
 * Tab row with icons and labels for Vault segments.
 */
@Composable
fun IconTabSelector(
    segments: List<VaultSegment>,
    selectedIndex: Int,
    onSegmentSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier
    ) {
        segments.forEachIndexed { index, segment ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSegmentSelected(index) },
                icon = { Icon(segment.icon, contentDescription = segment.title) },
                text = { Text(segment.title, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
}

/**
 * Tab row with underline indicator.
 */
@Composable
fun TabSelector(
    segments: List<String>,
    selectedIndex: Int,
    onSegmentSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier
    ) {
        segments.forEachIndexed { index, label ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSegmentSelected(index) },
                text = { Text(label) }
            )
        }
    }
}
