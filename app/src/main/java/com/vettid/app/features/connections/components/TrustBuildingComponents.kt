package com.vettid.app.features.connections.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vettid.app.features.connections.models.TrustLevel
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Trust history event.
 */
data class TrustEvent(
    val id: String,
    val type: TrustEventType,
    val description: String,
    val timestamp: Instant,
    val impact: TrustImpact
)

enum class TrustEventType {
    CONNECTION_ESTABLISHED,
    MESSAGE_EXCHANGED,
    CREDENTIAL_SHARED,
    CREDENTIAL_VERIFIED,
    PROFILE_UPDATED,
    MUTUAL_CONTACT_FOUND,
    LONG_TERM_CONNECTION,
    ACTIVITY_INCREASE,
    INACTIVE_PERIOD
}

enum class TrustImpact {
    POSITIVE,
    NEUTRAL,
    NEGATIVE
}

/**
 * Trust level progress card showing how trust builds over time.
 */
@Composable
fun TrustProgressCard(
    currentLevel: TrustLevel,
    nextLevel: TrustLevel?,
    progressToNext: Float,  // 0.0 to 1.0
    trustEvents: List<TrustEvent>,
    onViewHistory: () -> Unit,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.TrendingUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Trust Level",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                TrustBadge(trustLevel = currentLevel)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Trust level visualization
            TrustLevelVisualization(
                currentLevel = currentLevel,
                progressToNext = progressToNext
            )

            // Next level info
            nextLevel?.let {
                Spacer(modifier = Modifier.height(12.dp))
                NextLevelInfo(
                    nextLevel = it,
                    progress = progressToNext
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Divider()

            Spacer(modifier = Modifier.height(16.dp))

            // Recent trust events
            Text(
                text = "Recent Activity",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            trustEvents.take(3).forEach { event ->
                TrustEventItem(event = event)
            }

            if (trustEvents.size > 3) {
                TextButton(
                    onClick = onViewHistory,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("View full history")
                }
            }
        }
    }
}

@Composable
private fun TrustLevelVisualization(
    currentLevel: TrustLevel,
    progressToNext: Float
) {
    val levels = TrustLevel.entries

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        levels.forEachIndexed { index, level ->
            val isCurrentOrPast = level.ordinal <= currentLevel.ordinal
            val isCurrent = level == currentLevel

            // Level indicator
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(if (isCurrent) 32.dp else 24.dp),
                    shape = RoundedCornerShape(50),
                    color = if (isCurrentOrPast) getTrustLevelColor(level) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isCurrentOrPast) {
                            Icon(
                                imageVector = if (isCurrent) Icons.Default.Star else Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(if (isCurrent) 20.dp else 14.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = level.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Progress line between levels
            if (index < levels.size - 1) {
                val lineProgress = when {
                    index < currentLevel.ordinal -> 1f
                    index == currentLevel.ordinal -> progressToNext
                    else -> 0f
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .padding(horizontal = 4.dp)
                ) {
                    // Background
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(2.dp)
                    ) {}

                    // Progress
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(lineProgress)
                            .fillMaxHeight(),
                        color = getTrustLevelColor(currentLevel),
                        shape = RoundedCornerShape(2.dp)
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun NextLevelInfo(
    nextLevel: TrustLevel,
    progress: Float
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Next: ${nextLevel.displayName}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = getNextLevelRequirements(nextLevel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TrustEventItem(event: TrustEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Impact indicator
        Surface(
            modifier = Modifier.size(8.dp),
            shape = RoundedCornerShape(4.dp),
            color = when (event.impact) {
                TrustImpact.POSITIVE -> Color(0xFF4CAF50)
                TrustImpact.NEUTRAL -> Color(0xFF9E9E9E)
                TrustImpact.NEGATIVE -> Color(0xFFF44336)
            }
        ) {}

        Spacer(modifier = Modifier.width(12.dp))

        Icon(
            imageVector = getEventIcon(event.type),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = event.description,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = formatEventTime(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Trust verification badge with explanation.
 */
@Composable
fun TrustVerificationSection(
    level: TrustLevel,
    verifications: List<TrustVerification>,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Trust Verification",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Verified actions that build trust",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            verifications.forEach { verification ->
                VerificationItem(verification = verification)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onVerify,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Verify More")
            }
        }
    }
}

data class TrustVerification(
    val id: String,
    val name: String,
    val description: String,
    val isCompleted: Boolean,
    val completedAt: Instant? = null
)

@Composable
private fun VerificationItem(verification: TrustVerification) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (verification.isCompleted)
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (verification.isCompleted)
                        Icons.Default.CheckCircle
                    else
                        Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (verification.isCompleted)
                        Color(0xFF4CAF50)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = verification.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (verification.isCompleted)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = verification.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (verification.isCompleted && verification.completedAt != null) {
            Text(
                text = formatEventTime(verification.completedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Mutual connections indicator.
 */
@Composable
fun MutualConnectionsCard(
    mutualCount: Int,
    mutualNames: List<String>,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (mutualCount == 0) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$mutualCount mutual connection${if (mutualCount > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = mutualNames.take(3).joinToString(", ") +
                            if (mutualNames.size > 3) " +${mutualNames.size - 3} more" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onViewAll) {
                Text("View")
            }
        }
    }
}

// Helper functions
private fun getTrustLevelColor(level: TrustLevel): Color {
    return when (level) {
        TrustLevel.NEW -> Color(0xFF9E9E9E)
        TrustLevel.ESTABLISHED -> Color(0xFF2196F3)
        TrustLevel.TRUSTED -> Color(0xFF4CAF50)
        TrustLevel.VERIFIED -> Color(0xFF9C27B0)
    }
}

private fun getNextLevelRequirements(level: TrustLevel): String {
    return when (level) {
        TrustLevel.NEW -> "Connect to start"
        TrustLevel.ESTABLISHED -> "Exchange messages or share data"
        TrustLevel.TRUSTED -> "Verify credentials or find mutual connections"
        TrustLevel.VERIFIED -> "Complete identity verification"
    }
}

private fun getEventIcon(type: TrustEventType): ImageVector {
    return when (type) {
        TrustEventType.CONNECTION_ESTABLISHED -> Icons.Default.Handshake
        TrustEventType.MESSAGE_EXCHANGED -> Icons.Default.Chat
        TrustEventType.CREDENTIAL_SHARED -> Icons.Default.Share
        TrustEventType.CREDENTIAL_VERIFIED -> Icons.Default.Verified
        TrustEventType.PROFILE_UPDATED -> Icons.Default.Edit
        TrustEventType.MUTUAL_CONTACT_FOUND -> Icons.Default.Group
        TrustEventType.LONG_TERM_CONNECTION -> Icons.Default.Schedule
        TrustEventType.ACTIVITY_INCREASE -> Icons.Default.TrendingUp
        TrustEventType.INACTIVE_PERIOD -> Icons.Default.Pause
    }
}

private fun formatEventTime(instant: Instant): String {
    val now = Instant.now()
    val days = ChronoUnit.DAYS.between(instant, now)

    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7}w ago"
        else -> "${days / 30}mo ago"
    }
}
