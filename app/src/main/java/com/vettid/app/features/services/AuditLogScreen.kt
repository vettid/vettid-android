package com.vettid.app.features.services

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.services.models.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Audit log viewer for security-conscious users.
 *
 * Shows detailed logs of:
 * - Key operations
 * - Contract operations
 * - Authentication events
 * - Data access events
 * - Security events
 *
 * Issue #43 [AND-051] - Audit Log Viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogScreen(
    viewModel: AuditLogViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val logs by viewModel.logs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val verificationStatus by viewModel.verificationStatus.collectAsState()

    var showDetailDialog by remember { mutableStateOf<AuditLogEntry?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Audit Log")
                        Text(
                            text = "${logs.size} entries",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Outlined.Download, contentDescription = "Export")
                    }
                    IconButton(onClick = viewModel::verifyIntegrity) {
                        Icon(Icons.Outlined.VerifiedUser, contentDescription = "Verify")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Verification status
            verificationStatus?.let { status ->
                VerificationBanner(status = status)
            }

            // Category filters
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { viewModel.setCategory(null) },
                        label = { Text("All") }
                    )
                }
                items(AuditCategory.entries) { category ->
                    FilterChip(
                        selected = category == selectedCategory,
                        onClick = { viewModel.setCategory(category) },
                        label = { Text(category.displayName) }
                    )
                }
            }

            when {
                isLoading -> {
                    LoadingContent()
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
    }

    // Detail dialog
    showDetailDialog?.let { entry ->
        AuditLogDetailDialog(
            entry = entry,
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
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Log integrity verified",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF4CAF50)
                    )
                }
                VerificationStatus.TAMPERED -> {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Warning: Log tampering detected!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
                VerificationStatus.VERIFYING -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Verifying log integrity...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditLogList(
    logs: List<AuditLogEntry>,
    onLogClick: (AuditLogEntry) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(logs, key = { it.id }) { entry ->
            AuditLogEntryCard(
                entry = entry,
                onClick = { onLogClick(entry) }
            )
        }
    }
}

@Composable
private fun AuditLogEntryCard(
    entry: AuditLogEntry,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when (entry.result) {
                AuditResult.FAILURE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(eventCategoryColor(entry.eventType.category).copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = eventCategoryIcon(entry.eventType.category),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = eventCategoryColor(entry.eventType.category)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = entry.eventType.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )

                    // Result badge
                    Surface(
                        color = when (entry.result) {
                            AuditResult.SUCCESS -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                            AuditResult.FAILURE -> MaterialTheme.colorScheme.errorContainer
                            AuditResult.PENDING -> MaterialTheme.colorScheme.tertiaryContainer
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = entry.result.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (entry.result) {
                                AuditResult.SUCCESS -> Color(0xFF4CAF50)
                                AuditResult.FAILURE -> MaterialTheme.colorScheme.error
                                AuditResult.PENDING -> MaterialTheme.colorScheme.tertiary
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                entry.serviceName?.let { service ->
                    Text(
                        text = service,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AuditLogDetailDialog(
    entry: AuditLogEntry,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(entry.eventType.displayName)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DetailRow("ID", entry.id)
                DetailRow("Timestamp", formatTimestamp(entry.timestamp))
                DetailRow("Category", entry.eventType.category.displayName)
                DetailRow("Result", entry.result.name)

                entry.serviceName?.let {
                    DetailRow("Service", it)
                }

                if (entry.details.isNotEmpty()) {
                    Text(
                        text = "Details",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            entry.details.forEach { (key, value) ->
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

                entry.hash?.let { hash ->
                    Text(
                        text = "Hash",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = hash,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
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
            style = MaterialTheme.typography.bodySmall
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
        icon = {
            Icon(Icons.Outlined.Download, contentDescription = null)
        },
        title = {
            Text("Export Audit Log")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                            Text(
                                text = "JSON",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Machine-readable format",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                            Text(
                                text = "CSV",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Spreadsheet compatible",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun eventCategoryIcon(category: AuditCategory): androidx.compose.ui.graphics.vector.ImageVector {
    return when (category) {
        AuditCategory.KEYS -> Icons.Outlined.Key
        AuditCategory.CONTRACTS -> Icons.Outlined.Description
        AuditCategory.AUTH -> Icons.Outlined.Security
        AuditCategory.DATA -> Icons.Outlined.Storage
        AuditCategory.SECURITY -> Icons.Outlined.Shield
    }
}

@Composable
private fun eventCategoryColor(category: AuditCategory): Color {
    return when (category) {
        AuditCategory.KEYS -> MaterialTheme.colorScheme.tertiary
        AuditCategory.CONTRACTS -> MaterialTheme.colorScheme.primary
        AuditCategory.AUTH -> MaterialTheme.colorScheme.secondary
        AuditCategory.DATA -> Color(0xFF4CAF50)
        AuditCategory.SECURITY -> MaterialTheme.colorScheme.error
    }
}

private fun formatTimestamp(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
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
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No audit logs",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Security events and cryptographic operations will be logged here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
