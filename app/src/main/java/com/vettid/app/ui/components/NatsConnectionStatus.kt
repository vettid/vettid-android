package com.vettid.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.vettid.app.NatsConnectionState

/**
 * Small status indicator showing NATS connection state.
 * Designed to fit in an app bar or header.
 */
@Composable
fun NatsConnectionStatusIndicator(
    connectionState: NatsConnectionState,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(
        targetValue = when (connectionState) {
            NatsConnectionState.Connected -> Color(0xFF4CAF50) // Green
            NatsConnectionState.Connecting, NatsConnectionState.Checking -> Color(0xFFFFC107) // Amber
            NatsConnectionState.CredentialsExpired -> Color(0xFFFF9800) // Orange
            NatsConnectionState.Failed -> Color(0xFFF44336) // Red
            NatsConnectionState.Idle -> Color(0xFF9E9E9E) // Gray
        },
        animationSpec = tween(300),
        label = "statusColor"
    )

    // Pulsing animation for connecting state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val isConnecting = connectionState == NatsConnectionState.Connecting ||
                       connectionState == NatsConnectionState.Checking

    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer { alpha = if (isConnecting) pulseAlpha else 1f }
                .clip(CircleShape)
                .background(statusColor)
        )
    }
}

/**
 * Expanded status indicator with label, suitable for a status bar or banner.
 */
@Composable
fun NatsConnectionStatusBanner(
    connectionState: NatsConnectionState,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Only show banner for non-connected states (except Idle)
    if (connectionState == NatsConnectionState.Connected ||
        connectionState == NatsConnectionState.Idle) {
        return
    }

    val backgroundColor = when (connectionState) {
        NatsConnectionState.Connecting, NatsConnectionState.Checking ->
            MaterialTheme.colorScheme.secondaryContainer
        NatsConnectionState.CredentialsExpired ->
            MaterialTheme.colorScheme.tertiaryContainer
        NatsConnectionState.Failed ->
            MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = when (connectionState) {
        NatsConnectionState.Connecting, NatsConnectionState.Checking ->
            MaterialTheme.colorScheme.onSecondaryContainer
        NatsConnectionState.CredentialsExpired ->
            MaterialTheme.colorScheme.onTertiaryContainer
        NatsConnectionState.Failed ->
            MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val icon = when (connectionState) {
        NatsConnectionState.Connecting, NatsConnectionState.Checking -> Icons.Default.Sync
        NatsConnectionState.CredentialsExpired -> Icons.Default.Warning
        NatsConnectionState.Failed -> Icons.Default.CloudOff
        else -> Icons.Default.Cloud
    }

    val statusText = when (connectionState) {
        NatsConnectionState.Connecting -> "Connecting to vault..."
        NatsConnectionState.Checking -> "Checking credentials..."
        NatsConnectionState.CredentialsExpired -> "Session expired"
        NatsConnectionState.Failed -> errorMessage ?: "Connection failed"
        else -> ""
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        shape = RoundedCornerShape(0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rotating icon for connecting state
            val infiniteTransition = rememberInfiniteTransition(label = "rotate")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing)
                ),
                label = "rotation"
            )

            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .then(
                        if (connectionState == NatsConnectionState.Connecting ||
                            connectionState == NatsConnectionState.Checking) {
                            Modifier.graphicsLayer { rotationZ = rotation }
                        } else {
                            Modifier
                        }
                    ),
                tint = contentColor
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )

            // Retry button for failed states
            if (connectionState == NatsConnectionState.Failed) {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = contentColor
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Retry", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Dismiss button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(16.dp),
                    tint = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Connection status chip for use in lists or cards.
 */
@Composable
fun NatsConnectionStatusChip(
    connectionState: NatsConnectionState,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, contentColor, label) = when (connectionState) {
        NatsConnectionState.Connected -> Triple(
            Color(0xFF4CAF50).copy(alpha = 0.15f),
            Color(0xFF2E7D32),
            "Connected"
        )
        NatsConnectionState.Connecting, NatsConnectionState.Checking -> Triple(
            Color(0xFFFFC107).copy(alpha = 0.15f),
            Color(0xFFF57C00),
            "Connecting"
        )
        NatsConnectionState.CredentialsExpired -> Triple(
            Color(0xFFFF9800).copy(alpha = 0.15f),
            Color(0xFFE65100),
            "Expired"
        )
        NatsConnectionState.Failed -> Triple(
            Color(0xFFF44336).copy(alpha = 0.15f),
            Color(0xFFC62828),
            "Offline"
        )
        NatsConnectionState.Idle -> Triple(
            Color(0xFF9E9E9E).copy(alpha = 0.15f),
            Color(0xFF616161),
            "Idle"
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(contentColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}
