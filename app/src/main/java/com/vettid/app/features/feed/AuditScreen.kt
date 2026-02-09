package com.vettid.app.features.feed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.nats.EventPriority
import com.vettid.app.core.nats.FeedEvent
import java.text.SimpleDateFormat
import java.util.*

/**
 * Full-featured audit log viewer screen.
 *
 * Features:
 * - Filter by event types
 * - Date range filtering
 * - Pagination (load more)
 * - Export to JSON
 * - Event detail view
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(
    onBack: () -> Unit,
    viewModel: AuditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()

    var showFiltersSheet by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<FeedEvent?>(null) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audit Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Filter button with badge
                    BadgedBox(
                        badge = {
                            if (filters.hasActiveFilters) {
                                Badge { Text("!") }
                            }
                        }
                    ) {
                        IconButton(onClick = { showFiltersSheet = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filter")
                        }
                    }

                    // Export button
                    IconButton(
                        onClick = {
                            viewModel.exportAudit { result ->
                                result.onSuccess { json ->
                                    copyToClipboard(context, json)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Exported ${json.lines().size} lines to clipboard")
                                    }
                                }.onFailure { error ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Export failed: ${error.message}")
                                    }
                                }
                            }
                        },
                        enabled = !isExporting
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Download, contentDescription = "Export")
                        }
                    }

                    // Refresh button
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Active filters chips
            if (filters.hasActiveFilters) {
                ActiveFiltersRow(
                    filters = filters,
                    onClearAll = { viewModel.clearFilters() },
                    onRemoveType = { viewModel.toggleEventTypeFilter(it) },
                    onClearDateRange = { viewModel.setDateRange(null, null) }
                )
            }

            // Main content
            Box(modifier = Modifier.weight(1f)) {
                when (val currentState = state) {
                    is AuditState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    is AuditState.Empty -> {
                        EmptyAuditContent(
                            hasFilters = filters.hasActiveFilters,
                            onClearFilters = { viewModel.clearFilters() },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    is AuditState.Success -> {
                        AuditEventList(
                            events = currentState.events,
                            hasMore = currentState.hasMore,
                            onLoadMore = { viewModel.loadMore() },
                            onEventClick = { selectedEvent = it }
                        )
                    }

                    is AuditState.Error -> {
                        ErrorAuditContent(
                            message = currentState.message,
                            onRetry = { viewModel.refresh() },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }

    // Filters bottom sheet
    if (showFiltersSheet) {
        FilterBottomSheet(
            filters = filters,
            onDismiss = { showFiltersSheet = false },
            onToggleEventType = { viewModel.toggleEventTypeFilter(it) },
            onSetDateRange = { start, end -> viewModel.setDateRange(start, end) },
            onClearAll = { viewModel.clearFilters() }
        )
    }

    // Event detail dialog
    selectedEvent?.let { event ->
        EventDetailDialog(
            event = event,
            onDismiss = { selectedEvent = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveFiltersRow(
    filters: AuditFilters,
    onClearAll: () -> Unit,
    onRemoveType: (String) -> Unit,
    onClearDateRange: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Event type filters
            items(filters.selectedEventTypes.toList()) { type ->
                FilterChip(
                    selected = true,
                    onClick = { onRemoveType(type) },
                    label = { Text(getEventTypeDisplayName(type)) },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                    }
                )
            }

            // Date range filter
            if (filters.startDate != null || filters.endDate != null) {
                item {
                    FilterChip(
                        selected = true,
                        onClick = onClearDateRange,
                        label = { Text(formatDateRange(filters.startDate, filters.endDate)) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }

            // Clear all
            item {
                TextButton(onClick = onClearAll) {
                    Text("Clear All")
                }
            }
        }
    }
}

@Composable
private fun AuditEventList(
    events: List<FeedEvent>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onEventClick: (FeedEvent) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(events, key = { it.eventId }) { event ->
            AuditEventItem(
                event = event,
                onClick = { onEventClick(event) }
            )
        }

        if (hasMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = onLoadMore) {
                        Text("Load More")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuditEventItem(
    event: FeedEvent,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Event icon
            Surface(
                shape = MaterialTheme.shapes.small,
                color = getEventColor(event.eventType).copy(alpha = 0.2f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getEventIcon(event.eventType),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = getEventColor(event.eventType)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = event.eventType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatTimestamp(event.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "View details",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBottomSheet(
    filters: AuditFilters,
    onDismiss: () -> Unit,
    onToggleEventType: (String) -> Unit,
    onSetDateRange: (Long?, Long?) -> Unit,
    onClearAll: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Filter Audit Log",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Event Types section
            Text(
                "Event Types",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            AuditViewModel.ALL_EVENT_TYPES.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { eventType ->
                        FilterChip(
                            selected = eventType.type in filters.selectedEventTypes,
                            onClick = { onToggleEventType(eventType.type) },
                            label = { Text(eventType.displayName) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Fill empty space if odd number
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick date filters
            Text(
                "Date Range",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickDateChip(
                    label = "Today",
                    selected = isToday(filters.startDate, filters.endDate),
                    onClick = {
                        val todayStart = getTodayStart()
                        onSetDateRange(todayStart, null)
                    },
                    modifier = Modifier.weight(1f)
                )
                QuickDateChip(
                    label = "Week",
                    selected = isLastWeek(filters.startDate, filters.endDate),
                    onClick = {
                        val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                        onSetDateRange(weekAgo, null)
                    },
                    modifier = Modifier.weight(1f)
                )
                QuickDateChip(
                    label = "Month",
                    selected = isLastMonth(filters.startDate, filters.endDate),
                    onClick = {
                        val monthAgo = System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L
                        onSetDateRange(monthAgo, null)
                    },
                    modifier = Modifier.weight(1f)
                )
                QuickDateChip(
                    label = "All",
                    selected = filters.startDate == null && filters.endDate == null,
                    onClick = { onSetDateRange(null, null) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onClearAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear All")
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Apply")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickDateChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun EventDetailDialog(
    event: FeedEvent,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title) },
        text = {
            Column {
                DetailRow("Type", event.eventType)
                DetailRow("Status", event.feedStatus)
                DetailRow("Priority", event.priorityLevel.name)
                DetailRow("Created", formatTimestamp(event.createdAt))
                event.readAt?.let { DetailRow("Read", formatTimestamp(it)) }
                event.actionedAt?.let { DetailRow("Actioned", formatTimestamp(it)) }
                event.message?.let { DetailRow("Message", it) }
                event.sourceId?.let { DetailRow("Source ID", it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun EmptyAuditContent(
    hasFilters: Boolean,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            if (hasFilters) "No Matching Events" else "No Audit Events",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            if (hasFilters) "Try adjusting your filters" else "Activity events will appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
        if (hasFilters) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onClearFilters) {
                Text("Clear Filters")
            }
        }
    }
}

@Composable
private fun ErrorAuditContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Failed to Load",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

// Helper functions

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Audit Log Export", text)
    clipboard.setPrimaryClip(clip)
}

private fun getEventTypeDisplayName(type: String): String {
    return AuditViewModel.ALL_EVENT_TYPES.find { it.type == type }?.displayName ?: type
}

private fun formatDateRange(startDate: Long?, endDate: Long?): String {
    val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    return when {
        startDate != null && endDate != null -> "${dateFormat.format(Date(startDate))} - ${dateFormat.format(Date(endDate))}"
        startDate != null -> "Since ${dateFormat.format(Date(startDate))}"
        endDate != null -> "Until ${dateFormat.format(Date(endDate))}"
        else -> "All time"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return dateFormat.format(Date(com.vettid.app.util.toEpochMillis(timestamp)))
}

private fun getTodayStart(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun isToday(startDate: Long?, endDate: Long?): Boolean {
    if (endDate != null) return false
    val todayStart = getTodayStart()
    return startDate != null && startDate >= todayStart && startDate < todayStart + 24 * 60 * 60 * 1000
}

private fun isLastWeek(startDate: Long?, endDate: Long?): Boolean {
    if (endDate != null) return false
    val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
    return startDate != null && startDate >= weekAgo - 24 * 60 * 60 * 1000 && startDate < weekAgo + 24 * 60 * 60 * 1000
}

private fun isLastMonth(startDate: Long?, endDate: Long?): Boolean {
    if (endDate != null) return false
    val monthAgo = System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L
    return startDate != null && startDate >= monthAgo - 24 * 60 * 60 * 1000 && startDate < monthAgo + 24 * 60 * 60 * 1000
}

@Composable
private fun getEventIcon(eventType: String): ImageVector {
    return when {
        eventType.startsWith("call.") -> Icons.Default.Call
        eventType.startsWith("message.") -> Icons.AutoMirrored.Filled.Message
        eventType.startsWith("connection.") -> Icons.Default.Person
        eventType.startsWith("security.") -> Icons.Default.Security
        eventType.startsWith("transfer.") -> Icons.Default.SwapHoriz
        eventType.startsWith("backup.") -> Icons.Default.Backup
        eventType.startsWith("handler.") -> Icons.Default.CheckCircle
        else -> Icons.Default.Event
    }
}

@Composable
private fun getEventColor(eventType: String) = when {
    eventType.startsWith("call.") -> MaterialTheme.colorScheme.primary
    eventType.startsWith("message.") -> MaterialTheme.colorScheme.tertiary
    eventType.startsWith("connection.") -> MaterialTheme.colorScheme.secondary
    eventType.startsWith("security.") -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}
