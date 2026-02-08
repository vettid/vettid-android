package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vettid.app.core.nats.ApprovalRequest
import com.vettid.app.core.nats.ApprovalRequestType
import kotlinx.coroutines.delay

/**
 * Bottom sheet for authorization request prompts from services.
 *
 * Shows:
 * - Service identity with logo
 * - Action being authorized
 * - Resource being accessed (if applicable)
 * - Purpose/reason for the request
 * - Countdown timer (auto-decline after timeout)
 * - Approve/Deny actions
 *
 * Issue #28 [AND-021] - Authorization request prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthzRequestBottomSheet(
    request: AuthorizationRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var remainingSeconds by remember { mutableIntStateOf(request.timeoutSeconds) }

    // Countdown timer
    LaunchedEffect(request.requestId) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }
        // Auto-deny when timer expires
        onDeny()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        AuthzRequestContent(
            request = request,
            remainingSeconds = remainingSeconds,
            onApprove = onApprove,
            onDeny = onDeny
        )
    }
}

@Composable
private fun AuthzRequestContent(
    request: AuthorizationRequest,
    remainingSeconds: Int,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Shield icon
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Title
        Text(
            text = "Authorization Request",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Service info
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (request.serviceLogoUrl != null) {
                AsyncImage(
                    model = request.serviceLogoUrl,
                    contentDescription = request.serviceName,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = request.serviceName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            if (request.isVerified) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = "Verified",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action being authorized
        ActionCard(
            action = request.action,
            resource = request.resource
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Purpose
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Purpose",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = request.purpose,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Additional context (if any)
        if (request.context.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Details",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    request.context.forEach { (key, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Timer
        TimerIndicator(
            remainingSeconds = remainingSeconds,
            totalSeconds = request.timeoutSeconds
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Deny")
            }

            Button(
                onClick = onApprove,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Authorize")
            }
        }
    }
}

@Composable
private fun ActionCard(
    action: String,
    resource: String?
) {
    val (icon, actionColor) = getActionIconAndColor(action)

    Surface(
        color = actionColor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(actionColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = actionColor
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Action",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatActionName(action),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = actionColor
                )
                if (resource != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = resource,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerIndicator(
    remainingSeconds: Int,
    totalSeconds: Int
) {
    val progress = remainingSeconds.toFloat() / totalSeconds.toFloat()
    val timerColor = when {
        remainingSeconds <= 10 -> MaterialTheme.colorScheme.error
        remainingSeconds <= 20 -> Color(0xFFFF9800) // Orange
        else -> MaterialTheme.colorScheme.tertiary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(48.dp),
                color = timerColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 4.dp
            )
            Text(
                text = "${remainingSeconds}s",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = timerColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Request expires",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun getActionIconAndColor(action: String): Pair<ImageVector, Color> {
    return when {
        action.contains("read", ignoreCase = true) ->
            Icons.Outlined.Visibility to Color(0xFF2196F3) // Blue
        action.contains("write", ignoreCase = true) || action.contains("update", ignoreCase = true) ->
            Icons.Outlined.Edit to Color(0xFFFF9800) // Orange
        action.contains("delete", ignoreCase = true) ->
            Icons.Outlined.Delete to Color(0xFFF44336) // Red
        action.contains("payment", ignoreCase = true) || action.contains("pay", ignoreCase = true) ->
            Icons.Outlined.Payment to Color(0xFF4CAF50) // Green
        action.contains("send", ignoreCase = true) || action.contains("message", ignoreCase = true) ->
            Icons.AutoMirrored.Outlined.Send to Color(0xFF9C27B0) // Purple
        action.contains("access", ignoreCase = true) ->
            Icons.Outlined.Key to Color(0xFF009688) // Teal
        action.contains("share", ignoreCase = true) ->
            Icons.Outlined.Share to Color(0xFF3F51B5) // Indigo
        else ->
            Icons.Outlined.Security to Color(0xFF607D8B) // Blue Grey
    }
}

private fun formatActionName(action: String): String {
    return action
        .replace("_", " ")
        .replace("-", " ")
        .split(" ")
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

// MARK: - Data Classes

/**
 * Authorization request from a service.
 */
data class AuthorizationRequest(
    val requestId: String,
    val serviceId: String,
    val serviceName: String,
    val serviceLogoUrl: String? = null,
    val isVerified: Boolean = false,
    val action: String,
    val resource: String? = null,
    val purpose: String,
    val context: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int = 60
)

/**
 * Convert ApprovalRequest to AuthorizationRequest.
 */
fun ApprovalRequest.toAuthzRequest(): AuthorizationRequest? {
    if (type != ApprovalRequestType.AUTHORIZATION) return null

    return AuthorizationRequest(
        requestId = requestId,
        serviceId = serviceId,
        serviceName = serviceName,
        action = action ?: "access",
        resource = resource,
        purpose = purpose,
        context = context.mapValues { it.value.toString() },
        timeoutSeconds = ((expiresAt - System.currentTimeMillis()) / 1000).toInt().coerceIn(10, 120)
    )
}
