package com.vettid.app.features.postenrollment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.storage.CustomField
import com.vettid.app.core.storage.FieldCategory
import com.vettid.app.core.storage.OptionalField
import com.vettid.app.ui.components.BirthdayPickerInput
import com.vettid.app.ui.components.PhoneNumberInput
import kotlinx.coroutines.flow.collectLatest

/**
 * Screen for collecting and managing personal data after enrollment.
 *
 * Shows:
 * - Read-only system fields (firstName, lastName, email) with lock icon
 * - Editable optional fields (phone, address, birthday)
 * - Custom fields that users can add/edit/remove
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalDataCollectionScreen(
    onNavigateToMain: () -> Unit,
    viewModel: PersonalDataViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle side effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is PersonalDataEffect.NavigateToMain -> onNavigateToMain()
                is PersonalDataEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personal Data") },
                actions = {
                    if (state.hasPendingSync) {
                        IconButton(
                            onClick = { viewModel.onEvent(PersonalDataEvent.SyncNow) },
                            enabled = !state.isSyncing
                        ) {
                            if (state.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = "Sync now")
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Error message
                    state.error?.let { error ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.onEvent(PersonalDataEvent.DismissError) }
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // System Fields (Read-Only)
                    SystemFieldsSection(systemFields = state.systemFields)

                    Spacer(modifier = Modifier.height(24.dp))

                    // Optional Fields (Editable)
                    OptionalFieldsSection(
                        optionalFields = state.optionalFields,
                        onFieldUpdate = { field, value ->
                            viewModel.onEvent(PersonalDataEvent.UpdateOptionalField(field, value))
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Custom Fields
                    CustomFieldsSection(
                        customFields = state.customFields,
                        onAddField = { viewModel.onEvent(PersonalDataEvent.ShowAddFieldDialog) },
                        onEditField = { viewModel.onEvent(PersonalDataEvent.ShowEditFieldDialog(it)) },
                        onRemoveField = { viewModel.onEvent(PersonalDataEvent.RemoveCustomField(it)) }
                    )
                }

                // Bottom buttons
                BottomButtons(
                    hasPendingSync = state.hasPendingSync,
                    onSkip = { viewModel.onEvent(PersonalDataEvent.Skip) },
                    onContinue = { viewModel.onEvent(PersonalDataEvent.Continue) }
                )
            }
        }

        // Add Custom Field Dialog
        if (state.showAddFieldDialog) {
            AddCustomFieldDialog(
                onDismiss = { viewModel.onEvent(PersonalDataEvent.HideAddFieldDialog) },
                onAdd = { name, value, category ->
                    viewModel.onEvent(PersonalDataEvent.AddCustomField(name, value, category))
                }
            )
        }

        // Edit Custom Field Dialog
        state.editingField?.let { field ->
            EditCustomFieldDialog(
                field = field,
                onDismiss = { viewModel.onEvent(PersonalDataEvent.HideEditFieldDialog) },
                onSave = { viewModel.onEvent(PersonalDataEvent.UpdateCustomField(it)) },
                onDelete = { viewModel.onEvent(PersonalDataEvent.RemoveCustomField(field.id)) }
            )
        }
    }
}

@Composable
private fun SystemFieldsSection(systemFields: com.vettid.app.core.storage.SystemPersonalData?) {
    Text(
        text = "REGISTRATION INFO",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(8.dp))

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (systemFields != null) {
                ReadOnlyField(
                    label = "First Name",
                    value = systemFields.firstName
                )
                Spacer(modifier = Modifier.height(12.dp))
                ReadOnlyField(
                    label = "Last Name",
                    value = systemFields.lastName
                )
                Spacer(modifier = Modifier.height(12.dp))
                ReadOnlyField(
                    label = "Email",
                    value = systemFields.email
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Registration info will be available after re-enrollment",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "These fields are read-only. Contact admin to make changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReadOnlyField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value.ifEmpty { "Not set" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (value.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.Lock,
                contentDescription = "Read-only",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OptionalFieldsSection(
    optionalFields: com.vettid.app.core.storage.OptionalPersonalData,
    onFieldUpdate: (OptionalField, String?) -> Unit
) {
    val focusManager = LocalFocusManager.current

    Text(
        text = "CONTACT INFO",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(8.dp))

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Phone number with country selector and formatting
            PhoneNumberInput(
                value = optionalFields.phone ?: "",
                onValueChange = { onFieldUpdate(OptionalField.PHONE, it.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth(),
                label = "Phone",
                imeAction = ImeAction.Next,
                onImeAction = { focusManager.moveFocus(FocusDirection.Down) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Birthday with date picker
            BirthdayPickerInput(
                value = optionalFields.birthday ?: "",
                onValueChange = { onFieldUpdate(OptionalField.BIRTHDAY, it.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "ADDRESS",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(8.dp))

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = optionalFields.street ?: "",
                onValueChange = { onFieldUpdate(OptionalField.STREET, it) },
                label = { Text("Street Address") },
                leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = optionalFields.city ?: "",
                    onValueChange = { onFieldUpdate(OptionalField.CITY, it) },
                    label = { Text("City") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) }
                    ),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = optionalFields.state ?: "",
                    onValueChange = { onFieldUpdate(OptionalField.STATE, it) },
                    label = { Text("State") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    singleLine = true,
                    modifier = Modifier.weight(0.5f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = optionalFields.postalCode ?: "",
                    onValueChange = { onFieldUpdate(OptionalField.POSTAL_CODE, it) },
                    label = { Text("Postal Code") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) }
                    ),
                    singleLine = true,
                    modifier = Modifier.weight(0.5f)
                )

                OutlinedTextField(
                    value = optionalFields.country ?: "",
                    onValueChange = { onFieldUpdate(OptionalField.COUNTRY, it) },
                    label = { Text("Country") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CustomFieldsSection(
    customFields: List<CustomField>,
    onAddField: () -> Unit,
    onEditField: (CustomField) -> Unit,
    onRemoveField: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "CUSTOM FIELDS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )

        TextButton(onClick = onAddField) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Field")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (customFields.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AddCircleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "No custom fields yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        Card {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                customFields.forEachIndexed { index, field ->
                    CustomFieldItem(
                        field = field,
                        onClick = { onEditField(field) }
                    )
                    if (index < customFields.lastIndex) {
                        Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomFieldItem(
    field: CustomField,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (field.category) {
                    FieldCategory.IDENTITY -> Icons.Default.Badge
                    FieldCategory.CONTACT -> Icons.Default.ContactPhone
                    FieldCategory.ADDRESS -> Icons.Default.LocationOn
                    FieldCategory.FINANCIAL -> Icons.Default.AccountBalance
                    FieldCategory.MEDICAL -> Icons.Default.LocalHospital
                    FieldCategory.OTHER -> Icons.Default.Note
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = field.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = field.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BottomButtons(
    hasPendingSync: Boolean,
    onSkip: () -> Unit,
    onContinue: () -> Unit
) {
    Surface(
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f)
            ) {
                Text("Skip")
            }

            Button(
                onClick = onContinue,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (hasPendingSync) "Save & Continue" else "Continue")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCustomFieldDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, value: String, category: FieldCategory) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(FieldCategory.OTHER) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Field") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Field Name") },
                    placeholder = { Text("e.g., Driver's License") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = category.name.lowercase().replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        FieldCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    category = cat
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, value, category) },
                enabled = name.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditCustomFieldDialog(
    field: CustomField,
    onDismiss: () -> Unit,
    onSave: (CustomField) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(field.name) }
    var value by remember { mutableStateOf(field.value) }
    var category by remember { mutableStateOf(field.category) }
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Field?") },
            text = { Text("Are you sure you want to delete \"${field.name}\"?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Edit Field") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Field Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = category.name.lowercase().replaceFirstChar { it.uppercase() },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            FieldCategory.entries.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    onClick = {
                                        category = cat
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Field")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSave(field.copy(name = name, value = value, category = category))
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}
