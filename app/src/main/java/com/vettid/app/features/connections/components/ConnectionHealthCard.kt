package com.vettid.app.features.connections.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vettid.app.features.connections.models.*
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Card showing connection health indicators.
 */
@Composable
fun ConnectionHealthCard(
    health: ConnectionHealth,
    onRotateCredentials: () -> Unit,
    onSyncProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connection Health",
                    style = MaterialTheme.typography.titleMedium
                )

                HealthStatusBadge(status = health.healthStatus)
            }

            Divider()

            // Last Active
            HealthIndicatorRow(
                icon = Icons.Default.AccessTime,
                label = "Last Active",
                value = health.lastActiveAt?.let { formatLastActive(it) } ?: "Never",
                status = if (health.isStale) IndicatorStatus.WARNING else IndicatorStatus.GOOD
            )

            // Credential Status
            HealthIndicatorRow(
                icon = Icons.Default.Key,
                label = "Credentials",
                value = formatCredentialExpiry(health.credentialsExpireAt),
                status = if (health.credentialsExpiringSoon) IndicatorStatus.WARNING else IndicatorStatus.GOOD,
                action = if (health.credentialsExpiringSoon) {
                    {
                        TextButton(
                            onClick = onRotateCredentials,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Rotate", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else null
            )

            // Profile Sync Status
            HealthIndicatorRow(
                icon = Icons.Default.Sync,
                label = "Profile",
                value = if (health.needsProfileSync) "Update available" else "Up to date",
                status = if (health.needsProfileSync) IndicatorStatus.INFO else IndicatorStatus.GOOD,
                action = if (health.needsProfileSync) {
                    {
                        TextButton(
                            onClick = onSyncProfile,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Sync", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else null
            )

            // Trust Level
            HealthIndicatorRow(
                icon = Icons.Default.Verified,
                label = "Trust Level",
                value = health.trustLevel.displayName,
                status = IndicatorStatus.NEUTRAL
            )

            // Activity Count
            HealthIndicatorRow(
                icon = Icons.Default.SwapHoriz,
                label = "Total Activity",
                value = "${health.activityCount} interactions",
                status = IndicatorStatus.NEUTRAL
            )
        }
    }
}

@Composable
private fun HealthStatusBadge(status: HealthStatus) {
    val (color, text) = when (status) {
        HealthStatus.GOOD -> Color(0xFF4CAF50) to "Healthy"
        HealthStatus.INFO -> Color(0xFF2196F3) to "Info"
        HealthStatus.WARNING -> Color(0xFFFF9800) to "Attention"
        HealthStatus.STALE -> Color(0xFF9E9E9E) to "Stale"
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private enum class IndicatorStatus {
    GOOD, INFO, WARNING, NEUTRAL
}

@Composable
private fun HealthIndicatorRow(
    icon: ImageVector,
    label: String,
    value: String,
    status: IndicatorStatus,
    action: (@Composable () -> Unit)? = null
) {
    val statusColor = when (status) {
        IndicatorStatus.GOOD -> Color(0xFF4CAF50)
        IndicatorStatus.INFO -> Color(0xFF2196F3)
        IndicatorStatus.WARNING -> Color(0xFFFF9800)
        IndicatorStatus.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = statusColor
            )

            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor
                )
            }
        }

        action?.invoke()
    }
}

/**
 * Compact health indicator for list items.
 */
@Composable
fun CompactHealthIndicator(
    credentialsExpiringSoon: Boolean,
    needsProfileSync: Boolean,
    isStale: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (credentialsExpiringSoon) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Credentials expiring",
                modifier = Modifier.size(14.dp),
                tint = Color(0xFFFF9800)
            )
        }

        if (needsProfileSync) {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = "Profile update available",
                modifier = Modifier.size(14.dp),
                tint = Color(0xFF2196F3)
            )
        }

        if (isStale && !credentialsExpiringSoon && !needsProfileSync) {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = "Inactive",
                modifier = Modifier.size(14.dp),
                tint = Color(0xFF9E9E9E)
            )
        }
    }
}

// MARK: - Helper Functions

private fun formatLastActive(instant: Instant): String {
    val now = Instant.now()
    val minutes = ChronoUnit.MINUTES.between(instant, now)
    val hours = ChronoUnit.HOURS.between(instant, now)
    val days = ChronoUnit.DAYS.between(instant, now)

    return when {
        minutes < 5 -> "Just now"
        minutes < 60 -> "$minutes minutes ago"
        hours < 24 -> "$hours hours ago"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} weeks ago"
        else -> "${days / 30} months ago"
    }
}

private fun formatCredentialExpiry(expiresAt: Instant): String {
    val now = Instant.now()
    val days = ChronoUnit.DAYS.between(now, expiresAt)

    return when {
        days < 0 -> "Expired"
        days == 0L -> "Expires today"
        days == 1L -> "Expires tomorrow"
        days < 7 -> "Expires in $days days"
        days < 30 -> "Expires in ${days / 7} weeks"
        else -> "Valid"
    }
}
