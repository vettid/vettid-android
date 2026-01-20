package com.vettid.app.features.connections.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vettid.app.features.connections.models.ConnectionStatus
import com.vettid.app.features.connections.models.TrustLevel

/**
 * Status chip showing connection status with color coding.
 *
 * Colors:
 * - Green: Active connections
 * - Yellow/Orange: Pending states
 * - Red: Revoked/blocked
 * - Gray: Expired/inactive
 */
@Composable
fun ConnectionStatusChip(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
    compact: Boolean = false
) {
    val (backgroundColor, contentColor, icon) = getStatusColors(status)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 4.dp else 6.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (showIcon) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 14.dp else 16.dp),
                    tint = contentColor
                )
            }
            Text(
                text = if (compact) getCompactStatusText(status) else status.displayName,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = contentColor
            )
        }
    }
}

/**
 * Small status indicator dot for list items.
 */
@Composable
fun StatusDot(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    size: Int = 8
) {
    val (backgroundColor, _, _) = getStatusColors(status)

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(backgroundColor)
    )
}

/**
 * Trust level badge showing verification status.
 */
@Composable
fun TrustBadge(
    trustLevel: TrustLevel,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    val (backgroundColor, contentColor, icon) = getTrustColors(trustLevel)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor
            )
            if (showLabel) {
                Text(
                    text = trustLevel.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor
                )
            }
        }
    }
}

/**
 * Verification badge showing email/identity verification.
 */
@Composable
fun VerificationBadge(
    isVerified: Boolean,
    label: String,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isVerified) {
        Color(0xFF4CAF50)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isVerified) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor.copy(alpha = if (isVerified) 0.15f else 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isVerified) Icons.Default.Verified else Icons.Default.VerifiedUser,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isVerified) Color(0xFF4CAF50) else contentColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isVerified) Color(0xFF4CAF50) else contentColor
            )
        }
    }
}

/**
 * Connection count badge for navigation tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionCountBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Badge(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.error
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString()
            )
        }
    }
}

/**
 * Pending requests badge with different styling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Badge(
            modifier = modifier,
            containerColor = Color(0xFFFF9800)
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString()
            )
        }
    }
}

// MARK: - Helper Functions

private data class StatusColorScheme(
    val backgroundColor: Color,
    val contentColor: Color,
    val icon: ImageVector
)

private fun getStatusColors(status: ConnectionStatus): StatusColorScheme {
    return when (status) {
        ConnectionStatus.ACTIVE -> StatusColorScheme(
            backgroundColor = Color(0xFF4CAF50),
            contentColor = Color(0xFF2E7D32),
            icon = Icons.Default.CheckCircle
        )
        ConnectionStatus.PENDING_THEIR_REVIEW,
        ConnectionStatus.PENDING_THEIR_ACCEPT -> StatusColorScheme(
            backgroundColor = Color(0xFFFF9800),
            contentColor = Color(0xFFE65100),
            icon = Icons.Default.Schedule
        )
        ConnectionStatus.PENDING_OUR_REVIEW,
        ConnectionStatus.PENDING_OUR_ACCEPT -> StatusColorScheme(
            backgroundColor = Color(0xFF2196F3),
            contentColor = Color(0xFF1565C0),
            icon = Icons.Default.Notifications
        )
        ConnectionStatus.REVOKED -> StatusColorScheme(
            backgroundColor = Color(0xFFF44336),
            contentColor = Color(0xFFC62828),
            icon = Icons.Default.Cancel
        )
        ConnectionStatus.EXPIRED -> StatusColorScheme(
            backgroundColor = Color(0xFF9E9E9E),
            contentColor = Color(0xFF616161),
            icon = Icons.Default.AccessTime
        )
        ConnectionStatus.BLOCKED -> StatusColorScheme(
            backgroundColor = Color(0xFFF44336),
            contentColor = Color(0xFFC62828),
            icon = Icons.Default.Block
        )
    }
}

private fun getTrustColors(trustLevel: TrustLevel): StatusColorScheme {
    return when (trustLevel) {
        TrustLevel.NEW -> StatusColorScheme(
            backgroundColor = Color(0xFF9E9E9E),
            contentColor = Color(0xFF616161),
            icon = Icons.Default.FiberNew
        )
        TrustLevel.ESTABLISHED -> StatusColorScheme(
            backgroundColor = Color(0xFF2196F3),
            contentColor = Color(0xFF1565C0),
            icon = Icons.Default.Handshake
        )
        TrustLevel.TRUSTED -> StatusColorScheme(
            backgroundColor = Color(0xFF4CAF50),
            contentColor = Color(0xFF2E7D32),
            icon = Icons.Default.Verified
        )
        TrustLevel.VERIFIED -> StatusColorScheme(
            backgroundColor = Color(0xFF9C27B0),
            contentColor = Color(0xFF6A1B9A),
            icon = Icons.Default.VerifiedUser
        )
    }
}

private fun getCompactStatusText(status: ConnectionStatus): String {
    return when (status) {
        ConnectionStatus.ACTIVE -> "Active"
        ConnectionStatus.PENDING_THEIR_REVIEW -> "Pending"
        ConnectionStatus.PENDING_OUR_REVIEW -> "Review"
        ConnectionStatus.PENDING_THEIR_ACCEPT -> "Pending"
        ConnectionStatus.PENDING_OUR_ACCEPT -> "Accept"
        ConnectionStatus.REVOKED -> "Revoked"
        ConnectionStatus.EXPIRED -> "Expired"
        ConnectionStatus.BLOCKED -> "Blocked"
    }
}
