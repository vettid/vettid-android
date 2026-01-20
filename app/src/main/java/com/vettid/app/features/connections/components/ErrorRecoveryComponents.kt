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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Error types for connections.
 */
enum class ConnectionErrorType {
    NETWORK,
    TIMEOUT,
    INVITATION_EXPIRED,
    INVITATION_INVALID,
    INVITATION_ALREADY_USED,
    CONNECTION_REVOKED,
    VAULT_DISCONNECTED,
    AUTHENTICATION_REQUIRED,
    UNKNOWN
}

/**
 * Data class for error state with recovery options.
 */
data class RecoverableError(
    val type: ConnectionErrorType,
    val message: String,
    val detail: String? = null,
    val canRetry: Boolean = true,
    val primaryAction: ErrorAction? = null,
    val secondaryAction: ErrorAction? = null
)

data class ErrorAction(
    val label: String,
    val icon: ImageVector? = null,
    val action: () -> Unit
)

/**
 * Full-screen error state with recovery options.
 */
@Composable
fun ConnectionErrorScreen(
    error: RecoverableError,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Error icon
            ErrorIcon(type = error.type)

            Spacer(modifier = Modifier.height(24.dp))

            // Error title
            Text(
                text = getErrorTitle(error.type),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Error message
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Error detail
            error.detail?.let { detail ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Primary action
            error.primaryAction?.let { action ->
                Button(
                    onClick = action.action,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    action.icon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(action.label)
                }
            }

            // Secondary action
            error.secondaryAction?.let { action ->
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = action.action,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    action.icon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(action.label)
                }
            }

            // Back option
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onBack) {
                Text("Go Back")
            }

            // Help section
            Spacer(modifier = Modifier.height(24.dp))
            HelpSection(errorType = error.type)
        }
    }
}

/**
 * Inline error banner for non-blocking errors.
 */
@Composable
fun ErrorBanner(
    message: String,
    onDismiss: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )

            onRetry?.let {
                TextButton(
                    onClick = it,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Retry")
                }
            }

            onDismiss?.let {
                IconButton(onClick = it) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/**
 * Retry confirmation dialog.
 */
@Composable
fun RetryConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null
            )
        },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Retry")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Connection recovery options card.
 */
@Composable
fun RecoveryOptionsCard(
    errorType: ConnectionErrorType,
    onRetry: () -> Unit,
    onCreateNew: () -> Unit,
    onContactSupport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "What would you like to do?",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Retry option (if applicable)
            if (errorType in listOf(
                    ConnectionErrorType.NETWORK,
                    ConnectionErrorType.TIMEOUT,
                    ConnectionErrorType.VAULT_DISCONNECTED
                )
            ) {
                RecoveryOption(
                    icon = Icons.Default.Refresh,
                    title = "Try Again",
                    description = "Retry the connection",
                    onClick = onRetry
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Create new option (for expired/used invitations)
            if (errorType in listOf(
                    ConnectionErrorType.INVITATION_EXPIRED,
                    ConnectionErrorType.INVITATION_ALREADY_USED
                )
            ) {
                RecoveryOption(
                    icon = Icons.Default.Add,
                    title = "Create New Invitation",
                    description = "Generate a fresh invitation",
                    onClick = onCreateNew
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Contact support
            RecoveryOption(
                icon = Icons.Default.Support,
                title = "Contact Support",
                description = "Get help from VettID team",
                onClick = onContactSupport
            )
        }
    }
}

@Composable
private fun RecoveryOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
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
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorIcon(type: ConnectionErrorType) {
    val (icon, color) = when (type) {
        ConnectionErrorType.NETWORK -> Icons.Default.CloudOff to Color(0xFFE65100)
        ConnectionErrorType.TIMEOUT -> Icons.Default.Timer to Color(0xFFFF9800)
        ConnectionErrorType.INVITATION_EXPIRED -> Icons.Default.TimerOff to Color(0xFF9E9E9E)
        ConnectionErrorType.INVITATION_INVALID -> Icons.Default.ErrorOutline to Color(0xFFF44336)
        ConnectionErrorType.INVITATION_ALREADY_USED -> Icons.Default.Block to Color(0xFF9E9E9E)
        ConnectionErrorType.CONNECTION_REVOKED -> Icons.Default.Cancel to Color(0xFFF44336)
        ConnectionErrorType.VAULT_DISCONNECTED -> Icons.Default.LinkOff to Color(0xFFE65100)
        ConnectionErrorType.AUTHENTICATION_REQUIRED -> Icons.Default.Lock to Color(0xFF2196F3)
        ConnectionErrorType.UNKNOWN -> Icons.Default.Error to Color(0xFFF44336)
    }

    Surface(
        modifier = Modifier.size(80.dp),
        shape = RoundedCornerShape(40.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = color
            )
        }
    }
}

private fun getErrorTitle(type: ConnectionErrorType): String {
    return when (type) {
        ConnectionErrorType.NETWORK -> "No Internet Connection"
        ConnectionErrorType.TIMEOUT -> "Connection Timed Out"
        ConnectionErrorType.INVITATION_EXPIRED -> "Invitation Expired"
        ConnectionErrorType.INVITATION_INVALID -> "Invalid Invitation"
        ConnectionErrorType.INVITATION_ALREADY_USED -> "Invitation Already Used"
        ConnectionErrorType.CONNECTION_REVOKED -> "Connection Revoked"
        ConnectionErrorType.VAULT_DISCONNECTED -> "Vault Disconnected"
        ConnectionErrorType.AUTHENTICATION_REQUIRED -> "Authentication Required"
        ConnectionErrorType.UNKNOWN -> "Something Went Wrong"
    }
}

@Composable
private fun HelpSection(errorType: ConnectionErrorType) {
    val helpText = when (errorType) {
        ConnectionErrorType.NETWORK -> "Check your internet connection and try again."
        ConnectionErrorType.TIMEOUT -> "The server took too long to respond. This may be temporary."
        ConnectionErrorType.INVITATION_EXPIRED -> "Ask the sender to create a new invitation."
        ConnectionErrorType.INVITATION_INVALID -> "Make sure you scanned the correct QR code."
        ConnectionErrorType.INVITATION_ALREADY_USED -> "Each invitation can only be used once."
        ConnectionErrorType.CONNECTION_REVOKED -> "This connection has been ended by the other party."
        ConnectionErrorType.VAULT_DISCONNECTED -> "Your vault needs to reconnect. This usually happens automatically."
        ConnectionErrorType.AUTHENTICATION_REQUIRED -> "You need to authenticate to perform this action."
        ConnectionErrorType.UNKNOWN -> "If this persists, please contact support."
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = helpText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Utility function to create recoverable error from exception.
 */
fun createRecoverableError(
    error: Throwable,
    onRetry: () -> Unit,
    onBack: () -> Unit
): RecoverableError {
    val message = error.message ?: "An unknown error occurred"

    val type = when {
        message.contains("network", ignoreCase = true) ||
                message.contains("internet", ignoreCase = true) ||
                message.contains("connection refused", ignoreCase = true) -> ConnectionErrorType.NETWORK

        message.contains("timeout", ignoreCase = true) -> ConnectionErrorType.TIMEOUT

        message.contains("expired", ignoreCase = true) -> ConnectionErrorType.INVITATION_EXPIRED

        message.contains("invalid", ignoreCase = true) ||
                message.contains("malformed", ignoreCase = true) -> ConnectionErrorType.INVITATION_INVALID

        message.contains("already used", ignoreCase = true) ||
                message.contains("already accepted", ignoreCase = true) -> ConnectionErrorType.INVITATION_ALREADY_USED

        message.contains("revoked", ignoreCase = true) -> ConnectionErrorType.CONNECTION_REVOKED

        message.contains("not connected", ignoreCase = true) ||
                message.contains("vault", ignoreCase = true) -> ConnectionErrorType.VAULT_DISCONNECTED

        message.contains("auth", ignoreCase = true) ||
                message.contains("credential", ignoreCase = true) -> ConnectionErrorType.AUTHENTICATION_REQUIRED

        else -> ConnectionErrorType.UNKNOWN
    }

    return RecoverableError(
        type = type,
        message = message,
        canRetry = type in listOf(
            ConnectionErrorType.NETWORK,
            ConnectionErrorType.TIMEOUT,
            ConnectionErrorType.VAULT_DISCONNECTED
        ),
        primaryAction = if (type in listOf(
                ConnectionErrorType.NETWORK,
                ConnectionErrorType.TIMEOUT,
                ConnectionErrorType.VAULT_DISCONNECTED
            )
        ) {
            ErrorAction("Try Again", Icons.Default.Refresh, onRetry)
        } else null,
        secondaryAction = ErrorAction("Go Back", null, onBack)
    )
}
