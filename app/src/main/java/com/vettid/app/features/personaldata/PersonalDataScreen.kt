package com.vettid.app.features.personaldata

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * Personal Data screen content for embedding in MainScaffold.
 * Shows personal data organized by type (Public, Private, Keys, Minor Secrets).
 */
@Composable
fun PersonalDataContent(
    viewModel: PersonalDataViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val showAddDialog by viewModel.showAddDialog.collectAsState()
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
                    onAddClick = { viewModel.onEvent(PersonalDataEvent.AddItem) }
                )
            }

            is PersonalDataState.Error -> {
                ErrorContent(
                    message = currentState.message,
                    onRetry = { viewModel.onEvent(PersonalDataEvent.Refresh) }
                )
            }

            is PersonalDataState.Loaded -> {
                PersonalDataList(
                    groupedByCategory = viewModel.getDataByCategory(),
                    onItemClick = { viewModel.onEvent(PersonalDataEvent.ItemClicked(it)) },
                    onDeleteClick = { viewModel.onEvent(PersonalDataEvent.DeleteItem(it)) },
                    onTogglePublicProfile = { viewModel.onEvent(PersonalDataEvent.TogglePublicProfile(it)) }
                )
            }
        }

        // Snackbar host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // Add/Edit dialog
    if (showAddDialog) {
        AddEditDataDialog(
            state = editState,
            onNameChange = viewModel::updateEditName,
            onValueChange = viewModel::updateEditValue,
            onTypeChange = viewModel::updateEditType,
            onCategoryChange = viewModel::updateEditCategory,
            onSave = viewModel::saveItem,
            onDismiss = viewModel::dismissDialog
        )
    }
}

@Composable
private fun PersonalDataList(
    groupedByCategory: GroupedByCategory,
    onItemClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onTogglePublicProfile: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Group data by category
        groupedByCategory.categories.forEach { (category, categoryItems) ->
            item {
                SectionHeader(
                    title = category.displayName.uppercase(),
                    subtitle = getCategorySubtitle(category)
                )
            }
            items(categoryItems, key = { it.id }) { item ->
                PersonalDataCard(
                    item = item,
                    onClick = { onItemClick(item.id) },
                    onDelete = { onDeleteClick(item.id) },
                    onTogglePublicProfile = { onTogglePublicProfile(item.id) },
                    maskValue = item.type == DataType.PRIVATE ||
                               item.type == DataType.KEY ||
                               item.type == DataType.MINOR_SECRET
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/**
 * Get subtitle for a category.
 */
private fun getCategorySubtitle(category: DataCategory): String {
    return when (category) {
        DataCategory.IDENTITY -> "Name, birthdate, and identification"
        DataCategory.CONTACT -> "Phone, email, and social profiles"
        DataCategory.ADDRESS -> "Physical and mailing addresses"
        DataCategory.FINANCIAL -> "Bank accounts and payment info"
        DataCategory.MEDICAL -> "Health and medical information"
        DataCategory.CRYPTO -> "Cryptocurrency addresses and keys"
        DataCategory.DOCUMENT -> "Documents and files"
        DataCategory.OTHER -> "Other personal information"
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PersonalDataCard(
    item: PersonalDataItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePublicProfile: () -> Unit,
    maskValue: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon based on category or type
                Icon(
                    imageVector = when (item.category) {
                        DataCategory.IDENTITY -> Icons.Default.Person
                        DataCategory.CONTACT -> Icons.Default.Phone
                        DataCategory.ADDRESS -> Icons.Default.LocationOn
                        DataCategory.FINANCIAL -> Icons.Default.AccountBalance
                        DataCategory.MEDICAL -> Icons.Default.MedicalServices
                        DataCategory.CRYPTO -> Icons.Default.CurrencyBitcoin
                        DataCategory.DOCUMENT -> Icons.Default.Description
                        DataCategory.OTHER, null -> Icons.Default.Category
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (item.isSystemField) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "System field",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = if (maskValue) maskString(item.value) else item.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!item.isSystemField) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options"
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
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Public Profile Toggle - only show for non-MINOR_SECRET types
            if (item.type != DataType.MINOR_SECRET) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTogglePublicProfile() }
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.isInPublicProfile) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (item.isInPublicProfile)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (item.isInPublicProfile) "Visible in public profile" else "Hidden from public profile",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isInPublicProfile)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = item.isInPublicProfile,
                        onCheckedChange = { onTogglePublicProfile() },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPersonalDataContent(onAddClick: () -> Unit) {
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
private fun AddEditDataDialog(
    state: EditDataItemState,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onTypeChange: (DataType) -> Unit,
    onCategoryChange: (DataCategory?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var expandedType by remember { mutableStateOf(false) }
    var expandedCategory by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (state.isEditing) "Edit Data" else "Add Data")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Name field
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    isError = state.nameError != null,
                    supportingText = state.nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Value field
                OutlinedTextField(
                    value = state.value,
                    onValueChange = onValueChange,
                    label = { Text("Value") },
                    isError = state.valueError != null,
                    supportingText = state.valueError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
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
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        DataType.values().forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(type.displayName)
                                        Text(
                                            type.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onTypeChange(type)
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                // Category dropdown
                ExposedDropdownMenuBox(
                    expanded = expandedCategory,
                    onExpandedChange = { expandedCategory = it }
                ) {
                    OutlinedTextField(
                        value = state.category?.displayName ?: "Select category",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category (optional)") },
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
                        DropdownMenuItem(
                            text = { Text("None") },
                            onClick = {
                                onCategoryChange(null)
                                expandedCategory = false
                            }
                        )
                        DataCategory.values().forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.displayName) },
                                onClick = {
                                    onCategoryChange(category)
                                    expandedCategory = false
                                }
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
