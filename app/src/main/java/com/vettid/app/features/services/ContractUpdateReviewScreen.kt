package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vettid.app.features.services.models.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Screen for reviewing and approving contract updates.
 *
 * Shows:
 * - Current vs proposed contract comparison
 * - Highlighted changes (added, removed, changed)
 * - Approve/Reject actions
 * - Expiration countdown
 *
 * Issue #39 [AND-030] - Contract Update Review UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractUpdateReviewScreen(
    contractId: String,
    viewModel: ContractUpdateReviewViewModel = hiltViewModel(),
    onApproved: () -> Unit = {},
    onRejected: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    var showRejectDialog by remember { mutableStateOf(false) }

    // Load update on first composition
    LaunchedEffect(contractId) {
        viewModel.loadUpdate(contractId)
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ContractUpdateEffect.Approved -> onApproved()
                is ContractUpdateEffect.Rejected -> onRejected()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contract Update") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        },
        bottomBar = {
            if (state is ContractUpdateReviewState.Loaded) {
                UpdateActionBar(
                    isProcessing = isProcessing,
                    onApprove = { viewModel.approve() },
                    onReject = { showRejectDialog = true }
                )
            }
        }
    ) { padding ->
        when (val currentState = state) {
            is ContractUpdateReviewState.Loading -> {
                LoadingContent(modifier = Modifier.padding(padding))
            }
            is ContractUpdateReviewState.Loaded -> {
                UpdateReviewContent(
                    update = currentState.update,
                    modifier = Modifier.padding(padding)
                )
            }
            is ContractUpdateReviewState.Error -> {
                ErrorContent(
                    message = currentState.message,
                    onRetry = { viewModel.loadUpdate(contractId) },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    // Reject confirmation dialog
    if (showRejectDialog) {
        RejectConfirmationDialog(
            serviceName = (state as? ContractUpdateReviewState.Loaded)?.update?.serviceName ?: "",
            onConfirm = { reason ->
                showRejectDialog = false
                viewModel.reject(reason)
            },
            onDismiss = { showRejectDialog = false }
        )
    }
}

@Composable
private fun UpdateReviewContent(
    update: ContractUpdateData,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Service header
        item {
            ServiceUpdateHeader(
                serviceName = update.serviceName,
                serviceLogoUrl = update.serviceLogoUrl,
                currentVersion = update.currentVersion,
                proposedVersion = update.proposedVersion,
                expiresAt = update.expiresAt
            )
        }

        // Summary
        item {
            UpdateSummaryCard(changes = update.changes)
        }

        // Added fields
        if (update.changes.addedFields.isNotEmpty()) {
            item {
                Text(
                    text = "New Data Requested",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
            }

            items(update.changes.addedFields) { field ->
                FieldChangeCard(
                    field = field,
                    changeType = ChangeType.ADDED
                )
            }
        }

        // Removed fields
        if (update.changes.removedFields.isNotEmpty()) {
            item {
                Text(
                    text = "Data No Longer Requested",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }

            items(update.changes.removedFields) { field ->
                RemovedFieldCard(fieldName = field)
            }
        }

        // Changed fields
        if (update.changes.changedFields.isNotEmpty()) {
            item {
                Text(
                    text = "Modified Data Terms",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            items(update.changes.changedFields) { field ->
                FieldChangeCard(
                    field = field,
                    changeType = ChangeType.CHANGED
                )
            }
        }

        // Permission changes
        if (update.changes.permissionChanges.isNotEmpty()) {
            item {
                Text(
                    text = "Permission Changes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(update.changes.permissionChanges) { change ->
                PermissionChangeCard(change = change)
            }
        }

        // Rate limit changes
        update.changes.rateLimitChanges?.let { change ->
            item {
                RateLimitChangeCard(change = change)
            }
        }

        // Reason for update
        if (update.reason.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Why this update?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = update.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Spacer for bottom bar
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun ServiceUpdateHeader(
    serviceName: String,
    serviceLogoUrl: String?,
    currentVersion: Int,
    proposedVersion: Int,
    expiresAt: Instant?
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
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
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = serviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Contract Update",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Version comparison
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "v$currentVersion",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(16.dp))

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "v$proposedVersion",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Expiration warning
            expiresAt?.let { expires ->
                Spacer(modifier = Modifier.height(12.dp))
                val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a")
                    .withZone(ZoneId.systemDefault())

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
                            text = "Expires ${formatter.format(expires)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateSummaryCard(changes: ContractChanges) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (changes.addedFields.isNotEmpty()) {
                SummaryItem(
                    count = changes.addedFields.size,
                    label = "Added",
                    color = Color(0xFF4CAF50)
                )
            }
            if (changes.removedFields.isNotEmpty()) {
                SummaryItem(
                    count = changes.removedFields.size,
                    label = "Removed",
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (changes.changedFields.isNotEmpty()) {
                SummaryItem(
                    count = changes.changedFields.size,
                    label = "Changed",
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (changes.permissionChanges.isNotEmpty()) {
                SummaryItem(
                    count = changes.permissionChanges.size,
                    label = "Permissions",
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun SummaryItem(
    count: Int,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FieldChangeCard(
    field: FieldSpec,
    changeType: ChangeType
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (changeType) {
                ChangeType.ADDED -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                ChangeType.CHANGED -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                ChangeType.REMOVED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = when (changeType) {
                    ChangeType.ADDED -> Icons.Default.AddCircle
                    ChangeType.CHANGED -> Icons.Default.Edit
                    ChangeType.REMOVED -> Icons.Default.RemoveCircle
                },
                contentDescription = null,
                tint = when (changeType) {
                    ChangeType.ADDED -> Color(0xFF4CAF50)
                    ChangeType.CHANGED -> MaterialTheme.colorScheme.tertiary
                    ChangeType.REMOVED -> MaterialTheme.colorScheme.error
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = field.field,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Purpose: ${field.purpose}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Retention: ${field.retention}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RemovedFieldCard(fieldName: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.RemoveCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = fieldName,
                style = MaterialTheme.typography.titleSmall,
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionChangeCard(change: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = change,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun RateLimitChangeCard(change: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = "Rate Limit Change",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = change,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UpdateActionBar(
    isProcessing: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.weight(1f),
                enabled = !isProcessing,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Decline")
            }

            Button(
                onClick = onApprove,
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Accept Update")
                }
            }
        }
    }
}

@Composable
private fun RejectConfirmationDialog(
    serviceName: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Decline Update?") },
        text = {
            Column {
                Text(
                    "This will decline the contract update from $serviceName. " +
                    "The service may restrict functionality until you accept a future update."
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason.ifEmpty { null }) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Decline")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private enum class ChangeType {
    ADDED, CHANGED, REMOVED
}

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
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Failed to load update",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

// Data class for update review
data class ContractUpdateData(
    val contractId: String,
    val serviceName: String,
    val serviceLogoUrl: String?,
    val currentVersion: Int,
    val proposedVersion: Int,
    val changes: ContractChanges,
    val reason: String,
    val expiresAt: Instant?
)
