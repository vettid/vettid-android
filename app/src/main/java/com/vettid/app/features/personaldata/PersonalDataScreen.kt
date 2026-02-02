package com.vettid.app.features.personaldata

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * Personal Data screen content for embedding in MainScaffold.
 * Shows personal data organized by category with compact display.
 */
@Composable
fun PersonalDataContent(
    viewModel: PersonalDataViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val showAddDialog by viewModel.showAddDialog.collectAsState()
    val showPublicProfilePreview by viewModel.showPublicProfilePreview.collectAsState()
    val editState by viewModel.editState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is PersonalDataEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is PersonalDataEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                else -> {}
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val currentState = state) {
            is PersonalDataState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is PersonalDataState.Empty -> {
                EmptyPersonalDataContent(
                    onAddClick = { viewModel.onEvent(PersonalDataEvent.AddItem) },
                    onPublishClick = { viewModel.publishProfile() }
                )
            }

            is PersonalDataState.Error -> {
                ErrorContent(
                    message = currentState.message,
                    onRetry = { viewModel.onEvent(PersonalDataEvent.Refresh) }
                )
            }

            is PersonalDataState.Loaded -> {
                val hasUnpublishedChanges by viewModel.hasUnpublishedChanges.collectAsState()
                val isRefreshing by viewModel.isRefreshing.collectAsState()
                val publishedProfile by viewModel.publishedProfile.collectAsState()
                @OptIn(ExperimentalMaterial3Api::class)
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.onEvent(PersonalDataEvent.Refresh) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    PersonalDataList(
                        groupedByCategory = viewModel.getDataByCategory(),
                        hasUnpublishedChanges = hasUnpublishedChanges,
                        lastPublishedAt = publishedProfile?.updatedAt,
                        isProfilePublished = publishedProfile?.isFromVault == true && publishedProfile?.items?.isNotEmpty() == true,
                        onItemClick = { viewModel.onEvent(PersonalDataEvent.ItemClicked(it)) },
                        onDeleteClick = { viewModel.onEvent(PersonalDataEvent.DeleteItem(it)) },
                        onTogglePublicProfile = { viewModel.onEvent(PersonalDataEvent.TogglePublicProfile(it)) },
                        onPublishClick = { viewModel.publishProfile() },
                        onPreviewClick = { viewModel.showPublicProfilePreview() }
                    )
                }
            }
        }

        // FAB to add new data
        if (state is PersonalDataState.Loaded || state is PersonalDataState.Empty) {
            FloatingActionButton(
                onClick = { viewModel.onEvent(PersonalDataEvent.AddItem) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add personal data")
            }
        }

        // Snackbar host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp)
        )
    }

    // Add/Edit dialog
    if (showAddDialog) {
        AddFieldDialog(
            state = editState,
            onCategoryChange = viewModel::updateEditCategory,
            onNameChange = viewModel::updateEditName,
            onFieldTypeChange = viewModel::updateEditFieldType,
            onValueChange = viewModel::updateEditValue,
            onSave = viewModel::saveItem,
            onDismiss = viewModel::dismissDialog,
            onCreateCategory = { name -> viewModel.createCustomCategory(name) }
        )
    }

    // Full screen public profile view - use Dialog to cover entire window (including scaffold top bar)
    if (showPublicProfilePreview) {
        val publishedProfile by viewModel.publishedProfile.collectAsState()
        val isLoadingPublishedProfile by viewModel.isLoadingPublishedProfile.collectAsState()

        androidx.compose.ui.window.Dialog(
            onDismissRequest = viewModel::hidePublicProfilePreview,
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            PublicProfileFullScreen(
                publishedProfile = publishedProfile,
                isLoading = isLoadingPublishedProfile,
                onBack = viewModel::hidePublicProfilePreview
            )
        }
    }
}

@Composable
private fun PersonalDataList(
    groupedByCategory: GroupedByCategory,
    hasUnpublishedChanges: Boolean,
    lastPublishedAt: String?,
    isProfilePublished: Boolean,
    onItemClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onTogglePublicProfile: (String) -> Unit,
    onPublishClick: () -> Unit,
    onPreviewClick: () -> Unit
) {
    // Track collapsed categories
    var collapsedCategories by remember { mutableStateOf(setOf<DataCategory>()) }

    // Search query state
    var searchQuery by remember { mutableStateOf("") }

    // Filter items based on search query
    val filteredGroups: Map<DataCategory, List<PersonalDataItem>> = if (searchQuery.isBlank()) {
        groupedByCategory.categories
    } else {
        groupedByCategory.categories.mapNotNull { (category, items) ->
            val filtered = items.filter { item ->
                item.name.contains(searchQuery, ignoreCase = true) ||
                item.value.contains(searchQuery, ignoreCase = true)
            }
            if (filtered.isNotEmpty()) category to filtered else null
        }.toMap()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Search bar
        item {
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // Publish button
        item {
            PublishProfileButton(
                hasUnpublishedChanges = hasUnpublishedChanges,
                lastPublishedAt = lastPublishedAt,
                isProfilePublished = isProfilePublished,
                onPublishClick = onPublishClick,
                onPreviewClick = onPreviewClick
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Group data by category with collapsible sections
        filteredGroups.forEach { (category, categoryItems) ->
            val isCollapsed = collapsedCategories.contains(category)

            item(key = "header_${category.name}") {
                CollapsibleCategoryHeader(
                    title = category.displayName,
                    itemCount = categoryItems.size,
                    isCollapsed = isCollapsed,
                    onToggle = {
                        collapsedCategories = if (isCollapsed) {
                            collapsedCategories - category
                        } else {
                            collapsedCategories + category
                        }
                    }
                )
            }

            if (!isCollapsed) {
                items(categoryItems, key = { it.id }) { item ->
                    CompactDataRow(
                        item = item,
                        onClick = { onItemClick(item.id) },
                        onDelete = { onDeleteClick(item.id) },
                        onTogglePublic = { onTogglePublicProfile(item.id) }
                    )
                }
            }

            item(key = "spacer_${category.name}") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Empty search results message
        if (filteredGroups.isEmpty() && searchQuery.isNotBlank()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No results for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Bottom padding for FAB
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Search personal data...") },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear search"
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium
    )
}

@Composable
private fun CollapsibleCategoryHeader(
    title: String,
    itemCount: Int,
    isCollapsed: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isCollapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = itemCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun PublishProfileButton(
    hasUnpublishedChanges: Boolean,
    lastPublishedAt: String?,
    isProfilePublished: Boolean,
    onPublishClick: () -> Unit,
    onPreviewClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Status indicator
        when {
            hasUnpublishedChanges -> {
                // Unpublished changes warning
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "You have unpublished changes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            isProfilePublished -> {
                // Published successfully indicator
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (lastPublishedAt != null) "Published: $lastPublishedAt" else "Profile published",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            else -> {
                // Not yet published
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Profile not yet published",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPreviewClick,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("View")
            }
            Button(
                onClick = onPublishClick,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (hasUnpublishedChanges) "Publish" else "Republish")
            }
        }
    }
}


@Composable
private fun CompactDataRow(
    item: PersonalDataItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePublic: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Public/Private indicator (display only, not clickable)
            Icon(
                imageVector = if (item.isInPublicProfile) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (item.isInPublicProfile) "In public profile" else "Not in public profile",
                modifier = Modifier.size(16.dp),
                tint = if (item.isInPublicProfile)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Field name
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(120.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Field value
            Text(
                text = if (item.isSensitive) maskString(item.value) else item.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // System field lock icon
            if (item.isSystemField) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "System field",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            } else {
                // More options menu
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                showMenu = false
                                onClick()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(if (item.isInPublicProfile) "Hide from Profile" else "Show on Profile") },
                            onClick = {
                                showMenu = false
                                onTogglePublic()
                            },
                            leadingIcon = {
                                Icon(
                                    if (item.isInPublicProfile) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                            }
                        )
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun EmptyPersonalDataContent(
    onAddClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onPublishClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Personal Data",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Add your personal information to share with connections",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onAddClick) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Data")
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFieldDialog(
    state: EditDataItemState,
    onCategoryChange: (DataCategory?) -> Unit,
    onNameChange: (String) -> Unit,
    onFieldTypeChange: (FieldType) -> Unit,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onCreateCategory: (String) -> Unit = {}
) {
    var expandedCategory by remember { mutableStateOf(false) }
    var expandedFieldType by remember { mutableStateOf(false) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (state.isEditing) "Edit Field" else "Add Field")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Category (first)
                ExposedDropdownMenuBox(
                    expanded = expandedCategory,
                    onExpandedChange = { expandedCategory = it }
                ) {
                    OutlinedTextField(
                        value = state.category?.displayName ?: "Select category",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategory)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCategory,
                        onDismissRequest = { expandedCategory = false }
                    ) {
                        DataCategory.values().forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.displayName) },
                                onClick = {
                                    onCategoryChange(category)
                                    expandedCategory = false
                                }
                            )
                        }
                        Divider()
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Create New Category")
                                }
                            },
                            onClick = {
                                expandedCategory = false
                                showNewCategoryDialog = true
                            }
                        )
                    }
                }

                // 2. Field name
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text("Field Name") },
                    placeholder = { Text("e.g., Social Security, Driver License") },
                    isError = state.nameError != null,
                    supportingText = state.nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 3. Field type
                ExposedDropdownMenuBox(
                    expanded = expandedFieldType,
                    onExpandedChange = { expandedFieldType = it }
                ) {
                    OutlinedTextField(
                        value = state.fieldType.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Field Type") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFieldType)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedFieldType,
                        onDismissRequest = { expandedFieldType = false }
                    ) {
                        FieldType.values().forEach { fieldType ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(fieldType.displayName)
                                        Text(
                                            fieldType.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onFieldTypeChange(fieldType)
                                    expandedFieldType = false
                                }
                            )
                        }
                    }
                }

                // 4. Value
                OutlinedTextField(
                    value = state.value,
                    onValueChange = onValueChange,
                    label = { Text("Value") },
                    isError = state.valueError != null,
                    supportingText = state.valueError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (state.fieldType == FieldType.NOTE) 3 else 1,
                    maxLines = if (state.fieldType == FieldType.NOTE) 5 else 1
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !state.isSaving
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    // New category dialog
    if (showNewCategoryDialog) {
        NewCategoryDialog(
            onDismiss = { showNewCategoryDialog = false },
            onCreate = { categoryName ->
                onCreateCategory(categoryName)
                showNewCategoryDialog = false
            }
        )
    }
}

@Composable
private fun NewCategoryDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var categoryName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Category") },
        text = {
            OutlinedTextField(
                value = categoryName,
                onValueChange = { categoryName = it },
                label = { Text("Category Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (categoryName.isNotBlank()) {
                        onCreate(categoryName)
                        onDismiss()
                    }
                },
                enabled = categoryName.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Mask a string for display (e.g., "1234567890" -> "123***7890")
 */
private fun maskString(value: String): String {
    return when {
        value.length <= 4 -> "****"
        value.length <= 8 -> "${value.take(2)}***${value.takeLast(2)}"
        else -> "${value.take(3)}***${value.takeLast(4)}"
    }
}

/**
 * Full screen view showing how the user's public profile appears to connections.
 * Only shows actually published data from the vault, not previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublicProfileFullScreen(
    publishedProfile: PublishedProfileData?,
    isLoading: Boolean,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top app bar
            TopAppBar(
                title = { Text("Your Public Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Content
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Loading your profile...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                publishedProfile == null || !publishedProfile.isFromVault || publishedProfile.items.isEmpty() -> {
                    // No published profile yet
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                modifier = Modifier.size(96.dp),
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        Icons.Default.CloudOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "No Published Profile",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Your public profile hasn't been published yet.\n\nTo share your profile with connections:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                ProfileInstructionStep(
                                    number = 1,
                                    text = "Use the menu (⋮) on each field to \"Show on Profile\""
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                ProfileInstructionStep(
                                    number = 2,
                                    text = "Tap the \"Publish\" button to make it live"
                                )
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                            OutlinedButton(onClick = onBack) {
                                Text("Go Back")
                            }
                        }
                    }
                }
                else -> {
                    // Show published profile
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Header explaining what connections see
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.People,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "This is how your profile appears to connections",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Profile card
                        BusinessCardView(publishedProfile)

                        Spacer(modifier = Modifier.weight(1f))

                        // Action button at bottom
                        Button(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Done")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileInstructionStep(
    number: Int,
    text: String
) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Dynamic profile card view that adapts to whatever fields the user shares.
 * Shows primary contact info (name/email) prominently, then organizes
 * additional fields by category in a clean, scannable layout.
 */
@Composable
private fun BusinessCardView(profile: PublishedProfileData) {
    // Extract primary identity fields (always shown at top if present)
    val firstName = profile.items.find { it.name == "First Name" }?.value ?: ""
    val lastName = profile.items.find { it.name == "Last Name" }?.value ?: ""
    val fullName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
    val email = profile.items.find { it.name == "Email" }?.value
    val phone = profile.items.find { it.name.contains("Phone", ignoreCase = true) }?.value

    // Group remaining fields by category (excluding primary fields)
    val primaryFieldNames = setOf("First Name", "Last Name", "Email")
    val otherFields = profile.items.filter { it.name !in primaryFieldNames }
    val groupedByCategory = otherFields.groupBy { it.category ?: DataCategory.OTHER }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Primary identity section - name with avatar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar circle with initials
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val initials = listOf(firstName, lastName)
                            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                            .joinToString("")
                            .take(2)
                        Text(
                            text = initials.ifEmpty { "?" },
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    if (fullName.isNotBlank()) {
                        Text(
                            text = fullName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    // Primary contact row (email and/or phone)
                    if (email != null || phone != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Column {
                            email?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Email,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            phone?.let {
                                if (email != null) Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Phone,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Additional fields organized by category
        if (groupedByCategory.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))

            // Define category display order
            val categoryOrder = listOf(
                DataCategory.CONTACT,
                DataCategory.ADDRESS,
                DataCategory.IDENTITY,
                DataCategory.FINANCIAL,
                DataCategory.MEDICAL,
                DataCategory.CRYPTO,
                DataCategory.OTHER
            )

            categoryOrder.forEach { category ->
                val categoryItems = groupedByCategory[category]
                if (categoryItems != null && categoryItems.isNotEmpty()) {
                    // Skip phone if already shown in header
                    val filteredItems = if (phone != null) {
                        categoryItems.filter { !it.name.contains("Phone", ignoreCase = true) || it.value != phone }
                    } else categoryItems

                    if (filteredItems.isNotEmpty()) {
                        ProfileCategorySection(
                            category = category,
                            items = filteredItems
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // Published timestamp
        profile.updatedAt?.let { updatedAt ->
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Published $updatedAt",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun ProfileCategorySection(
    category: DataCategory,
    items: List<PersonalDataItem>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Category label
            Text(
                text = category.displayName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            items.forEachIndexed { index, item ->
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                BusinessCardField(item)
            }
        }
    }
}

@Composable
private fun BusinessCardField(item: PersonalDataItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Icon based on field type/category
        Surface(
            modifier = Modifier.size(32.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = when {
                        item.name.contains("Phone", ignoreCase = true) -> Icons.Default.Phone
                        item.name.contains("Address", ignoreCase = true) ||
                        item.name.contains("Street", ignoreCase = true) -> Icons.Default.LocationOn
                        item.name.contains("City", ignoreCase = true) ||
                        item.name.contains("State", ignoreCase = true) ||
                        item.name.contains("Country", ignoreCase = true) ||
                        item.name.contains("Postal", ignoreCase = true) -> Icons.Default.Place
                        item.name.contains("LinkedIn", ignoreCase = true) -> Icons.Default.Work
                        item.name.contains("Twitter", ignoreCase = true) ||
                        item.name.contains("X (", ignoreCase = true) -> Icons.Default.Tag
                        item.name.contains("Instagram", ignoreCase = true) -> Icons.Default.CameraAlt
                        item.name.contains("GitHub", ignoreCase = true) -> Icons.Default.Code
                        item.name.contains("Website", ignoreCase = true) -> Icons.Default.Language
                        item.name.contains("Birthday", ignoreCase = true) -> Icons.Default.Cake
                        item.category == DataCategory.IDENTITY -> Icons.Default.Person
                        item.category == DataCategory.CONTACT -> Icons.Default.ContactPhone
                        item.category == DataCategory.ADDRESS -> Icons.Default.Home
                        item.category == DataCategory.FINANCIAL -> Icons.Default.AccountBalance
                        item.category == DataCategory.MEDICAL -> Icons.Default.LocalHospital
                        item.category == DataCategory.CRYPTO -> Icons.Default.CurrencyBitcoin
                        else -> Icons.Default.Info
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = item.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
