package com.vettid.app.features.services

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
 * Data transparency screen for a service connection.
 *
 * Shows all data the service has stored in the user's sandbox:
 * - Data items grouped by category
 * - Storage usage statistics
 * - Visibility levels (what user can see)
 * - Expiration dates
 * - Options to view, export, or request deletion
 *
 * This gives users full visibility into what services store about them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceDataTransparencyScreen(
    serviceName: String,
    storageRecords: List<ServiceStorageRecord>,
    summary: ServiceDataSummary?,
    isLoading: Boolean = false,
    error: String? = null,
    onBackClick: () -> Unit = {},
    onViewRecord: (ServiceStorageRecord) -> Unit = {},
    onRequestDeletion: (ServiceStorageRecord) -> Unit = {},
    onExportData: () -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    val filteredRecords = if (selectedCategory != null) {
        storageRecords.filter { it.category == selectedCategory }
    } else {
        storageRecords
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Data Transparency")
                        Text(
                            text = serviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onExportData) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export Data"
                        )
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                LoadingContent(modifier = Modifier.padding(padding))
            }
            error != null -> {
                ErrorContent(
                    message = error,
                    onRetry = onRefresh,
                    modifier = Modifier.padding(padding)
                )
            }
            storageRecords.isEmpty() -> {
                EmptyDataContent(modifier = Modifier.padding(padding))
            }
            else -> {
                DataTransparencyContent(
                    records = filteredRecords,
                    allRecords = storageRecords,
                    summary = summary,
                    selectedCategory = selectedCategory,
                    onCategorySelect = { selectedCategory = if (selectedCategory == it) null else it },
                    onViewRecord = onViewRecord,
                    onRequestDeletion = onRequestDeletion,
                    onDeleteAll = { showDeleteAllDialog = true },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    // Delete all confirmation
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Request Data Deletion?") },
            text = {
                Text(
                    "This will request $serviceName to delete all data they have stored " +
                    "about you. Some data may be required for the service to function properly."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllDialog = false
                        // Request deletion for all records
                        storageRecords.forEach { onRequestDeletion(it) }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Request Deletion")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DataTransparencyContent(
    records: List<ServiceStorageRecord>,
    allRecords: List<ServiceStorageRecord>,
    summary: ServiceDataSummary?,
    selectedCategory: String?,
    onCategorySelect: (String) -> Unit,
    onViewRecord: (ServiceStorageRecord) -> Unit,
    onRequestDeletion: (ServiceStorageRecord) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Storage summary
        summary?.let {
            item {
                StorageSummaryCard(
                    summary = it,
                    onDeleteAll = onDeleteAll
                )
            }
        }

        // Category filters
        val categories = allRecords.groupBy { it.category }
        if (categories.size > 1) {
            item {
                CategoryFilterRow(
                    categories = categories.mapValues { it.value.size },
                    selectedCategory = selectedCategory,
                    onCategorySelect = onCategorySelect
                )
            }
        }

        // Records count
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${records.size} items${if (selectedCategory != null) " in $selectedCategory" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Visibility legend
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VisibilityLegendItem(
                        level = VisibilityLevel.VIEWABLE,
                        label = "Viewable"
                    )
                    VisibilityLegendItem(
                        level = VisibilityLevel.METADATA,
                        label = "Metadata"
                    )
                    VisibilityLegendItem(
                        level = VisibilityLevel.HIDDEN,
                        label = "Hidden"
                    )
                }
            }
        }

        // Storage records
        items(records) { record ->
            StorageRecordCard(
                record = record,
                onView = { onViewRecord(record) },
                onRequestDeletion = { onRequestDeletion(record) }
            )
        }
    }
}

// MARK: - Storage Summary Card

@Composable
private fun StorageSummaryCard(
    summary: ServiceDataSummary,
    onDeleteAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Storage Overview",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onDeleteAll,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete All")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StorageStatItem(
                    icon = Icons.Outlined.Folder,
                    value = "${summary.totalItems}",
                    label = "Items"
                )
                StorageStatItem(
                    icon = Icons.Outlined.Storage,
                    value = formatBytes(summary.totalSizeBytes),
                    label = "Total Size"
                )
                StorageStatItem(
                    icon = Icons.Outlined.Category,
                    value = "${summary.categories.size}",
                    label = "Categories"
                )
            }

            // Date range
            if (summary.oldestItem != null && summary.newestItem != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Oldest",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatDate(summary.oldestItem),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Newest",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatDate(summary.newestItem),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Categories breakdown
            if (summary.categories.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "By Category",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                summary.categories.forEach { (category, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "$count items",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// MARK: - Category Filter Row

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterRow(
    categories: Map<String, Int>,
    selectedCategory: String?,
    onCategorySelect: (String) -> Unit
) {
    Text(
        text = "Categories",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { (category, count) ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onCategorySelect(category) },
                label = { Text("$category ($count)") }
            )
        }
    }
}

// MARK: - Visibility Legend

@Composable
private fun VisibilityLegendItem(
    level: VisibilityLevel,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    getVisibilityColor(level),
                    CircleShape
                )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// MARK: - Storage Record Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageRecordCard(
    record: ServiceStorageRecord,
    onView: () -> Unit,
    onRequestDeletion: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = {
            if (record.visibilityLevel != VisibilityLevel.HIDDEN) {
                expanded = !expanded
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Visibility indicator
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = getVisibilityColor(record.visibilityLevel).copy(alpha = 0.1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = getVisibilityIcon(record.visibilityLevel),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = getVisibilityColor(record.visibilityLevel)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Label/key
                    Text(
                        text = record.label ?: record.key,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Description
                    record.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Metadata row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Category badge
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = record.category,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // Data type
                        record.dataType?.let { dataType ->
                            Text(
                                text = dataType,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Dates
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Created ${formatDate(record.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Expiration warning
                    record.expiresAt?.let { expiresAt ->
                        val isExpiringSoon = expiresAt.isBefore(Instant.now().plusSeconds(7 * 24 * 60 * 60))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = if (isExpiringSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Expires ${formatDate(expiresAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isExpiringSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Expand/action button
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded content
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Show value if viewable
                if (record.visibilityLevel == VisibilityLevel.VIEWABLE) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "[Encrypted data - ${record.encryptedValue.size} bytes]",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (record.visibilityLevel == VisibilityLevel.VIEWABLE) {
                        TextButton(onClick = onView) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("View")
                        }
                    }

                    TextButton(
                        onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Request Deletion")
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Request Deletion?") },
            text = {
                Text("Request the service to delete \"${record.label ?: record.key}\"?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onRequestDeletion()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Request")
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

// MARK: - State Composables

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun EmptyDataContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Storage,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Data Stored",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "This service hasn't stored any data in your vault",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// MARK: - Utility Functions

private fun getVisibilityIcon(level: VisibilityLevel) = when (level) {
    VisibilityLevel.VIEWABLE -> Icons.Default.Visibility
    VisibilityLevel.METADATA -> Icons.Default.Info
    VisibilityLevel.HIDDEN -> Icons.Default.VisibilityOff
}

private fun getVisibilityColor(level: VisibilityLevel) = when (level) {
    VisibilityLevel.VIEWABLE -> Color(0xFF4CAF50)
    VisibilityLevel.METADATA -> Color(0xFFFF9800)
    VisibilityLevel.HIDDEN -> Color(0xFF9E9E9E)
}

private fun formatDate(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
