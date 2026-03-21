package com.vettid.app.ui.navigation

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.vettid.app.NatsConnectionState

private const val TAG = "MainScaffold"

/**
 * Main scaffold for the Activity screen (home).
 * Shows Feed | Voting | Archive tabs at the top.
 * Avatar navigates to Vault screen, cloud icon opens Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActivityScaffold(
    activitySegment: ActivitySegment,
    onActivitySegmentChange: (ActivitySegment) -> Unit,
    isSettingsOpen: Boolean,
    onSettingsToggle: () -> Unit,
    profilePhotoBase64: String? = null,
    natsConnectionState: NatsConnectionState = NatsConnectionState.Idle,
    onAvatarClick: () -> Unit = {},
    // Activity content slots
    feedContent: @Composable (searchQuery: String) -> Unit,
    votingContent: @Composable (searchQuery: String) -> Unit,
    archiveContent: @Composable (searchQuery: String) -> Unit,
    // Settings overlay
    settingsContent: @Composable () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(activitySegment, isSettingsOpen) {
        if (isSearchActive) { isSearchActive = false; searchQuery = "" }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HeaderView(
                title = if (isSettingsOpen) "Settings" else activitySegment.title,
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
                ActivityIconTabSelector(
                    segments = ActivitySegment.entries,
                    selectedIndex = ActivitySegment.entries.indexOf(activitySegment),
                    onSegmentSelected = { onActivitySegmentChange(ActivitySegment.entries[it]) }
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    when (activitySegment) {
                        ActivitySegment.FEED -> feedContent(searchQuery)
                        ActivitySegment.VOTING -> votingContent(searchQuery)
                        ActivitySegment.ARCHIVE -> archiveContent(searchQuery)
                    }
                }
            }
        }
    }
}

/**
 * Vault scaffold — shown when user taps their avatar.
 * Shows Connections | Data | Secrets tabs at the top.
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
    // Vault content slots
    connectionsContent: @Composable (searchQuery: String) -> Unit,
    personalDataContent: @Composable (searchQuery: String) -> Unit,
    secretsContent: @Composable (searchQuery: String) -> Unit,
    // FAB
    onFabClick: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(vaultSegment) {
        if (isSearchActive) { isSearchActive = false; searchQuery = "" }
    }

    // FAB only for Connections and Data — Secrets has its own built-in FAB with template chooser
    val showFab = vaultSegment != VaultSegment.SECRETS
    val fabIcon = when (vaultSegment) {
        VaultSegment.CONNECTIONS -> Icons.Default.PersonAdd
        VaultSegment.DATA -> Icons.Default.Add
        VaultSegment.SECRETS -> Icons.Default.Add
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HeaderView(
                title = "Vault",
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
            IconTabSelector(
                segments = VaultSegment.entries,
                selectedIndex = VaultSegment.entries.indexOf(vaultSegment),
                onSegmentSelected = { onVaultSegmentChange(VaultSegment.entries[it]) }
            )
            Box(modifier = Modifier.fillMaxSize()) {
                when (vaultSegment) {
                    VaultSegment.CONNECTIONS -> connectionsContent(searchQuery)
                    VaultSegment.DATA -> personalDataContent(searchQuery)
                    VaultSegment.SECRETS -> secretsContent(searchQuery)
                }
            }
        }
    }
}

/**
 * Tab row with icons only for Vault segments.
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
                icon = { Icon(segment.icon, contentDescription = segment.title) }
            )
        }
    }
}

/**
 * Tab row with icons only for Activity segments.
 */
@Composable
fun ActivityIconTabSelector(
    segments: List<ActivitySegment>,
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
                icon = { Icon(segment.icon, contentDescription = segment.title) }
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
