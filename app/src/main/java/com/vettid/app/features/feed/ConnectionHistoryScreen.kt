package com.vettid.app.features.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import com.vettid.app.core.nats.AuditEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionHistoryScreen(
    onBack: () -> Unit,
    onOpenConversation: (connectionId: String) -> Unit = {},
    viewModel: ConnectionHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isPaginating by viewModel.isPaginating.collectAsState()
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
                is ConnectionHistoryState.Loaded -> HistoryList(
                    events = current.events,
                    endReached = current.endReached,
                    isPaginating = isPaginating,
                    onLoadMore = viewModel::loadNextPage,
                    onEventClick = { event -> handleHistoryClick(event, onOpenConversation) }
                )
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

// handleHistoryClick maps an audit row to a navigation intent. Only
// message rows navigate today — call and transfer detail screens are
// tracked as follow-ups in docs/CONNECTION-AUDIT-TRAIL-PLAN.md §5.
private fun handleHistoryClick(event: AuditEntry, onOpenConversation: (String) -> Unit) {
    if (event.event_type.startsWith("message.")) {
        onOpenConversation(event.connection_id)
    }
}

@Composable
private fun HistoryList(
    events: List<AuditEntry>,
    endReached: Boolean,
    isPaginating: Boolean,
    onLoadMore: () -> Unit,
    onEventClick: (AuditEntry) -> Unit,
) {
    val listState = rememberLazyListState()

    // Trigger pagination when the user scrolls within the last three
    // rows of the current page. LazyListState exposes visible-item
    // info synchronously, no LaunchedEffect needed.
    val lastVisibleIndex by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
    }
    LaunchedEffect(lastVisibleIndex, events.size, endReached, isPaginating) {
        if (!endReached && !isPaginating && lastVisibleIndex >= events.size - 3) {
            onLoadMore()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(items = events, key = { it.entry_id }) { event ->
            HistoryRow(event, onClick = { onEventClick(event) })
        }
        if (!endReached) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(event: AuditEntry, onClick: () -> Unit) {
    val (icon, tint) = iconForEventType(event.event_type)
    val body = renderEventBody(event)
    val clickable = event.event_type.startsWith("message.")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
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
                text = event.title.ifEmpty { humanizeEventType(event.event_type) },
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
                text = formatEventTimestamp(event.created_at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

private fun renderEventBody(event: AuditEntry): String {
    val body = event.body?.trim().orEmpty()
    if (body.isNotEmpty()) return body

    val meta = event.metadata ?: emptyMap()
    return when {
        event.event_type.startsWith("call.") -> {
            val duration = meta["duration_seconds"]?.toLongOrNull()?.let { formatDuration(it) }
            val prefix = when {
                event.event_type.contains("missed") -> "Missed call"
                event.event_type.contains("completed") -> "Call ended"
                event.event_type.contains("started") -> "Call started"
                else -> "Call"
            }
            listOfNotNull(prefix, duration?.let { "Duration $it" }).joinToString(" · ")
        }
        event.event_type.startsWith("transfer.btc.") -> {
            val amount = meta["amount_sats"]
            if (amount != null) "Amount $amount sats" else ""
        }
        else -> ""
    }
}

private fun humanizeEventType(eventType: String): String =
    eventType.replace(".", " · ").replaceFirstChar { it.uppercase() }

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun iconForEventType(eventType: String): Pair<ImageVector, Color> {
    val green = Color(0xFF4CAF50)
    val red = Color(0xFFE53935)
    return when {
        eventType.contains("missed") -> Icons.Default.CallMissed to red
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
