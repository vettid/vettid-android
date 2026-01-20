package com.vettid.app.features.services.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vettid.app.features.services.models.*
import java.time.format.DateTimeFormatter

/**
 * Expanded view showing all trusted resources for a service.
 *
 * Trusted resources are verified URLs and signed downloads that
 * users can safely access. Each resource includes signature verification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedResourcesView(
    resources: List<TrustedResource>,
    serviceName: String,
    onResourceClick: (TrustedResource) -> Unit = {},
    onDownloadClick: (TrustedResource) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showVerificationDialog by remember { mutableStateOf<TrustedResource?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TrustedResourcesHeader(serviceName = serviceName)
        }

        // Group by type
        val groupedResources = resources.groupBy { it.type }

        groupedResources.forEach { (type, typeResources) ->
            item {
                Text(
                    text = type.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            items(typeResources) { resource ->
                TrustedResourceCard(
                    resource = resource,
                    onClick = {
                        if (resource.type == ResourceType.APP_DOWNLOAD) {
                            onDownloadClick(resource)
                        } else {
                            onResourceClick(resource)
                        }
                    },
                    onVerifyClick = { showVerificationDialog = resource }
                )
            }
        }
    }

    // Verification dialog
    showVerificationDialog?.let { resource ->
        SignatureVerificationDialog(
            resource = resource,
            onDismiss = { showVerificationDialog = null }
        )
    }
}

@Composable
private fun TrustedResourcesHeader(serviceName: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Verified Resources",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "These resources are verified and signed by $serviceName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrustedResourceCard(
    resource: TrustedResource,
    onClick: () -> Unit,
    onVerifyClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Type icon
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = getResourceTypeColor(resource.type).copy(alpha = 0.1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = getResourceTypeIcon(resource.type),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = getResourceTypeColor(resource.type)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = resource.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        // Verified badge
                        if (resource.download?.signatures?.isNotEmpty() == true) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = "Signed",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    resource.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // URL preview
                    Text(
                        text = resource.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Open icon
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "Open",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Download info
            resource.download?.let { download ->
                Spacer(modifier = Modifier.height(12.dp))
                DownloadInfoSection(download = download)
            }

            // Verification link
            if (resource.download?.signatures?.isNotEmpty() == true) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onVerifyClick,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Verify Signature")
                }
            }

            // Timestamps
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Added ${formatDate(resource.addedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (resource.updatedAt != resource.addedAt) {
                    Text(
                        text = "Updated ${formatDate(resource.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadInfoSection(download: DownloadInfo) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Platform badge
                Surface(
                    color = getPlatformColor(download.platform),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = download.platform.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Version
                Text(
                    text = "v${download.version}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Details row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // File size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatFileSize(download.fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Min OS version
                download.minOsVersion?.let { minOs ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Min: $minOs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Signatures
                if (download.signatures.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${download.signatures.size} signature(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // File name
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = download.fileName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SignatureVerificationDialog(
    resource: TrustedResource,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Signature Verification") },
        text = {
            Column {
                Text(
                    text = "This resource is cryptographically signed:",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                resource.download?.signatures?.forEach { signature ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row {
                                Text(
                                    text = "Algorithm:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = signature.algorithm.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row {
                                Text(
                                    text = "Signed by:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = signature.signedBy,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Hash:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = signature.hash.take(32) + "...",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// MARK: - Utility Functions

private fun getResourceTypeIcon(type: ResourceType) = when (type) {
    ResourceType.WEBSITE -> Icons.Default.Language
    ResourceType.APP_DOWNLOAD -> Icons.Default.Download
    ResourceType.DOCUMENT -> Icons.Default.Description
    ResourceType.API -> Icons.Default.Api
}

private fun getResourceTypeColor(type: ResourceType) = when (type) {
    ResourceType.WEBSITE -> Color(0xFF2196F3)
    ResourceType.APP_DOWNLOAD -> Color(0xFF4CAF50)
    ResourceType.DOCUMENT -> Color(0xFFFF9800)
    ResourceType.API -> Color(0xFF9C27B0)
}

private fun getPlatformColor(platform: Platform) = when (platform) {
    Platform.ANDROID -> Color(0xFF3DDC84)
    Platform.IOS -> Color(0xFF007AFF)
    Platform.WINDOWS -> Color(0xFF0078D4)
    Platform.MACOS -> Color(0xFF000000)
    Platform.LINUX -> Color(0xFFFCC624)
}

private fun formatDate(instant: java.time.Instant): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
