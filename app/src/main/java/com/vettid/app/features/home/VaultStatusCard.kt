package com.vettid.app.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.app.features.vault.VaultStatusIcon
import com.vettid.app.features.vault.VaultStatusSummary

/**
 * Vault status card for home screen
 */
@Composable
fun VaultStatusCard(
    summary: VaultStatusSummary,
    onClick: () -> Unit,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(getIconBackgroundColor(summary.icon)),
                contentAlignment = Alignment.Center
            ) {
                if (summary.icon == VaultStatusIcon.LOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = getIconForStatus(summary.icon),
                        contentDescription = null,
                        tint = getIconColor(summary.icon),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = summary.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                summary.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action button or chevron
            if (summary.actionLabel != null && onActionClick != null) {
                TextButton(onClick = onActionClick) {
                    Text(summary.actionLabel)
                }
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "View details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Compact vault status indicator for app bar or small spaces
 */
@Composable
fun VaultStatusIndicator(
    icon: VaultStatusIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(getIconBackgroundColor(icon)),
            contentAlignment = Alignment.Center
        ) {
            if (icon == VaultStatusIcon.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.5.dp
                )
            } else {
                Icon(
                    imageVector = getIconForStatus(icon),
                    contentDescription = "Vault status",
                    modifier = Modifier.size(16.dp),
                    tint = getIconColor(icon)
                )
            }
        }
    }
}

/**
 * Quick actions row for vault
 */
@Composable
fun VaultQuickActions(
    onSync: () -> Unit,
    onSettings: () -> Unit,
    isSyncing: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onSync,
            enabled = !isSyncing,
            modifier = Modifier.weight(1f)
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text("Sync")
        }

        OutlinedButton(
            onClick = onSettings,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Settings")
        }
    }
}

private fun getIconForStatus(icon: VaultStatusIcon): ImageVector {
    return when (icon) {
        VaultStatusIcon.LOADING -> Icons.Default.HourglassEmpty
        VaultStatusIcon.NOT_ENROLLED -> Icons.Default.CloudOff
        VaultStatusIcon.ENROLLED -> Icons.Default.Cloud
        VaultStatusIcon.PROVISIONING -> Icons.Default.CloudSync
        VaultStatusIcon.HEALTHY -> Icons.Default.CheckCircle
        VaultStatusIcon.DEGRADED -> Icons.Default.Warning
        VaultStatusIcon.UNHEALTHY -> Icons.Default.Error
        VaultStatusIcon.UNKNOWN -> Icons.AutoMirrored.Filled.Help
        VaultStatusIcon.STOPPED -> Icons.Default.PauseCircle
        VaultStatusIcon.TERMINATED -> Icons.Default.Cancel
        VaultStatusIcon.ERROR -> Icons.Default.Error
    }
}

private fun getIconColor(icon: VaultStatusIcon): Color {
    return when (icon) {
        VaultStatusIcon.HEALTHY -> Color(0xFF4CAF50)
        VaultStatusIcon.DEGRADED -> Color(0xFFFF9800)
        VaultStatusIcon.UNHEALTHY, VaultStatusIcon.ERROR -> Color(0xFFF44336)
        VaultStatusIcon.STOPPED, VaultStatusIcon.UNKNOWN -> Color(0xFF9E9E9E)
        VaultStatusIcon.TERMINATED -> Color(0xFF757575)
        VaultStatusIcon.LOADING, VaultStatusIcon.PROVISIONING -> Color(0xFF2196F3)
        VaultStatusIcon.NOT_ENROLLED -> Color(0xFF9E9E9E)
        VaultStatusIcon.ENROLLED -> Color(0xFF4CAF50)
    }
}

private fun getIconBackgroundColor(icon: VaultStatusIcon): Color {
    return getIconColor(icon).copy(alpha = 0.1f)
}
