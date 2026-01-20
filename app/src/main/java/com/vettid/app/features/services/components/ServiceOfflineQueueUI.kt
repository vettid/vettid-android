package com.vettid.app.features.services.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vettid.app.features.services.models.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Offline queue UI components for service connections.
 *
 * Shows pending offline actions and their sync status:
 * - Pending operations queue
 * - Sync progress indicator
 * - Retry failed operations
 * - Clear completed operations
 */

// MARK: - Sync Status Banner

@Composable
fun ServiceSyncStatusBanner(
    isOnline: Boolean,
    syncStatus: SyncStatus,
    pendingCount: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, icon, message) = when {
        !isOnline -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Default.CloudOff,
            "Offline - $pendingCount pending ${if (pendingCount == 1) "action" else "actions"}"
        )
        syncStatus == SyncStatus.PENDING -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            Icons.Default.Sync,
            "Syncing $pendingCount ${if (pendingCount == 1) "action" else "actions"}..."
        )
        syncStatus == SyncStatus.FAILED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Default.SyncProblem,
            "Sync failed - $pendingCount pending"
        )
        else -> return // Don't show banner when synced with no pending
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated sync icon
            if (syncStatus == SyncStatus.PENDING && isOnline) {
                val infiniteTransition = rememberInfiniteTransition(label = "sync")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing)
                    ),
                    label = "syncRotation"
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(rotation)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )

            if (isOnline && syncStatus == SyncStatus.FAILED) {
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Retry", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// MARK: - Offline Queue Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineQueueCard(
    pendingActions: List<OfflineServiceAction>,
    syncStatus: SyncStatus,
    isOnline: Boolean,
    onRetryAll: () -> Unit,
    onClearCompleted: () -> Unit,
    onViewDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pendingCount = pendingActions.count { it.syncStatus == SyncStatus.PENDING }
    val failedCount = pendingActions.count { it.syncStatus == SyncStatus.FAILED }
    val syncedCount = pendingActions.count { it.syncStatus == SyncStatus.SYNCED }

    Card(
        onClick = onViewDetails,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isOnline) Icons.Outlined.Sync else Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Offline Queue",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (pendingCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text("$pendingCount")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QueueStatusItem(
                    count = pendingCount,
                    label = "Pending",
                    color = Color(0xFFFF9800)
                )
                QueueStatusItem(
                    count = failedCount,
                    label = "Failed",
                    color = MaterialTheme.colorScheme.error
                )
                QueueStatusItem(
                    count = syncedCount,
                    label = "Synced",
                    color = Color(0xFF4CAF50)
                )
            }

            // Actions
            if (failedCount > 0 || syncedCount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (syncedCount > 0) {
                        TextButton(onClick = onClearCompleted) {
                            Text("Clear Completed")
                        }
                    }
                    if (failedCount > 0 && isOnline) {
                        TextButton(onClick = onRetryAll) {
                            Text("Retry Failed")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueStatusItem(
    count: Int,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) color else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// MARK: - Offline Queue List

@Composable
fun OfflineQueueList(
    actions: List<OfflineServiceAction>,
    onRetry: (OfflineServiceAction) -> Unit,
    onDelete: (OfflineServiceAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "All synced!",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "No pending offline actions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(actions, key = { it.actionId }) { action ->
                OfflineActionCard(
                    action = action,
                    onRetry = { onRetry(action) },
                    onDelete = { onDelete(action) }
                )
            }
        }
    }
}

// MARK: - Offline Action Card

@Composable
private fun OfflineActionCard(
    action: OfflineServiceAction,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = getStatusColor(action.syncStatus).copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getStatusIcon(action.syncStatus),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = getStatusColor(action.syncStatus)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = getActionTypeDisplayName(action.actionType),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Created ${formatTime(action.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show error if failed
                action.error?.let { error ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Show synced time
                action.syncedAt?.let { syncedAt ->
                    Text(
                        text = "Synced ${formatTime(syncedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50)
                    )
                }
            }

            // Actions
            when (action.syncStatus) {
                SyncStatus.FAILED -> {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                SyncStatus.PENDING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                SyncStatus.SYNCED -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Synced",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    // Delete confirmation
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Action?") },
            text = {
                Text("This action will be removed from the queue and won't be synced to the server.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// MARK: - Compact Sync Indicator

@Composable
fun CompactSyncIndicator(
    syncStatus: SyncStatus,
    pendingCount: Int,
    modifier: Modifier = Modifier
) {
    if (pendingCount == 0 && syncStatus == SyncStatus.SYNCED) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (syncStatus) {
            SyncStatus.PENDING -> {
                val infiniteTransition = rememberInfiniteTransition(label = "sync")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing)
                    ),
                    label = "rotation"
                )
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Syncing",
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(rotation),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            SyncStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Default.SyncProblem,
                    contentDescription = "Sync failed",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            SyncStatus.SYNCED -> {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = "Synced",
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF4CAF50)
                )
            }
        }

        if (pendingCount > 0) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$pendingCount",
                style = MaterialTheme.typography.labelSmall,
                color = when (syncStatus) {
                    SyncStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

// MARK: - Utility Functions

private fun getStatusColor(status: SyncStatus) = when (status) {
    SyncStatus.PENDING -> Color(0xFFFF9800)
    SyncStatus.SYNCED -> Color(0xFF4CAF50)
    SyncStatus.FAILED -> Color(0xFFF44336)
}

private fun getStatusIcon(status: SyncStatus) = when (status) {
    SyncStatus.PENDING -> Icons.Default.Schedule
    SyncStatus.SYNCED -> Icons.Default.CheckCircle
    SyncStatus.FAILED -> Icons.Default.Error
}

private fun getActionTypeDisplayName(type: OfflineActionType) = when (type) {
    OfflineActionType.REQUEST_RESPONSE -> "Data Request Response"
    OfflineActionType.REVOKE -> "Connection Revocation"
    OfflineActionType.CONTRACT_ACCEPT -> "Contract Acceptance"
    OfflineActionType.CONTRACT_REJECT -> "Contract Rejection"
}

private fun formatTime(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("h:mm a")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}
