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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    onOpenGuide: (guideId: String, userName: String) -> Unit = { _, _ -> },
    viewModel: ConnectionHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isPaginating by viewModel.isPaginating.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val peerName by viewModel.peerName.collectAsState()
    var searchExpanded by remember { mutableStateOf(false) }

    // Refresh on every re-entry so entries produced on other screens
    // (e.g. a message just sent from ConversationScreen) appear
    // without requiring pull-to-refresh. LaunchedEffect(Unit) only
    // fires on first composition; nav-back doesn't recompose, so
    // subscribe to ON_RESUME via the lifecycle observer too.
    val historyLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(historyLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        historyLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { historyLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Detail sheets open in-place rather than as separate routes —
    // these entries are leaf views (a read-only summary) and a sheet
    // keeps the user's position in the list when they dismiss.
    var detailSheet by remember { mutableStateOf<AuditEntry?>(null) }

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
                        Column {
                            Text(
                                text = peerName.ifEmpty { "Interaction History" },
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (peerName.isNotEmpty()) {
                                Text(
                                    text = "Interaction history",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                    onEventClick = { event ->
                        handleHistoryClick(
                            event = event,
                            onOpenConversation = onOpenConversation,
                            onOpenGuide = onOpenGuide,
                            onOpenDetailSheet = { detailSheet = it },
                        )
                    }
                )
            }
        }

        detailSheet?.let { entry ->
            when {
                entry.event_type.startsWith("call.") -> CallDetailSheet(
                    entry = entry,
                    onDismiss = { detailSheet = null },
                )
                entry.event_type.startsWith("transfer.btc.") -> TransferDetailSheet(
                    entry = entry,
                    onDismiss = { detailSheet = null },
                )
                entry.event_type.startsWith("system.") -> SystemEventDetailSheet(
                    entry = entry,
                    onDismiss = { detailSheet = null },
                )
                else -> {
                    detailSheet = null
                }
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

// handleHistoryClick maps an audit row to a navigation intent.
//   - message.*              → open the conversation
//   - call.*                 → open the call detail bottom sheet
//   - transfer.btc           → open the transfer detail bottom sheet
//   - system.guide.published → open the guide
//   - other system.*         → detail sheet fallback
private fun handleHistoryClick(
    event: AuditEntry,
    onOpenConversation: (String) -> Unit,
    onOpenGuide: (guideId: String, userName: String) -> Unit,
    onOpenDetailSheet: (AuditEntry) -> Unit,
) {
    when {
        event.event_type.startsWith("message.") -> onOpenConversation(event.connection_id)
        event.event_type.startsWith("call.") -> onOpenDetailSheet(event)
        event.event_type.startsWith("transfer.btc.") -> onOpenDetailSheet(event)
        event.event_type == "system.guide.published" -> {
            val guideId = event.refs?.get("guide_id").orEmpty()
            val userName = event.metadata?.get("user_name").orEmpty()
            if (guideId.isNotEmpty()) onOpenGuide(guideId, userName)
        }
        event.event_type.startsWith("system.") -> onOpenDetailSheet(event)
    }
}

private fun AuditEntry.isInteractive(): Boolean =
    event_type.startsWith("message.") ||
        event_type.startsWith("call.") ||
        event_type.startsWith("transfer.btc.") ||
        event_type.startsWith("system.")

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
    val clickable = event.isInteractive()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallDetailSheet(entry: AuditEntry, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val meta = entry.metadata ?: emptyMap()
    val isVideo = entry.event_type.contains("video")
    val outcome = when {
        entry.event_type.contains("missed") -> "Missed"
        entry.event_type.contains("completed") -> "Completed"
        entry.event_type.contains("rejected") -> "Rejected"
        entry.event_type.contains("started") -> "Started"
        else -> "Call"
    }
    val duration = meta["duration_seconds"]?.toLongOrNull()?.let { formatDuration(it) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = if (isVideo) "Video call" else "Voice call",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatEventTimestamp(entry.created_at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            DetailRow("Outcome", outcome)
            if (duration != null) DetailRow("Duration", duration)
            meta["reason"]?.takeIf { it.isNotBlank() }?.let { DetailRow("Reason", it) }
            entry.refs?.get("call_id")?.let { DetailRow("Call ID", it, mono = true) }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferDetailSheet(entry: AuditEntry, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val meta = entry.metadata ?: emptyMap()
    val txId = entry.refs?.get("tx_id")
    val uriHandler = LocalUriHandler.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = if (entry.event_type.endsWith(".sent")) "BTC sent" else "BTC received",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatEventTimestamp(entry.created_at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            meta["amount_sats"]?.let { DetailRow("Amount", "$it sats") }
            meta["fee_sats"]?.let { DetailRow("Fee", "$it sats") }
            txId?.let { DetailRow("Transaction", it, mono = true) }
            if (!txId.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://mempool.space/tx/$txId") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View on mempool.space")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemEventDetailSheet(entry: AuditEntry, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = entry.title.ifEmpty { entry.event_type.replace(".", " · ") },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatEventTimestamp(entry.created_at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!entry.body.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = entry.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            entry.metadata?.forEach { (key, value) ->
                if (key == "is_update") return@forEach
                DetailRow(
                    label = key.replace("_", " ").replaceFirstChar { it.uppercase() },
                    value = value,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = value,
            style = if (mono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (mono) FontWeight.Normal else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}
