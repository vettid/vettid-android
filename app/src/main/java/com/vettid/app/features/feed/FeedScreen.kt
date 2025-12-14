package com.vettid.app.features.feed

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FeedEffect.NavigateToConversation -> onNavigateToConversation(effect.connectionId)
                is FeedEffect.NavigateToConnectionRequest -> onNavigateToConnectionRequest(effect.requestId)
                is FeedEffect.NavigateToHandler -> onNavigateToHandler(effect.handlerId)
                is FeedEffect.NavigateToBackup -> onNavigateToBackup(effect.backupId)
                else -> { /* Handle other effects */ }
            }
        }
    }

    when (val currentState = state) {
        is FeedState.Loading -> LoadingContent()
        is FeedState.Empty -> EmptyContent()
        is FeedState.Loaded -> FeedList(
            events = currentState.events,
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            onEventClick = { viewModel.onEventClick(it) }
        )
        is FeedState.Error -> ErrorContent(
            message = currentState.message,
            onRetry = { viewModel.loadFeed() }
        )
    }
}

@Composable
private fun FeedList(
    events: List<FeedEvent>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onEventClick: (FeedEvent) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(events, key = { it.id }) { event ->
                EventCard(
                    event = event,
                    onClick = { onEventClick(event) }
                )
            }
        }

        // Simple refresh indicator
        if (isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventCard(
    event: FeedEvent,
    onClick: () -> Unit
) {
    val alpha = if (event.isRead) 0.7f else 1f

    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .alpha(alpha),
        headlineContent = {
            Text(
                text = getEventTitle(event),
                fontWeight = if (!event.isRead) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = getEventDescription(event),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTimestamp(event.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            EventIcon(event = event, isRead = event.isRead)
        },
        trailingContent = {
            if (!event.isRead) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(8.dp)
                ) { }
            }
        }
    )
    Divider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun EventIcon(
    event: FeedEvent,
    isRead: Boolean
) {
    val (icon, containerColor) = when (event) {
        is FeedEvent.Message -> Icons.Default.Message to MaterialTheme.colorScheme.primaryContainer
        is FeedEvent.ConnectionRequest -> Icons.Default.PersonAdd to MaterialTheme.colorScheme.tertiaryContainer
        is FeedEvent.AuthRequest -> Icons.Default.Security to MaterialTheme.colorScheme.secondaryContainer
        is FeedEvent.HandlerComplete -> {
            if ((event).success) {
                Icons.Default.CheckCircle to MaterialTheme.colorScheme.primaryContainer
            } else {
                Icons.Default.Error to MaterialTheme.colorScheme.errorContainer
            }
        }
        is FeedEvent.VaultStatusChange -> Icons.Default.Cloud to MaterialTheme.colorScheme.secondaryContainer
        is FeedEvent.BackupComplete -> {
            if ((event).success) {
                Icons.Default.Backup to MaterialTheme.colorScheme.primaryContainer
            } else {
                Icons.Default.BackupTable to MaterialTheme.colorScheme.errorContainer
            }
        }
    }

    Surface(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = containerColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isRead) 0.6f else 1f)
            )
        }
    }
}

private fun getEventTitle(event: FeedEvent): String {
    return when (event) {
        is FeedEvent.Message -> event.senderName
        is FeedEvent.ConnectionRequest -> "Connection Request"
        is FeedEvent.AuthRequest -> event.serviceName
        is FeedEvent.HandlerComplete -> event.handlerName
        is FeedEvent.VaultStatusChange -> "Vault Status"
        is FeedEvent.BackupComplete -> "Backup Complete"
    }
}

private fun getEventDescription(event: FeedEvent): String {
    return when (event) {
        is FeedEvent.Message -> event.preview
        is FeedEvent.ConnectionRequest -> "${event.fromName} wants to connect"
        is FeedEvent.AuthRequest -> "Requesting ${event.scope} access"
        is FeedEvent.HandlerComplete -> event.resultSummary ?: if (event.success) "Completed successfully" else "Failed"
        is FeedEvent.VaultStatusChange -> "Changed from ${event.previousStatus} to ${event.newStatus}"
        is FeedEvent.BackupComplete -> {
            if (event.success) {
                event.sizeBytes?.let { "Backed up ${formatBytes(it)}" } ?: "Backup completed"
            } else {
                "Backup failed"
            }
        }
    }
}

private fun formatTimestamp(instant: Instant): String {
    val now = Instant.now()
    val duration = Duration.between(instant, now)

    return when {
        duration.toMinutes() < 1 -> "Just now"
        duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
        duration.toHours() < 24 -> "${duration.toHours()}h ago"
        duration.toDays() < 7 -> "${duration.toDays()}d ago"
        else -> {
            val formatter = DateTimeFormatter.ofPattern("MMM d")
            instant.atZone(ZoneId.systemDefault()).format(formatter)
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
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
