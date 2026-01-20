package com.vettid.app.features.services.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import com.vettid.app.features.services.models.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Health indicator components for service connections.
 *
 * Shows connection health status, usage metrics, and warnings:
 * - Overall health status (healthy, warning, critical)
 * - Request rate and limits
 * - Storage usage
 * - Contract status
 * - Recent issues
 */

// MARK: - Health Status Badge

@Composable
fun HealthStatusBadge(
    status: HealthStatus,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier
) {
    val color = Color(status.color)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pulsing dot for non-healthy status
        if (status != HealthStatus.HEALTHY) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color.copy(alpha = alpha), CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
        }

        if (showLabel) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = status.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// MARK: - Compact Health Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactHealthCard(
    health: ServiceConnectionHealth,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(health.status.color).copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (health.status) {
                    HealthStatus.HEALTHY -> Icons.Default.CheckCircle
                    HealthStatus.WARNING -> Icons.Default.Warning
                    HealthStatus.CRITICAL -> Icons.Default.Error
                },
                contentDescription = null,
                tint = Color(health.status.color),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Connection Health",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = health.status.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(health.status.color),
                    fontWeight = FontWeight.Medium
                )
            }

            if (health.issues.isNotEmpty()) {
                Badge(
                    containerColor = Color(health.status.color)
                ) {
                    Text("${health.issues.size}")
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "View details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// MARK: - Full Health Card

@Composable
fun ServiceHealthCard(
    health: ServiceConnectionHealth,
    onViewDetails: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.MonitorHeart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Connection Health",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                HealthStatusBadge(status = health.status)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Usage meters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                UsageMeter(
                    label = "Requests",
                    current = health.requestsThisHour,
                    max = health.requestLimit,
                    unit = "/hr",
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                UsageMeter(
                    label = "Storage",
                    current = health.dataStorageUsed,
                    max = health.dataStorageLimit,
                    unit = "",
                    formatValue = { formatBytes(it) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Contract status
            Spacer(modifier = Modifier.height(12.dp))
            ContractStatusRow(status = health.contractStatus)

            // Last activity
            health.lastActiveAt?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Last active: ${formatRelativeTime(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Issues
            if (health.issues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Issues",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(4.dp))

                health.issues.forEach { issue ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = issue,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Usage Meter

@Composable
private fun UsageMeter(
    label: String,
    current: Int,
    max: Int,
    unit: String,
    formatValue: (Long) -> String = { it.toString() },
    modifier: Modifier = Modifier
) {
    val percentage = if (max > 0) (current.toFloat() / max) else 0f
    val color = when {
        percentage >= 0.9f -> MaterialTheme.colorScheme.error
        percentage >= 0.7f -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${formatValue(current.toLong())}/${formatValue(max.toLong())}$unit",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        LinearProgressIndicator(
            progress = percentage.coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )
    }
}

@Composable
private fun UsageMeter(
    label: String,
    current: Long,
    max: Long,
    unit: String,
    formatValue: (Long) -> String = { it.toString() },
    modifier: Modifier = Modifier
) {
    val percentage = if (max > 0) (current.toFloat() / max) else 0f
    val color = when {
        percentage >= 0.9f -> MaterialTheme.colorScheme.error
        percentage >= 0.7f -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${formatValue(current)}/${formatValue(max)}$unit",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        LinearProgressIndicator(
            progress = percentage.coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )
    }
}

// MARK: - Contract Status Row

@Composable
private fun ContractStatusRow(status: ContractHealthStatus) {
    val (icon, color, text) = when (status) {
        ContractHealthStatus.CURRENT -> Triple(
            Icons.Default.CheckCircle,
            Color(0xFF4CAF50),
            "Contract up to date"
        )
        ContractHealthStatus.UPDATE_AVAILABLE -> Triple(
            Icons.Default.Info,
            Color(0xFFFF9800),
            "Contract update available"
        )
        ContractHealthStatus.EXPIRED -> Triple(
            Icons.Default.Error,
            MaterialTheme.colorScheme.error,
            "Contract expired"
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

// MARK: - Trust Score Indicator

@Composable
fun TrustScoreIndicator(
    indicators: ServiceTrustIndicators,
    modifier: Modifier = Modifier
) {
    val score = calculateTrustScore(indicators)
    val (color, label) = when {
        score >= 80 -> Color(0xFF4CAF50) to "High Trust"
        score >= 50 -> Color(0xFFFF9800) to "Medium Trust"
        else -> MaterialTheme.colorScheme.error to "Low Trust"
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            progress = score / 100f,
            modifier = Modifier.size(32.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
            strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = "$score%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun calculateTrustScore(indicators: ServiceTrustIndicators): Int {
    var score = 50 // Base score

    // Organization verification (+20)
    if (indicators.organizationVerified) score += 20

    // Connection age (+10 for 30+ days, +5 for 7+ days)
    when {
        indicators.connectionAge >= 30 -> score += 10
        indicators.connectionAge >= 7 -> score += 5
    }

    // Interactions (+5 for any, +10 for 10+)
    when {
        indicators.totalInteractions >= 10 -> score += 10
        indicators.totalInteractions >= 1 -> score += 5
    }

    // Violations (-10 each, max -30)
    score -= minOf(indicators.rateLimitViolations * 10, 15)
    score -= minOf(indicators.contractViolations * 10, 15)

    // Excessive requests (-10)
    if (indicators.hasExcessiveRequests) score -= 10

    // Pending contract update (-5)
    if (indicators.pendingContractUpdate) score -= 5

    return score.coerceIn(0, 100)
}

// MARK: - Utility Functions

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

private fun formatRelativeTime(instant: Instant): String {
    val now = Instant.now()
    val minutes = ChronoUnit.MINUTES.between(instant, now)
    val hours = ChronoUnit.HOURS.between(instant, now)
    val days = ChronoUnit.DAYS.between(instant, now)

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val formatter = DateTimeFormatter.ofPattern("MMM d")
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        }
    }
}
