package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
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
import java.time.temporal.ChronoUnit

/**
 * Detailed view of an existing service connection.
 *
 * Shows:
 * - Service profile information
 * - Current contract status
 * - Connection health indicators
 * - Activity summary
 * - Data storage summary
 * - Quick actions and navigation to detailed screens
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceConnectionDetailScreen(
    connection: ServiceConnectionRecord?,
    activitySummary: ActivitySummary? = null,
    dataSummary: ServiceDataSummary? = null,
    healthStatus: ServiceConnectionHealth? = null,
    trustIndicators: ServiceTrustIndicators? = null,
    isLoading: Boolean = false,
    error: String? = null,
    onBackClick: () -> Unit = {},
    onViewContract: () -> Unit = {},
    onViewActivity: () -> Unit = {},
    onViewDataTransparency: () -> Unit = {},
    onViewTrustedResources: () -> Unit = {},
    onNotificationSettings: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onRevokeConnection: () -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showRevokeDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(connection?.serviceProfile?.serviceName ?: "Service Details")
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    connection?.let { conn ->
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                imageVector = if (conn.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (conn.isFavorite) "Remove from favorites" else "Add to favorites",
                                tint = if (conn.isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options"
                                )
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (conn.isMuted) "Unmute notifications" else "Mute notifications") },
                                    onClick = {
                                        showMoreMenu = false
                                        onToggleMute()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (conn.isMuted) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Notification settings") },
                                    onClick = {
                                        showMoreMenu = false
                                        onNotificationSettings()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Notifications, contentDescription = null)
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Revoke connection", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMoreMenu = false
                                        showRevokeDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.LinkOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
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
            connection != null -> {
                ServiceConnectionDetailContent(
                    connection = connection,
                    activitySummary = activitySummary,
                    dataSummary = dataSummary,
                    healthStatus = healthStatus,
                    trustIndicators = trustIndicators,
                    onViewContract = onViewContract,
                    onViewActivity = onViewActivity,
                    onViewDataTransparency = onViewDataTransparency,
                    onViewTrustedResources = onViewTrustedResources,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    // Revoke confirmation dialog
    if (showRevokeDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Revoke Connection?") },
            text = {
                Text(
                    "This will permanently revoke your connection to ${connection?.serviceProfile?.serviceName}. " +
                    "They will no longer be able to access any of your data or send you requests. " +
                    "Any data they have stored in your sandbox will be deleted."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeDialog = false
                        onRevokeConnection()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ServiceConnectionDetailContent(
    connection: ServiceConnectionRecord,
    activitySummary: ActivitySummary?,
    dataSummary: ServiceDataSummary?,
    healthStatus: ServiceConnectionHealth?,
    trustIndicators: ServiceTrustIndicators?,
    onViewContract: () -> Unit,
    onViewActivity: () -> Unit,
    onViewDataTransparency: () -> Unit,
    onViewTrustedResources: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profile = connection.serviceProfile

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Service header
        item {
            ServiceHeaderCard(
                profile = profile,
                connection = connection
            )
        }

        // Connection health (if available)
        healthStatus?.let {
            item {
                ConnectionHealthCard(health = it)
            }
        }

        // Trust indicators
        trustIndicators?.let {
            item {
                TrustIndicatorsCard(indicators = it)
            }
        }

        // Contract status
        item {
            ContractStatusCard(
                connection = connection,
                onViewContract = onViewContract
            )
        }

        // Quick stats
        item {
            QuickStatsRow(
                activitySummary = activitySummary,
                dataSummary = dataSummary
            )
        }

        // Activity summary
        activitySummary?.let {
            item {
                ActivitySummaryCard(
                    summary = it,
                    onViewAll = onViewActivity
                )
            }
        }

        // Data storage summary
        dataSummary?.let {
            item {
                DataStorageSummaryCard(
                    summary = it,
                    onViewAll = onViewDataTransparency
                )
            }
        }

        // Trusted resources (if any)
        if (profile.trustedResources.isNotEmpty()) {
            item {
                TrustedResourcesPreviewCard(
                    resources = profile.trustedResources,
                    onViewAll = onViewTrustedResources
                )
            }
        }

        // Connection info
        item {
            ConnectionInfoCard(connection = connection)
        }

        // Tags
        if (connection.tags.isNotEmpty()) {
            item {
                TagsCard(tags = connection.tags)
            }
        }
    }
}

// MARK: - Service Header Card

@Composable
private fun ServiceHeaderCard(
    profile: ServiceProfile,
    connection: ServiceConnectionRecord
) {
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
                    .size(72.dp)
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
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Name and verification
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = profile.serviceName,
                    style = MaterialTheme.typography.titleLarge,
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

            // Organization
            Text(
                text = profile.organization.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Category and status badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = profile.serviceCategory.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                StatusBadge(status = connection.status)
            }

            // Muted indicator
            if (connection.isMuted) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsOff,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Notifications muted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ServiceConnectionStatus) {
    val (color, text) = when (status) {
        ServiceConnectionStatus.ACTIVE -> MaterialTheme.colorScheme.primary to "Active"
        ServiceConnectionStatus.PENDING_CONTRACT_UPDATE -> MaterialTheme.colorScheme.tertiary to "Update Available"
        ServiceConnectionStatus.SUSPENDED -> MaterialTheme.colorScheme.error to "Suspended"
        ServiceConnectionStatus.REVOKED -> MaterialTheme.colorScheme.error to "Revoked"
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

// MARK: - Connection Health Card

@Composable
private fun ConnectionHealthCard(health: ServiceConnectionHealth) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = health.status.color.let { Color(it) }.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (health.status) {
                        HealthStatus.HEALTHY -> Icons.Default.CheckCircle
                        HealthStatus.WARNING -> Icons.Default.Warning
                        HealthStatus.CRITICAL -> Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = Color(health.status.color)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connection Health: ${health.status.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }

            if (health.issues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                health.issues.forEach { issue ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = issue,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Usage indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Request usage
                Column {
                    Text(
                        text = "Requests",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${health.requestsThisHour}/${health.requestLimit}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Storage usage
                Column {
                    Text(
                        text = "Storage",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${formatBytes(health.dataStorageUsed)}/${formatBytes(health.dataStorageLimit)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// MARK: - Trust Indicators Card

@Composable
private fun TrustIndicatorsCard(indicators: ServiceTrustIndicators) {
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
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Trust Indicators",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Trust indicators grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TrustIndicatorItem(
                    icon = Icons.Default.Verified,
                    label = "Verified",
                    value = if (indicators.organizationVerified) "Yes" else "No",
                    isPositive = indicators.organizationVerified
                )
                TrustIndicatorItem(
                    icon = Icons.Default.Timer,
                    label = "Age",
                    value = "${indicators.connectionAge}d",
                    isPositive = indicators.connectionAge > 30
                )
                TrustIndicatorItem(
                    icon = Icons.Default.SyncAlt,
                    label = "Interactions",
                    value = "${indicators.totalInteractions}",
                    isPositive = true
                )
            }

            // Warnings
            if (indicators.hasExcessiveRequests || indicators.contractViolations > 0 || indicators.rateLimitViolations > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                if (indicators.hasExcessiveRequests) {
                    WarningRow("High request volume detected")
                }
                if (indicators.rateLimitViolations > 0) {
                    WarningRow("${indicators.rateLimitViolations} rate limit violation(s)")
                }
                if (indicators.contractViolations > 0) {
                    WarningRow("${indicators.contractViolations} contract violation(s)")
                }
            }
        }
    }
}

@Composable
private fun TrustIndicatorItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    isPositive: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isPositive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WarningRow(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

// MARK: - Contract Status Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContractStatusCard(
    connection: ServiceConnectionRecord,
    onViewContract: () -> Unit
) {
    Card(
        onClick = onViewContract,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Data Contract",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Version ${connection.contractVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Accepted ${formatDate(connection.contractAcceptedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (connection.pendingContractVersion != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "Update v${connection.pendingContractVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// MARK: - Quick Stats Row

@Composable
private fun QuickStatsRow(
    activitySummary: ActivitySummary?,
    dataSummary: ServiceDataSummary?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QuickStatCard(
            icon = Icons.Outlined.Sync,
            value = "${activitySummary?.activityThisMonth ?: 0}",
            label = "This Month",
            modifier = Modifier.weight(1f)
        )
        QuickStatCard(
            icon = Icons.Outlined.Storage,
            value = "${dataSummary?.totalItems ?: 0}",
            label = "Items Stored",
            modifier = Modifier.weight(1f)
        )
        QuickStatCard(
            icon = Icons.Outlined.Payment,
            value = activitySummary?.totalPaymentAmount?.formatted() ?: "$0",
            label = "Total Paid",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickStatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// MARK: - Activity Summary Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivitySummaryCard(
    summary: ActivitySummary,
    onViewAll: () -> Unit
) {
    Card(
        onClick = onViewAll,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timeline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Activity Summary",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onViewAll) {
                    Text("View All")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ActivityStatItem("Data Requests", "${summary.totalDataRequests}")
                ActivityStatItem("Data Stored", "${summary.totalDataStored}")
                ActivityStatItem("Auth Requests", "${summary.totalAuthRequests}")
                ActivityStatItem("Payments", "${summary.totalPayments}")
            }

            summary.lastActivityAt?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Last activity: ${formatRelativeTime(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActivityStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// MARK: - Data Storage Summary Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataStorageSummaryCard(
    summary: ServiceDataSummary,
    onViewAll: () -> Unit
) {
    Card(
        onClick = onViewAll,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Data Transparency",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onViewAll) {
                    Text("View All")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "${summary.totalItems}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Items",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column {
                    Text(
                        text = formatBytes(summary.totalSizeBytes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Storage Used",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column {
                    Text(
                        text = "${summary.categories.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Categories",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// MARK: - Trusted Resources Preview Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrustedResourcesPreviewCard(
    resources: List<TrustedResource>,
    onViewAll: () -> Unit
) {
    Card(
        onClick = onViewAll,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Verified,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Trusted Resources",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onViewAll) {
                    Text("View All (${resources.size})")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(resources.take(3)) { resource ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when (resource.type) {
                                    ResourceType.WEBSITE -> Icons.Default.Language
                                    ResourceType.APP_DOWNLOAD -> Icons.Default.Download
                                    ResourceType.DOCUMENT -> Icons.Default.Description
                                    ResourceType.API -> Icons.Default.Api
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = resource.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Connection Info Card

@Composable
private fun ConnectionInfoCard(connection: ServiceConnectionRecord) {
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
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connection Info",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            InfoRow("Connection ID", connection.connectionId.take(16) + "...")
            InfoRow("Connected", formatDate(connection.createdAt))
            connection.lastActiveAt?.let {
                InfoRow("Last Active", formatRelativeTime(it))
            }
            InfoRow("Service Profile Version", "v${connection.serviceProfile.profileVersion}")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// MARK: - Tags Card

@Composable
private fun TagsCard(tags: List<String>) {
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
                    imageVector = Icons.AutoMirrored.Outlined.Label,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tags",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tags) { tag ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Loading and Error

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

private fun formatRelativeTime(instant: java.time.Instant): String {
    val now = java.time.Instant.now()
    val days = ChronoUnit.DAYS.between(instant, now)
    val hours = ChronoUnit.HOURS.between(instant, now)
    val minutes = ChronoUnit.MINUTES.between(instant, now)

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> formatDate(instant)
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
