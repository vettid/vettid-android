package com.vettid.app.features.services.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.app.features.services.models.*

/**
 * Notification preferences screen for a service connection.
 *
 * Allows users to customize how they receive notifications from a service:
 * - Overall notification level (All, Important, Muted)
 * - Per-type toggles (data requests, auth requests, payment requests, messages)
 * - Quiet hours bypass
 * - Quick mute options
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceNotificationPreferences(
    serviceName: String,
    settings: ServiceNotificationSettings,
    onSettingsChange: (ServiceNotificationSettings) -> Unit,
    onBackClick: () -> Unit = {},
    onSave: () -> Unit = {}
) {
    var localSettings by remember { mutableStateOf(settings) }
    var showQuickMuteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Notifications")
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        onSettingsChange(localSettings)
                        onSave()
                    }) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Quick actions
            item {
                QuickActionsCard(
                    isMuted = localSettings.level == NotificationLevel.MUTED,
                    onMuteToggle = {
                        localSettings = localSettings.copy(
                            level = if (localSettings.level == NotificationLevel.MUTED) {
                                NotificationLevel.ALL
                            } else {
                                NotificationLevel.MUTED
                            }
                        )
                    },
                    onQuickMute = { showQuickMuteDialog = true }
                )
            }

            // Notification level
            item {
                NotificationLevelCard(
                    level = localSettings.level,
                    onLevelChange = { localSettings = localSettings.copy(level = it) }
                )
            }

            // Notification types
            item {
                NotificationTypesCard(
                    settings = localSettings,
                    onSettingsChange = { localSettings = it },
                    enabled = localSettings.level != NotificationLevel.MUTED
                )
            }

            // Advanced settings
            item {
                AdvancedSettingsCard(
                    bypassQuietHours = localSettings.bypassQuietHours,
                    onBypassChange = { localSettings = localSettings.copy(bypassQuietHours = it) },
                    enabled = localSettings.level != NotificationLevel.MUTED
                )
            }
        }
    }

    // Quick mute dialog
    if (showQuickMuteDialog) {
        QuickMuteDialog(
            onDismiss = { showQuickMuteDialog = false },
            onMuteFor = { duration ->
                localSettings = localSettings.copy(level = NotificationLevel.MUTED)
                onSettingsChange(localSettings)
                showQuickMuteDialog = false
                // TODO: Schedule unmute after duration
            }
        )
    }
}

// MARK: - Quick Actions Card

@Composable
private fun QuickActionsCard(
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onQuickMute: () -> Unit
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
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                        contentDescription = null,
                        tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isMuted) "Notifications Muted" else "Notifications Active",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (isMuted) "You won't receive any notifications" else "You'll receive notifications based on settings below",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = !isMuted,
                    onCheckedChange = { onMuteToggle() }
                )
            }

            if (!isMuted) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onQuickMute,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Quick Mute...")
                }
            }
        }
    }
}

// MARK: - Notification Level Card

@Composable
private fun NotificationLevelCard(
    level: NotificationLevel,
    onLevelChange: (NotificationLevel) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Notification Level",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            NotificationLevel.entries.forEach { notifLevel ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = level == notifLevel,
                        onClick = { onLevelChange(notifLevel) }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = getLevelDisplayName(notifLevel),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = getLevelDescription(notifLevel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun getLevelDisplayName(level: NotificationLevel) = when (level) {
    NotificationLevel.ALL -> "All Notifications"
    NotificationLevel.IMPORTANT -> "Important Only"
    NotificationLevel.MUTED -> "Muted"
}

private fun getLevelDescription(level: NotificationLevel) = when (level) {
    NotificationLevel.ALL -> "Receive all notifications from this service"
    NotificationLevel.IMPORTANT -> "Only payment and authentication requests"
    NotificationLevel.MUTED -> "Don't receive any notifications"
}

// MARK: - Notification Types Card

@Composable
private fun NotificationTypesCard(
    settings: ServiceNotificationSettings,
    onSettingsChange: (ServiceNotificationSettings) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Notification Types",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Choose which types of notifications to receive",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            NotificationTypeRow(
                icon = Icons.Outlined.Description,
                title = "Data Requests",
                subtitle = "When service requests access to your data",
                enabled = enabled && settings.allowDataRequests,
                onToggle = {
                    onSettingsChange(settings.copy(allowDataRequests = !settings.allowDataRequests))
                }
            )

            NotificationTypeRow(
                icon = Icons.Outlined.VpnKey,
                title = "Authentication Requests",
                subtitle = "When service needs to verify your identity",
                enabled = enabled && settings.allowAuthRequests,
                onToggle = {
                    onSettingsChange(settings.copy(allowAuthRequests = !settings.allowAuthRequests))
                }
            )

            NotificationTypeRow(
                icon = Icons.Outlined.Payment,
                title = "Payment Requests",
                subtitle = "When service requests a payment",
                enabled = enabled && settings.allowPaymentRequests,
                onToggle = {
                    onSettingsChange(settings.copy(allowPaymentRequests = !settings.allowPaymentRequests))
                }
            )

            NotificationTypeRow(
                icon = Icons.AutoMirrored.Outlined.Message,
                title = "Messages",
                subtitle = "General messages from the service",
                enabled = enabled && settings.allowMessages,
                onToggle = {
                    onSettingsChange(settings.copy(allowMessages = !settings.allowMessages))
                }
            )
        }
    }
}

@Composable
private fun NotificationTypeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() }
        )
    }
}

// MARK: - Advanced Settings Card

@Composable
private fun AdvancedSettingsCard(
    bypassQuietHours: Boolean,
    onBypassChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Advanced",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.DoNotDisturbOff,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (bypassQuietHours && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bypass Quiet Hours",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Allow important notifications during quiet hours",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = bypassQuietHours,
                    onCheckedChange = onBypassChange,
                    enabled = enabled
                )
            }
        }
    }
}

// MARK: - Quick Mute Dialog

@Composable
private fun QuickMuteDialog(
    onDismiss: () -> Unit,
    onMuteFor: (MuteDuration) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mute Notifications") },
        text = {
            Column {
                Text("How long would you like to mute notifications from this service?")
                Spacer(modifier = Modifier.height(16.dp))

                MuteDuration.entries.forEach { duration ->
                    TextButton(
                        onClick = { onMuteFor(duration) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(duration.displayName)
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

enum class MuteDuration(val displayName: String, val minutes: Int) {
    ONE_HOUR("1 hour", 60),
    FOUR_HOURS("4 hours", 240),
    EIGHT_HOURS("8 hours", 480),
    ONE_DAY("1 day", 1440),
    ONE_WEEK("1 week", 10080),
    INDEFINITELY("Until I turn it back on", -1)
}

/**
 * Compact notification preferences card for embedding in other screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactNotificationPreferencesCard(
    settings: ServiceNotificationSettings,
    onMuteToggle: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Card(
        onClick = onNavigateToSettings,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (settings.level == NotificationLevel.MUTED) {
                    Icons.Default.NotificationsOff
                } else {
                    Icons.Default.Notifications
                },
                contentDescription = null,
                tint = if (settings.level == NotificationLevel.MUTED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Notifications",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = when (settings.level) {
                        NotificationLevel.ALL -> "All notifications"
                        NotificationLevel.IMPORTANT -> "Important only"
                        NotificationLevel.MUTED -> "Muted"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = settings.level != NotificationLevel.MUTED,
                onCheckedChange = { onMuteToggle() }
            )
        }
    }
}
