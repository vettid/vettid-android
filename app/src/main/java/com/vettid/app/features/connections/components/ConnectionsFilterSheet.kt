package com.vettid.app.features.connections.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vettid.app.features.connections.models.*

/**
 * Bottom sheet for filtering and sorting connections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsFilterSheet(
    currentFilter: ConnectionFilter,
    availableTags: List<ConnectionTag>,
    onFilterChange: (ConnectionFilter) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Filter & Sort",
                    style = MaterialTheme.typography.titleLarge
                )

                if (currentFilter.hasActiveFilters) {
                    TextButton(
                        onClick = { onFilterChange(ConnectionFilter()) }
                    ) {
                        Text("Clear All")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Sort Order
            SortOrderSection(
                selectedOrder = currentFilter.sortOrder,
                onOrderChange = { onFilterChange(currentFilter.copy(sortOrder = it)) }
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // Status Filter
            StatusFilterSection(
                selectedStatuses = currentFilter.statuses,
                onStatusToggle = { status ->
                    val newStatuses = if (status in currentFilter.statuses) {
                        currentFilter.statuses - status
                    } else {
                        currentFilter.statuses + status
                    }
                    onFilterChange(currentFilter.copy(statuses = newStatuses))
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // Tags Filter
            if (availableTags.isNotEmpty()) {
                TagSelector(
                    availableTags = availableTags,
                    selectedTags = currentFilter.tags,
                    onTagToggle = { tagId ->
                        val newTags = if (tagId in currentFilter.tags) {
                            currentFilter.tags - tagId
                        } else {
                            currentFilter.tags + tagId
                        }
                        onFilterChange(currentFilter.copy(tags = newTags))
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Trust Level Filter
            TrustLevelSection(
                selectedLevel = currentFilter.verificationLevel,
                onLevelChange = { onFilterChange(currentFilter.copy(verificationLevel = it)) }
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // Toggle Options
            ToggleOptionsSection(
                showArchived = currentFilter.showArchived,
                favoritesOnly = currentFilter.favoritesOnly,
                onShowArchivedChange = { onFilterChange(currentFilter.copy(showArchived = it)) },
                onFavoritesOnlyChange = { onFilterChange(currentFilter.copy(favoritesOnly = it)) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Apply Button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply Filters")
            }
        }
    }
}

@Composable
private fun SortOrderSection(
    selectedOrder: SortOrder,
    onOrderChange: (SortOrder) -> Unit
) {
    Column {
        Text(
            text = "Sort By",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.selectableGroup()) {
            SortOrder.entries.forEach { order ->
                SortOrderOption(
                    order = order,
                    selected = order == selectedOrder,
                    onClick = { onOrderChange(order) }
                )
            }
        }
    }
}

@Composable
private fun SortOrderOption(
    order: SortOrder,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = order.displayName,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun StatusFilterSection(
    selectedStatuses: Set<ConnectionStatus>,
    onStatusToggle: (ConnectionStatus) -> Unit
) {
    Column {
        Text(
            text = "Status",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        val statusGroups = listOf(
            "Active" to listOf(ConnectionStatus.ACTIVE),
            "Pending" to listOf(
                ConnectionStatus.PENDING_OUR_REVIEW,
                ConnectionStatus.PENDING_THEIR_REVIEW,
                ConnectionStatus.PENDING_OUR_ACCEPT,
                ConnectionStatus.PENDING_THEIR_ACCEPT
            ),
            "Inactive" to listOf(
                ConnectionStatus.REVOKED,
                ConnectionStatus.EXPIRED,
                ConnectionStatus.BLOCKED
            )
        )

        statusGroups.forEach { (groupName, statuses) ->
            val anySelected = statuses.any { it in selectedStatuses }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = anySelected,
                    onCheckedChange = {
                        statuses.forEach { status ->
                            if (it && status !in selectedStatuses) {
                                onStatusToggle(status)
                            } else if (!it && status in selectedStatuses) {
                                onStatusToggle(status)
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun TrustLevelSection(
    selectedLevel: TrustLevel?,
    onLevelChange: (TrustLevel?) -> Unit
) {
    Column {
        Text(
            text = "Minimum Trust Level",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.selectableGroup()) {
            // "Any" option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedLevel == null,
                        onClick = { onLevelChange(null) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedLevel == null,
                    onClick = null
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Any",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            TrustLevel.entries.forEach { level ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = level == selectedLevel,
                            onClick = { onLevelChange(level) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = level == selectedLevel,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    TrustBadge(trustLevel = level)
                }
            }
        }
    }
}

@Composable
private fun ToggleOptionsSection(
    showArchived: Boolean,
    favoritesOnly: Boolean,
    onShowArchivedChange: (Boolean) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit
) {
    Column {
        Text(
            text = "Options",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Favorites Only",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Switch(
                checked = favoritesOnly,
                onCheckedChange = onFavoritesOnlyChange
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Show Archived",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Switch(
                checked = showArchived,
                onCheckedChange = onShowArchivedChange
            )
        }
    }
}
