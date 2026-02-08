package com.vettid.app.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.network.Backup
import com.vettid.app.core.network.BackupStatus
import com.vettid.app.core.network.BackupType
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupListScreen(
    viewModel: BackupListViewModel = hiltViewModel(),
    onBackupClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isCreatingBackup by viewModel.isCreatingBackup.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backups") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.createBackup() },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                if (isCreatingBackup) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Add, contentDescription = "Create Backup")
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val currentState = state) {
                is BackupListState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is BackupListState.Empty -> {
                    EmptyBackupState(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is BackupListState.Loaded -> {
                    Column {
                        if (isRefreshing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                items = currentState.backups,
                                key = { it.backupId }
                            ) { backup ->
                                BackupListItem(
                                    backup = backup,
                                    onClick = { onBackupClick(backup.backupId) },
                                    onDelete = { showDeleteDialog = backup.backupId }
                                )
                            }
                        }
                    }
                }

                is BackupListState.Error -> {
                    ErrorState(
                        message = currentState.message,
                        onRetry = { viewModel.loadBackups() },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { backupId ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Backup") },
            text = { Text("Are you sure you want to delete this backup? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBackup(backupId)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BackupListItem(
    backup: Backup,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatDate(backup.createdAt),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    BackupTypeBadge(type = backup.type)
                    BackupStatusBadge(status = backup.status)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatSize(backup.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (backup.handlersCount > 0 || backup.connectionsCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = buildContentSummary(backup),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun BackupTypeBadge(type: BackupType) {
    val (text, color) = when (type) {
        BackupType.AUTO -> "Auto" to MaterialTheme.colorScheme.secondary
        BackupType.MANUAL -> "Manual" to MaterialTheme.colorScheme.primary
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
fun BackupStatusBadge(status: BackupStatus) {
    val (text, color) = when (status) {
        BackupStatus.COMPLETE -> "Complete" to MaterialTheme.colorScheme.primary
        BackupStatus.PARTIAL -> "Partial" to MaterialTheme.colorScheme.tertiary
        BackupStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        BackupStatus.IN_PROGRESS -> "In Progress" to MaterialTheme.colorScheme.secondary
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
fun EmptyBackupState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No Backups Yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create your first backup to protect your data. Tap the + button to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
    return format.format(date)
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

private fun buildContentSummary(backup: Backup): String {
    val parts = mutableListOf<String>()
    if (backup.handlersCount > 0) {
        parts.add("${backup.handlersCount} handlers")
    }
    if (backup.connectionsCount > 0) {
        parts.add("${backup.connectionsCount} connections")
    }
    if (backup.messagesCount > 0) {
        parts.add("${backup.messagesCount} messages")
    }
    return parts.joinToString(" • ")
}
