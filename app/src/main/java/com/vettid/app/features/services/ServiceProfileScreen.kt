package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vettid.app.features.services.models.*
import java.time.format.DateTimeFormatter

/**
 * Screen displaying a service profile for discovery.
 *
 * Shows service details, organization verification, trusted resources,
 * and the current contract before allowing the user to connect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceProfileScreen(
    serviceProfile: ServiceProfile?,
    isLoading: Boolean = false,
    isConnecting: Boolean = false,
    error: String? = null,
    onBackClick: () -> Unit = {},
    onConnectClick: () -> Unit = {},
    onTrustedResourceClick: (TrustedResource) -> Unit = {},
    onViewContract: () -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(serviceProfile?.serviceName ?: "Service Profile")
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                LoadingContent(modifier = Modifier.padding(padding))
            }
            error != null -> {
                ErrorContent(
                    message = error,
                    onRetry = onRefresh,
                    modifier = Modifier.padding(padding)
                )
            }
            serviceProfile != null -> {
                ServiceProfileContent(
                    profile = serviceProfile,
                    isConnecting = isConnecting,
                    onConnectClick = onConnectClick,
                    onTrustedResourceClick = onTrustedResourceClick,
                    onViewContract = onViewContract,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun ServiceProfileContent(
    profile: ServiceProfile,
    isConnecting: Boolean,
    onConnectClick: () -> Unit,
    onTrustedResourceClick: (TrustedResource) -> Unit,
    onViewContract: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Service header
        item {
            ServiceHeaderCard(profile = profile)
        }

        // Organization verification
        item {
            OrganizationCard(organization = profile.organization)
        }

        // Contact info
        item {
            ContactInfoCard(contactInfo = profile.contactInfo)
        }

        // Contract summary
        item {
            ContractSummaryCard(
                contract = profile.currentContract,
                onViewDetails = onViewContract
            )
        }

        // Trusted resources
        if (profile.trustedResources.isNotEmpty()) {
            item {
                Text(
                    text = "Trusted Resources",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(profile.trustedResources) { resource ->
                TrustedResourceCard(
                    resource = resource,
                    onClick = { onTrustedResourceClick(resource) }
                )
            }
        }

        // Connect button
        item {
            Spacer(modifier = Modifier.height(8.dp))
            ConnectButton(
                isConnecting = isConnecting,
                onClick = onConnectClick
            )
        }

        // Footer info
        item {
            Text(
                text = "By connecting, you agree to review the service's data contract.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }
    }
}

// MARK: - Service Header Card

@Composable
private fun ServiceHeaderCard(profile: ServiceProfile) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (profile.serviceLogoUrl != null) {
                    AsyncImage(
                        model = profile.serviceLogoUrl,
                        contentDescription = profile.serviceName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Business,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Name and verification
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = profile.serviceName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                if (profile.organization.verified) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = "Verified",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Category badge
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = profile.serviceCategory.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Description
            Text(
                text = profile.serviceDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// MARK: - Organization Card

@Composable
private fun OrganizationCard(organization: OrganizationInfo) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Business,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Organization",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Organization name
            Text(
                text = organization.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            // Verification status
            if (organization.verified && organization.verificationType != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = organization.verificationType.badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        organization.verifiedAt?.let { verifiedAt ->
                            Text(
                                text = "Verified ${formatDate(verifiedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Registration details
            if (organization.registrationId != null || organization.country != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    organization.registrationId?.let {
                        Text(
                            text = "Reg: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    organization.country?.let {
                        if (organization.registrationId != null) {
                            Text(" • ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Contact Info Card

@Composable
private fun ContactInfoCard(contactInfo: ServiceContactInfo) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContactMail,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Contact",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Support contacts
            contactInfo.supportEmail?.let { email ->
                ContactRow(
                    icon = Icons.Outlined.Email,
                    label = "Support Email",
                    value = email,
                    isVerified = true
                )
            }

            contactInfo.supportPhone?.let { phone ->
                ContactRow(
                    icon = Icons.Outlined.Phone,
                    label = "Support Phone",
                    value = phone,
                    isVerified = true
                )
            }

            contactInfo.supportUrl?.let { url ->
                ContactRow(
                    icon = Icons.Default.OpenInNew,
                    label = "Support Website",
                    value = url,
                    isVerified = false
                )
            }

            // Physical address
            contactInfo.address?.let { address ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = address.street,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "${address.city}${address.state?.let { ", $it" } ?: ""} ${address.postalCode}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = address.country,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    isVerified: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isVerified) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Verified",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// MARK: - Contract Summary Card

@Composable
private fun ContractSummaryCard(
    contract: ServiceDataContract,
    onViewDetails: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Data Contract",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onViewDetails) {
                    Text("View Details")
                }
            }

            Text(
                text = contract.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = contract.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Required data summary
            if (contract.requiredFields.isNotEmpty()) {
                Text(
                    text = "Required Data",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    contract.requiredFields.take(4).forEach { field ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = field.field,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (contract.requiredFields.size > 4) {
                        Text(
                            text = "+${contract.requiredFields.size - 4} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Optional data summary
            if (contract.optionalFields.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Optional Data",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    contract.optionalFields.take(4).forEach { field ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = field.field,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (contract.optionalFields.size > 4) {
                        Text(
                            text = "+${contract.optionalFields.size - 4} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Permissions summary
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PermissionIndicator(
                    enabled = contract.canStoreData,
                    label = "Store Data",
                    icon = Icons.Outlined.Save
                )
                PermissionIndicator(
                    enabled = contract.canSendMessages,
                    label = "Messages",
                    icon = Icons.Outlined.Message
                )
                PermissionIndicator(
                    enabled = contract.canRequestAuth,
                    label = "Auth",
                    icon = Icons.Outlined.VpnKey
                )
                PermissionIndicator(
                    enabled = contract.canRequestPayment,
                    label = "Payment",
                    icon = Icons.Outlined.Payment
                )
            }
        }
    }
}

@Composable
private fun PermissionIndicator(
    enabled: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

// MARK: - Trusted Resource Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrustedResourceCard(
    resource: TrustedResource,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Resource type icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getResourceTypeIcon(resource.type),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resource.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                resource.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Download info for app downloads
                resource.download?.let { download ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = "v${download.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = " • ${formatFileSize(download.fileSize)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (download.signatures.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = "Signed",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = "Open",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getResourceTypeIcon(type: ResourceType) = when (type) {
    ResourceType.WEBSITE -> Icons.Default.Language
    ResourceType.APP_DOWNLOAD -> Icons.Default.Download
    ResourceType.DOCUMENT -> Icons.Default.Description
    ResourceType.API -> Icons.Default.Api
}

// MARK: - Connect Button

@Composable
private fun ConnectButton(
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = !isConnecting
    ) {
        if (isConnecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Connecting...")
        } else {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Connect to Service")
        }
    }
}

// MARK: - Loading and Error Content

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
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
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

// MARK: - Utility Functions

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
