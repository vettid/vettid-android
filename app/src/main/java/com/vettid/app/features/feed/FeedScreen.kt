package com.vettid.app.features.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    onNavigateToConversation: (String) -> Unit = {},
    onNavigateToConnectionRequest: (String) -> Unit = {},
    onNavigateToHandler: (String) -> Unit = {},
    onNavigateToBackup: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    // Snackbar state for action feedback
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FeedEffect.NavigateToConversation -> onNavigateToConversation(effect.connectionId)
                is FeedEffect.NavigateToConnectionRequest -> onNavigateToConnectionRequest(effect.requestId)
                is FeedEffect.NavigateToHandler -> onNavigateToHandler(effect.handlerId)
                is FeedEffect.NavigateToBackup -> onNavigateToBackup(effect.backupId)
                is FeedEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
                is FeedEffect.ShowActionSuccess -> snackbarHostState.showSnackbar(effect.message)
                else -> { /* Handle other effects */ }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val currentState = state) {
            is FeedState.Loading -> LoadingContent()
            is FeedState.Empty -> EmptyContent()
            is FeedState.Loaded -> FeedList(
                events = currentState.events,
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                onEventClick = { viewModel.onEventClick(it) },
                onArchive = { viewModel.archiveEvent(it.eventId) },
                onDelete = { viewModel.deleteEvent(it.eventId) },
                onAction = { event, action -> viewModel.executeAction(event.eventId, action) }
            )
            is FeedState.Error -> ErrorContent(
                message = currentState.message,
                onRetry = { viewModel.loadFeed() }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun FeedList(
    events: List<FeedEvent>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onEventClick: (FeedEvent) -> Unit,
    onArchive: (FeedEvent) -> Unit,
    onDelete: (FeedEvent) -> Unit,
    onAction: (FeedEvent, String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(events, key = { it.eventId }) { event ->
                EventCard(
                    event = event,
                    onClick = { onEventClick(event) },
                    onArchive = { onArchive(event) },
                    onDelete = { onDelete(event) },
                    onAction = { action -> onAction(event, action) }
                )
            }
        }

        // Refresh indicator
        if (isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun EventCard(
    event: FeedEvent,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onAction: (String) -> Unit
) {
    val isRead = event.feedStatus == FeedStatus.READ
    val alpha = if (isRead) 0.7f else 1f
    val priorityColor = getPriorityColor(event.priorityLevel)
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
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
                    if (event.priorityLevel != EventPriority.NORMAL && event.priorityLevel != EventPriority.LOW) {
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

                // Overflow menu
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Archive") },
                            onClick = {
                                showMenu = false
                                onArchive()
                            },
                            leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }

            // Action buttons based on actionType
            if (event.requiresAction && event.actionType != null) {
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
                    Icon(Icons.Default.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
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
        EventTypes.CALL_MISSED -> Icons.Default.PhoneMissed to Color(0xFFF44336).copy(alpha = 0.2f)
        EventTypes.CALL_COMPLETED -> Icons.Default.CallEnd to MaterialTheme.colorScheme.surfaceVariant
        EventTypes.CONNECTION_REQUEST -> Icons.Default.PersonAdd to Color(0xFF2196F3).copy(alpha = 0.2f)
        EventTypes.CONNECTION_ACCEPTED -> Icons.Default.PersonAddAlt to MaterialTheme.colorScheme.primaryContainer
        EventTypes.CONNECTION_REVOKED -> Icons.Default.PersonRemove to MaterialTheme.colorScheme.surfaceVariant
        EventTypes.MESSAGE_RECEIVED -> Icons.Default.Message to Color(0xFF2196F3).copy(alpha = 0.2f)
        EventTypes.SECURITY_ALERT -> Icons.Default.Shield to Color(0xFFF44336).copy(alpha = 0.2f)
        EventTypes.SECURITY_MIGRATION -> Icons.Default.Security to MaterialTheme.colorScheme.primaryContainer
        EventTypes.TRANSFER_REQUEST -> Icons.Default.SwapHoriz to Color(0xFFFF9800).copy(alpha = 0.2f)
        EventTypes.BACKUP_COMPLETE -> Icons.Default.Backup to MaterialTheme.colorScheme.primaryContainer
        EventTypes.VAULT_STATUS -> Icons.Default.Cloud to MaterialTheme.colorScheme.secondaryContainer
        EventTypes.HANDLER_COMPLETE -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primaryContainer
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

private fun formatTimestamp(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMillis

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
        else -> {
            val instant = Instant.ofEpochMilli(epochMillis)
            val formatter = DateTimeFormatter.ofPattern("MMM d")
            instant.atZone(ZoneId.systemDefault()).format(formatter)
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
private fun EmptyContent() {
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
                text = "Your activity feed is empty. Events will appear here as you use VettID.",
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
