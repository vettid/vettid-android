package com.vettid.app.features.archive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Archive screen content for embedding in MainScaffold.
 * Shows archived items grouped by month with selection mode for bulk operations.
 */
@Composable
fun ArchiveContent(
    viewModel: ArchiveViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is ArchiveEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is ArchiveEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is ArchiveEffect.ConfirmDelete -> {
                    // Confirmation is handled via dialog
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Selection mode header
            val currentState = state
            if (currentState is ArchiveState.Loaded && currentState.isSelectionMode) {
                SelectionModeHeader(
                    selectedCount = currentState.selectedIds.size,
                    totalCount = currentState.items.size,
                    onSelectAll = { viewModel.onEvent(ArchiveEvent.SelectAll) },
                    onClearSelection = { viewModel.onEvent(ArchiveEvent.ClearSelection) },
                    onDelete = { viewModel.onEvent(ArchiveEvent.DeleteSelected) },
                    onRestore = { viewModel.onEvent(ArchiveEvent.RestoreSelected) },
                    onCancel = { viewModel.onEvent(ArchiveEvent.ExitSelectionMode) }
                )
            }

            when (currentState) {
                is ArchiveState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is ArchiveState.Empty -> {
                    EmptyArchiveContent()
                }

                is ArchiveState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = { viewModel.onEvent(ArchiveEvent.Refresh) }
                    )
                }

                is ArchiveState.Loaded -> {
                    ArchiveList(
                        groupedItems = currentState.groupedByMonth,
                        selectedIds = currentState.selectedIds,
                        isSelectionMode = currentState.isSelectionMode,
                        onItemClick = { viewModel.onEvent(ArchiveEvent.ItemClicked(it)) },
                        onItemLongPress = { viewModel.onEvent(ArchiveEvent.ItemLongPressed(it)) }
                    )
                }
            }
        }

        // Snackbar host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun SelectionModeHeader(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }

            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )

            if (selectedCount < totalCount) {
                TextButton(onClick = onSelectAll) {
                    Text("Select All")
                }
            } else {
                TextButton(onClick = onClearSelection) {
                    Text("Clear")
                }
            }

            IconButton(
                onClick = onRestore,
                enabled = selectedCount > 0
            ) {
                Icon(Icons.Default.Restore, contentDescription = "Restore")
            }

            IconButton(
                onClick = onDelete,
                enabled = selectedCount > 0
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = if (selectedCount > 0) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveList(
    groupedItems: Map<YearMonth, List<ArchivedItem>>,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    onItemClick: (String) -> Unit,
    onItemLongPress: (String) -> Unit
) {
    val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groupedItems.forEach { (yearMonth, items) ->
            item(key = yearMonth.toString()) {
                Text(
                    text = yearMonth.format(monthFormatter).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(items, key = { it.id }) { item ->
                ArchiveItemCard(
                    item = item,
                    isSelected = selectedIds.contains(item.id),
                    isSelectionMode = isSelectionMode,
                    onClick = { onItemClick(item.id) },
                    onLongClick = { onItemLongPress(item.id) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveItemCard(
    item: ArchivedItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Icon based on type
            Icon(
                imageVector = when (item.type) {
                    ArchivedItemType.MESSAGE -> Icons.Default.Chat
                    ArchivedItemType.CONNECTION -> Icons.Default.Person
                    ArchivedItemType.FILE -> Icons.Default.Description
                    ArchivedItemType.AUTH_REQUEST -> Icons.Default.VerifiedUser
                    ArchivedItemType.EVENT -> Icons.Default.Event
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.subtitle != null) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatArchivedDate(item.archivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.expiresAt != null) {
                val daysUntilExpiry = java.time.Duration.between(
                    java.time.Instant.now(),
                    item.expiresAt
                ).toDays()

                if (daysUntilExpiry <= 7) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "${daysUntilExpiry}d",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyArchiveContent() {
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
                imageVector = Icons.Default.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Archive Empty",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Archived items will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
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
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall
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

private fun formatArchivedDate(instant: java.time.Instant): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    return formatter.format(instant.atZone(java.time.ZoneId.systemDefault()))
}

/**
 * Full-screen Archive with Scaffold and back navigation.
 * Used when navigating from More menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreenFull(
    viewModel: ArchiveViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archive") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            ArchiveContent(viewModel = viewModel)
        }
    }
}
