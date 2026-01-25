package com.vettid.app.features.enrollmentwizard.phases

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
import com.vettid.app.core.storage.CustomField
import com.vettid.app.core.storage.FieldCategory
import com.vettid.app.core.storage.OptionalField
import com.vettid.app.core.storage.OptionalPersonalData
import com.vettid.app.core.storage.SystemPersonalData

/**
 * Personal data collection phase content.
 */
@Composable
fun PersonalDataPhaseContent(
    isLoading: Boolean,
    isSyncing: Boolean,
    systemFields: SystemPersonalData?,
    optionalFields: OptionalPersonalData,
    customFields: List<CustomField>,
    hasPendingSync: Boolean,
    error: String?,
    showAddFieldDialog: Boolean,
    editingField: CustomField?,
    onUpdateOptionalField: (OptionalField, String?) -> Unit,
    onAddCustomField: (name: String, value: String, category: FieldCategory) -> Unit,
    onUpdateCustomField: (CustomField) -> Unit,
    onRemoveCustomField: (String) -> Unit,
    onSyncNow: () -> Unit,
    onShowAddDialog: () -> Unit,
    onHideAddDialog: () -> Unit,
    onShowEditDialog: (CustomField) -> Unit,
    onHideEditDialog: () -> Unit,
    onDismissError: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar with sync button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Personal Data",
                    style = MaterialTheme.typography.titleLarge
                )
                if (hasPendingSync) {
                    IconButton(
                        onClick = onSyncNow,
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
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

            // Scrollable content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Error message
                if (error != null) {
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
                            IconButton(onClick = onDismissError) {
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

                // System Fields
                SystemFieldsSection(systemFields = systemFields)

                Spacer(modifier = Modifier.height(24.dp))

                // Optional Fields
                OptionalFieldsSection(
                    optionalFields = optionalFields,
                    onFieldUpdate = onUpdateOptionalField
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Custom Fields
                CustomFieldsSection(
                    customFields = customFields,
                    onAddField = onShowAddDialog,
                    onEditField = onShowEditDialog,
                    onRemoveField = onRemoveCustomField
                )
            }

            // Bottom buttons
            BottomButtons(
                hasPendingSync = hasPendingSync,
                onSkip = onSkip,
                onContinue = onContinue
            )
        }

        // Add Custom Field Dialog
        if (showAddFieldDialog) {
            AddCustomFieldDialog(
                onDismiss = onHideAddDialog,
                onAdd = onAddCustomField
            )
        }

        // Edit Custom Field Dialog
        if (editingField != null) {
            EditCustomFieldDialog(
                field = editingField,
                onDismiss = onHideEditDialog,
                onSave = onUpdateCustomField,
                onDelete = { onRemoveCustomField(editingField.id) }
            )
        }
    }
}

@Composable
private fun SystemFieldsSection(systemFields: SystemPersonalData?) {
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
                ReadOnlyField(label = "First Name", value = systemFields.firstName)
                Spacer(modifier = Modifier.height(12.dp))
                ReadOnlyField(label = "Last Name", value = systemFields.lastName)
                Spacer(modifier = Modifier.height(12.dp))
                ReadOnlyField(label = "Email", value = systemFields.email)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Registration data will sync from your vault",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
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
    optionalFields: OptionalPersonalData,
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
            OutlinedTextField(
                value = optionalFields.phone ?: "",
                onValueChange = { onFieldUpdate(OptionalField.PHONE, it) },
                label = { Text("Phone") },
                placeholder = { Text("+1 (555) 123-4567") },
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = optionalFields.birthday ?: "",
                onValueChange = { onFieldUpdate(OptionalField.BIRTHDAY, it) },
                label = { Text("Birthday") },
                placeholder = { Text("YYYY-MM-DD") },
                leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
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
            Column(modifier = Modifier.fillMaxWidth()) {
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
                Text(text = field.name, style = MaterialTheme.typography.bodyLarge)
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
    Surface(shadowElevation = 8.dp) {
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
