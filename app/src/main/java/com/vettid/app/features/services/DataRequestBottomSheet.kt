package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.vettid.app.features.services.models.*

/**
 * Bottom sheet for data access requests from services.
 *
 * Shows:
 * - Service info
 * - Requested data type
 * - Purpose and retention
 * - Share options (once, add to contract)
 *
 * Issue #35 [AND-022] - Data request prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataRequestBottomSheet(
    request: DataAccessRequest,
    onShareOnce: () -> Unit,
    onAddToContract: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit
) {
    var isProcessing by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Service header
            ServiceHeader(
                serviceName = request.serviceName,
                serviceLogoUrl = request.serviceLogoUrl
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Requesting access to:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Data item card
            DataItemCard(
                dataType = request.dataType,
                purpose = request.purpose,
                retention = request.retention
            )

            // Urgency indicator
            if (request.urgency == RequestUrgency.HIGH) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Time-sensitive request",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "How would you like to share?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Share options
            ShareOptionCard(
                icon = Icons.Outlined.Share,
                title = "Share Once",
                description = "One-time access only. ${request.serviceName} won't be able to request this data again without your approval.",
                onClick = {
                    isProcessing = true
                    onShareOnce()
                },
                enabled = !isProcessing
            )

            Spacer(modifier = Modifier.height(12.dp))

            ShareOptionCard(
                icon = Icons.Outlined.AddCircleOutline,
                title = "Add to Contract",
                description = "Allow ongoing access until you disconnect from ${request.serviceName}.",
                onClick = {
                    isProcessing = true
                    onAddToContract()
                },
                enabled = !isProcessing
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Deny button
            TextButton(
                onClick = {
                    isProcessing = true
                    onDeny()
                },
                enabled = !isProcessing
            ) {
                Text(
                    text = "Deny",
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Processing indicator
            if (isProcessing) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ServiceHeader(
    serviceName: String,
    serviceLogoUrl: String?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (serviceLogoUrl != null) {
                AsyncImage(
                    model = serviceLogoUrl,
                    contentDescription = serviceName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Business,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = serviceName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DataItemCard(
    dataType: DataType,
    purpose: String,
    retention: String
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = dataTypeIcon(dataType),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dataType.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Purpose: $purpose",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Retention: $retention",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = if (enabled) 1f else 0.5f
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
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

/**
 * Get icon for a data type.
 */
private fun dataTypeIcon(dataType: DataType): androidx.compose.ui.graphics.vector.ImageVector {
    return when (dataType) {
        DataType.FULL_NAME -> Icons.Outlined.Person
        DataType.EMAIL -> Icons.Outlined.Email
        DataType.PHONE -> Icons.Outlined.Phone
        DataType.ADDRESS -> Icons.Outlined.LocationOn
        DataType.DATE_OF_BIRTH -> Icons.Outlined.Cake
        DataType.GOVERNMENT_ID -> Icons.Outlined.Badge
        DataType.PAYMENT_INFO -> Icons.Outlined.CreditCard
        DataType.HEALTH_DATA -> Icons.Outlined.Favorite
        DataType.LOCATION -> Icons.Outlined.MyLocation
        DataType.PHOTO -> Icons.Outlined.PhotoCamera
        DataType.CUSTOM -> Icons.Outlined.Description
    }
}
