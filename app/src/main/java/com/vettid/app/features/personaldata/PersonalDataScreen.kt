package com.vettid.app.features.personaldata

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.ui.components.PhoneNumberInput
import com.vettid.app.ui.components.ProfilePhotoCapture
import kotlinx.coroutines.flow.collectLatest
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Format ISO 8601 timestamp to local timezone for display.
 * Input: "2025-02-03T15:30:00Z" or similar ISO format
 * Output: "Feb 3, 2025 at 10:30 AM" (in user's timezone)
 */
private fun formatPublishedTimestamp(isoTimestamp: String?): String? {
    if (isoTimestamp == null) return null
    return try {
        val instant = Instant.parse(isoTimestamp)
        val localDateTime = instant.atZone(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a")
        localDateTime.format(formatter)
    } catch (e: Exception) {
        // Fallback to original if parsing fails
        isoTimestamp
    }
}

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
    val showPhotoCapture by viewModel.showPhotoCapture.collectAsState()
    val editState by viewModel.editState.collectAsState()
    val profilePhoto by viewModel.profilePhoto.collectAsState()
    val customCategories by viewModel.customCategories.collectAsState()
    val systemFields by viewModel.systemFields.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Search bar visibility state (lifted up for FAB control)
    var showSearchBar by remember { mutableStateOf(false) }

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
                // Compute groupedByCategory directly (no remember) to ensure recomposition on sort order changes
                val groupedByCategory = GroupedByCategory.fromItems(currentState.items)
                @OptIn(ExperimentalMaterial3Api::class)
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.onEvent(PersonalDataEvent.Refresh) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    PersonalDataList(
                        groupedByCategory = groupedByCategory,
                        hasUnpublishedChanges = hasUnpublishedChanges,
                        lastPublishedAt = publishedProfile?.updatedAt,
                        isProfilePublished = publishedProfile?.isFromVault == true && publishedProfile?.items?.isNotEmpty() == true,
                        firstName = systemFields?.firstName ?: "",
                        lastName = systemFields?.lastName ?: "",
                        profilePhotoBase64 = profilePhoto,
                        showSearchBar = showSearchBar,
                        onSearchBarClose = { showSearchBar = false },
                        onItemClick = { viewModel.onEvent(PersonalDataEvent.ItemClicked(it)) },
                        onDeleteClick = { viewModel.onEvent(PersonalDataEvent.DeleteItem(it)) },
                        onTogglePublicProfile = { viewModel.onEvent(PersonalDataEvent.TogglePublicProfile(it)) },
                        onMoveUp = { viewModel.onEvent(PersonalDataEvent.MoveItemUp(it)) },
                        onMoveDown = { viewModel.onEvent(PersonalDataEvent.MoveItemDown(it)) },
                        onPublishClick = { viewModel.publishProfile() },
                        onPreviewClick = { viewModel.showPublicProfilePreview() },
                        onEditPhoto = { viewModel.showPhotoCaptureDialog() }
                    )
                }
            }
        }

        // FABs for search and add
        if (state is PersonalDataState.Loaded || state is PersonalDataState.Empty) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Search FAB (smaller)
                SmallFloatingActionButton(
                    onClick = { showSearchBar = !showSearchBar },
                    containerColor = if (showSearchBar)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(
                        if (showSearchBar) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (showSearchBar) "Close search" else "Search"
                    )
                }
                // Add FAB (primary)
                FloatingActionButton(
                    onClick = { viewModel.onEvent(PersonalDataEvent.AddItem) }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add personal data")
                }
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
            customCategories = customCategories,
            onCategoryChange = viewModel::updateEditCategory,
            onNameChange = viewModel::updateEditName,
            onFieldTypeChange = viewModel::updateEditFieldType,
            onValueChange = viewModel::updateEditValue,
            onPublicProfileChange = viewModel::updateEditPublicProfile,
            onSave = viewModel::saveItem,
            onDismiss = viewModel::dismissDialog,
            onCreateCategory = { name -> viewModel.createCustomCategory(name) },
            onDelete = {
                editState.id?.let { id ->
                    viewModel.onEvent(PersonalDataEvent.DeleteItem(id))
                    viewModel.dismissDialog()
                }
            }
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

    // Full screen photo capture dialog
    if (showPhotoCapture) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = viewModel::hidePhotoCaptureDialog,
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            ProfilePhotoCapture(
                onPhotoCapture = { bytes ->
                    viewModel.uploadPhoto(bytes)
                },
                onCancel = viewModel::hidePhotoCaptureDialog
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
    firstName: String,
    lastName: String,
    profilePhotoBase64: String?,
    showSearchBar: Boolean,
    onSearchBarClose: () -> Unit,
    onItemClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onTogglePublicProfile: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onPublishClick: () -> Unit,
    onPreviewClick: () -> Unit,
    onEditPhoto: () -> Unit
) {
    // Track collapsed categories - start with all categories collapsed
    var collapsedCategories by remember { mutableStateOf(DataCategory.values().toSet()) }

    // Search query state
    var searchQuery by remember { mutableStateOf("") }

    // Clear search when bar is hidden
    LaunchedEffect(showSearchBar) {
        if (!showSearchBar) searchQuery = ""
    }

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

    // Auto-expand categories with search matches
    LaunchedEffect(searchQuery, filteredGroups) {
        if (searchQuery.isNotBlank()) {
            // Expand all categories that have matches
            collapsedCategories = collapsedCategories - filteredGroups.keys
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Search bar (only shown when toggled via FAB)
        if (showSearchBar) {
            item {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClose = onSearchBarClose,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
        }

        // Publish button (above photo)
        item {
            PublishProfileButton(
                hasUnpublishedChanges = hasUnpublishedChanges,
                lastPublishedAt = lastPublishedAt,
                isProfilePublished = isProfilePublished,
                onPublishClick = onPublishClick,
                onPreviewClick = onPreviewClick
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Profile header with photo/initials
        item {
            ProfileHeaderSection(
                firstName = firstName,
                lastName = lastName,
                photoBase64 = profilePhotoBase64,
                onEditPhoto = onEditPhoto
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
                itemsIndexed(categoryItems, key = { _, item -> item.id }) { index, item ->
                    val isFirst = index == 0
                    val isLast = index == categoryItems.size - 1
                    CompactDataRow(
                        item = item,
                        isFirst = isFirst,
                        isLast = isLast,
                        onClick = { onItemClick(item.id) },
                        onDelete = { onDeleteClick(item.id) },
                        onTogglePublic = { onTogglePublicProfile(item.id) },
                        onMoveUp = { onMoveUp(item.id) },
                        onMoveDown = { onMoveDown(item.id) }
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
    onClose: () -> Unit,
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
            IconButton(onClick = {
                if (query.isNotEmpty()) {
                    onQueryChange("")
                } else {
                    onClose()
                }
            }) {
                Icon(
                    if (query.isNotEmpty()) Icons.Default.Clear else Icons.Default.Close,
                    contentDescription = if (query.isNotEmpty()) "Clear search" else "Close search"
                )
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium
    )
}

/**
 * Profile header section showing user's avatar with photo or initials.
 * Tap to edit photo.
 */
@Composable
private fun ProfileHeaderSection(
    firstName: String,
    lastName: String,
    photoBase64: String?,
    onEditPhoto: () -> Unit
) {
    // Decode Base64 photo if present
    val photoBitmap = remember(photoBase64) {
        photoBase64?.let { base64 ->
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular avatar with photo or initials
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .clickable { onEditPhoto() }
        ) {
            if (photoBitmap != null) {
                Image(
                    bitmap = photoBitmap.asImageBitmap(),
                    contentDescription = "Profile photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Show initials on colored background
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 4.dp
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
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            // Camera overlay icon
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "Edit photo",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            val fullName = listOf(firstName, lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            Text(
                text = fullName.ifEmpty { "Your Profile" },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Tap photo to change",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
                            text = if (lastPublishedAt != null) "Published: ${formatPublishedTimestamp(lastPublishedAt) ?: lastPublishedAt}" else "Profile published",
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
                Text("Publish")
            }
        }
    }
}


/**
 * Redesigned field row with cleaner layout:
 * - Drag handle on left for reordering
 * - Clickable visibility toggle
 * - Full field name and value (no truncation)
 * - Lock icon for system fields
 * - Tap anywhere to edit
 */
@Composable
private fun CompactDataRow(
    item: PersonalDataItem,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePublic: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var showReorderMenu by remember { mutableStateOf(false) }

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
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle - tap to show reorder options
            Box {
                IconButton(
                    onClick = { showReorderMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Reorder",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                // Reorder dropdown menu
                DropdownMenu(
                    expanded = showReorderMenu,
                    onDismissRequest = { showReorderMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Move Up") },
                        onClick = {
                            showReorderMenu = false
                            onMoveUp()
                        },
                        enabled = !isFirst,
                        leadingIcon = {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Move Down") },
                        onClick = {
                            showReorderMenu = false
                            onMoveDown()
                        },
                        enabled = !isLast,
                        leadingIcon = {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Visibility toggle (clickable) or lock icon for system fields
            if (item.isSystemField) {
                // Gold lock icon for system fields (always shared)
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Required field (always shared)",
                        modifier = Modifier.size(22.dp),
                        tint = androidx.compose.ui.graphics.Color(0xFFD4A017) // Gold color
                    )
                }
            } else {
                IconButton(
                    onClick = onTogglePublic,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (item.isInPublicProfile) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (item.isInPublicProfile) "Visible in profile (tap to hide)" else "Hidden from profile (tap to show)",
                        modifier = Modifier.size(22.dp),
                        tint = if (item.isInPublicProfile)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Field name and value (full text, wrapped)
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (item.isSensitive) maskString(item.value) else item.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
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
    customCategories: List<com.vettid.app.core.storage.CategoryInfo> = emptyList(),
    onCategoryChange: (DataCategory?) -> Unit,
    onNameChange: (String) -> Unit,
    onFieldTypeChange: (FieldType) -> Unit,
    onValueChange: (String) -> Unit,
    onPublicProfileChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onCreateCategory: (String) -> Unit = {},
    onDelete: () -> Unit = {}
) {
    var expandedCategory by remember { mutableStateOf(false) }
    var expandedFieldType by remember { mutableStateOf(false) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    // Track selected custom category name (if a custom category is selected)
    var selectedCustomCategoryName by remember { mutableStateOf<String?>(null) }

    // Determine displayed category name
    val displayedCategoryName = selectedCustomCategoryName ?: state.category?.displayName ?: "Select category"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (state.isEditing) "Edit Field" else "Add Field")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Category (first) - includes both enum categories and custom categories
                ExposedDropdownMenuBox(
                    expanded = expandedCategory,
                    onExpandedChange = { expandedCategory = it }
                ) {
                    OutlinedTextField(
                        value = displayedCategoryName,
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
                        // Built-in categories
                        DataCategory.values().forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.displayName) },
                                onClick = {
                                    onCategoryChange(category)
                                    selectedCustomCategoryName = null
                                    expandedCategory = false
                                }
                            )
                        }
                        // Custom categories (if any)
                        if (customCategories.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                text = "Custom Categories",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                            customCategories.forEach { customCat ->
                                DropdownMenuItem(
                                    text = { Text(customCat.name) },
                                    onClick = {
                                        // Map custom category to OTHER for storage, but track name for display
                                        onCategoryChange(DataCategory.OTHER)
                                        selectedCustomCategoryName = customCat.name
                                        expandedCategory = false
                                    }
                                )
                            }
                        }
                        HorizontalDivider()
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
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words
                    )
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
                        // Filter out PASSWORD type - it's for minor secrets, not personal data
                        FieldType.values().filter { it != FieldType.PASSWORD }.forEach { fieldType ->
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

                // 4. Value - use PhoneNumberInput for phone fields
                if (state.fieldType == FieldType.PHONE) {
                    PhoneNumberInput(
                        value = state.value,
                        onValueChange = onValueChange,
                        label = "Phone Number",
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.valueError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = state.value,
                        onValueChange = onValueChange,
                        label = { Text("Value") },
                        isError = state.valueError != null,
                        supportingText = state.valueError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = if (state.fieldType == FieldType.NOTE) 3 else 1,
                        maxLines = if (state.fieldType == FieldType.NOTE) 5 else 1,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (state.fieldType) {
                                FieldType.EMAIL -> KeyboardType.Email
                                FieldType.NUMBER -> KeyboardType.Number
                                FieldType.URL -> KeyboardType.Uri
                                else -> KeyboardType.Text
                            },
                            capitalization = when (state.fieldType) {
                                FieldType.EMAIL, FieldType.URL, FieldType.PASSWORD, FieldType.NUMBER -> KeyboardCapitalization.None
                                FieldType.NOTE -> KeyboardCapitalization.Sentences
                                else -> KeyboardCapitalization.Words
                            }
                        )
                    )
                }

                // 5. Show in public profile toggle
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show in public profile",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (state.isInPublicProfile) "Visible to connections" else "Hidden from profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.isInPublicProfile,
                        onCheckedChange = onPublicProfileChange
                    )
                }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Delete button (only shown when editing, not for system fields)
                if (state.isEditing && state.id?.startsWith("_system_") != true) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
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
                publishedProfile == null || !publishedProfile.isFromVault || (publishedProfile.items.isEmpty() && publishedProfile.photo == null) -> {
                    // No published profile yet (no items and no photo)
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

                        // Profile card - scrollable (read-only view)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            BusinessCardView(
                                profile = publishedProfile,
                                isReadOnly = true
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

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
 * Modern biographical profile view with a clean, engaging design.
 * Shows the user's identity prominently with clickable contact info.
 */
@Composable
private fun BusinessCardView(
    profile: PublishedProfileData,
    isReadOnly: Boolean = false,
    onEditPhoto: () -> Unit = {}
) {
    val context = LocalContext.current

    // Extract primary identity fields
    val firstName = profile.items.find { it.name == "First Name" }?.value ?: ""
    val lastName = profile.items.find { it.name == "Last Name" }?.value ?: ""
    val fullName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
    val email = profile.items.find { it.name == "Email" }?.value
    val phone = profile.items.find { it.name.contains("Phone", ignoreCase = true) }?.value

    // Group all fields by category, sorted by sortOrder
    // Note: First Name and Last Name are shown in header, Email shown in Contact section,
    // but we still show all identity fields under their category
    val groupedByCategory = profile.items
        .groupBy { it.category ?: DataCategory.OTHER }
        .mapValues { (_, items) -> items.sortedBy { it.sortOrder } }

    // Helper to open intents
    fun openEmail(emailAddress: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$emailAddress")))
        } catch (e: Exception) { /* ignore */ }
    }

    fun openPhone(phoneNumber: String) {
        try {
            val cleaned = phoneNumber.replace(Regex("[^+0-9]"), "")
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleaned")))
        } catch (e: Exception) { /* ignore */ }
    }

    // Decode Base64 photo if present
    val photoBitmap = remember(profile.photo) {
        profile.photo?.let { base64 ->
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Hero section with large avatar and name
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Large avatar - show photo if available, otherwise initials
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .then(if (!isReadOnly) Modifier.clickable { onEditPhoto() } else Modifier)
                ) {
                    if (photoBitmap != null) {
                        Image(
                            bitmap = photoBitmap.asImageBitmap(),
                            contentDescription = "Profile photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 4.dp
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
                                    style = MaterialTheme.typography.displaySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    // Camera icon overlay (only shown when not read-only)
                    if (!isReadOnly) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(28.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = "Edit photo",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Name
                if (fullName.isNotBlank()) {
                    Text(
                        text = fullName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Contact details card (clickable text, no buttons)
        if (email != null || phone != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "CONTACT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    email?.let { emailAddr ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openEmail(emailAddr) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Email,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Email",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = emailAddr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline
                                )
                            }
                        }
                    }

                    if (email != null && phone != null) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    }

                    phone?.let { phoneNum ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openPhone(phoneNum) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Phone",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = phoneNum,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // Additional fields organized by category
        if (groupedByCategory.isNotEmpty()) {
            val categoryOrder = listOf(
                DataCategory.IDENTITY,
                DataCategory.CONTACT,
                DataCategory.ADDRESS,
                DataCategory.FINANCIAL,
                DataCategory.MEDICAL,
                DataCategory.OTHER
            )

            categoryOrder.forEach { category ->
                val categoryItems = groupedByCategory[category]
                if (categoryItems != null && categoryItems.isNotEmpty()) {
                    // Skip items already shown in Contact card (email and phone)
                    val filteredItems = categoryItems.filter { item ->
                        val isEmailShown = email != null && item.name == "Email" && item.value == email
                        val isPhoneShown = phone != null && item.name.contains("Phone", ignoreCase = true) && item.value == phone
                        !isEmailShown && !isPhoneShown
                    }

                    if (filteredItems.isNotEmpty()) {
                        ProfileCategorySection(
                            category = category,
                            items = filteredItems
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }

        // Published timestamp - format to local timezone
        profile.updatedAt?.let { updatedAt ->
            val formattedTime = formatPublishedTimestamp(updatedAt) ?: updatedAt
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Verified,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Profile verified \u2022 Updated $formattedTime",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
    val context = LocalContext.current

    // Determine if this field is clickable
    val isPhone = item.name.contains("Phone", ignoreCase = true) ||
                  item.fieldType == FieldType.PHONE
    val isEmail = item.name.contains("Email", ignoreCase = true) ||
                  item.fieldType == FieldType.EMAIL ||
                  item.value.contains("@") && item.value.contains(".")
    val isUrl = item.name.contains("Website", ignoreCase = true) ||
                item.name.contains("LinkedIn", ignoreCase = true) ||
                item.name.contains("GitHub", ignoreCase = true) ||
                item.name.contains("Twitter", ignoreCase = true) ||
                item.name.contains("Instagram", ignoreCase = true) ||
                item.fieldType == FieldType.URL ||
                item.value.startsWith("http://") ||
                item.value.startsWith("https://") ||
                item.value.startsWith("www.")

    val isClickable = isPhone || isEmail || isUrl

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
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = if (isClickable) TextDecoration.Underline else TextDecoration.None
                ),
                color = if (isClickable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (isClickable) {
                    Modifier.clickable {
                        try {
                            val intent = when {
                                isPhone -> {
                                    val phoneNumber = item.value.replace(Regex("[^+0-9]"), "")
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                                }
                                isEmail -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${item.value}"))
                                isUrl -> {
                                    val url = if (item.value.startsWith("http://") || item.value.startsWith("https://")) {
                                        item.value
                                    } else {
                                        "https://${item.value}"
                                    }
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                }
                                else -> null
                            }
                            intent?.let { context.startActivity(it) }
                        } catch (e: Exception) {
                            // Handle error silently
                        }
                    }
                } else Modifier
            )
        }
    }
}
