package com.vettid.app.ui.navigation

import android.util.Log
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlin.math.abs
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vettid.app.NatsConnectionState

private const val TAG = "MainScaffold"

/**
 * Simplified main scaffold with drawer navigation (no bottom bar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    navigationState: NavigationState,
    onNavigationStateChange: (NavigationState) -> Unit,
    userName: String = "",
    userEmail: String = "",
    profilePhotoBase64: String? = null,
    // NATS connection state
    natsConnectionState: NatsConnectionState = NatsConnectionState.Idle,
    natsErrorMessage: String? = null,
    onNatsRetry: () -> Unit = {},
    onNatsStatusClick: () -> Unit = {},
    onHeaderAction: () -> Unit = {},
    // Content slots - each receives the current search query
    feedContent: @Composable (searchQuery: String) -> Unit,
    connectionsContent: @Composable (searchQuery: String) -> Unit,
    personalDataContent: @Composable (searchQuery: String) -> Unit,
    secretsContent: @Composable (searchQuery: String) -> Unit,
    archiveContent: @Composable (searchQuery: String) -> Unit,
    votingContent: @Composable (searchQuery: String) -> Unit,
    settingsContent: @Composable () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    drawerBadgeCounts: Map<DrawerItem, Int> = emptyMap()
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val density = LocalDensity.current

    // Search state managed at scaffold level
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Clear search when screen changes or settings opens
    LaunchedEffect(navigationState.currentItem, navigationState.isSettingsOpen) {
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        }
    }

    // Track cumulative drag for swipe detection
    var cumulativeDrag by remember { mutableFloatStateOf(0f) }

    // Get ordered list of drawer items for cycling
    val drawerItems = remember { DrawerItem.entries.toList() }
    val currentIndex = drawerItems.indexOf(navigationState.currentItem)

    Log.d(TAG, "Current navigation item: ${navigationState.currentItem}, index: $currentIndex, photoLength: ${profilePhotoBase64?.length ?: 0}")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(navigationState.currentItem) {
                detectHorizontalDragGestures(
                    onDragStart = { cumulativeDrag = 0f },
                    onDragEnd = {
                        val threshold = with(density) { 100.dp.toPx() }
                        // Only allow swiping when not in drawer or settings
                        if (!navigationState.isDrawerOpen && !navigationState.isSettingsOpen) {
                            when {
                                // Swipe left - go to next screen
                                cumulativeDrag < -threshold -> {
                                    val nextIndex = (currentIndex + 1) % drawerItems.size
                                    val nextItem = drawerItems[nextIndex]
                                    Log.d(TAG, "Swiping to next screen: $nextItem")
                                    onNavigationStateChange(navigationState.copy(currentItem = nextItem))
                                }
                                // Swipe right - go to previous screen
                                cumulativeDrag > threshold -> {
                                    val prevIndex = if (currentIndex > 0) currentIndex - 1 else drawerItems.size - 1
                                    val prevItem = drawerItems[prevIndex]
                                    Log.d(TAG, "Swiping to previous screen: $prevItem")
                                    onNavigationStateChange(navigationState.copy(currentItem = prevItem))
                                }
                            }
                        }
                        cumulativeDrag = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        cumulativeDrag += dragAmount
                    }
                )
            }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                HeaderView(
                    title = if (navigationState.isSettingsOpen) "Settings" else navigationState.currentItem.title,
                    onProfileClick = {
                        if (!navigationState.isSettingsOpen) {
                            onNavigationStateChange(navigationState.copy(isDrawerOpen = true))
                        }
                    },
                    natsConnectionState = natsConnectionState,
                    onNatsStatusClick = {
                        // Toggle Settings when cloud icon is clicked
                        onNavigationStateChange(navigationState.copy(isSettingsOpen = !navigationState.isSettingsOpen))
                    },
                    scrollBehavior = scrollBehavior,
                    profilePhotoBase64 = profilePhotoBase64,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onSearchToggle = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) searchQuery = ""
                    },
                    showSearchIcon = !navigationState.isSettingsOpen
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Main content based on current drawer item or settings overlay
                Box(modifier = Modifier.fillMaxSize()) {
                    if (navigationState.isSettingsOpen) {
                        settingsContent()
                    } else {
                        when (navigationState.currentItem) {
                            DrawerItem.FEED -> feedContent(searchQuery)
                            DrawerItem.CONNECTIONS -> connectionsContent(searchQuery)
                            DrawerItem.PERSONAL_DATA -> personalDataContent(searchQuery)
                            DrawerItem.SECRETS -> secretsContent(searchQuery)
                            DrawerItem.ARCHIVE -> archiveContent(searchQuery)
                            DrawerItem.VOTING -> votingContent(searchQuery)
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
            currentItem = navigationState.currentItem,
            onItemSelected = { item ->
                onNavigationStateChange(
                    navigationState.copy(
                        currentItem = item,
                        isDrawerOpen = false
                    )
                )
            },
            userName = userName,
            userEmail = userEmail,
            profilePhotoBase64 = profilePhotoBase64,
            badgeCounts = drawerBadgeCounts
        )
    }
}
