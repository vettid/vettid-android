package com.vettid.app.features.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Feed settings screen for configuring retention and archive behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedSettingsScreen(
    onBack: () -> Unit,
    viewModel: FeedSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val editableSettings by viewModel.editableSettings.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FeedSettingsEffect.SettingsSaved -> {
                    snackbarHostState.showSnackbar("Settings saved")
                }
                is FeedSettingsEffect.SaveFailed -> {
                    snackbarHostState.showSnackbar("Failed to save: ${effect.message}")
                }
            }
        }
    }

    // Handle back navigation with unsaved changes
    val handleBack = {
        if (viewModel.hasUnsavedChanges()) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Feed Settings") },
                navigationIcon = {
                    IconButton(onClick = { handleBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Save button
                    TextButton(
                        onClick = { viewModel.saveSettings() },
                        enabled = !isSaving && viewModel.hasUnsavedChanges()
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val currentState = state) {
                is FeedSettingsState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is FeedSettingsState.Error -> {
                    ErrorSettingsContent(
                        message = currentState.message,
                        onRetry = { viewModel.loadSettings() },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is FeedSettingsState.Success -> {
                    editableSettings?.let { settings ->
                        SettingsContent(
                            settings = settings,
                            onFeedRetentionChange = { viewModel.updateFeedRetentionDays(it) },
                            onAuditRetentionChange = { viewModel.updateAuditRetentionDays(it) },
                            onArchiveBehaviorChange = { viewModel.updateArchiveBehavior(it) },
                            onAutoArchiveChange = { viewModel.updateAutoArchiveEnabled(it) }
                        )
                    }
                }
            }
        }
    }

    // Discard changes dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard Changes?") },
            text = { Text("You have unsaved changes. Do you want to discard them?") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    viewModel.discardChanges()
                    onBack()
                }) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsContent(
    settings: EditableFeedSettings,
    onFeedRetentionChange: (Int) -> Unit,
    onAuditRetentionChange: (Int) -> Unit,
    onArchiveBehaviorChange: (String) -> Unit,
    onAutoArchiveChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Feed Retention Section
        SettingsSection(
            title = "Feed Retention",
            icon = Icons.Default.Timer
        ) {
            Text(
                text = "How long to keep items in your feed before auto-archiving",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            RetentionSlider(
                value = settings.feedRetentionDays,
                options = FeedSettingsViewModel.FEED_RETENTION_OPTIONS,
                label = "days",
                onValueChange = onFeedRetentionChange
            )
        }

        // Audit Retention Section
        SettingsSection(
            title = "Audit Log Retention",
            icon = Icons.Default.History
        ) {
            Text(
                text = "How long to keep entries in your security audit log",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            RetentionSlider(
                value = settings.auditRetentionDays,
                options = FeedSettingsViewModel.AUDIT_RETENTION_OPTIONS,
                label = "days",
                onValueChange = onAuditRetentionChange
            )
        }

        // Archive Behavior Section
        SettingsSection(
            title = "Archive Behavior",
            icon = Icons.Default.Archive
        ) {
            Text(
                text = "What happens to old feed items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Column(modifier = Modifier.selectableGroup()) {
                FeedSettingsViewModel.ARCHIVE_BEHAVIORS.forEach { option ->
                    ArchiveBehaviorOption(
                        option = option,
                        selected = settings.archiveBehavior == option.value,
                        onClick = { onArchiveBehaviorChange(option.value) }
                    )
                }
            }
        }

        // Auto Archive Section
        SettingsSection(
            title = "Auto Archive",
            icon = Icons.Default.AutoAwesome
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Automatically archive old items",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Items older than retention period will be processed automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.autoArchiveEnabled,
                    onCheckedChange = onAutoArchiveChange
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            content()
        }
    }
}

@Composable
private fun RetentionSlider(
    value: Int,
    options: List<Int>,
    label: String,
    onValueChange: (Int) -> Unit
) {
    val sliderValue = options.indexOf(value).coerceAtLeast(0).toFloat()

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${options.first()} $label",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "$value $label",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${options.last()} $label",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Slider(
            value = sliderValue,
            onValueChange = { newValue ->
                val index = newValue.toInt().coerceIn(0, options.size - 1)
                onValueChange(options[index])
            },
            valueRange = 0f..(options.size - 1).toFloat(),
            steps = options.size - 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ArchiveBehaviorOption(
    option: ArchiveBehaviorOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorSettingsContent(
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
            "Failed to Load Settings",
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
