package com.vettid.app.features.connections.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Notification preferences for a specific connection.
 */
data class ConnectionNotificationPreferences(
    val connectionId: String,
    val messagesEnabled: Boolean = true,
    val connectionRequestsEnabled: Boolean = true,
    val credentialRequestsEnabled: Boolean = true,
    val activityUpdatesEnabled: Boolean = false,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val isMuted: Boolean = false,
    val mutedUntil: Long? = null  // Timestamp for temporary mute
)

/**
 * Card for configuring connection notification preferences.
 */
@Composable
fun ConnectionNotificationPreferencesCard(
    preferences: ConnectionNotificationPreferences,
    onPreferencesChange: (ConnectionNotificationPreferences) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Notification Settings",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Mute toggle
            MuteSection(
                isMuted = preferences.isMuted,
                mutedUntil = preferences.mutedUntil,
                onMuteChange = { muted, until ->
                    onPreferencesChange(preferences.copy(isMuted = muted, mutedUntil = until))
                }
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            // Notification types
            Text(
                text = "Notification Types",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            NotificationToggle(
                icon = Icons.Default.Chat,
                title = "Messages",
                description = "New messages from this connection",
                enabled = preferences.messagesEnabled && !preferences.isMuted,
                onToggle = {
                    onPreferencesChange(preferences.copy(messagesEnabled = it))
                }
            )

            NotificationToggle(
                icon = Icons.Default.PersonAdd,
                title = "Connection Requests",
                description = "Requests to share data",
                enabled = preferences.connectionRequestsEnabled && !preferences.isMuted,
                onToggle = {
                    onPreferencesChange(preferences.copy(connectionRequestsEnabled = it))
                }
            )

            NotificationToggle(
                icon = Icons.Default.Badge,
                title = "Credential Requests",
                description = "Requests for your credentials",
                enabled = preferences.credentialRequestsEnabled && !preferences.isMuted,
                onToggle = {
                    onPreferencesChange(preferences.copy(credentialRequestsEnabled = it))
                }
            )

            NotificationToggle(
                icon = Icons.Default.Update,
                title = "Activity Updates",
                description = "Profile changes and updates",
                enabled = preferences.activityUpdatesEnabled && !preferences.isMuted,
                onToggle = {
                    onPreferencesChange(preferences.copy(activityUpdatesEnabled = it))
                }
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            // Sound and vibration
            Text(
                text = "Alert Style",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            NotificationToggle(
                icon = Icons.Default.VolumeUp,
                title = "Sound",
                description = "Play notification sound",
                enabled = preferences.soundEnabled && !preferences.isMuted,
                onToggle = {
                    onPreferencesChange(preferences.copy(soundEnabled = it))
                }
            )

            NotificationToggle(
                icon = Icons.Default.Vibration,
                title = "Vibration",
                description = "Vibrate on notification",
                enabled = preferences.vibrationEnabled && !preferences.isMuted,
                onToggle = {
                    onPreferencesChange(preferences.copy(vibrationEnabled = it))
                }
            )
        }
    }
}

@Composable
private fun MuteSection(
    isMuted: Boolean,
    mutedUntil: Long?,
    onMuteChange: (Boolean, Long?) -> Unit
) {
    var showMuteOptions by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isMuted) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isMuted) "Muted" else "Notifications On",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (isMuted && mutedUntil != null) {
                        Text(
                            text = "Until ${formatMuteTime(mutedUntil)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isMuted) {
                TextButton(onClick = { onMuteChange(false, null) }) {
                    Text("Unmute")
                }
            } else {
                TextButton(onClick = { showMuteOptions = true }) {
                    Text("Mute")
                }
            }
        }
    }

    if (showMuteOptions) {
        MuteOptionsDialog(
            onDismiss = { showMuteOptions = false },
            onMuteFor = { duration ->
                val until = if (duration > 0) System.currentTimeMillis() + duration else null
                onMuteChange(true, until)
                showMuteOptions = false
            }
        )
    }
}

@Composable
private fun MuteOptionsDialog(
    onDismiss: () -> Unit,
    onMuteFor: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mute Notifications") },
        text = {
            Column {
                MuteOption("1 hour", 1 * 60 * 60 * 1000L, onMuteFor)
                MuteOption("8 hours", 8 * 60 * 60 * 1000L, onMuteFor)
                MuteOption("24 hours", 24 * 60 * 60 * 1000L, onMuteFor)
                MuteOption("1 week", 7 * 24 * 60 * 60 * 1000L, onMuteFor)
                MuteOption("Until I turn it back on", 0L, onMuteFor)
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

@Composable
private fun MuteOption(
    label: String,
    duration: Long,
    onSelect: (Long) -> Unit
) {
    TextButton(
        onClick = { onSelect(duration) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun NotificationToggle(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}

/**
 * Quick mute button for list items.
 */
@Composable
fun QuickMuteButton(
    isMuted: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
    ) {
        Icon(
            imageVector = if (isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
            contentDescription = if (isMuted) "Unmute" else "Mute",
            tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatMuteTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = timestamp - now
    val hours = diff / (60 * 60 * 1000)
    val days = hours / 24

    return when {
        days > 0 -> "$days day${if (days > 1) "s" else ""}"
        hours > 0 -> "$hours hour${if (hours > 1) "s" else ""}"
        else -> "soon"
    }
}
