package com.vettid.app.features.secrets

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vettid.app.core.storage.MinorSecret
import com.vettid.app.core.storage.SecretCategory
import com.vettid.app.core.storage.SecretType
import kotlinx.coroutines.flow.collectLatest

/**
 * Secrets screen content for embedding in MainScaffold.
 * Shows secrets organized by category with QR code display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsContent(
    viewModel: SecretsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val editState by viewModel.editState.collectAsState()
    val showAddDialog by viewModel.showAddDialog.collectAsState()
    val showDeleteConfirmDialog by viewModel.showDeleteConfirmDialog.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Search bar visibility
    var showSearchBar by remember { mutableStateOf(false) }

    // QR code dialog state
    var qrDialogSecret by remember { mutableStateOf<MinorSecret?>(null) }

    // Ensure enrollment key is in secrets on first load
    LaunchedEffect(Unit) {
        viewModel.ensureEnrollmentKeyInSecrets()
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is SecretsEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is SecretsEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is SecretsEffect.ShowQRCode -> {
                    qrDialogSecret = effect.secret
                }
                is SecretsEffect.SecretCopied -> {
                    snackbarHostState.showSnackbar("Copied to clipboard")
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val currentState = state) {
            is SecretsState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is SecretsState.Empty -> {
                EmptySecretsContent(
                    onAddClick = { viewModel.onEvent(SecretsEvent.AddSecret) }
                )
            }

            is SecretsState.Error -> {
                ErrorSecretsContent(
                    message = currentState.message,
                    onRetry = { viewModel.onEvent(SecretsEvent.Refresh) }
                )
            }

            is SecretsState.Loaded -> {
                val groupedByCategory = remember(currentState.items) {
                    GroupedByCategory.fromItems(currentState.items)
                }

                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = { viewModel.onEvent(SecretsEvent.Refresh) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    SecretsList(
                        groupedByCategory = groupedByCategory,
                        hasUnpublishedChanges = currentState.hasUnpublishedChanges,
                        showSearchBar = showSearchBar,
                        searchQuery = currentState.searchQuery,
                        onSearchQueryChanged = { viewModel.onEvent(SecretsEvent.SearchQueryChanged(it)) },
                        onSearchBarClose = { showSearchBar = false },
                        onSecretClick = { viewModel.onEvent(SecretsEvent.SecretClicked(it)) },
                        onCopyClick = { viewModel.onEvent(SecretsEvent.CopySecret(it)) },
                        onDeleteClick = { viewModel.onEvent(SecretsEvent.DeleteSecret(it)) },
                        onTogglePublicProfile = { viewModel.onEvent(SecretsEvent.TogglePublicProfile(it)) },
                        onMoveUp = { viewModel.onEvent(SecretsEvent.MoveSecretUp(it)) },
                        onMoveDown = { viewModel.onEvent(SecretsEvent.MoveSecretDown(it)) },
                        onPublishClick = { viewModel.onEvent(SecretsEvent.PublishPublicKeys) }
                    )
                }
            }
        }

        // FABs - always show when not in error state
        if (state !is SecretsState.Error) {
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
                    onClick = { viewModel.onEvent(SecretsEvent.AddSecret) }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add secret")
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
        AddSecretDialog(
            state = editState,
            onStateChange = { viewModel.updateEditState(it) },
            onSave = { viewModel.saveSecret() },
            onDismiss = { viewModel.dismissAddDialog() }
        )
    }

    // Delete confirmation dialog
    showDeleteConfirmDialog?.let { secretId ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmDialog() },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Secret?") },
            text = {
                Text("This action cannot be undone. The secret will be permanently deleted.")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteSecret(secretId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirmDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // QR Code dialog
    qrDialogSecret?.let { secret ->
        SecretQRDialog(
            secret = secret,
            onDismiss = { qrDialogSecret = null },
            onCopy = {
                viewModel.onEvent(SecretsEvent.CopySecret(secret.id))
            }
        )
    }
}

@Composable
private fun SecretsList(
    groupedByCategory: GroupedByCategory,
    hasUnpublishedChanges: Boolean,
    showSearchBar: Boolean,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onSearchBarClose: () -> Unit,
    onSecretClick: (String) -> Unit,
    onCopyClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onTogglePublicProfile: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onPublishClick: () -> Unit
) {
    // Track collapsed categories - start with all expanded
    var collapsedCategories by remember { mutableStateOf(emptySet<SecretCategory>()) }

    // Filter items based on search
    val filteredGroups: Map<SecretCategory, List<MinorSecret>> = if (searchQuery.isBlank()) {
        groupedByCategory.categories
    } else {
        groupedByCategory.categories.mapNotNull { (category, items) ->
            val filtered = items.filter { item ->
                item.name.contains(searchQuery, ignoreCase = true) ||
                item.notes?.contains(searchQuery, ignoreCase = true) == true ||
                item.value.contains(searchQuery, ignoreCase = true)
            }
            if (filtered.isNotEmpty()) category to filtered else null
        }.toMap()
    }

    // Auto-expand categories with search matches
    LaunchedEffect(searchQuery, filteredGroups) {
        if (searchQuery.isNotBlank()) {
            collapsedCategories = collapsedCategories - filteredGroups.keys
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Search bar
        if (showSearchBar) {
            item {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChanged,
                    onClose = onSearchBarClose,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
        }

        // Publish banner (if unpublished public keys)
        if (hasUnpublishedChanges) {
            item {
                PublishBanner(onPublishClick = onPublishClick)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Category sections
        filteredGroups.forEach { (category, categoryItems) ->
            val isCollapsed = collapsedCategories.contains(category)

            item(key = "header_${category.name}") {
                CollapsibleCategoryHeader(
                    title = category.displayName,
                    icon = getCategoryIcon(category),
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
                itemsIndexed(categoryItems, key = { _, item -> item.id }) { index, secret ->
                    val isFirst = index == 0
                    val isLast = index == categoryItems.size - 1
                    SecretRow(
                        secret = secret,
                        isFirst = isFirst,
                        isLast = isLast,
                        onClick = { onSecretClick(secret.id) },
                        onCopy = { onCopyClick(secret.id) },
                        onDelete = { onDeleteClick(secret.id) },
                        onTogglePublic = { onTogglePublicProfile(secret.id) },
                        onMoveUp = { onMoveUp(secret.id) },
                        onMoveDown = { onMoveDown(secret.id) }
                    )
                }
            }

            item(key = "spacer_${category.name}") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Empty search results
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
        placeholder = { Text("Search secrets...") },
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

@Composable
private fun PublishBanner(onPublishClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Unpublished Public Keys",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Publish to update your public profile",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Button(
                onClick = onPublishClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Publish")
            }
        }
    }
}

@Composable
private fun CollapsibleCategoryHeader(
    title: String,
    icon: ImageVector,
    itemCount: Int,
    isCollapsed: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            ) {
                Text(
                    text = itemCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SecretRow(
    secret: MinorSecret,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onTogglePublic: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (secret.isSystemField)
                    MaterialTheme.colorScheme.tertiaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getSecretTypeIcon(secret.type),
                        contentDescription = null,
                        tint = if (secret.isSystemField)
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name and value preview
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = secret.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (secret.isSystemField) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "System field",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (secret.isInPublicProfile) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Public,
                            contentDescription = "In public profile",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = truncateValue(secret.value),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // QR code button
            IconButton(onClick = onClick) {
                Icon(
                    Icons.Default.QrCode,
                    contentDescription = "Show QR code",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Menu button
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = {
                            showMenu = false
                            onCopy()
                        }
                    )
                    if (secret.type == SecretType.PUBLIC_KEY) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (secret.isInPublicProfile)
                                        "Remove from profile"
                                    else
                                        "Add to profile"
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (secret.isInPublicProfile)
                                        Icons.Default.VisibilityOff
                                    else
                                        Icons.Default.Visibility,
                                    null
                                )
                            },
                            onClick = {
                                showMenu = false
                                onTogglePublic()
                            }
                        )
                    }
                    if (!isFirst) {
                        DropdownMenuItem(
                            text = { Text("Move up") },
                            leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                            onClick = {
                                showMenu = false
                                onMoveUp()
                            }
                        )
                    }
                    if (!isLast) {
                        DropdownMenuItem(
                            text = { Text("Move down") },
                            leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                            onClick = {
                                showMenu = false
                                onMoveDown()
                            }
                        )
                    }
                    if (!secret.isSystemField) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretQRDialog(
    secret: MinorSecret,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    val qrBitmap = remember(secret.value) {
        generateQrCode(secret.value, 300)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    getSecretTypeIcon(secret.type),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = secret.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // QR code
                qrBitmap?.let { bitmap ->
                    Card(
                        modifier = Modifier.size(280.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "QR Code for ${secret.name}",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                } ?: run {
                    Box(
                        modifier = Modifier.size(280.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Could not generate QR code",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Full value display
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    SelectionContainer {
                        Text(
                            text = secret.value,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Notes if present
                secret.notes?.let { notes ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onCopy) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSecretDialog(
    state: EditSecretState,
    onStateChange: (EditSecretState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var expandedCategory by remember { mutableStateOf(false) }
    var expandedType by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (state.isEditing) "Edit Secret" else "Add Secret")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Category dropdown (first - helps set context)
                ExposedDropdownMenuBox(
                    expanded = expandedCategory,
                    onExpandedChange = { expandedCategory = it }
                ) {
                    OutlinedTextField(
                        value = state.category.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategory) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCategory,
                        onDismissRequest = { expandedCategory = false }
                    ) {
                        SecretCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.displayName) },
                                leadingIcon = { Icon(getCategoryIcon(category), null) },
                                onClick = {
                                    onStateChange(state.copy(category = category))
                                    expandedCategory = false
                                }
                            )
                        }
                    }
                }

                // Name field
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { onStateChange(state.copy(name = it, nameError = null)) },
                    label = { Text("Name") },
                    placeholder = { Text("e.g., Bitcoin Wallet") },
                    isError = state.nameError != null,
                    supportingText = state.nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Type dropdown
                ExposedDropdownMenuBox(
                    expanded = expandedType,
                    onExpandedChange = { expandedType = it }
                ) {
                    OutlinedTextField(
                        value = state.type.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        SecretType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.displayName) },
                                leadingIcon = { Icon(getSecretTypeIcon(type), null) },
                                onClick = {
                                    onStateChange(state.copy(type = type))
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                // Value field
                OutlinedTextField(
                    value = state.value,
                    onValueChange = { onStateChange(state.copy(value = it, valueError = null)) },
                    label = { Text("Value") },
                    placeholder = { Text("Secret value...") },
                    isError = state.valueError != null,
                    supportingText = state.valueError?.let { { Text(it) } },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                // Notes field (optional)
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = { onStateChange(state.copy(notes = it)) },
                    label = { Text("Notes (optional)") },
                    placeholder = { Text("Additional notes...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Public profile toggle (only for PUBLIC_KEY)
                if (state.type == SecretType.PUBLIC_KEY) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Share to public profile",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = state.isInPublicProfile,
                            onCheckedChange = { onStateChange(state.copy(isInPublicProfile = it)) }
                        )
                    }
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
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EmptySecretsContent(onAddClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "No Secrets Yet",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Store API keys, passwords, crypto wallets, and other sensitive data securely.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onAddClick) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Secret")
            }
        }
    }
}

@Composable
private fun ErrorSecretsContent(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(24.dp))
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

private fun getCategoryIcon(category: SecretCategory): ImageVector {
    return when (category) {
        SecretCategory.CRYPTOCURRENCY -> Icons.Default.CurrencyBitcoin
        SecretCategory.BANK_ACCOUNT -> Icons.Default.AccountBalance
        SecretCategory.CREDIT_CARD -> Icons.Default.CreditCard
        SecretCategory.INSURANCE -> Icons.Default.HealthAndSafety
        SecretCategory.DRIVERS_LICENSE -> Icons.Default.Badge
        SecretCategory.PASSPORT -> Icons.Default.Flight
        SecretCategory.SSN -> Icons.Default.Security
        SecretCategory.API_KEY -> Icons.Default.Key
        SecretCategory.PASSWORD -> Icons.Default.Password
        SecretCategory.WIFI -> Icons.Default.Wifi
        SecretCategory.CERTIFICATE -> Icons.Default.VerifiedUser
        SecretCategory.NOTE -> Icons.Default.Notes
        SecretCategory.OTHER -> Icons.Default.Category
    }
}

private fun getSecretTypeIcon(type: SecretType): ImageVector {
    return when (type) {
        SecretType.PUBLIC_KEY -> Icons.Default.VpnKey
        SecretType.PRIVATE_KEY -> Icons.Default.Key
        SecretType.TOKEN -> Icons.Default.Token
        SecretType.PASSWORD -> Icons.Default.Password
        SecretType.PIN -> Icons.Default.Pin
        SecretType.ACCOUNT_NUMBER -> Icons.Default.Numbers
        SecretType.SEED_PHRASE -> Icons.Default.FormatListNumbered
        SecretType.TEXT -> Icons.Default.TextFields
    }
}

private fun truncateValue(value: String): String {
    return if (value.length <= 40) {
        value
    } else {
        "${value.take(16)}...${value.takeLast(8)}"
    }
}

private fun generateQrCode(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

// MARK: - Legacy Compatibility

/**
 * Full screen version for backwards compatibility.
 * Use SecretsContent for embedding in MainScaffold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsScreenFull(
    viewModel: SecretsViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToAddSecret: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secrets") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            SecretsContent(viewModel = viewModel)
        }
    }
}
