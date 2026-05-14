package com.vettid.app.features.secrets

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vettid.app.core.storage.MinorSecret
import com.vettid.app.core.storage.SecretCategory
import com.vettid.app.core.storage.SecretType
import com.vettid.app.ui.components.DropdownPickerField
import com.vettid.app.ui.components.FieldDatePickerDialog
import com.vettid.app.ui.components.commonCountries
import com.vettid.app.ui.components.usStatesAndTerritories
import kotlinx.coroutines.flow.collectLatest

/**
 * Secrets screen content for embedding in MainScaffold.
 * Shows secrets organized by category with QR code display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsContent(
    viewModel: SecretsViewModel = hiltViewModel(),
    searchQuery: String = "",
    onNavigateToCriticalSecrets: () -> Unit = {}
) {
    // Route search query from top bar to ViewModel
    LaunchedEffect(searchQuery) {
        viewModel.onEvent(SecretsEvent.SearchQueryChanged(searchQuery))
    }

    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val editState by viewModel.editState.collectAsState()
    val showAddDialog by viewModel.showAddDialog.collectAsState()
    val showDeleteConfirmDialog by viewModel.showDeleteConfirmDialog.collectAsState()
    val catalogNudgeDismissed by viewModel.catalogNudgeDismissed.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // QR code dialog state
    var qrDialogSecret by remember { mutableStateOf<MinorSecret?>(null) }

    // Template chooser and form state
    var showTemplateChooser by remember { mutableStateOf(false) }
    var templateFormState by remember { mutableStateOf<TemplateFormState?>(null) }

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
                    onAddClick = { showTemplateChooser = true },
                    onCriticalSecretsTap = onNavigateToCriticalSecrets
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
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.onEvent(SecretsEvent.Refresh) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    SecretsList(
                        groupedByCategory = groupedByCategory,
                        hasUnpublishedChanges = currentState.hasUnpublishedChanges,
                        searchQuery = currentState.searchQuery,
                        nudgeDismissed = catalogNudgeDismissed,
                        onDismissNudge = { viewModel.dismissCatalogNudge() },
                        onSecretClick = { viewModel.onEvent(SecretsEvent.SecretClicked(it)) },
                        onTogglePublicProfile = { viewModel.onEvent(SecretsEvent.TogglePublicProfile(it)) },
                        onToggleHideFromCatalog = { viewModel.onEvent(SecretsEvent.ToggleHideFromCatalog(it)) },
                        onMoveUp = { /* sort order is vault-driven; UI move is a no-op */ },
                        onMoveDown = { /* sort order is vault-driven; UI move is a no-op */ },
                        onRenameGroup = { groupId, newLabel -> viewModel.renameGroup(groupId, newLabel) },
                        onPublishClick = { viewModel.onEvent(SecretsEvent.PublishPublicKeys) },
                        onCriticalSecretsTap = onNavigateToCriticalSecrets
                    )
                }
            }
        }

        // FAB - always show when not in error state
        if (state !is SecretsState.Error) {
            FloatingActionButton(
                onClick = { showTemplateChooser = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add secret")
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
            onDismiss = { viewModel.dismissAddDialog() },
            onDelete = {
                editState.id?.let { id ->
                    viewModel.onEvent(SecretsEvent.DeleteSecret(id))
                    viewModel.dismissAddDialog()
                }
            }
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
            },
            onDelete = {
                viewModel.onEvent(SecretsEvent.DeleteSecret(secret.id))
                qrDialogSecret = null
            }
        )
    }

    // Template chooser dialog
    if (showTemplateChooser) {
        TemplateChooserDialog(
            onCustomField = {
                showTemplateChooser = false
                viewModel.onEvent(SecretsEvent.AddSecret)
            },
            onTemplateSelected = { template ->
                showTemplateChooser = false
                templateFormState = TemplateFormState(template)
            },
            onDismiss = { showTemplateChooser = false }
        )
    }

    // Template form dialog
    templateFormState?.let { formState ->
        TemplateFormDialog(
            state = formState,
            onFieldValueChange = { index, value ->
                templateFormState = formState.copy(
                    fieldValues = formState.fieldValues + (index to value)
                )
            },
            onGroupNameChange = { name ->
                templateFormState = formState.copy(groupName = name)
            },
            onAliasChange = { alias ->
                templateFormState = formState.copy(alias = alias)
            },
            onSave = {
                viewModel.saveTemplate(formState)
                templateFormState = null
            },
            onDismiss = { templateFormState = null }
        )
    }

}

@Composable
private fun SecretsList(
    groupedByCategory: GroupedByCategory,
    hasUnpublishedChanges: Boolean,
    searchQuery: String,
    nudgeDismissed: Boolean,
    onDismissNudge: () -> Unit,
    onSecretClick: (String) -> Unit,
    onTogglePublicProfile: (String) -> Unit,
    onToggleHideFromCatalog: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onPublishClick: () -> Unit,
    onCriticalSecretsTap: () -> Unit = {}
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

    // Default visibility for new secrets flipped to "Hidden" on
    // 2026-05-12 (plans/data-request-grants.md Phase 3). Surface a
    // nudge so users with already-visible secrets can review them —
    // it appears at the top of the list when there's at least one
    // non-hidden minor secret. Dismissal is persisted (via
    // AppPreferencesStore, hoisted into SecretsViewModel) so the
    // banner shows at most once ever — previously it was a
    // remember-scoped flag and reappeared on every navigation back.
    val visibleSecretsCount = remember(groupedByCategory) {
        groupedByCategory.categories.values.flatten()
            .count { !it.hideFromCatalog }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (!nudgeDismissed && visibleSecretsCount > 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "$visibleSecretsCount secret${if (visibleSecretsCount == 1) "" else "s"} visible to connections",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "New secrets now default to Hidden. Review your existing secrets and hide any you don't want peers to see.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = onDismissNudge) { Text("Dismiss") }
                        }
                    }
                }
            }
        }

        // "Unpublished Public Keys" banner is rendered by
        // VaultProfileSection above the tabs so the Data and Secrets
        // tabs share one consistent publish surface.

        // Critical Secrets card
        item {
            CriticalSecretsCard(onClick = onCriticalSecretsTap)
            Spacer(modifier = Modifier.height(12.dp))
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
                // Build group structure for display
                val groupOrder = buildDisplayGroups(categoryItems)

                groupOrder.forEachIndexed { groupIdx, group ->
                    val isFirstGroup = groupIdx == 0
                    val isLastGroup = groupIdx == groupOrder.size - 1

                    if (group.label != null) {
                        // Group header
                        item(key = "group_${group.key}") {
                            GroupHeader(
                                label = group.label,
                                isFirst = isFirstGroup,
                                isLast = isLastGroup,
                                onMoveUp = { group.items.firstOrNull()?.let { onMoveUp(it.id) } },
                                onMoveDown = { group.items.firstOrNull()?.let { onMoveDown(it.id) } },
                                onRename = { newLabel -> onRenameGroup(group.key, newLabel) }
                            )
                        }
                    }

                    items(group.items, key = { it.id }) { secret ->
                        val isFirst = group.label == null && isFirstGroup
                        val isLast = group.label == null && isLastGroup
                        SecretRow(
                            secret = secret,
                            isFirst = isFirst,
                            isLast = isLast,
                            isInGroup = group.label != null,
                            onClick = { onSecretClick(secret.id) },
                            onTogglePublic = { onTogglePublicProfile(secret.id) },
                            onToggleHideFromCatalog = { onToggleHideFromCatalog(secret.id) },
                            onMoveUp = { onMoveUp(secret.id) },
                            onMoveDown = { onMoveDown(secret.id) }
                        )
                    }
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

// Display group: either a named group (template-based) or a single ungrouped item
private data class DisplayGroup(
    val key: String,       // groupId or item id
    val label: String?,    // null for ungrouped singles
    val items: List<MinorSecret>
)

private fun buildDisplayGroups(categoryItems: List<MinorSecret>): List<DisplayGroup> {
    val result = mutableListOf<DisplayGroup>()
    val seen = mutableSetOf<String>()

    for (item in categoryItems) {
        if (item.id in seen) continue

        val gid = item.groupId
        if (gid != null) {
            if (gid in seen) continue
            seen.add(gid)
            val members = categoryItems.filter { it.groupId == gid }
            members.forEach { seen.add(it.id) }
            result.add(DisplayGroup(
                key = gid,
                label = members.firstOrNull()?.groupLabel ?: gid,
                items = members
            ))
        } else {
            seen.add(item.id)
            result.add(DisplayGroup(
                key = item.id,
                label = null,
                items = listOf(item)
            ))
        }
    }
    return result
}

@Composable
private fun GroupHeader(
    label: String,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: (String) -> Unit = {}
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(label) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = !isFirst,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move group up",
                        modifier = Modifier.size(20.dp),
                        tint = if (!isFirst)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = !isLast,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move group down",
                        modifier = Modifier.size(20.dp),
                        tint = if (!isLast)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (isEditing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.labelMedium,
                    trailingIcon = {
                        IconButton(onClick = {
                            if (editText.isNotBlank()) {
                                onRename(editText.trim())
                            }
                            isEditing = false
                        }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Check, "Save", modifier = Modifier.size(16.dp))
                        }
                    }
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            editText = label
                            isEditing = true
                        }
                )
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Rename group",
                    modifier = Modifier
                        .size(14.dp)
                        .clickable {
                            editText = label
                            isEditing = true
                        },
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun SecretRow(
    secret: MinorSecret,
    isFirst: Boolean,
    isLast: Boolean,
    isInGroup: Boolean = false,
    onClick: () -> Unit,
    onTogglePublic: () -> Unit,
    onToggleHideFromCatalog: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag-to-reorder handle (long-press, then drag).
            // Hidden for grouped items — the group header is the
            // reorder unit for the whole group.
            if (!isInGroup) {
                com.vettid.app.ui.components.DragReorderHandle(
                    canMoveUp = !isFirst,
                    canMoveDown = !isLast,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Visibility selector (3-state) or lock icon for system fields.
            // Placed after the name+value column on the right (see end of Row).
            if (secret.isSystemField) {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "System field (always shared)",
                        modifier = Modifier.size(22.dp),
                        tint = androidx.compose.ui.graphics.Color(0xFFD4A017) // Gold
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Name and value - clickable to edit (or show QR for public keys)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick)
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = secret.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                // Show the type under the name (mirrors the data tab's
                // "value under field name" treatment). The actual
                // value stays out of the list — tap to reveal.
                Text(
                    text = if (secret.alias.isNotBlank()) {
                        "${secret.type.displayName} • ${secret.alias}"
                    } else {
                        secret.type.displayName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 3-state visibility selector. Hidden for system fields
            // (gold lock above) and shown otherwise. PROFILE pill is
            // only enabled for PUBLIC_KEY-typed secrets.
            if (!secret.isSystemField) {
                Spacer(modifier = Modifier.width(8.dp))
                val current = when {
                    secret.hideFromCatalog -> com.vettid.app.ui.components.FieldVisibility.PRIVATE
                    secret.isInPublicProfile -> com.vettid.app.ui.components.FieldVisibility.PROFILE
                    else -> com.vettid.app.ui.components.FieldVisibility.CATALOG
                }
                com.vettid.app.ui.components.VisibilitySegmented(
                    visibility = current,
                    allowProfile = secret.type == SecretType.PUBLIC_KEY,
                    // Minor secrets don't get USE_ONLY — that state is
                    // specific to credential-bound critical secrets.
                    allowUseOnly = false,
                    onVisibilityChange = { next ->
                        when (next) {
                            com.vettid.app.ui.components.FieldVisibility.PROFILE -> {
                                if (secret.hideFromCatalog) onToggleHideFromCatalog()
                                if (!secret.isInPublicProfile) onTogglePublic()
                            }
                            com.vettid.app.ui.components.FieldVisibility.CATALOG -> {
                                if (secret.hideFromCatalog) onToggleHideFromCatalog()
                                if (secret.isInPublicProfile) onTogglePublic()
                            }
                            com.vettid.app.ui.components.FieldVisibility.USE_ONLY -> {
                                // Not reachable when allowUseOnly=false.
                            }
                            com.vettid.app.ui.components.FieldVisibility.PRIVATE -> {
                                if (secret.isInPublicProfile) onTogglePublic()
                                if (!secret.hideFromCatalog) onToggleHideFromCatalog()
                            }
                        }
                    },
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun SecretQRDialog(
    secret: MinorSecret,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onDelete: (() -> Unit)? = null
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
                // QR code - tap to copy
                qrBitmap?.let { bitmap ->
                    Card(
                        onClick = onCopy,
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
                                contentDescription = "QR Code for ${secret.name} - tap to copy",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Text(
                        text = "Tap QR code to copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
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
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            if (onDelete != null && !secret.isSystemField) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
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
    onDismiss: () -> Unit,
    onDelete: () -> Unit = {}
) {
    var expandedCategory by remember { mutableStateOf(false) }
    var expandedType by remember { mutableStateOf(false) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var customCategoryName by remember { mutableStateOf<String?>(null) }
    var selectedNetwork by remember { mutableStateOf<com.vettid.app.core.util.CryptoNetwork?>(null) }
    var expandedNetwork by remember { mutableStateOf(false) }

    /**
     * Compose the catalog alias from the network ticker + the user's
     * alias entry: "BTC · Trading Wallet". Drives the existing alias-
     * grouping in the catalog dialog.
     */
    fun composedAlias(raw: String): String {
        val ticker = selectedNetwork?.takeIf { it.ticker != "OTHER" }?.ticker
        val rawTrim = raw.trim()
        return when {
            ticker != null && rawTrim.isNotEmpty() -> "$ticker · $rawTrim"
            ticker != null -> ticker
            else -> rawTrim
        }
    }

    // New category dialog
    if (showNewCategoryDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewCategoryDialog = false },
            title = { Text("New Category") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Category Name") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            customCategoryName = newName
                            onStateChange(state.copy(category = SecretCategory.OTHER))
                            showNewCategoryDialog = false
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewCategoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (state.isEditing) "Edit Secret" else "Add Secret")
        },
        text = {
            // The form fields collectively grow taller than the dialog
            // can render (especially once a Crypto Network picker, Notes,
            // and a long Value field are visible). Wrap in a bounded
            // scrollable column so the user can reach Notes and the
            // Save button below.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Category dropdown (first - helps set context)
                ExposedDropdownMenuBox(
                    expanded = expandedCategory,
                    onExpandedChange = { expandedCategory = it }
                ) {
                    OutlinedTextField(
                        value = customCategoryName ?: state.category.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategory) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCategory,
                        onDismissRequest = { expandedCategory = false },
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        SecretCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.displayName) },
                                leadingIcon = { Icon(getCategoryIcon(category), null) },
                                onClick = {
                                    onStateChange(state.copy(category = category))
                                    customCategoryName = null
                                    expandedCategory = false
                                }
                            )
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

                // Alias field (optional) — groups related secrets in
                // the catalog (e.g. "Trading Wallet", "Hardware Backup").
                OutlinedTextField(
                    value = state.alias,
                    onValueChange = { onStateChange(state.copy(alias = it)) },
                    label = { Text("Alias (optional)") },
                    placeholder = { Text("e.g. Trading Wallet") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Crypto network picker — shown when category is
                // Cryptocurrency. Composed into alias on save as
                // "TICKER · <user alias>".
                if (state.category == SecretCategory.CRYPTOCURRENCY) {
                    ExposedDropdownMenuBox(
                        expanded = expandedNetwork,
                        onExpandedChange = { expandedNetwork = it }
                    ) {
                        OutlinedTextField(
                            value = selectedNetwork?.let { "${it.ticker} — ${it.displayName}" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Network (optional)") },
                            placeholder = { Text("Pick a chain") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedNetwork) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedNetwork,
                            onDismissRequest = { expandedNetwork = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("(none)") },
                                onClick = {
                                    selectedNetwork = null
                                    expandedNetwork = false
                                }
                            )
                            com.vettid.app.core.util.CryptoNetworks.all.forEach { net ->
                                DropdownMenuItem(
                                    text = { Text("${net.ticker} — ${net.displayName}") },
                                    onClick = {
                                        selectedNetwork = net
                                        expandedNetwork = false
                                    }
                                )
                            }
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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
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
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Apply the composed alias (network ticker + user
                    // alias) into state right before save fires, so
                    // the catalog groups by chain.
                    onStateChange(state.copy(alias = composedAlias(state.alias)))
                    onSave()
                },
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
                if (state.isEditing && state.id?.startsWith("enrollment_") != true) {
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
}

@Composable
private fun EmptySecretsContent(
    onAddClick: () -> Unit,
    onCriticalSecretsTap: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Critical Secrets card at top
        CriticalSecretsCard(onClick = onCriticalSecretsTap)

        // Empty state centered below
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
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

// MARK: - Template Chooser

@Composable
private fun TemplateChooserDialog(
    onCustomField: () -> Unit,
    onTemplateSelected: (SecretTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Secret") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Custom field option (always first)
                Surface(
                    onClick = onCustomField,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Custom Field",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Add a single field with custom name and value",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "TEMPLATES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                // Template options
                SecretTemplate.all.forEach { template ->
                    Surface(
                        onClick = { onTemplateSelected(template) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                getCategoryIcon(template.category),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    template.name,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    template.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "${template.fields.size} fields",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TemplateFormDialog(
    state: TemplateFormState,
    onFieldValueChange: (Int, String) -> Unit,
    onGroupNameChange: (String) -> Unit = {},
    onAliasChange: (String) -> Unit = {},
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    // Track which date picker is open (by field index)
    var datePickerFieldIndex by remember { mutableIntStateOf(-1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    getCategoryIcon(state.template.category),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(state.template.name)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Group name prompt (e.g., "Wallet Name" for crypto wallets)
                state.template.groupNamePrompt?.let { prompt ->
                    OutlinedTextField(
                        value = state.groupName,
                        onValueChange = onGroupNameChange,
                        label = { Text(prompt) },
                        placeholder = { Text("e.g., Bitcoin Main") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Alias — optional catalog disambiguator, same as the
                // Personal Data template form. Lets two entries of the
                // same template ("Credit Card") be told apart.
                OutlinedTextField(
                    value = state.alias,
                    onValueChange = onAliasChange,
                    label = { Text("Alias (optional)") },
                    placeholder = { Text("e.g. Visa, Amex, Joint Account") },
                    supportingText = { Text("Helps tell similar entries apart in your catalog.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                state.template.fields.forEachIndexed { index, field ->
                    when (field.inputHint) {
                        FieldInputHint.DATE, FieldInputHint.EXPIRY_DATE -> {
                            // Date field — read-only field that opens a date
                            // picker on tap. enabled = false (not just
                            // readOnly) so the field doesn't consume the tap
                            // itself — a readOnly-but-enabled OutlinedTextField
                            // swallows pointer input, leaving the .clickable
                            // dead and the picker unreachable. Disabled colors
                            // overridden so it still reads as an active field.
                            OutlinedTextField(
                                value = state.getValue(index),
                                onValueChange = {},
                                label = { Text(field.name) },
                                placeholder = {
                                    Text(if (field.inputHint == FieldInputHint.EXPIRY_DATE) "MM/YYYY" else "MM/DD/YYYY")
                                },
                                readOnly = true,
                                enabled = false,
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.CalendarMonth,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { datePickerFieldIndex = index }
                            )
                        }
                        FieldInputHint.COUNTRY -> {
                            DropdownPickerField(
                                value = state.getValue(index),
                                onValueChange = { onFieldValueChange(index, it) },
                                label = field.name,
                                options = commonCountries,
                                leadingIcon = Icons.Default.Public
                            )
                        }
                        FieldInputHint.STATE -> {
                            DropdownPickerField(
                                value = state.getValue(index),
                                onValueChange = { onFieldValueChange(index, it) },
                                label = field.name,
                                options = usStatesAndTerritories,
                                leadingIcon = Icons.Default.LocationOn
                            )
                        }
                        else -> {
                            // Standard text field with appropriate keyboard
                            val (keyboardType, capitalization, visualTransformation) = when (field.inputHint) {
                                FieldInputHint.NUMBER -> Triple(
                                    KeyboardType.Number,
                                    KeyboardCapitalization.None,
                                    VisualTransformation.None
                                )
                                FieldInputHint.PIN -> Triple(
                                    KeyboardType.NumberPassword,
                                    KeyboardCapitalization.None,
                                    PasswordVisualTransformation()
                                )
                                FieldInputHint.PASSWORD -> Triple(
                                    KeyboardType.Password,
                                    KeyboardCapitalization.None,
                                    PasswordVisualTransformation()
                                )
                                else -> Triple(
                                    KeyboardType.Text,
                                    KeyboardCapitalization.Words,
                                    VisualTransformation.None
                                )
                            }
                            OutlinedTextField(
                                value = state.getValue(index),
                                onValueChange = { onFieldValueChange(index, it) },
                                label = { Text(field.name) },
                                placeholder = {
                                    if (field.placeholder.isNotEmpty()) {
                                        Text(field.placeholder)
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = keyboardType,
                                    capitalization = capitalization
                                ),
                                visualTransformation = visualTransformation,
                                leadingIcon = {
                                    Icon(
                                        getSecretTypeIcon(field.type),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
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

    // Date picker — a scrolling-wheel roller, not a calendar grid.
    // EXPIRY_DATE fields (e.g. credit-card expiry) roll month + year
    // only; DATE fields add a day column.
    if (datePickerFieldIndex >= 0) {
        val field = state.template.fields[datePickerFieldIndex]
        val isExpiryOnly = field.inputHint == FieldInputHint.EXPIRY_DATE

        FieldDatePickerDialog(
            monthYearOnly = isExpiryOnly,
            initialValue = state.getValue(datePickerFieldIndex),
            onDismiss = { datePickerFieldIndex = -1 },
            onConfirm = { formatted ->
                onFieldValueChange(datePickerFieldIndex, formatted)
                datePickerFieldIndex = -1
            }
        )
    }
}

// DropdownPickerField, commonCountries, usStatesAndTerritories imported from ui.components

@Composable
private fun CriticalSecretsCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Critical Secrets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Secured in your credential",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f)
            )
        }
    }
}

// MARK: - Utility Functions

private fun getCategoryIcon(category: SecretCategory): ImageVector {
    return when (category) {
        SecretCategory.IDENTITY -> Icons.Default.Fingerprint
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
        SecretCategory.NOTE -> Icons.AutoMirrored.Filled.Notes
        SecretCategory.LOGIN -> Icons.AutoMirrored.Filled.Login
        SecretCategory.TOTP -> Icons.Default.Timer
        SecretCategory.SOFTWARE_LICENSE -> Icons.Default.Key
        SecretCategory.VPN -> Icons.Default.VpnKey
        SecretCategory.SSH -> Icons.Default.Terminal
        SecretCategory.VEHICLE -> Icons.Default.DirectionsCar
        SecretCategory.LOYALTY -> Icons.Default.CardGiftcard
        SecretCategory.TAX -> Icons.AutoMirrored.Filled.ReceiptLong
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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

