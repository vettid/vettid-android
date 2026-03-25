package com.vettid.app.features.calling

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * Call history screen showing past calls with direction, type, duration, and timestamp.
 *
 * - Missed calls are highlighted in red
 * - Tapping an entry navigates to the connection detail for that peer
 * - Pull-to-refresh reloads the list
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    viewModel: CallHistoryViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onCallClick: (peerGuid: String) -> Unit = {}
) {
    val calls by viewModel.calls.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is CallHistoryEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                calls.isEmpty() -> {
                    EmptyCallHistoryContent()
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(
                                items = calls,
                                key = { it.call.callId }
                            ) { entry ->
                                CallHistoryItem(
                                    entry = entry,
                                    onClick = { onCallClick(entry.call.peerGuid) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryItem(
    entry: CallHistoryEntry,
    onClick: () -> Unit
) {
    val isMissed = entry.endReason == CallEndReason.MISSED
    val contentColor = if (isMissed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = if (isMissed) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = entry.call.peerDisplayName,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Direction indicator
                Icon(
                    imageVector = directionIcon(entry.call.direction, isMissed),
                    contentDescription = directionDescription(entry.call.direction, isMissed),
                    modifier = Modifier.size(14.dp),
                    tint = secondaryColor
                )

                // Status text
                Text(
                    text = statusText(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor
                )
            }
        },
        leadingContent = {
            // Call type icon (phone or video)
            Icon(
                imageVector = if (entry.call.callType == CallType.VIDEO) {
                    Icons.Default.Videocam
                } else {
                    Icons.Default.Phone
                },
                contentDescription = if (entry.call.callType == CallType.VIDEO) "Video call" else "Voice call",
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End
            ) {
                // Relative timestamp
                Text(
                    text = relativeTimestamp(entry.call.initiatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor
                )

                // Duration (if answered)
                if (entry.duration > 0) {
                    Text(
                        text = formatDuration(entry.duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor
                    )
                }
            }
        }
    )

    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun EmptyCallHistoryContent() {
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
                Icons.Default.Phone,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No call history",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your recent calls will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// MARK: - Utility Functions

/**
 * Get the appropriate direction icon.
 */
private fun directionIcon(direction: CallDirection, isMissed: Boolean): androidx.compose.ui.graphics.vector.ImageVector {
    return when {
        isMissed -> Icons.Default.CallMissed
        direction == CallDirection.INCOMING -> Icons.Default.CallReceived
        else -> Icons.Default.CallMade
    }
}

/**
 * Get the content description for the direction icon.
 */
private fun directionDescription(direction: CallDirection, isMissed: Boolean): String {
    return when {
        isMissed -> "Missed"
        direction == CallDirection.INCOMING -> "Incoming"
        else -> "Outgoing"
    }
}

/**
 * Build status text for a call history entry.
 */
private fun statusText(entry: CallHistoryEntry): String {
    return when (entry.endReason) {
        CallEndReason.MISSED -> "Missed"
        CallEndReason.REJECTED -> if (entry.call.direction == CallDirection.INCOMING) "Declined" else "Rejected"
        CallEndReason.BUSY -> "Busy"
        CallEndReason.TIMEOUT -> "No answer"
        CallEndReason.FAILED -> "Failed"
        CallEndReason.CANCELLED -> "Cancelled"
        CallEndReason.COMPLETED -> if (entry.call.direction == CallDirection.INCOMING) "Incoming" else "Outgoing"
    }
}

/**
 * Format a duration in seconds as MM:SS.
 */
private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

/**
 * Format a timestamp as a relative time string (e.g. "5 min ago", "Yesterday").
 */
private fun relativeTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    return DateUtils.getRelativeTimeSpanString(
        timestampMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
}
