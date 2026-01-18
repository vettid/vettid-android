package com.vettid.app.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.network.BackupFrequency
import com.vettid.app.core.network.BackupSettings
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    viewModel: BackupSettingsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isBackingUp by viewModel.isBackingUp.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 16.dp),
                            strokeWidth = 2.dp
                        )
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
                is BackupSettingsState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is BackupSettingsState.Loaded -> {
                    BackupSettingsContent(
                        settings = currentState.settings,
                        isBackingUp = isBackingUp,
                        onAutoBackupChange = viewModel::setAutoBackupEnabled,
                        onFrequencyChange = viewModel::setBackupFrequency,
                        onTimeChange = viewModel::setBackupTime,
                        onRetentionChange = viewModel::setRetentionDays,
                        onIncludeMessagesChange = viewModel::setIncludeMessages,
                        onWifiOnlyChange = viewModel::setWifiOnly,
                        onBackupNow = viewModel::backupNow
                    )
                }

                is BackupSettingsState.Error -> {
                    ErrorState(
                        message = currentState.message,
                        onRetry = { viewModel.loadSettings() },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
fun BackupSettingsContent(
    settings: BackupSettings,
    isBackingUp: Boolean,
    onAutoBackupChange: (Boolean) -> Unit,
    onFrequencyChange: (BackupFrequency) -> Unit,
    onTimeChange: (String) -> Unit,
    onRetentionChange: (Int) -> Unit,
    onIncludeMessagesChange: (Boolean) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onBackupNow: () -> Unit
) {
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Explanatory text about default backup behavior
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                text = "Backups are enabled by default to protect your credentials. You can disable automatic backups below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(16.dp)
            )
        }

        // Auto-backup Section
        SettingsSection(title = "Automatic Backups") {
            SwitchSettingItem(
                title = "Enable Auto-Backup",
                subtitle = "Automatically backup your data on a schedule",
                checked = settings.autoBackupEnabled,
                onCheckedChange = onAutoBackupChange
            )

            if (settings.autoBackupEnabled) {
                ClickableSettingItem(
                    title = "Backup Frequency",
                    subtitle = settings.backupFrequency.displayName(),
                    onClick = { showFrequencyDialog = true }
                )

                ClickableSettingItem(
                    title = "Backup Time",
                    subtitle = "${settings.backupTimeUtc} UTC",
                    onClick = { showTimeDialog = true }
                )
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // Content Section
        SettingsSection(title = "Backup Content") {
            SwitchSettingItem(
                title = "Include Messages",
                subtitle = "Include your message history in backups",
                checked = settings.includeMessages,
                onCheckedChange = onIncludeMessagesChange
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // Network Section
        SettingsSection(title = "Network") {
            SwitchSettingItem(
                title = "WiFi Only",
                subtitle = "Only backup when connected to WiFi",
                checked = settings.wifiOnly,
                onCheckedChange = onWifiOnlyChange
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // Retention Section
        SettingsSection(title = "Storage") {
            ClickableSettingItem(
                title = "Retention Period",
                subtitle = "${settings.retentionDays} days",
                onClick = { showRetentionDialog = true }
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // Last Backup Info
        settings.lastBackupAt?.let { timestamp ->
            SettingsSection(title = "Last Backup") {
                Text(
                    text = formatLastBackup(timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Backup Now Button
        Button(
            onClick = onBackupNow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            enabled = !isBackingUp
        ) {
            if (isBackingUp) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isBackingUp) "Backing Up..." else "Backup Now")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Frequency Dialog
    if (showFrequencyDialog) {
        FrequencySelectionDialog(
            currentFrequency = settings.backupFrequency,
            onSelect = {
                onFrequencyChange(it)
                showFrequencyDialog = false
            },
            onDismiss = { showFrequencyDialog = false }
        )
    }

    // Time Dialog
    if (showTimeDialog) {
        TimeSelectionDialog(
            currentTime = settings.backupTimeUtc,
            onSelect = {
                onTimeChange(it)
                showTimeDialog = false
            },
            onDismiss = { showTimeDialog = false }
        )
    }

    // Retention Dialog
    if (showRetentionDialog) {
        RetentionSelectionDialog(
            currentDays = settings.retentionDays,
            onSelect = {
                onRetentionChange(it)
                showRetentionDialog = false
            },
            onDismiss = { showRetentionDialog = false }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}

@Composable
fun SwitchSettingItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun ClickableSettingItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FrequencySelectionDialog(
    currentFrequency: BackupFrequency,
    onSelect: (BackupFrequency) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup Frequency") },
        text = {
            Column {
                BackupFrequency.entries.forEach { frequency ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(frequency) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = frequency == currentFrequency,
                            onClick = { onSelect(frequency) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(frequency.displayName())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun TimeSelectionDialog(
    currentTime: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val times = listOf("00:00", "03:00", "06:00", "09:00", "12:00", "15:00", "18:00", "21:00")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup Time (UTC)") },
        text = {
            Column {
                times.forEach { time ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(time) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = time == currentTime,
                            onClick = { onSelect(time) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(time)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun RetentionSelectionDialog(
    currentDays: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(7 to "7 days", 14 to "14 days", 30 to "30 days", 60 to "60 days", 90 to "90 days")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Retention Period") },
        text = {
            Column {
                options.forEach { (days, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(days) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = days == currentDays,
                            onClick = { onSelect(days) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun BackupFrequency.displayName(): String = when (this) {
    BackupFrequency.DAILY -> "Daily"
    BackupFrequency.WEEKLY -> "Weekly"
    BackupFrequency.MONTHLY -> "Monthly"
}

private fun formatLastBackup(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return format.format(date)
}
