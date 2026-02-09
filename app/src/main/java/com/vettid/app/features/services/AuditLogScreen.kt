package com.vettid.app.features.services

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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

/**
 * Audit log content for embedding in MainScaffold drawer.
 * Shows search bar, time/event filters, and event list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogContent(
    viewModel: AuditLogViewModel = hiltViewModel()
) {
    val logs by viewModel.logs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val timeWindow by viewModel.timeWindow.collectAsState()
    val selectedEventTypes by viewModel.selectedEventTypes.collectAsState()
    val verificationStatus by viewModel.verificationStatus.collectAsState()
    val error by viewModel.error.collectAsState()

    var showDetailDialog by remember { mutableStateOf<FeedEvent?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showEventTypeFilter by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search audit logs...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                Row {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Outlined.Download, contentDescription = "Export")
                    }
                    IconButton(onClick = { viewModel.verifyIntegrity() }) {
                        Icon(Icons.Outlined.VerifiedUser, contentDescription = "Verify")
                    }
                }
            },
            singleLine = true
        )

        // Verification status
        verificationStatus?.let { status ->
            VerificationBanner(status = status)
        }

        // Time window dropdown
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var showTimeDropdown by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { showTimeDropdown = true }) {
                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(timeWindow.label)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(
                    expanded = showTimeDropdown,
                    onDismissRequest = { showTimeDropdown = false }
                ) {
                    TimeWindow.entries.forEach { window ->
                        DropdownMenuItem(
                            text = { Text(window.label) },
                            onClick = {
                                showTimeDropdown = false
                                viewModel.setTimeWindow(window)
                            },
                            trailingIcon = {
                                if (timeWindow == window) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }
            }
        }

        // Event type filter row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = showEventTypeFilter,
                    onClick = { showEventTypeFilter = !showEventTypeFilter },
                    label = {
                        Text(
                            if (selectedEventTypes.isEmpty()) "Event Types"
                            else "${selectedEventTypes.size} selected"
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                if (selectedEventTypes.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { viewModel.clearEventTypeFilter() }) {
                        Text("Clear")
                    }
                }
            }
            Text(
                text = "${logs.size} entries",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Expandable event type filter chips
        if (showEventTypeFilter) {
            EventTypeFilterSection(
                selectedTypes = selectedEventTypes,
                onToggle = { viewModel.toggleEventType(it) }
            )
        }

        // Content
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                ErrorContent(
                    message = error ?: "Unknown error",
                    onRetry = { viewModel.loadLogs() }
                )
            }
            logs.isEmpty() -> {
                EmptyContent()
            }
            else -> {
                AuditLogList(
                    logs = logs,
                    onLogClick = { showDetailDialog = it }
                )
            }
        }
    }

    // Detail dialog
    showDetailDialog?.let { event ->
        AuditEventDetailDialog(
            event = event,
            onDismiss = { showDetailDialog = null }
        )
    }

    // Export dialog
    if (showExportDialog) {
        ExportDialog(
            onExportJson = {
                showExportDialog = false
                viewModel.exportJson()
            },
            onExportCsv = {
                showExportDialog = false
                viewModel.exportCsv()
            },
            onDismiss = { showExportDialog = false }
        )
    }
}

/**
 * Standalone audit log screen with back navigation (for full-screen route).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogScreen(
    viewModel: AuditLogViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audit Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            AuditLogContent(viewModel = viewModel)
        }
    }
}

@Composable
private fun EventTypeFilterSection(
    selectedTypes: Set<String>,
    onToggle: (String) -> Unit
) {
    val eventTypeGroups = mapOf(
        "Connections" to listOf(
            "connection.request", "connection.accepted", "connection.revoked"
        ),
        "Messages" to listOf(
            "message.received", "message.sent"
        ),
        "Calls" to listOf(
            "call.incoming", "call.completed", "call.missed"
        ),
        "Security" to listOf(
            "security.alert", "security.migration", "vault.status"
        ),
        "Feed" to listOf(
            "feed.item.read", "feed.item.archived", "feed.item.deleted", "feed.action.taken"
        ),
        "Agents" to listOf(
            "agent.connection.approved", "agent.secret.request", "agent.action.request"
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        eventTypeGroups.forEach { (group, types) ->
            Text(
                text = group,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(types) { type ->
                    FilterChip(
                        selected = type in selectedTypes,
                        onClick = { onToggle(type) },
                        label = {
                            Text(
                                text = type.substringAfter(".").replace(".", " ")
                                    .replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VerificationBanner(status: VerificationStatus) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = when (status) {
            VerificationStatus.VERIFIED -> Color(0xFF4CAF50).copy(alpha = 0.1f)
            VerificationStatus.TAMPERED -> MaterialTheme.colorScheme.errorContainer
            VerificationStatus.VERIFYING -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (status) {
                VerificationStatus.VERIFIED -> {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Log integrity verified", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4CAF50))
                }
                VerificationStatus.TAMPERED -> {
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Warning: Log tampering detected!", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                VerificationStatus.VERIFYING -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verifying log integrity...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AuditLogList(
    logs: List<FeedEvent>,
    onLogClick: (FeedEvent) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(logs, key = { it.eventId }) { event ->
            AuditEventCard(
                event = event,
                onClick = { onLogClick(event) }
            )
        }
    }
}

@Composable
private fun AuditEventCard(
    event: FeedEvent,
    onClick: () -> Unit
) {
    val categoryColor = getEventCategoryColor(event.eventType)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Category icon
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(8.dp),
                color = categoryColor.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getEventCategoryIcon(event.eventType),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = categoryColor
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = categoryColor.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = event.eventType.substringBefore(".").uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = categoryColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                event.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTimestamp(event.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AuditEventDetailDialog(
    event: FeedEvent,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow("Event ID", event.eventId)
                DetailRow("Type", event.eventType)
                DetailRow("Timestamp", formatTimestampFull(event.createdAt))
                event.sourceType?.let { DetailRow("Source", it) }
                DetailRow("Priority", event.priorityLevel.name)
                DetailRow("Status", event.feedStatus)
                DetailRow("Sequence", event.syncSequence.toString())

                event.message?.let { message ->
                    Text("Message", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }

                event.metadata?.let { metadata ->
                    if (metadata.isNotEmpty()) {
                        Text("Details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                metadata.forEach { (key, value) ->
                                    Row {
                                        Text(
                                            text = "$key: ",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = value,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp)
        )
    }
}

@Composable
private fun ExportDialog(
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
        title = { Text("Export Audit Log") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose export format:")
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onExportJson)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Code, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("JSON", style = MaterialTheme.typography.titleSmall)
                            Text("Machine-readable format", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onExportCsv)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.TableChart, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("CSV", style = MaterialTheme.typography.titleSmall)
                            Text("Spreadsheet compatible", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun getEventCategoryIcon(eventType: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when {
        eventType.startsWith("connection.") -> Icons.Outlined.People
        eventType.startsWith("message.") -> Icons.Outlined.Email
        eventType.startsWith("call.") -> Icons.Outlined.Call
        eventType.startsWith("security.") -> Icons.Outlined.Shield
        eventType.startsWith("vault.") -> Icons.Outlined.Cloud
        eventType.startsWith("feed.") -> Icons.Outlined.DynamicFeed
        eventType.startsWith("agent.") -> Icons.Outlined.SmartToy
        eventType.startsWith("backup.") -> Icons.Outlined.Backup
        eventType.startsWith("guide") -> Icons.Outlined.School
        else -> Icons.Outlined.Security
    }
}

@Composable
private fun getEventCategoryColor(eventType: String): Color {
    return when {
        eventType.startsWith("connection.") -> MaterialTheme.colorScheme.primary
        eventType.startsWith("message.") -> Color(0xFF2196F3)
        eventType.startsWith("call.") -> Color(0xFF4CAF50)
        eventType.startsWith("security.") -> MaterialTheme.colorScheme.error
        eventType.startsWith("vault.") -> MaterialTheme.colorScheme.tertiary
        eventType.startsWith("feed.") -> MaterialTheme.colorScheme.secondary
        eventType.startsWith("agent.") -> Color(0xFFFF9800)
        eventType.startsWith("backup.") -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun formatTimestamp(epochSeconds: Long): String {
    val millis = if (epochSeconds < 10000000000L) epochSeconds * 1000 else epochSeconds
    val now = System.currentTimeMillis()
    val diff = now - millis
    val instant = Instant.ofEpochMilli(millis)
    val zoned = instant.atZone(ZoneId.systemDefault())

    return when {
        diff < 60 * 1000 -> "Just now"
        diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}m ago"
        diff < 24 * 60 * 60 * 1000 -> {
            val hours = diff / (60 * 60 * 1000)
            "${hours}h ago"
        }
        else -> zoned.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
    }
}

private fun formatTimestampFull(epochSeconds: Long): String {
    val millis = if (epochSeconds < 10000000000L) epochSeconds * 1000 else epochSeconds
    val instant = Instant.ofEpochMilli(millis)
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("No audit logs", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Security events and vault operations will be logged here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
