package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vettid.app.features.services.models.*
import java.time.format.DateTimeFormatter

/**
 * Screen for reviewing a service's data contract before accepting.
 *
 * Shows detailed breakdown of:
 * - Required and optional data fields with purposes
 * - Data retention policies
 * - Permissions requested
 * - Rate limits and storage limits
 * - Terms and privacy policy links
 *
 * For contract updates, shows a diff of changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractReviewScreen(
    contract: ServiceDataContract?,
    contractUpdate: ContractUpdate? = null,
    serviceName: String = "",
    isLoading: Boolean = false,
    isAccepting: Boolean = false,
    error: String? = null,
    onBackClick: () -> Unit = {},
    onAcceptClick: () -> Unit = {},
    onDeclineClick: () -> Unit = {},
    onTermsClick: () -> Unit = {},
    onPrivacyClick: () -> Unit = {}
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (contractUpdate != null) "Contract Update" else "Review Contract",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (serviceName.isNotEmpty()) {
                            Text(
                                text = serviceName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (contract != null && !isLoading) {
                ContractActionBar(
                    isUpdate = contractUpdate != null,
                    isAccepting = isAccepting,
                    onAcceptClick = onAcceptClick,
                    onDeclineClick = onDeclineClick
                )
            }
        }
    ) { padding ->
        when {
            isLoading -> {
                LoadingContent(modifier = Modifier.padding(padding))
            }
            error != null -> {
                ErrorContent(
                    message = error,
                    modifier = Modifier.padding(padding)
                )
            }
            contract != null -> {
                ContractReviewContent(
                    contract = contract,
                    contractUpdate = contractUpdate,
                    onTermsClick = onTermsClick,
                    onPrivacyClick = onPrivacyClick,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun ContractReviewContent(
    contract: ServiceDataContract,
    contractUpdate: ContractUpdate?,
    onTermsClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Contract header
        item {
            ContractHeaderCard(contract = contract, update = contractUpdate)
        }

        // Update changes summary (if this is an update)
        if (contractUpdate != null) {
            item {
                ContractChangesCard(changes = contractUpdate.changes)
            }
        }

        // Required fields
        if (contract.requiredFields.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Required Data",
                    subtitle = "This service requires the following data",
                    icon = Icons.AutoMirrored.Outlined.Assignment,
                    color = MaterialTheme.colorScheme.error
                )
            }

            items(contract.requiredFields) { field ->
                FieldCard(
                    field = field,
                    isRequired = true,
                    isNew = contractUpdate?.changes?.addedFields?.any { it.field == field.field } == true
                )
            }
        }

        // Optional fields
        if (contract.optionalFields.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Optional Data",
                    subtitle = "You can choose whether to share this data",
                    icon = Icons.Outlined.Tune,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            items(contract.optionalFields) { field ->
                FieldCard(
                    field = field,
                    isRequired = false,
                    isNew = contractUpdate?.changes?.addedFields?.any { it.field == field.field } == true
                )
            }
        }

        // On-demand fields
        if (contract.onDemandFields.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "On-Demand Requests",
                    subtitle = "Service may request this data when needed",
                    icon = Icons.Outlined.AccessTime,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            item {
                OnDemandFieldsCard(fields = contract.onDemandFields)
            }
        }

        // Permissions
        item {
            PermissionsCard(contract = contract)
        }

        // Rate limits
        if (contract.maxRequestsPerHour != null || contract.maxStorageMb != null) {
            item {
                LimitsCard(contract = contract)
            }
        }

        // Legal links
        if (contract.termsUrl != null || contract.privacyUrl != null) {
            item {
                LegalLinksCard(
                    termsUrl = contract.termsUrl,
                    privacyUrl = contract.privacyUrl,
                    onTermsClick = onTermsClick,
                    onPrivacyClick = onPrivacyClick
                )
            }
        }

        // Expiration warning
        contract.expiresAt?.let { expiresAt ->
            item {
                ExpirationWarningCard(expiresAt = expiresAt)
            }
        }

        // Bottom padding for action bar
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

// MARK: - Contract Header Card

@Composable
private fun ContractHeaderCard(
    contract: ServiceDataContract,
    update: ContractUpdate?
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
                    text = contract.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "v${contract.version}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = contract.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (update != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Update,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Contract Update: v${update.previousVersion} → v${update.newVersion}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = update.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                            )
                            update.requiredBy?.let { requiredBy ->
                                Text(
                                    text = "Required by ${formatDate(requiredBy)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Contract Changes Card

@Composable
private fun ContractChangesCard(changes: ContractChanges) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "What's Changed",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Added fields
            if (changes.addedFields.isNotEmpty()) {
                ChangeRow(
                    icon = Icons.Default.Add,
                    color = Color(0xFF4CAF50),
                    label = "New data requested:",
                    items = changes.addedFields.map { it.field }
                )
            }

            // Removed fields
            if (changes.removedFields.isNotEmpty()) {
                ChangeRow(
                    icon = Icons.Default.Remove,
                    color = Color(0xFFF44336),
                    label = "Data no longer requested:",
                    items = changes.removedFields
                )
            }

            // Changed fields
            if (changes.changedFields.isNotEmpty()) {
                ChangeRow(
                    icon = Icons.Default.Edit,
                    color = Color(0xFFFF9800),
                    label = "Changed data usage:",
                    items = changes.changedFields.map { it.field }
                )
            }

            // Permission changes
            if (changes.permissionChanges.isNotEmpty()) {
                ChangeRow(
                    icon = Icons.Default.Security,
                    color = MaterialTheme.colorScheme.primary,
                    label = "Permission changes:",
                    items = changes.permissionChanges
                )
            }

            // Rate limit changes
            changes.rateLimitChanges?.let { rateChange ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Rate limits: $rateChange",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    label: String,
    items: List<String>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = items.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// MARK: - Section Header

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = color
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 28.dp)
        )
    }
}

// MARK: - Field Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldCard(
    field: FieldSpec,
    isRequired: Boolean,
    isNew: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isNew) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Field icon
            Icon(
                imageVector = if (isRequired) Icons.Default.Star else Icons.Outlined.StarBorder,
                contentDescription = if (isRequired) "Required" else "Optional",
                modifier = Modifier.size(16.dp),
                tint = if (isRequired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = field.field,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (isNew) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "NEW",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Text(
                    text = field.purpose,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Retention badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatRetention(field.retention),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// MARK: - On-Demand Fields Card

@Composable
private fun OnDemandFieldsCard(fields: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            fields.forEach { field ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = field,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "You'll be asked to approve each request individually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// MARK: - Permissions Card

@Composable
private fun PermissionsCard(contract: ServiceDataContract) {
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
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Permissions",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            PermissionRow(
                icon = Icons.Outlined.Save,
                label = "Store data in your sandbox",
                enabled = contract.canStoreData,
                detail = if (contract.canStoreData && contract.storageCategories.isNotEmpty()) {
                    "Categories: ${contract.storageCategories.joinToString(", ")}"
                } else null
            )

            PermissionRow(
                icon = Icons.AutoMirrored.Outlined.Message,
                label = "Send you messages",
                enabled = contract.canSendMessages
            )

            PermissionRow(
                icon = Icons.Outlined.VpnKey,
                label = "Request authentication",
                enabled = contract.canRequestAuth
            )

            PermissionRow(
                icon = Icons.Outlined.Payment,
                label = "Request payments",
                enabled = contract.canRequestPayment
            )
        }
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    detail: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = if (!enabled) TextDecoration.LineThrough else null
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = if (enabled) Icons.Default.Check else Icons.Default.Close,
            contentDescription = if (enabled) "Allowed" else "Not allowed",
            modifier = Modifier.size(16.dp),
            tint = if (enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

// MARK: - Limits Card

@Composable
private fun LimitsCard(contract: ServiceDataContract) {
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
                    imageVector = Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Limits",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            contract.maxRequestsPerHour?.let { limit ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Max $limit requests per hour",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            contract.maxStorageMb?.let { limit ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Max ${limit}MB storage",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// MARK: - Legal Links Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalLinksCard(
    termsUrl: String?,
    privacyUrl: String?,
    onTermsClick: () -> Unit,
    onPrivacyClick: () -> Unit
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
                    imageVector = Icons.Outlined.Gavel,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Legal Documents",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            termsUrl?.let {
                OutlinedButton(
                    onClick = onTermsClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Terms of Service")
                }
            }

            privacyUrl?.let {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onPrivacyClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Privacy Policy")
                }
            }
        }
    }
}

// MARK: - Expiration Warning Card

@Composable
private fun ExpirationWarningCard(expiresAt: java.time.Instant) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Contract Expiration",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "This contract expires on ${formatDate(expiresAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

// MARK: - Action Bar

@Composable
private fun ContractActionBar(
    isUpdate: Boolean,
    isAccepting: Boolean,
    onAcceptClick: () -> Unit,
    onDeclineClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDeclineClick,
                modifier = Modifier.weight(1f),
                enabled = !isAccepting
            ) {
                Text(if (isUpdate) "Decline Update" else "Decline")
            }

            Button(
                onClick = onAcceptClick,
                modifier = Modifier.weight(1f),
                enabled = !isAccepting
            ) {
                if (isAccepting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isUpdate) "Accept Update" else "Accept & Connect")
                }
            }
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
        }
    }
}

// MARK: - Utility Functions

private fun formatDate(instant: java.time.Instant): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun formatRetention(retention: String): String {
    return when (retention) {
        "session" -> "Kept for session only"
        "until_revoked" -> "Kept until you revoke"
        "30_days" -> "Deleted after 30 days"
        "90_days" -> "Deleted after 90 days"
        "1_year" -> "Deleted after 1 year"
        else -> retention
    }
}
