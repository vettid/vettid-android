package com.vettid.app.features.feed

import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.PhoneMissed
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.nats.FeedEvent
import com.vettid.app.core.nats.EventPriority
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Feed screen showing activity events.
 * This is an embedded content composable meant to be used within MainScaffold.
 */
@Composable
fun FeedContent(
    viewModel: FeedViewModel = hiltViewModel(),
    searchQuery: String = "",
    onNavigateToConversation: (String) -> Unit = {},
    onNavigateToConnectionDetail: (String) -> Unit = {},
    onNavigateToConnectionHistory: (String) -> Unit = {},
    onNavigateToConnectionRequest: (String) -> Unit = {},
    onResurfaceVaultUpdate: () -> Unit = {},
    onNavigateToHandler: (String) -> Unit = {},
    onNavigateToBackup: (String) -> Unit = {},
    onNavigateToGuide: (guideId: String, eventId: String, userName: String) -> Unit = { _, _, _ -> },
    onNavigateToAgentApproval: (requestId: String) -> Unit = {},
    onNavigateToConnectionReview: (connectionId: String, eventId: String) -> Unit = { _, _ -> },
    onNavigateToCreateInvitation: () -> Unit = {},
    onNavigateToScanInvitation: () -> Unit = {},
    onNavigateToCreateAgentInvitation: () -> Unit = {},
    onNavigateToConnectDesktop: () -> Unit = {},
    onBtcSend: (connectionId: String) -> Unit = {},
    onBtcRequest: (connectionId: String) -> Unit = {},
    onNavigateToVaultMessages: () -> Unit = {},
    onNavigateToVotes: () -> Unit = {},
    onNavigateToGuidesList: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Route search query from top bar to ViewModel
    LaunchedEffect(searchQuery) {
        viewModel.updateSearchQuery(searchQuery)
    }

    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isOfflineModeEnabled by viewModel.isOfflineModeEnabled.collectAsState()

    // Snackbar state for action feedback
    val snackbarHostState = remember { SnackbarHostState() }

    // Event detail dialog state
    var selectedEvent by remember { mutableStateOf<FeedEvent?>(null) }

    // Call permission handling
    var pendingCallConnectionId by remember { mutableStateOf<String?>(null) }
    var pendingCallIsVideo by remember { mutableStateOf(false) }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            pendingCallConnectionId?.let { connId ->
                if (pendingCallIsVideo) {
                    viewModel.startVideoCall(connId)
                } else {
                    viewModel.startVoiceCall(connId)
                }
            }
        }
        pendingCallConnectionId = null
    }

    // Refresh on every resume so the card's last-activity timestamp
    // reflects anything that happened on another screen (e.g. a call
    // just placed from the detail view) without the user pulling to
    // refresh. Same pattern as ConnectionHistoryScreen.
    val feedLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(feedLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        feedLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { feedLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FeedEffect.NavigateToConversation -> onNavigateToConversation(effect.connectionId)
                is FeedEffect.NavigateToConnectionRequest -> onNavigateToConnectionRequest(effect.requestId)
                is FeedEffect.NavigateToHandler -> onNavigateToHandler(effect.handlerId)
                is FeedEffect.NavigateToBackup -> onNavigateToBackup(effect.backupId)
                is FeedEffect.NavigateToGuide -> onNavigateToGuide(effect.guideId, effect.eventId, effect.userName)
                is FeedEffect.NavigateToAgentApproval -> onNavigateToAgentApproval(effect.requestId)
                is FeedEffect.NavigateToConnectionReview -> onNavigateToConnectionReview(effect.connectionId, effect.eventId)
                is FeedEffect.NavigateToConnectionDetail -> onNavigateToConnectionDetail(effect.connectionId)
                is FeedEffect.ShowEventDetail -> selectedEvent = effect.event
                is FeedEffect.ShowVaultUpdatePrompt -> onResurfaceVaultUpdate()
                is FeedEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
                is FeedEffect.ShowActionSuccess -> snackbarHostState.showSnackbar(effect.message)
                else -> { /* Handle other effects */ }
            }
        }
    }

    // Event detail dialog
    selectedEvent?.let { event ->
        EventDetailDialog(
            event = event,
            onDismiss = { selectedEvent = null },
            onAction = { action ->
                viewModel.executeAction(event.eventId, action)
                selectedEvent = null
            },
            onArchive = {
                viewModel.archiveEvent(event.eventId)
                selectedEvent = null
            },
            onDelete = {
                viewModel.deleteEvent(event.eventId)
                selectedEvent = null
            }
        )
    }

    // Handle in-app notifications (real-time from NATS)
    // Skip guide events (shown in feed, not as popup notifications)
    LaunchedEffect(Unit) {
        viewModel.inAppNotification.collect { notification ->
            if (notification.eventType == "guide") return@collect
            val result = snackbarHostState.showSnackbar(
                message = notification.title + (notification.message?.let { ": $it" } ?: ""),
                actionLabel = "View",
                duration = if (notification.priority >= 1) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.navigateToEventById(notification.eventId)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (val currentState = state) {
                is FeedState.Loading -> {
                    // If in offline mode, don't show loading - show empty or cached
                    if (isOfflineModeEnabled) {
                        OfflineModeContent()
                    } else {
                        LoadingContent()
                    }
                }
                is FeedState.Empty -> {
                    if (isOfflineModeEnabled) {
                        OfflineModeContent()
                    } else {
                        EmptyContent(
                            isRefreshing = isRefreshing,
                            onRefresh = { viewModel.refresh() }
                        )
                    }
                }
                is FeedState.Loaded -> FeedList(
                    items = currentState.items,
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    onDisplayItemClick = { viewModel.onDisplayItemClick(it) },
                    onNavigateToConversation = onNavigateToConversation,
                    onNavigateToConnectionDetail = onNavigateToConnectionDetail,
                    onNavigateToConnectionHistory = onNavigateToConnectionHistory,
                    onEventClick = { viewModel.onEventClick(it) },
                    onArchive = { viewModel.archiveEvent(it.eventId) },
                    onDelete = { viewModel.deleteEvent(it.eventId) },
                    onAction = { event, action -> viewModel.executeAction(event.eventId, action) },
                    onTogglePriority = { viewModel.togglePriority(it.eventId) },
                    onConnectionAccept = { connectionId -> viewModel.acceptConnection(connectionId) },
                    onConnectionDecline = { connectionId -> viewModel.declineConnection(connectionId) },
                    onNavigateToConnectionReview = onNavigateToConnectionReview,
                    onVoiceCall = { connectionId ->
                        val hasAudio = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (hasAudio) {
                            viewModel.startVoiceCall(connectionId)
                        } else {
                            pendingCallConnectionId = connectionId
                            pendingCallIsVideo = false
                            callPermissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                        }
                    },
                    onVideoCall = { connectionId ->
                        val hasAudio = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        val hasCamera = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (hasAudio && hasCamera) {
                            viewModel.startVideoCall(connectionId)
                        } else {
                            pendingCallConnectionId = connectionId
                            pendingCallIsVideo = true
                            callPermissionLauncher.launch(arrayOf(
                                android.Manifest.permission.RECORD_AUDIO,
                                android.Manifest.permission.CAMERA
                            ))
                        }
                    },
                    onResurfaceVaultUpdate = onResurfaceVaultUpdate,
                    onBtcSend = onBtcSend,
                    onBtcRequest = onBtcRequest,
                    onSystemVaultMessages = onNavigateToVaultMessages,
                    onSystemVotes = onNavigateToVotes,
                    onSystemGuides = onNavigateToGuidesList,
                    onOpenGuideById = { guideId ->
                        viewModel.markGuideRead(guideId)
                        onNavigateToGuide(guideId, "", "")
                    },
                )
                is FeedState.Error -> {
                    // If in offline mode, show friendly offline content instead of error
                    if (isOfflineModeEnabled) {
                        OfflineModeContent()
                    } else {
                        ErrorContent(
                            message = currentState.message,
                            onRetry = { viewModel.loadFeed() }
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // FAB — Connection actions (create/scan/agent invitation)
        var showFabMenu by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.animation.AnimatedVisibility(visible = showFabMenu) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExtendedFloatingActionButton(
                        onClick = { showFabMenu = false; onNavigateToCreateAgentInvitation() },
                        icon = { Icon(Icons.Default.Computer, contentDescription = null) },
                        text = { Text("Agent Invitation") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    ExtendedFloatingActionButton(
                        onClick = { showFabMenu = false; onNavigateToConnectDesktop() },
                        icon = { Icon(Icons.Default.Computer, contentDescription = null) },
                        text = { Text("Connect Desktop") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    ExtendedFloatingActionButton(
                        onClick = { showFabMenu = false; onNavigateToScanInvitation() },
                        icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                        text = { Text("Scan Invitation") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    ExtendedFloatingActionButton(
                        onClick = { showFabMenu = false; onNavigateToCreateInvitation() },
                        icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                        text = { Text("Create Invitation") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            FloatingActionButton(
                onClick = { showFabMenu = !showFabMenu }
            ) {
                Icon(
                    if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = "Connections"
                )
            }
        }
    }
}

/**
 * Content shown when user has intentionally chosen offline mode.
 * Shows a friendly message instead of error state.
 */
@Composable
private fun OfflineModeContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Offline Mode",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You're working offline. Go online to see your feed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FeedList(
    items: List<FeedDisplayItem>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDisplayItemClick: (FeedDisplayItem) -> Unit,
    onNavigateToConversation: (String) -> Unit,
    onNavigateToConnectionDetail: (String) -> Unit = {},
    onNavigateToConnectionHistory: (String) -> Unit = {},
    onEventClick: (FeedEvent) -> Unit,
    onArchive: (FeedEvent) -> Unit,
    onDelete: (FeedEvent) -> Unit,
    onAction: (FeedEvent, String) -> Unit,
    onTogglePriority: (FeedEvent) -> Unit = {},
    onConnectionAccept: (String) -> Unit = {},
    onConnectionDecline: (String) -> Unit = {},
    onNavigateToConnectionReview: (String, String) -> Unit = { _, _ -> },
    onVoiceCall: (String) -> Unit = {},
    onVideoCall: (String) -> Unit = {},
    onBtcSend: (String) -> Unit = {},
    onBtcRequest: (String) -> Unit = {},
    onResurfaceVaultUpdate: () -> Unit = {},
    onSystemVaultMessages: () -> Unit = {},
    onSystemVotes: () -> Unit = {},
    onSystemGuides: () -> Unit = {},
    onOpenGuideById: (guideId: String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to top when new items appear — only if user is near the top
    var previousFirstItemId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(items) {
        val currentFirstId = items.firstOrNull()?.let {
            when (it) {
                is FeedDisplayItem.ConnectionCard -> "conn-${it.connectionId}"
                is FeedDisplayItem.EventItem -> it.event.eventId
            }
        }
        if (previousFirstItemId != null && currentFirstId != previousFirstItemId) {
            // Only auto-scroll if user is at or near the top (within first 3 items)
            if (listState.firstVisibleItemIndex <= 2) {
                coroutineScope.launch { listState.animateScrollToItem(0) }
            }
        }
        previousFirstItemId = currentFirstId
    }

    @OptIn(ExperimentalMaterial3Api::class)
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Connection-only feed — service events (guides, migration
            // prompts, vote notifications, security alerts) now live on
            // the VettID system connection's audit trail, not as
            // separate activity rows. See
            // plans/luminous-unifying-manatee.md.
            val connectionCards = items.filterIsInstance<FeedDisplayItem.ConnectionCard>()

            items(
                items = connectionCards,
                key = { "conn-${it.connectionId}" }
            ) { item ->
                StatusAwareConnectionCard(
                        item = item,
                        onClick = {
                            when {
                                item.needsReview -> onNavigateToConnectionReview(item.connectionId, "")
                                // The VettID system connection has no
                                // profile / detail screen — tapping the
                                // card goes straight to its Interaction
                                // History (the natural "detail" for
                                // system events).
                                item.connectionType == "system" -> onNavigateToConnectionHistory(item.connectionId)
                                // Tap on the card (name/photo) opens the
                                // peer's profile. The dedicated Text button
                                // handles the messaging entry point — this
                                // way each visible target has one clear
                                // meaning and the More menu drops its
                                // redundant "View Profile" item.
                                else -> onNavigateToConnectionDetail(item.connectionId)
                            }
                        },
                        onLongClick = { onNavigateToConnectionDetail(item.connectionId) },
                        onAccept = { onConnectionAccept(item.connectionId) },
                        onDecline = { onConnectionDecline(item.connectionId) },
                        onMessageClick = { onNavigateToConversation(item.connectionId) },
                        onHistoryClick = { onNavigateToConnectionHistory(item.connectionId) },
                        onCallClick = { onVoiceCall(item.connectionId) },
                        onVideoCallClick = { onVideoCall(item.connectionId) },
                        onBtcClick = { onBtcSend(item.connectionId) },
                        onBtcRequestClick = { onBtcRequest(item.connectionId) },
                        onNavigateToConnectionReview = onNavigateToConnectionReview,
                        onOpenMigration = onResurfaceVaultUpdate,
                        onSystemVaultMessagesClick = onSystemVaultMessages,
                        onSystemVotesClick = onSystemVotes,
                        onSystemGuidesClick = onSystemGuides,
                        onOpenGuide = onOpenGuideById,
                    )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusAwareConnectionCard(
    item: FeedDisplayItem.ConnectionCard,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
    onMessageClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onCallClick: () -> Unit = {},
    onVideoCallClick: () -> Unit = {},
    onBtcClick: () -> Unit = {},
    onBtcRequestClick: () -> Unit = {},
    onNavigateToConnectionReview: (connectionId: String, eventId: String) -> Unit = { _, _ -> },
    onOpenMigration: () -> Unit = {},
    onSystemVaultMessagesClick: () -> Unit = {},
    onSystemVotesClick: () -> Unit = {},
    onSystemGuidesClick: () -> Unit = {},
    onOpenGuide: (guideId: String) -> Unit = {},
) {
    // Render different card variants based on connection status
    when {
        item.needsReview -> PendingReviewConnectionCard(item, onClick)
        item.hasAccepted -> WaitingConnectionCard(item, onClick)
        item.connectionStatus == "active" -> ActiveConnectionCard(
            item, onClick, onLongClick, onMessageClick, onHistoryClick,
            onCallClick, onVideoCallClick, onBtcClick, onBtcRequestClick,
            onNavigateToConnectionReview, onOpenMigration,
            onSystemVaultMessagesClick, onSystemVotesClick, onSystemGuidesClick,
            onOpenGuide,
        )
        item.connectionStatus == "revoked" || item.connectionStatus == "rejected" -> InactiveConnectionCard(item, onClick)
        else -> ActiveConnectionCard(
            item, onClick, onLongClick, onMessageClick, onHistoryClick,
            onCallClick, onVideoCallClick, onBtcClick, onBtcRequestClick,
            onNavigateToConnectionReview, onOpenMigration,
            onSystemVaultMessagesClick, onSystemVotesClick, onSystemGuidesClick,
            onOpenGuide,
        )
    }
}

@Composable
private fun PendingReviewConnectionCard(
    item: FeedDisplayItem.ConnectionCard,
    onClick: () -> Unit,
) {
    // Pending cards are tap-to-review only — accept/decline live on
    // the review screen so the user always reviews the peer's
    // profile before acting. Avoids the card and review screen
    // offering duplicate actions that can drift out of sync after
    // an accept.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionAvatar(item.peerPhotoBase64, item.peerName, item.connectionType)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.peerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.peerEmail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "Wants to connect · Tap to review",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun WaitingConnectionCard(
    item: FeedDisplayItem.ConnectionCard,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConnectionAvatar(item.peerPhotoBase64, item.peerName, item.connectionType)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.peerName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Waiting for response",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = "Pending",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun InactiveConnectionCard(
    item: FeedDisplayItem.ConnectionCard,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).alpha(0.6f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConnectionAvatar(item.peerPhotoBase64, item.peerName, item.connectionType)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.peerName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (item.connectionStatus == "rejected") "Declined" else "Connection revoked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ActiveConnectionCard(
    item: FeedDisplayItem.ConnectionCard,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onMessageClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onCallClick: () -> Unit = {},
    onVideoCallClick: () -> Unit = {},
    onBtcClick: () -> Unit = {},
    onBtcRequestClick: () -> Unit = {},
    onNavigateToConnectionReview: (connectionId: String, eventId: String) -> Unit = { _, _ -> },
    onOpenMigration: () -> Unit = {},
    onSystemVaultMessagesClick: () -> Unit = {},
    onSystemVotesClick: () -> Unit = {},
    onSystemGuidesClick: () -> Unit = {},
    onOpenGuide: (guideId: String) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isUnread) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        val isSystem = item.connectionType == "system"

        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // Avatar — defers to the shared ConnectionAvatar helper so
                // agent and system connections get their branded glyphs.
                ConnectionAvatar(
                    photoBase64 = item.peerPhotoBase64,
                    name = item.peerName,
                    connectionType = item.connectionType
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Header is now just the peer name. Timestamp moved
                    // to the pending-row block at the bottom (same line
                    // as the event description). The space right of the
                    // name is reserved for a future presence indicator
                    // (plans/luminous-unifying-manatee.md §15).
                    Text(
                        text = item.peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (item.isUnread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (isSystem) {
                // VettID system connection gets three outward actions
                // (Messages / Votes / Guides). History lives at the
                // card body — tapping the card opens the system
                // connection's Interaction History, which IS the
                // detail view for this connection.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ConnectionActionButton(
                        icon = Icons.Default.Campaign,
                        label = "Messages",
                        onClick = onSystemVaultMessagesClick,
                        badgeCount = item.systemVaultMessagesBadge,
                    )
                    ConnectionActionButton(
                        icon = Icons.Default.HowToVote,
                        label = "Votes",
                        onClick = onSystemVotesClick,
                        badgeCount = item.systemVotesBadge,
                    )
                    ConnectionActionButton(
                        icon = Icons.Default.MenuBook,
                        label = "Guides",
                        onClick = onSystemGuidesClick,
                        badgeCount = item.systemGuidesBadge,
                    )
                }
            } else {
                // Peer connection action strip.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ConnectionActionButton(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        label = "Text",
                        onClick = onMessageClick,
                    )
                    ConnectionActionButton(
                        icon = Icons.Default.Call,
                        label = "Voice",
                        onClick = onCallClick,
                    )
                    ConnectionActionButton(
                        icon = Icons.Default.Videocam,
                        label = "Video",
                        onClick = onVideoCallClick,
                    )
                    Box {
                        var showMoreMenu by remember { mutableStateOf(false) }
                        ConnectionActionButton(
                            icon = Icons.Default.MoreVert,
                            label = "More",
                            onClick = { showMoreMenu = true },
                        )
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Send BTC") },
                                onClick = { showMoreMenu = false; onBtcClick() },
                                leadingIcon = {
                                    Icon(Icons.Default.CurrencyBitcoin, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Request BTC") },
                                onClick = { showMoreMenu = false; onBtcRequestClick() },
                                leadingIcon = {
                                    Icon(Icons.Default.CallReceived, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("History") },
                                onClick = { showMoreMenu = false; onHistoryClick() },
                                leadingIcon = {
                                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                            )
                        }
                    }
                }
            }

            // Notification / last-activity rows sit below the action
            // strip so waiting items are legible and tappable without
            // competing with the quick-send buttons above.
            //
            // Collapse when there's more than one: show just the
            // first row and a secondary "N more" row that expands
            // into the full list on tap. Keeps cards short by
            // default but still one-tap accessible when a connection
            // has a lot of waiting items.
            if (item.pendingRows.isNotEmpty()) {
                var expanded by remember(item.connectionId) { mutableStateOf(false) }
                val total = item.pendingRows.size
                val shown = if (expanded || total == 1) item.pendingRows else item.pendingRows.take(1)

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                shown.forEachIndexed { idx, row ->
                    if (idx > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    }
                    PendingRowView(
                        row = row,
                        onClick = {
                            when (row) {
                                is PendingRow.PendingReview ->
                                    onNavigateToConnectionReview(item.connectionId, "")
                                is PendingRow.UnreadMessages ->
                                    onMessageClick()
                                is PendingRow.MissedCall ->
                                    onHistoryClick()
                                is PendingRow.PendingMigration ->
                                    onOpenMigration()
                                is PendingRow.LastActivity ->
                                    onHistoryClick()
                                is PendingRow.GuideUnread -> {
                                    onOpenGuide(row.guideId)
                                }
                            }
                        }
                    )
                }
                if (total > 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    MoreNotificationsRow(
                        hiddenCount = if (expanded) 0 else total - 1,
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreNotificationsRow(
    hiddenCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (expanded) "Show less" else "$hiddenCount more notifications",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PendingRowView(row: PendingRow, onClick: () -> Unit) {
    val (icon, tint) = pendingRowIcon(row)
    val label = pendingRowLabel(row)
    val interactive = row !is PendingRow.LastActivity
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (interactive) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (interactive) FontWeight.Medium else FontWeight.Normal,
            color = if (interactive) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.timestamp > 0L) {
            Text(
                text = formatTimestamp(row.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun pendingRowIcon(row: PendingRow): Pair<ImageVector?, Color> {
    return when (row) {
        is PendingRow.UnreadMessages ->
            Icons.AutoMirrored.Filled.Chat to MaterialTheme.colorScheme.primary
        is PendingRow.MissedCall ->
            Icons.Default.CallMissed to DeclineRedColor
        is PendingRow.PendingReview ->
            Icons.Default.PersonAdd to MaterialTheme.colorScheme.primary
        is PendingRow.PendingMigration ->
            Icons.Default.Lock to MaterialTheme.colorScheme.secondary
        is PendingRow.GuideUnread ->
            Icons.Default.MenuBook to MaterialTheme.colorScheme.secondary
        is PendingRow.LastActivity -> lastActivityIcon(
            activityType = row.activityType,
            direction = row.direction,
            subtype = row.subtype,
            outcome = row.outcome,
        )
    }
}

private fun pendingRowLabel(row: PendingRow): String = when (row) {
    is PendingRow.UnreadMessages ->
        if (row.count == 1) "1 new message" else "${row.count} new messages"
    is PendingRow.MissedCall -> {
        val subtype = when (row.subtype) { "video" -> "video call"; "voice" -> "call"; else -> "call" }
        if (row.count == 1) "Missed $subtype" else "${row.count} missed calls"
    }
    is PendingRow.PendingReview -> "Wants to connect"
    is PendingRow.PendingMigration -> "Vault security update available"
    is PendingRow.GuideUnread -> "Guide: ${row.title}"
    is PendingRow.LastActivity -> row.text.ifEmpty { "Connected" }
}

/**
 * Reusable connection avatar — shows photo, initials, or agent icon based on type.
 */
@Composable
private fun ConnectionAvatar(
    photoBase64: String?,
    name: String,
    connectionType: String
) {
    if (connectionType == "agent") {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Computer,
                    contentDescription = "Agent",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    } else if (connectionType == "system") {
        // The VettID vector logo is multicolor (path-tinted inside the
        // asset), so render it via Image rather than Icon to preserve
        // its fill. Drop shape clipping since the logo is already a
        // shield.
        Image(
            painter = androidx.compose.ui.res.painterResource(
                id = com.vettid.app.R.drawable.vettid_logo
            ),
            contentDescription = "VettID",
            modifier = Modifier.size(44.dp)
        )
    } else {
        val photoBitmap = remember(photoBase64) {
            photoBase64?.let { base64 ->
                try {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { null }
            }
        }
        if (photoBitmap != null) {
            Image(
                bitmap = photoBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = name.take(2).uppercase(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    badgeCount: Int = 0
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Badge rides on the icon so the user's eye is drawn to the
        // action they need to take — Text button lights up for unread
        // messages, Voice for a missed call, etc.
        BadgedBox(
            badge = {
                if (badgeCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Text(
                            text = badgeCount.toString(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventCard(
    event: FeedEvent,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onAction: (String) -> Unit,
    onTogglePriority: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        EventCardContent(
            event = event,
            onClick = onClick,
            onAction = onAction,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showMenu = true
            }
        )

        // Context menu anchored to the card
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (event.priority >= 1) "Unpin" else "Pin to Top") },
                onClick = {
                    showMenu = false
                    onTogglePriority()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text("Archive") },
                onClick = {
                    showMenu = false
                    onArchive()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    showMenu = false
                    showDeleteConfirm = true
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Event?") },
            text = {
                Text("This will permanently delete this event. This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventCardContent(
    event: FeedEvent,
    onClick: () -> Unit,
    onAction: (String) -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isRead = event.feedStatus == FeedStatus.READ
    val alpha = if (isRead) 0.7f else 1f
    val priorityColor = getPriorityColor(event.priorityLevel)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (event.priorityLevel == EventPriority.URGENT) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.alpha(alpha),
                verticalAlignment = Alignment.Top
            ) {
                // Event icon with priority indicator
                Box {
                    EventIcon(event = event, isRead = isRead)
                    // Show priority indicator only if unread or still requires action
                    if (event.priorityLevel != EventPriority.NORMAL && event.priorityLevel != EventPriority.LOW
                        && (!isRead || event.requiresAction)) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(priorityColor)
                                .align(Alignment.TopEnd)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (event.priority >= 1) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = "Pinned",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (!isRead) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (!isRead) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }

                    event.message?.let { message ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatTimestamp(event.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Action buttons based on actionType (only for accept/decline and reply)
            if (event.requiresAction && event.actionType != null &&
                event.actionType != ActionTypes.VIEW && event.actionType != ActionTypes.ACKNOWLEDGE) {
                Spacer(modifier = Modifier.height(8.dp))
                ActionButtons(
                    actionType = event.actionType,
                    onAction = onAction
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(
    actionType: String,
    onAction: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        when (actionType) {
            ActionTypes.ACCEPT_DECLINE -> {
                OutlinedButton(
                    onClick = { onAction("decline") },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Decline")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onAction("accept") }) {
                    Text("Accept")
                }
            }
            ActionTypes.REPLY -> {
                Button(onClick = { onAction("reply") }) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reply")
                }
            }
            ActionTypes.VIEW -> {
                OutlinedButton(onClick = { onAction("view") }) {
                    Text("View Details")
                }
            }
            ActionTypes.ACKNOWLEDGE -> {
                TextButton(onClick = { onAction("acknowledge") }) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun EventIcon(
    event: FeedEvent,
    isRead: Boolean
) {
    val (icon, containerColor) = getEventIconAndColor(event.eventType)

    Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = containerColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isRead) 0.6f else 1f)
            )
        }
    }
}

@Composable
private fun getEventIconAndColor(eventType: String): Pair<ImageVector, Color> {
    return when (eventType) {
        EventTypes.CALL_INCOMING -> Icons.Default.Call to Color(0xFF4CAF50).copy(alpha = 0.2f)
        EventTypes.CALL_MISSED -> Icons.AutoMirrored.Filled.PhoneMissed to Color(0xFFF44336).copy(alpha = 0.2f)
        EventTypes.CALL_COMPLETED -> Icons.Default.CallEnd to MaterialTheme.colorScheme.surfaceVariant
        EventTypes.CONNECTION_REQUEST -> Icons.Default.PersonAdd to Color(0xFF2196F3).copy(alpha = 0.2f)
        EventTypes.CONNECTION_ACCEPTED -> Icons.Default.PersonAddAlt to MaterialTheme.colorScheme.primaryContainer
        EventTypes.CONNECTION_REVOKED -> Icons.Default.PersonRemove to MaterialTheme.colorScheme.surfaceVariant
        EventTypes.MESSAGE_RECEIVED -> Icons.AutoMirrored.Filled.Message to Color(0xFF2196F3).copy(alpha = 0.2f)
        EventTypes.MESSAGE_SENT -> Icons.AutoMirrored.Filled.Reply to MaterialTheme.colorScheme.primaryContainer
        EventTypes.SECURITY_ALERT -> Icons.Default.Shield to Color(0xFFF44336).copy(alpha = 0.2f)
        EventTypes.SECURITY_MIGRATION -> Icons.Default.Security to MaterialTheme.colorScheme.primaryContainer
        EventTypes.TRANSFER_REQUEST -> Icons.Default.SwapHoriz to Color(0xFFFF9800).copy(alpha = 0.2f)
        EventTypes.BACKUP_COMPLETE -> Icons.Default.Backup to MaterialTheme.colorScheme.primaryContainer
        EventTypes.VAULT_STATUS -> Icons.Default.Cloud to MaterialTheme.colorScheme.secondaryContainer
        EventTypes.HANDLER_COMPLETE -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primaryContainer
        EventTypes.GUIDE -> Icons.Default.School to Color(0xFF9C27B0).copy(alpha = 0.2f)
        else -> Icons.Default.Notifications to MaterialTheme.colorScheme.surfaceVariant
    }
}

@Composable
private fun getPriorityColor(priority: EventPriority): Color {
    return when (priority) {
        EventPriority.URGENT -> MaterialTheme.colorScheme.error
        EventPriority.HIGH -> Color(0xFFFF9800) // Orange
        EventPriority.NORMAL -> MaterialTheme.colorScheme.primary
        EventPriority.LOW -> MaterialTheme.colorScheme.outline
    }
}

/**
 * Map the connection's last activity to a compact type icon + tint. Sits
 * under the timestamp so the user can tell at a glance what the last
 * interaction was (call vs text, missed vs completed) without reading
 * the preview text.
 *
 * The connection-list API today only flags activity as "message" or
 * "connection" — it doesn't distinguish calls. We lean on the preview
 * string for the missed/voice/video hint. If the FeedRepository later
 * starts surfacing the specific event type per connection, this can be
 * replaced with an eventType-based lookup.
 */
@Composable
private fun lastActivityIcon(
    activityType: String,
    direction: String? = null,
    subtype: String? = null,
    outcome: String? = null,
): Pair<ImageVector?, Color> {
    val isCall = activityType == "call"
    val isVideo = subtype == "video"
    return when {
        // Calls: missed reads red; completed/rejected use directional
        // green glyphs matching who started the call.
        isCall && outcome == "missed" -> Icons.Default.CallMissed to DeclineRedColor
        isCall && isVideo && direction == "outgoing" -> Icons.Default.Videocam to AnswerGreenColor
        isCall && isVideo -> Icons.Default.Videocam to AnswerGreenColor
        isCall && direction == "outgoing" -> Icons.Default.CallMade to AnswerGreenColor
        isCall && direction == "incoming" -> Icons.Default.CallReceived to AnswerGreenColor
        isCall -> Icons.Default.Call to AnswerGreenColor
        // Messages: direction arrow when we have it.
        activityType == "message" && direction == "outgoing" ->
            Icons.Default.Send to MaterialTheme.colorScheme.outline
        activityType == "message" && direction == "incoming" ->
            Icons.AutoMirrored.Filled.Reply to MaterialTheme.colorScheme.outline
        activityType == "message" -> Icons.AutoMirrored.Filled.Chat to MaterialTheme.colorScheme.outline
        activityType == "connection" -> Icons.Default.Person to MaterialTheme.colorScheme.outline
        else -> null to MaterialTheme.colorScheme.outline
    }
}

private val DeclineRedColor = Color(0xFFE53935)
private val AnswerGreenColor = Color(0xFF4CAF50)

private fun formatTimestamp(epochMillis: Long): String {
    val millis = com.vettid.app.util.toEpochMillis(epochMillis)
    val now = System.currentTimeMillis()
    val diff = now - millis
    val instant = Instant.ofEpochMilli(millis)
    val zoned = instant.atZone(ZoneId.systemDefault())

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1) -> {
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            "${hours}h ago · ${zoned.format(DateTimeFormatter.ofPattern("h:mm a"))}"
        }
        diff < TimeUnit.DAYS.toMillis(7) -> {
            "${TimeUnit.MILLISECONDS.toDays(diff)}d ago · ${zoned.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))}"
        }
        else -> {
            zoned.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
            }

            Icon(
                imageVector = Icons.Default.Inbox,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "No Activity Yet",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your activity feed is empty.\nPull down to refresh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Error Loading Feed",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun EventDetailDialog(
    event: FeedEvent,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Event?") },
            text = {
                Text("This will permanently delete this event. This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            val (icon, _) = getEventIconAndColor(event.eventType)
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                // Message
                event.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Event type
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Label,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Type: ${event.eventType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Timestamp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatTimestamp(event.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Metadata
                event.metadata?.let { metadata ->
                    if (metadata.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Details",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        metadata.forEach { (key, value) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = key.replace("_", " ").replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(0.4f)
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(0.6f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Archive / Delete actions
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onArchive) {
                        Icon(
                            Icons.Default.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Archive")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        },
        confirmButton = {
            if (event.requiresAction && event.actionType != null) {
                Row {
                    when (event.actionType) {
                        ActionTypes.ACCEPT_DECLINE -> {
                            OutlinedButton(
                                onClick = { onAction("decline") },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Decline")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { onAction("accept") }) {
                                Text("Accept")
                            }
                        }
                        else -> {
                            TextButton(onClick = onDismiss) {
                                Text("Close")
                            }
                        }
                    }
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        dismissButton = if (event.requiresAction && event.actionType == ActionTypes.ACCEPT_DECLINE) null else {
            { /* No dismiss button for action dialogs */ }
        }
    )
}

// ConnectionPickerDialog and FeedFabMenuItem removed — messaging now via connection cards in feed
