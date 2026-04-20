package com.vettid.app.features.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.nats.FeedEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-connection audit trail. Opened from the feed card's More > History
 * and intended as the user's complete record of interactions with a peer:
 * messages, calls (voice + video, including missed), connection
 * lifecycle, key rotations, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionHistoryScreen(
    onBack: () -> Unit,
    viewModel: ConnectionHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var searchExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchExpanded) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = viewModel::onSearchQueryChanged,
                            placeholder = { Text("Search history") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Interaction History")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Single Search toggle — refresh is handled by
                    // pulling down on the list, not a bar button.
                    IconButton(onClick = {
                        if (searchExpanded && searchQuery.isNotEmpty()) {
                            viewModel.onSearchQueryChanged("")
                        }
                        searchExpanded = !searchExpanded
                    }) {
                        Icon(
                            imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchExpanded) "Close search" else "Search"
                        )
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val current = state) {
                is ConnectionHistoryState.Loading -> LoadingCentered()
                is ConnectionHistoryState.Empty -> EmptyCentered(
                    title = "No recorded interactions yet",
                    subtitle = "Messages, calls, and lifecycle events with this contact will show up here."
                )
                is ConnectionHistoryState.NoMatches -> EmptyCentered(
                    title = "No matches",
                    subtitle = "Nothing in this contact's history mentions \"${current.query}\"."
                )
                is ConnectionHistoryState.Error -> ErrorCentered(current.message)
                is ConnectionHistoryState.Loaded -> HistoryList(current.events)
            }
        }
    }
}

@Composable
private fun LoadingCentered() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyCentered(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun ErrorCentered(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Composable
private fun HistoryList(events: List<FeedEvent>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(items = events, key = { it.eventId }) { event ->
            HistoryRow(event)
        }
    }
}

@Composable
private fun HistoryRow(event: FeedEvent) {
    val (icon, tint) = iconForEventType(event.eventType)
    val body = renderEventBody(event)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = tint.copy(alpha = 0.15f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title.ifEmpty { humanizeEventType(event.eventType) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (body.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatEventTimestamp(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

/**
 * Resolve a useful body string for an event. The vault frequently sets
 * event.message = "New message" as a placeholder title fragment, which
 * isn't worth showing. Prefer the real message body (or a call summary)
 * from metadata, and fall back to a humanized description.
 */
private fun renderEventBody(event: FeedEvent): String {
    val meta = event.metadata ?: emptyMap()
    val rawMessage = event.message?.trim().orEmpty()
    val placeholder = rawMessage.equals("New message", ignoreCase = true) ||
        rawMessage.equals("Tap to view", ignoreCase = true) ||
        rawMessage.equals("View details", ignoreCase = true)

    return when {
        event.eventType.startsWith("message.") -> {
            val preview = meta["preview"] ?: meta["content"] ?: meta["body"]
                ?: if (!placeholder) rawMessage else ""
            preview.orEmpty().ifBlank {
                if (event.eventType == "message.sent") "Sent a message" else "Received a message"
            }
        }
        event.eventType.startsWith("call.") -> {
            val duration = meta["duration_seconds"]?.toLongOrNull()?.let { formatDuration(it) }
            val parts = listOfNotNull(
                when (event.eventType) {
                    "call.missed" -> "Missed call"
                    "call.completed" -> "Call ended"
                    "call.incoming" -> "Incoming call"
                    else -> null
                },
                duration?.let { "Duration $it" }
            )
            parts.joinToString(" · ")
        }
        else -> if (placeholder) "" else rawMessage
    }
}

private fun humanizeEventType(eventType: String): String =
    eventType.replace(".", " · ").replaceFirstChar { it.uppercase() }

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

/**
 * Map event type → (icon, tint). Aligned with FeedScreen's card icon so
 * a phone call shows the same green phone in both places.
 */
@Composable
private fun iconForEventType(eventType: String): Pair<ImageVector, Color> {
    val green = Color(0xFF4CAF50)
    val red = Color(0xFFE53935)
    return when {
        eventType == "call.missed" -> Icons.Default.CallMissed to red
        eventType.startsWith("call.") -> Icons.Default.Call to green
        eventType.startsWith("message.") -> Icons.AutoMirrored.Filled.Message to MaterialTheme.colorScheme.primary
        eventType == "connection.revoked" -> Icons.Default.PersonRemove to red
        eventType.startsWith("connection.") -> Icons.Default.Person to MaterialTheme.colorScheme.secondary
        eventType.startsWith("transfer.") -> Icons.Default.SwapHoriz to MaterialTheme.colorScheme.tertiary
        eventType.startsWith("security.") -> Icons.Default.Security to red
        else -> Icons.Default.Event to MaterialTheme.colorScheme.outline
    }
}

private fun formatEventTimestamp(epochSeconds: Long): String {
    val millis = if (epochSeconds < 10_000_000_000L) epochSeconds * 1000 else epochSeconds
    val fmt = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
    return fmt.format(Date(millis))
}
