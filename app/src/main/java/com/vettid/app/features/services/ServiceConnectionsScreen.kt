package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vettid.app.features.services.models.*
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*

/**
 * Screen displaying the list of service connections.
 *
 * Services are business/organization vaults with rich profiles and contracts.
 * This screen is separate from peer connections to distinguish the different
 * types of relationships (person-to-person vs person-to-service).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceConnectionsScreen(
    viewModel: ServiceConnectionsViewModel = hiltViewModel(),
    onServiceClick: (String) -> Unit = {},
    onBrowseServices: () -> Unit = {},
    onScanServiceQR: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val pendingActionsCount by viewModel.pendingActionsCount.collectAsState()
    val pendingContractUpdates by viewModel.pendingContractUpdates.collectAsState()

    var showFabMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showRevokeDialog by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ServiceConnectionsEffect.NavigateToServiceDetail -> {
                    onServiceClick(effect.connectionId)
                }
                is ServiceConnectionsEffect.NavigateToServiceDiscovery -> {
                    onBrowseServices()
                }
                is ServiceConnectionsEffect.NavigateToScanServiceQR -> {
                    onScanServiceQR()
                }
                is ServiceConnectionsEffect.ShowFilterSheet -> {
                    showFilterSheet = true
                }
                is ServiceConnectionsEffect.ShowRevokeConfirmation -> {
                    showRevokeDialog = effect.connectionId
                }
                is ServiceConnectionsEffect.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                else -> {
                    // Handle other navigation effects when screens are created
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Services") },
                actions = {
                    // Contract updates badge
                    if (pendingContractUpdates > 0) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text("$pendingContractUpdates")
                                }
                            }
                        ) {
                            IconButton(onClick = { /* Navigate to contract updates */ }) {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = "Contract updates"
                                )
                            }
                        }
                    }

                    // Filter button
                    IconButton(onClick = { viewModel.showFilterSheet() }) {
                        Icon(
                            imageVector = if (filter.isDefault) Icons.Default.FilterList else Icons.Default.FilterListOff,
                            contentDescription = "Filter"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End
            ) {
                // Mini FABs
                if (showFabMenu) {
                    SmallFloatingActionButton(
                        onClick = {
                            showFabMenu = false
                            viewModel.onScanServiceQR()
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan Service QR"
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SmallFloatingActionButton(
                        onClick = {
                            showFabMenu = false
                            viewModel.onBrowseServices()
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Browse Services"
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Main FAB
                FloatingActionButton(
                    onClick = { showFabMenu = !showFabMenu }
                ) {
                    Icon(
                        imageVector = if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (showFabMenu) "Close menu" else "Add service"
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Offline/sync status banner
            if (!isOnline || pendingActionsCount > 0) {
                SyncStatusBanner(
                    isOnline = isOnline,
                    syncStatus = syncStatus,
                    pendingCount = pendingActionsCount,
                    onRetry = { viewModel.retrySyncActions() }
                )
            }

            // Search bar
            if (state is ServiceConnectionsState.Loaded || state is ServiceConnectionsState.Empty) {
                ServiceSearchBar(
                    query = searchQuery,
                    onQueryChange = { viewModel.onSearchQueryChanged(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Active filters
            if (!filter.isDefault) {
                ActiveFiltersRow(
                    filter = filter,
                    onClearFilters = { viewModel.updateFilter(ServiceConnectionFilter()) }
                )
            }

            when (val currentState = state) {
                is ServiceConnectionsState.Loading -> {
                    ServiceLoadingContent()
                }

                is ServiceConnectionsState.Empty -> {
                    ServiceEmptyContent(
                        onBrowseServices = { viewModel.onBrowseServices() },
                        onScanServiceQR = { viewModel.onScanServiceQR() }
                    )
                }

                is ServiceConnectionsState.Loaded -> {
                    if (currentState.connections.isEmpty() && currentState.isSearchResult) {
                        ServiceNoSearchResultsContent(query = searchQuery)
                    } else {
                        ServiceConnectionsList(
                            connections = currentState.connections,
                            isRefreshing = isRefreshing,
                            onRefresh = { viewModel.refresh() },
                            onServiceClick = { viewModel.onServiceClick(it) },
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onReviewContract = { viewModel.reviewContractUpdate(it) }
                        )
                    }
                }

                is ServiceConnectionsState.Error -> {
                    ServiceErrorContent(
                        message = currentState.message,
                        onRetry = { viewModel.loadServiceConnections() }
                    )
                }
            }
        }
    }

    // Filter bottom sheet
    if (showFilterSheet) {
        ServiceFilterSheet(
            filter = filter,
            availableCategories = viewModel.availableCategories.collectAsState().value,
            availableTags = viewModel.availableTags.collectAsState().value,
            onFilterChange = { viewModel.updateFilter(it) },
            onDismiss = { showFilterSheet = false }
        )
    }

    // Revoke confirmation dialog
    showRevokeDialog?.let { connectionId ->
        AlertDialog(
            onDismissRequest = { showRevokeDialog = null },
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
                    "This will permanently revoke your connection to this service. " +
                    "They will no longer be able to access any of your data or send you requests."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmRevoke(connectionId)
                        showRevokeDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// MARK: - Sync Status Banner

@Composable
private fun SyncStatusBanner(
    isOnline: Boolean,
    syncStatus: SyncStatus,
    pendingCount: Int,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = when {
            !isOnline -> MaterialTheme.colorScheme.errorContainer
            syncStatus == SyncStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
            syncStatus == SyncStatus.PENDING -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (!isOnline) Icons.Default.CloudOff else Icons.Default.Sync,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    !isOnline -> "Offline - $pendingCount pending actions"
                    syncStatus == SyncStatus.PENDING -> "Syncing $pendingCount actions..."
                    syncStatus == SyncStatus.FAILED -> "Sync failed - $pendingCount pending"
                    else -> "Synced"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.weight(1f))
            if (isOnline && syncStatus == SyncStatus.FAILED) {
                TextButton(onClick = onRetry) {
                    Text("Retry", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// MARK: - Search Bar

@Composable
private fun ServiceSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search services...") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search"
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
    )
}

// MARK: - Active Filters Row

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveFiltersRow(
    filter: ServiceConnectionFilter,
    onClearFilters: () -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (filter.showFavoritesOnly) {
            item {
                FilterChip(
                    selected = true,
                    onClick = { /* Remove filter */ },
                    label = { Text("Favorites") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }

        items(filter.categoryFilter.toList()) { category ->
            FilterChip(
                selected = true,
                onClick = { /* Remove category filter */ },
                label = { Text(category.displayName) }
            )
        }

        items(filter.selectedTags.toList()) { tag ->
            FilterChip(
                selected = true,
                onClick = { /* Remove tag filter */ },
                label = { Text(tag) }
            )
        }

        item {
            AssistChip(
                onClick = onClearFilters,
                label = { Text("Clear all") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}

// MARK: - Service Connections List

@Composable
private fun ServiceConnectionsList(
    connections: List<ServiceConnectionRecord>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onServiceClick: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onReviewContract: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Group by category
            val grouped = connections.groupBy { it.serviceProfile.serviceCategory }

            grouped.forEach { (category, categoryConnections) ->
                item {
                    CategoryHeader(category = category)
                }

                items(
                    items = categoryConnections,
                    key = { it.connectionId }
                ) { connection ->
                    ServiceConnectionItem(
                        connection = connection,
                        onClick = { onServiceClick(connection.connectionId) },
                        onToggleFavorite = { onToggleFavorite(connection.connectionId) },
                        onReviewContract = {
                            if (connection.pendingContractVersion != null) {
                                onReviewContract(connection.connectionId)
                            }
                        }
                    )
                }
            }
        }

        // Refresh indicator
        if (isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }
    }
}

// MARK: - Category Header

@Composable
private fun CategoryHeader(category: ServiceCategory) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = getCategoryIcon(category),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = category.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun getCategoryIcon(category: ServiceCategory) = when (category) {
    ServiceCategory.RETAIL -> Icons.Default.ShoppingCart
    ServiceCategory.HEALTHCARE -> Icons.Default.LocalHospital
    ServiceCategory.FINANCE -> Icons.Default.AccountBalance
    ServiceCategory.GOVERNMENT -> Icons.Default.AccountBalance
    ServiceCategory.EDUCATION -> Icons.Default.School
    ServiceCategory.ENTERTAINMENT -> Icons.Default.TheaterComedy
    ServiceCategory.TRAVEL -> Icons.Default.Flight
    ServiceCategory.FOOD_DELIVERY -> Icons.Default.Restaurant
    ServiceCategory.UTILITIES -> Icons.Default.Build
    ServiceCategory.INSURANCE -> Icons.Default.Security
    ServiceCategory.OTHER -> Icons.Default.Business
}

// MARK: - Service Connection Item

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceConnectionItem(
    connection: ServiceConnectionRecord,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onReviewContract: () -> Unit
) {
    val profile = connection.serviceProfile
    val hasPendingContract = connection.pendingContractVersion != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Service logo
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
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
                        imageVector = getCategoryIcon(profile.serviceCategory),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Service info
            Column(modifier = Modifier.weight(1f)) {
                // Name and verification badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.serviceName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (profile.organization.verified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = profile.organization.verificationType?.badgeText,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Organization
                Text(
                    text = profile.organization.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Status and contract badges
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Connection status
                    StatusBadge(status = connection.status)

                    // Pending contract update
                    if (hasPendingContract) {
                        AssistChip(
                            onClick = onReviewContract,
                            label = { Text("Review Contract", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(24.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        )
                    }
                }
            }

            // Trailing actions
            Column(
                horizontalAlignment = Alignment.End
            ) {
                // Favorite button
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (connection.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (connection.isFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (connection.isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Last active time
                connection.lastActiveAt?.let {
                    Text(
                        text = formatTime(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// MARK: - Status Badge

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
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// MARK: - Filter Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceFilterSheet(
    filter: ServiceConnectionFilter,
    availableCategories: List<ServiceCategory>,
    availableTags: List<String>,
    onFilterChange: (ServiceConnectionFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var localFilter by remember { mutableStateOf(filter) }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Filter Services",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Sort order
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                items(ServiceSortOrder.entries) { order ->
                    FilterChip(
                        selected = localFilter.sortOrder == order,
                        onClick = { localFilter = localFilter.copy(sortOrder = order) },
                        label = { Text(order.displayName) }
                    )
                }
            }

            // Categories
            Text(
                text = "Categories",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                items(availableCategories) { category ->
                    FilterChip(
                        selected = category in localFilter.categoryFilter,
                        onClick = {
                            val newCategories = if (category in localFilter.categoryFilter) {
                                localFilter.categoryFilter - category
                            } else {
                                localFilter.categoryFilter + category
                            }
                            localFilter = localFilter.copy(categoryFilter = newCategories)
                        },
                        label = { Text(category.displayName) },
                        leadingIcon = {
                            Icon(
                                imageVector = getCategoryIcon(category),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            // Toggles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Favorites only")
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = localFilter.showFavoritesOnly,
                    onCheckedChange = { localFilter = localFilter.copy(showFavoritesOnly = it) }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Show archived")
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = localFilter.showArchivedOnly,
                    onCheckedChange = { localFilter = localFilter.copy(showArchivedOnly = it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        localFilter = ServiceConnectionFilter()
                        onFilterChange(localFilter)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset")
                }
                Button(
                    onClick = {
                        onFilterChange(localFilter)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Apply")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// MARK: - State Composables

@Composable
private fun ServiceLoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ServiceEmptyContent(
    onBrowseServices: () -> Unit,
    onScanServiceQR: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Business,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "No Service Connections",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Connect with businesses and services to manage your data relationships securely.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onBrowseServices) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Browse Services")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(onClick = onScanServiceQR) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Service QR")
            }
        }
    }
}

@Composable
private fun ServiceNoSearchResultsContent(query: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No results for \"$query\"",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Try a different search term or adjust your filters",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ServiceErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
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

private fun formatTime(instant: Instant): String {
    val now = System.currentTimeMillis()
    val timestamp = instant.toEpochMilli()
    val diff = now - timestamp

    return when {
        diff < 60 * 1000 -> "now"
        diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}m"
        diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)}h"
        diff < 7 * 24 * 60 * 60 * 1000 -> "${diff / (24 * 60 * 60 * 1000)}d"
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
