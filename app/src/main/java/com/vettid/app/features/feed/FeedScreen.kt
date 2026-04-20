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
    onNavigateToConnectDesktop: () -> Unit = {}
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
                    }
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
    onVideoCall: (String) -> Unit = {}
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
            // --- Section: Connections ---
            val connectionCards = items.filterIsInstance<FeedDisplayItem.ConnectionCard>()
            val activityItems = items.filterIsInstance<FeedDisplayItem.EventItem>()

            items(
                items = connectionCards,
                key = { "conn-${it.connectionId}" }
            ) { item ->
                StatusAwareConnectionCard(
                        item = item,
                        onClick = {
                            when {
                                item.needsReview -> onNavigateToConnectionReview(item.connectionId, "")
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
                        onBtcClick = { /* TODO */ }
                    )
            }

            // --- Section divider ---
            if (connectionCards.isNotEmpty() && activityItems.isNotEmpty()) {
                item(key = "section-divider") {
                    Text(
                        text = "ACTIVITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }

            // --- Section: Activity ---
            items(
                items = activityItems,
                key = { it.event.eventId }
            ) { item ->
                EventCard(
                    event = item.event,
                    onClick = { onEventClick(item.event) },
                    onArchive = { onArchive(item.event) },
                    onDelete = { onDelete(item.event) },
                    onAction = { action -> onAction(item.event, action) },
                    onTogglePriority = { onTogglePriority(item.event) }
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
    onBtcClick: () -> Unit = {}
) {
    // Render different card variants based on connection status
    when {
        item.needsReview -> PendingReviewConnectionCard(item, onClick, onAccept, onDecline)
        item.hasAccepted -> WaitingConnectionCard(item, onClick)
        item.connectionStatus == "active" -> ActiveConnectionCard(item, onClick, onLongClick, onMessageClick, onHistoryClick, onCallClick, onVideoCallClick, onBtcClick)
        item.connectionStatus == "revoked" || item.connectionStatus == "rejected" -> InactiveConnectionCard(item, onClick)
        else -> ActiveConnectionCard(item, onClick, onLongClick, onMessageClick, onHistoryClick, onCallClick, onVideoCallClick, onBtcClick)
    }
}

@Composable
private fun PendingReviewConnectionCard(
    item: FeedDisplayItem.ConnectionCard,
    onClick: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionAvatar(item.peerPhotoBase64, item.peerName, item.connectionType)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.peerName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    item.peerEmail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "Wants to connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Decline") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onAccept) { Text("Accept") }
            }
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
    onBtcClick: () -> Unit = {}
) {
    val photoBitmap = remember(item.peerPhotoBase64) {
        item.peerPhotoBase64?.let { base64 ->
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
        }
    }

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
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // Avatar
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
                                text = item.peerName.take(2).uppercase(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = item.peerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (item.isUnread) FontWeight.Bold else FontWeight.Normal,
                            // Long names ("Firstname Middlename Lastname")
                            // were getting elided at a single line. Allow up
                            // to two lines before truncating.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = formatTimestamp(item.sortTimestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            // Compact type icon under the timestamp —
                            // tells the eye what the last interaction
                            // was (message / call / missed). Unread
                            // counts live on the action buttons below
                            // so the badge highlights *where to tap*.
                            val (typeIcon, typeTint) = lastActivityIcon(item.lastActivityType, item.lastActivityPreview)
                            if (typeIcon != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Icon(
                                    imageVector = typeIcon,
                                    contentDescription = null,
                                    tint = typeTint,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Quick action buttons
            Spacer(modifier = Modifier.height(8.dp))
            // If the last interaction was a missed call, flag the Voice
            // button so the user sees where to call back. Pure text
            // heuristic until the connection-list API surfaces a
            // per-type unread count.
            val missedCallPending = item.lastActivityPreview.contains("Missed", ignoreCase = true) && item.isUnread
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ConnectionActionButton(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    label = "Text",
                    onClick = onMessageClick,
                    badgeCount = if (item.lastActivityType == "message") item.unreadCount else 0
                )
                ConnectionActionButton(
                    icon = Icons.Default.Call,
                    label = "Voice",
                    onClick = onCallClick,
                    badgeCount = if (missedCallPending) 1 else 0
                )
                ConnectionActionButton(
                    icon = Icons.Default.Videocam,
                    label = "Video",
                    onClick = onVideoCallClick
                )
                // More button with dropdown menu
                Box {
                    var showMoreMenu by remember { mutableStateOf(false) }
                    ConnectionActionButton(
                        icon = Icons.Default.MoreVert,
                        label = "More",
                        onClick = { showMoreMenu = true }
                    )
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        // "View Profile" intentionally dropped — the card
                        // itself navigates to the profile on tap.
                        DropdownMenuItem(
                            text = { Text("Send BTC") },
                            onClick = {
                                showMoreMenu = false
                                onBtcClick()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.CurrencyBitcoin, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("View History") },
                            onClick = {
                                showMoreMenu = false
                                onHistoryClick()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        )
                    }
                }
            }
        }
    }
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
    preview: String
): Pair<ImageVector?, Color> {
    val missed = preview.contains("Missed", ignoreCase = true)
    val isCall = preview.contains("call", ignoreCase = true) ||
        preview.contains("voice", ignoreCase = true) ||
        preview.contains("video", ignoreCase = true)
    return when {
        missed -> Icons.Default.CallMissed to DeclineRedColor
        isCall -> Icons.Default.Call to AnswerGreenColor
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
