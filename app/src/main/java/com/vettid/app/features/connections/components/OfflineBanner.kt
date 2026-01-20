package com.vettid.app.features.connections.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vettid.app.features.connections.offline.SyncStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Offline banner that shows when device is disconnected.
 */
@Composable
fun OfflineBanner(
    isOnline: Boolean,
    syncStatus: SyncStatus,
    pendingCount: Int,
    lastSyncTime: Instant?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = !isOnline || syncStatus == SyncStatus.SYNCING || syncStatus == SyncStatus.ERROR,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = when {
                !isOnline -> Color(0xFFE65100)
                syncStatus == SyncStatus.ERROR -> Color(0xFFC62828)
                syncStatus == SyncStatus.SYNCING -> Color(0xFF1565C0)
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (syncStatus == SyncStatus.SYNCING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = if (isOnline) Icons.Default.SyncProblem else Icons.Default.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color.White
                        )
                    }

                    Column {
                        Text(
                            text = when {
                                syncStatus == SyncStatus.SYNCING -> "Syncing..."
                                !isOnline -> "You're offline"
                                syncStatus == SyncStatus.ERROR -> "Sync failed"
                                else -> "Offline"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )

                        if (!isOnline) {
                            Text(
                                text = buildString {
                                    append("Showing cached data")
                                    if (pendingCount > 0) {
                                        append(" • $pendingCount pending")
                                    }
                                    lastSyncTime?.let {
                                        append(" • Last sync: ${formatSyncTime(it)}")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                if (syncStatus != SyncStatus.SYNCING) {
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

/**
 * Compact offline indicator for inline display.
 */
@Composable
fun CompactOfflineIndicator(
    isOnline: Boolean,
    syncStatus: SyncStatus,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = !isOnline || syncStatus == SyncStatus.SYNCING,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (syncStatus == SyncStatus.SYNCING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = "Offline",
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFFE65100)
                )
            }

            Text(
                text = if (syncStatus == SyncStatus.SYNCING) "Syncing" else "Offline",
                style = MaterialTheme.typography.labelSmall,
                color = if (syncStatus == SyncStatus.SYNCING) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color(0xFFE65100)
                }
            )
        }
    }
}

/**
 * Pending operations indicator.
 */
@Composable
fun PendingOperationsIndicator(
    pendingCount: Int,
    isOnline: Boolean,
    onViewPending: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = pendingCount > 0,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isOnline) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isOnline) Icons.Default.CloudUpload else Icons.Default.CloudQueue,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )

                    Text(
                        text = if (isOnline) {
                            "$pendingCount operations will sync automatically"
                        } else {
                            "$pendingCount operations queued for when you're online"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                TextButton(
                    onClick = onViewPending,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("View", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Sync success snackbar content.
 */
@Composable
fun SyncSuccessContent(
    syncedCount: Int,
    onDismiss: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF4CAF50)
        )

        Text(
            text = "$syncedCount operation${if (syncedCount > 1) "s" else ""} synced successfully"
        )
    }
}

// MARK: - Helper Functions

private fun formatSyncTime(instant: Instant): String {
    val now = Instant.now()
    val minutes = ChronoUnit.MINUTES.between(instant, now)
    val hours = ChronoUnit.HOURS.between(instant, now)

    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> {
            val days = ChronoUnit.DAYS.between(instant, now)
            "${days}d ago"
        }
    }
}
