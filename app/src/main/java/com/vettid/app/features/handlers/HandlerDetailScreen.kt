package com.vettid.app.features.handlers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vettid.app.core.network.HandlerDetailResponse
import com.vettid.app.core.network.HandlerPermission
import kotlinx.coroutines.flow.collectLatest

/**
 * Screen for viewing handler details.
 *
 * Features:
 * - Handler header with icon and metadata
 * - Description and changelog
 * - Permissions list
 * - Install/Uninstall/Execute actions
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandlerDetailScreen(
    viewModel: HandlerDetailViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onNavigateToExecution: (String) -> Unit = {},
    onRequireAuth: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isInstalling by viewModel.isInstalling.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is HandlerDetailEffect.RequireAuth -> onRequireAuth()
                is HandlerDetailEffect.NavigateBack -> onNavigateBack()
                is HandlerDetailEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is HandlerDetailEffect.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        actionLabel = "Dismiss"
                    )
                }
                is HandlerDetailEffect.NavigateToExecution -> {
                    onNavigateToExecution(effect.handlerId)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Handler Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (val currentState = state) {
            is HandlerDetailState.Loading -> LoadingContent(
                modifier = Modifier.padding(padding)
            )
            is HandlerDetailState.Loaded -> HandlerDetailContent(
                handler = currentState.handler,
                isInstalling = isInstalling,
                onInstall = { viewModel.installHandler() },
                onUninstall = { viewModel.uninstallHandler() },
                onExecute = { viewModel.executeHandler() },
                modifier = Modifier.padding(padding)
            )
            is HandlerDetailState.Error -> ErrorContent(
                message = currentState.message,
                onRetry = { viewModel.refresh() },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading handler details...")
        }
    }
}

@Composable
private fun HandlerDetailContent(
    handler: HandlerDetailResponse,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onExecute: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header section
        item {
            HandlerHeader(handler = handler)
        }

        // Action buttons
        item {
            ActionButtons(
                handler = handler,
                isInstalling = isInstalling,
                onInstall = onInstall,
                onUninstall = onUninstall,
                onExecute = onExecute
            )
        }

        // Description section
        item {
            DescriptionSection(description = handler.description)
        }

        // Permissions section
        if (handler.permissions.isNotEmpty()) {
            item {
                Text(
                    text = "Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(handler.permissions) { permission ->
                PermissionItem(permission = permission)
            }
        }

        // Changelog section
        handler.changelog?.let { changelog ->
            item {
                ChangelogSection(changelog = changelog)
            }
        }

        // Stats section
        item {
            StatsSection(handler = handler)
        }
    }
}

@Composable
private fun HandlerHeader(handler: HandlerDetailResponse) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Handler icon
        if (handler.iconUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(handler.iconUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = handler.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(80.dp)
                    .clip(MaterialTheme.shapes.medium)
            )
        } else {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = handler.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "v${handler.version}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "by ${handler.publisher}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (handler.installed && handler.installedVersion != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = {},
                    label = { Text("Installed v${handler.installedVersion}") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(
    handler: HandlerDetailResponse,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onExecute: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (handler.installed) {
            // Execute button
            Button(
                onClick = onExecute,
                modifier = Modifier.weight(1f),
                enabled = !isInstalling
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Execute")
            }

            // Uninstall button
            OutlinedButton(
                onClick = onUninstall,
                modifier = Modifier.weight(1f),
                enabled = !isInstalling
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Uninstall")
            }
        } else {
            // Install button
            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isInstalling
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Install")
            }
        }
    }
}

@Composable
private fun DescriptionSection(description: String) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionItem(permission: HandlerPermission) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getPermissionIcon(permission.type),
                contentDescription = null,
                tint = getPermissionColor(permission.type),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatPermissionType(permission.type),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = permission.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (permission.scope.isNotBlank()) {
                    Text(
                        text = "Scope: ${permission.scope}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun getPermissionIcon(type: String) = when (type.lowercase()) {
    "network" -> Icons.Default.Wifi
    "storage" -> Icons.Default.Storage
    "crypto" -> Icons.Default.Lock
    "messaging" -> Icons.AutoMirrored.Filled.Message
    else -> Icons.Default.Security
}

@Composable
private fun getPermissionColor(type: String) = when (type.lowercase()) {
    "network" -> MaterialTheme.colorScheme.primary
    "storage" -> MaterialTheme.colorScheme.tertiary
    "crypto" -> MaterialTheme.colorScheme.error
    "messaging" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.outline
}

private fun formatPermissionType(type: String): String {
    return type.replaceFirstChar { it.uppercase() }
}

@Composable
private fun ChangelogSection(changelog: String) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Changelog",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = changelog,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatsSection(handler: HandlerDetailResponse) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                icon = Icons.Default.Download,
                value = formatCount(handler.installCount),
                label = "Installs"
            )

            handler.rating?.let { rating ->
                StatItem(
                    icon = Icons.Default.Star,
                    value = String.format("%.1f", rating),
                    label = "${handler.ratingCount} ratings"
                )
            }

            StatItem(
                icon = Icons.Default.Storage,
                value = formatSize(handler.sizeBytes),
                label = "Size"
            )
        }
    }
}

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
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

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> "${count / 1_000_000}M+"
        count >= 1_000 -> "${count / 1_000}K+"
        else -> count.toString()
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Failed to load handler",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}
