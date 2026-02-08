package com.vettid.app.features.handlers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vettid.app.core.network.HandlerCategory
import com.vettid.app.core.network.HandlerSummary
import kotlinx.coroutines.flow.collectLatest

/**
 * Screen for discovering and browsing handlers.
 *
 * Features:
 * - Category tabs for filtering
 * - Search bar
 * - Handler list with install/uninstall actions
 * - Pagination on scroll
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandlerDiscoveryScreen(
    viewModel: HandlerDiscoveryViewModel = hiltViewModel(),
    onHandlerSelected: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onRequireAuth: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSearch by remember { mutableStateOf(false) }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is HandlerDiscoveryEffect.RequireAuth -> onRequireAuth()
                is HandlerDiscoveryEffect.ShowLoading -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is HandlerDiscoveryEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is HandlerDiscoveryEffect.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        actionLabel = "Dismiss"
                    )
                }
                is HandlerDiscoveryEffect.NavigateToDetails -> {
                    onHandlerSelected(effect.handlerId)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (showSearch) {
                SearchTopBar(
                    query = searchQuery,
                    onQueryChange = { viewModel.search(it) },
                    onClose = {
                        showSearch = false
                        viewModel.search("")
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Handlers") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Category tabs
            if (!showSearch) {
                CategoryTabs(
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onCategorySelected = { viewModel.selectCategory(it) }
                )
            }

            // Content
            when (val currentState = state) {
                is HandlerDiscoveryState.Loading -> LoadingContent()
                is HandlerDiscoveryState.Loaded -> HandlerListContent(
                    handlers = currentState.handlers,
                    hasMore = currentState.hasMore,
                    onHandlerClick = { onHandlerSelected(it.id) },
                    onInstall = { viewModel.installHandler(it) },
                    onUninstall = { viewModel.uninstallHandler(it) },
                    onLoadMore = { viewModel.loadMore() }
                )
                is HandlerDiscoveryState.Error -> ErrorContent(
                    message = currentState.message,
                    onRetry = { viewModel.refresh() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search handlers...") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }
    )
}

@Composable
private fun CategoryTabs(
    categories: List<HandlerCategory>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit
) {
    val allCategories = listOf(null to "All") + categories.map { it.id to it.name }

    ScrollableTabRow(
        selectedTabIndex = allCategories.indexOfFirst { it.first == selectedCategory }.coerceAtLeast(0),
        edgePadding = 16.dp
    ) {
        allCategories.forEach { (category, label) ->
            Tab(
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                text = { Text(label) }
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading handlers...")
        }
    }
}

@Composable
private fun HandlerListContent(
    handlers: List<HandlerSummary>,
    hasMore: Boolean,
    onHandlerClick: (HandlerSummary) -> Unit,
    onInstall: (HandlerSummary) -> Unit,
    onUninstall: (HandlerSummary) -> Unit,
    onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()

    // Load more when reaching end
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null && lastIndex >= handlers.size - 3 && hasMore) {
                    onLoadMore()
                }
            }
    }

    if (handlers.isEmpty()) {
        EmptyContent()
    } else {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(handlers, key = { it.id }) { handler ->
                HandlerListItem(
                    handler = handler,
                    onClick = { onHandlerClick(handler) },
                    onInstall = { onInstall(handler) },
                    onUninstall = { onUninstall(handler) }
                )
            }

            if (hasMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HandlerListItem(
    handler: HandlerSummary,
    onClick: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Handler icon
            HandlerIcon(
                iconUrl = handler.iconUrl,
                name = handler.name,
                modifier = Modifier.size(56.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Handler info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = handler.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = handler.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "v${handler.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = handler.publisher,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (handler.installCount > 0) {
                        Text(
                            text = " • ${formatInstallCount(handler.installCount)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Install/Uninstall button
            if (handler.installed) {
                OutlinedButton(
                    onClick = onUninstall,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Uninstall")
                }
            } else {
                Button(
                    onClick = onInstall,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Install")
                }
            }
        }
    }
}

@Composable
private fun HandlerIcon(
    iconUrl: String?,
    name: String,
    modifier: Modifier = Modifier
) {
    if (iconUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(iconUrl)
                .crossfade(true)
                .build(),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(MaterialTheme.shapes.medium)
        )
    } else {
        Surface(
            modifier = modifier,
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
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Extension,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No handlers found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Try a different category or search term",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
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
                text = "Failed to load handlers",
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

private fun formatInstallCount(count: Int): String {
    return when {
        count >= 1_000_000 -> "${count / 1_000_000}M+"
        count >= 1_000 -> "${count / 1_000}K+"
        else -> "$count installs"
    }
}
