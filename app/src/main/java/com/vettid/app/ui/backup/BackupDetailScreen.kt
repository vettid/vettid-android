package com.vettid.app.ui.backup

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.network.Backup
import com.vettid.app.core.network.RestoreResult
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDetailScreen(
    viewModel: BackupDetailViewModel = hiltViewModel(),
    onRestoreComplete: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val deleteSuccess by viewModel.deleteSuccess.collectAsState()

    var showRestoreDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(deleteSuccess) {
        if (deleteSuccess) {
            onDeleted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val currentState = state) {
                is BackupDetailState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is BackupDetailState.Loaded -> {
                    BackupDetailContent(
                        backup = currentState.backup,
                        onRestore = { showRestoreDialog = true },
                        onDelete = { showDeleteDialog = true }
                    )
                }

                is BackupDetailState.Restoring -> {
                    RestoringContent(
                        backup = currentState.backup,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is BackupDetailState.RestoreComplete -> {
                    RestoreCompleteContent(
                        result = currentState.result,
                        onDone = onRestoreComplete,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is BackupDetailState.Error -> {
                    ErrorState(
                        message = currentState.message,
                        onRetry = { viewModel.loadBackup() },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }

    // Restore confirmation dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restore Backup") },
            text = {
                Text("This will restore your data from this backup. Any conflicting data will be handled based on your preference.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.restoreBackup(overwriteConflicts = false)
                        showRestoreDialog = false
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Backup") },
            text = { Text("Are you sure you want to delete this backup? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBackup()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
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

@Composable
fun BackupDetailContent(
    backup: Backup,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Backup Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Backup Information",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                DetailRow(label = "Created", value = formatDateTime(backup.createdAt))
                DetailRow(label = "Type", value = backup.type.name)
                DetailRow(label = "Status", value = backup.status.name)
                DetailRow(label = "Size", value = formatSize(backup.sizeBytes))
                DetailRow(label = "Encryption", value = backup.encryptionMethod)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Contents Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Contents",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                DetailRow(label = "Handlers", value = backup.handlersCount.toString())
                DetailRow(label = "Connections", value = backup.connectionsCount.toString())
                DetailRow(label = "Messages", value = backup.messagesCount.toString())
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action Buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Restore from Backup")
            }

            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Backup")
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun RestoringContent(
    backup: Backup,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Restoring Backup...",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Please wait while your data is being restored.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun RestoreCompleteContent(
    result: RestoreResult,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (result.success) "Restore Complete" else "Restore Failed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (result.success) {
            Text(
                text = "${result.restoredItems} items restored",
                style = MaterialTheme.typography.bodyLarge
            )

            if (result.conflicts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${result.conflicts.size} conflicts were skipped",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (result.requiresReauth) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "You may need to re-authenticate some services",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onDone) {
            Text("Done")
        }
    }
}

private fun formatDateTime(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return format.format(date)
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
