package com.vettid.app.features.services

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vettid.app.core.nats.ApprovalRequest
import com.vettid.app.core.nats.ApprovalRequestType
import kotlinx.coroutines.delay

/**
 * Bottom sheet for authentication request prompts from services.
 *
 * Shows:
 * - Service identity with logo
 * - Purpose of the authentication request
 * - Countdown timer (auto-decline after timeout)
 * - Approve/Deny actions
 *
 * Issue #27 [AND-020] - Authentication request prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthRequestBottomSheet(
    request: AuthenticationRequest,
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
        AuthRequestContent(
            request = request,
            remainingSeconds = remainingSeconds,
            onApprove = onApprove,
            onDeny = onDeny
        )
    }
}

@Composable
private fun AuthRequestContent(
    request: AuthenticationRequest,
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
        // Lock icon with pulse animation
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Title
        Text(
            text = "Authentication Request",
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
                color = MaterialTheme.colorScheme.primary
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

        Spacer(modifier = Modifier.height(16.dp))

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

        // Context info (if any)
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
                    request.context.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall
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
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Approve")
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
        else -> MaterialTheme.colorScheme.primary
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

// MARK: - Data Classes

/**
 * Authentication request from a service.
 */
data class AuthenticationRequest(
    val requestId: String,
    val serviceId: String,
    val serviceName: String,
    val serviceLogoUrl: String? = null,
    val isVerified: Boolean = false,
    val purpose: String,
    val context: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int = 60
)

/**
 * Convert ApprovalRequest to AuthenticationRequest.
 */
fun ApprovalRequest.toAuthRequest(): AuthenticationRequest? {
    if (type != ApprovalRequestType.AUTHENTICATION) return null

    return AuthenticationRequest(
        requestId = requestId,
        serviceId = serviceId,
        serviceName = serviceName,
        purpose = purpose,
        context = context.mapValues { it.value.toString() },
        timeoutSeconds = ((expiresAt - System.currentTimeMillis()) / 1000).toInt().coerceIn(10, 120)
    )
}
