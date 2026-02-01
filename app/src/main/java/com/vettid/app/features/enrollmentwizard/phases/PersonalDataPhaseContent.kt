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
import com.vettid.app.core.storage.CategoryInfo
import com.vettid.app.core.storage.CustomField
import com.vettid.app.core.storage.FieldCategory
import com.vettid.app.core.storage.FieldType
import com.vettid.app.core.storage.OptionalField
import com.vettid.app.core.storage.OptionalPersonalData
import com.vettid.app.core.storage.SystemPersonalData
import com.vettid.app.ui.components.PhoneNumberInput
import com.vettid.app.ui.components.WheelBirthdayPicker

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
    customCategories: List<CategoryInfo>,
    hasPendingSync: Boolean,
    error: String?,
    showAddFieldDialog: Boolean,
    editingField: CustomField?,
    onUpdateOptionalField: (OptionalField, String?) -> Unit,
    onAddCustomField: (name: String, value: String, category: FieldCategory, fieldType: FieldType) -> Unit,
    onUpdateCustomField: (CustomField) -> Unit,
    onRemoveCustomField: (String) -> Unit,
    onCreateCategory: (String) -> Unit,
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
                customCategories = customCategories,
                onDismiss = onHideAddDialog,
                onAdd = onAddCustomField,
                onCreateCategory = onCreateCategory
            )
        }

        // Edit Custom Field Dialog
        if (editingField != null) {
            EditCustomFieldDialog(
                field = editingField,
                customCategories = customCategories,
                onDismiss = onHideEditDialog,
                onSave = onUpdateCustomField,
                onDelete = { onRemoveCustomField(editingField.id) },
                onCreateCategory = onCreateCategory
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionalFieldsSection(
    optionalFields: OptionalPersonalData,
    onFieldUpdate: (OptionalField, String?) -> Unit
) {
    val focusManager = LocalFocusManager.current

    // Legal Name Section
    Text(
        text = "LEGAL NAME",
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
            // Prefix dropdown
            var prefixExpanded by remember { mutableStateOf(false) }
            val prefixes = listOf("", "Mr.", "Ms.", "Mrs.", "Dr.", "Prof.")
            ExposedDropdownMenuBox(
                expanded = prefixExpanded,
                onExpandedChange = { prefixExpanded = it }
            ) {
                OutlinedTextField(
                    value = optionalFields.prefix ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Prefix") },
                    placeholder = { Text("Select prefix") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = prefixExpanded) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = prefixExpanded,
                    onDismissRequest = { prefixExpanded = false }
                ) {
                    prefixes.forEach { prefix ->
                        DropdownMenuItem(
                            text = { Text(if (prefix.isEmpty()) "None" else prefix) },
                            onClick = {
                                onFieldUpdate(OptionalField.PREFIX, prefix.ifBlank { null })
                                prefixExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // First name
            OutlinedTextField(
                value = optionalFields.firstName ?: "",
                onValueChange = { onFieldUpdate(OptionalField.FIRST_NAME, it.ifBlank { null }) },
                label = { Text("First Name") },
                placeholder = { Text("Enter first name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Middle name
            OutlinedTextField(
                value = optionalFields.middleName ?: "",
                onValueChange = { onFieldUpdate(OptionalField.MIDDLE_NAME, it.ifBlank { null }) },
                label = { Text("Middle Name") },
                placeholder = { Text("Enter middle name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Last name
            OutlinedTextField(
                value = optionalFields.lastName ?: "",
                onValueChange = { onFieldUpdate(OptionalField.LAST_NAME, it.ifBlank { null }) },
                label = { Text("Last Name") },
                placeholder = { Text("Enter last name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Suffix dropdown
            var suffixExpanded by remember { mutableStateOf(false) }
            val suffixes = listOf("", "Jr.", "Sr.", "II", "III", "IV", "Ph.D.", "M.D.", "Esq.")
            ExposedDropdownMenuBox(
                expanded = suffixExpanded,
                onExpandedChange = { suffixExpanded = it }
            ) {
                OutlinedTextField(
                    value = optionalFields.suffix ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Suffix") },
                    placeholder = { Text("Select suffix") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = suffixExpanded) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = suffixExpanded,
                    onDismissRequest = { suffixExpanded = false }
                ) {
                    suffixes.forEach { suffix ->
                        DropdownMenuItem(
                            text = { Text(if (suffix.isEmpty()) "None" else suffix) },
                            onClick = {
                                onFieldUpdate(OptionalField.SUFFIX, suffix.ifBlank { null })
                                suffixExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Contact Info Section
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
                imeAction = ImeAction.Done,
                onImeAction = { focusManager.clearFocus() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Birthday with wheel picker
            WheelBirthdayPicker(
                value = optionalFields.birthday ?: "",
                onValueChange = { onFieldUpdate(OptionalField.BIRTHDAY, it.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Address Section
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
            // Street address line 1
            OutlinedTextField(
                value = optionalFields.street ?: "",
                onValueChange = { onFieldUpdate(OptionalField.STREET, it.ifBlank { null }) },
                label = { Text("Street Address") },
                placeholder = { Text("123 Main St") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Street address line 2
            OutlinedTextField(
                value = optionalFields.street2 ?: "",
                onValueChange = { onFieldUpdate(OptionalField.STREET2, it.ifBlank { null }) },
                label = { Text("Address Line 2") },
                placeholder = { Text("Apt, Suite, Unit, etc.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // City
            OutlinedTextField(
                value = optionalFields.city ?: "",
                onValueChange = { onFieldUpdate(OptionalField.CITY, it.ifBlank { null }) },
                label = { Text("City") },
                placeholder = { Text("City") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // State dropdown and Postal Code
            Row(modifier = Modifier.fillMaxWidth()) {
                // State dropdown
                var stateExpanded by remember { mutableStateOf(false) }
                val usStates = listOf(
                    "" to "Select State",
                    "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
                    "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware",
                    "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho",
                    "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
                    "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
                    "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
                    "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
                    "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
                    "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
                    "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
                    "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah",
                    "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia",
                    "WI" to "Wisconsin", "WY" to "Wyoming", "DC" to "District of Columbia",
                    "PR" to "Puerto Rico", "VI" to "Virgin Islands", "GU" to "Guam"
                )
                ExposedDropdownMenuBox(
                    expanded = stateExpanded,
                    onExpandedChange = { stateExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = optionalFields.state ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("State") },
                        placeholder = { Text("Select") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stateExpanded) },
                        singleLine = true,
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = stateExpanded,
                        onDismissRequest = { stateExpanded = false }
                    ) {
                        usStates.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(if (code.isEmpty()) name else "$code - $name") },
                                onClick = {
                                    onFieldUpdate(OptionalField.STATE, code.ifBlank { null })
                                    stateExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                OutlinedTextField(
                    value = optionalFields.postalCode ?: "",
                    onValueChange = { onFieldUpdate(OptionalField.POSTAL_CODE, it.ifBlank { null }) },
                    label = { Text("ZIP Code") },
                    placeholder = { Text("ZIP") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Country dropdown
            var countryExpanded by remember { mutableStateOf(false) }
            val countries = listOf(
                "" to "Select Country",
                "US" to "United States",
                "CA" to "Canada",
                "MX" to "Mexico",
                "GB" to "United Kingdom",
                "DE" to "Germany",
                "FR" to "France",
                "IT" to "Italy",
                "ES" to "Spain",
                "AU" to "Australia",
                "NZ" to "New Zealand",
                "JP" to "Japan",
                "KR" to "South Korea",
                "CN" to "China",
                "IN" to "India",
                "BR" to "Brazil",
                "AR" to "Argentina",
                "ZA" to "South Africa",
                "NG" to "Nigeria",
                "EG" to "Egypt",
                "AE" to "United Arab Emirates",
                "SG" to "Singapore",
                "HK" to "Hong Kong",
                "TW" to "Taiwan",
                "PH" to "Philippines",
                "TH" to "Thailand",
                "VN" to "Vietnam",
                "ID" to "Indonesia",
                "MY" to "Malaysia",
                "NL" to "Netherlands",
                "BE" to "Belgium",
                "CH" to "Switzerland",
                "AT" to "Austria",
                "SE" to "Sweden",
                "NO" to "Norway",
                "DK" to "Denmark",
                "FI" to "Finland",
                "IE" to "Ireland",
                "PT" to "Portugal",
                "PL" to "Poland",
                "CZ" to "Czech Republic",
                "GR" to "Greece",
                "TR" to "Turkey",
                "RU" to "Russia",
                "IL" to "Israel",
                "SA" to "Saudi Arabia"
            )
            ExposedDropdownMenuBox(
                expanded = countryExpanded,
                onExpandedChange = { countryExpanded = it }
            ) {
                OutlinedTextField(
                    value = countries.find { it.first == optionalFields.country }?.second
                        ?: optionalFields.country ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Country") },
                    placeholder = { Text("Select country") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = countryExpanded) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = countryExpanded,
                    onDismissRequest = { countryExpanded = false }
                ) {
                    countries.forEach { (code, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                onFieldUpdate(OptionalField.COUNTRY, code.ifBlank { null })
                                countryExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Social & Web Section
    Text(
        text = "SOCIAL & WEB",
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
            // Website
            OutlinedTextField(
                value = optionalFields.website ?: "",
                onValueChange = { onFieldUpdate(OptionalField.WEBSITE, it.ifBlank { null }) },
                label = { Text("Website") },
                placeholder = { Text("https://yourwebsite.com") },
                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // LinkedIn
            OutlinedTextField(
                value = optionalFields.linkedin ?: "",
                onValueChange = { onFieldUpdate(OptionalField.LINKEDIN, it.ifBlank { null }) },
                label = { Text("LinkedIn") },
                placeholder = { Text("linkedin.com/in/username or username") },
                leadingIcon = { Icon(Icons.Default.Work, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Twitter/X
            OutlinedTextField(
                value = optionalFields.twitter ?: "",
                onValueChange = { onFieldUpdate(OptionalField.TWITTER, it.ifBlank { null }) },
                label = { Text("X (Twitter)") },
                placeholder = { Text("username (without @)") },
                leadingIcon = { Text("𝕏", style = MaterialTheme.typography.titleMedium) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Instagram
            OutlinedTextField(
                value = optionalFields.instagram ?: "",
                onValueChange = { onFieldUpdate(OptionalField.INSTAGRAM, it.ifBlank { null }) },
                label = { Text("Instagram") },
                placeholder = { Text("username (without @)") },
                leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // GitHub
            OutlinedTextField(
                value = optionalFields.github ?: "",
                onValueChange = { onFieldUpdate(OptionalField.GITHUB, it.ifBlank { null }) },
                label = { Text("GitHub") },
                placeholder = { Text("username") },
                leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = field.name, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.width(8.dp))
                    // Show field type badge
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = field.fieldType.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                // Display value based on field type
                Text(
                    text = when (field.fieldType) {
                        FieldType.PASSWORD -> "••••••••"
                        else -> field.value.ifEmpty { "(empty)" }
                    },
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
    customCategories: List<CategoryInfo>,
    onDismiss: () -> Unit,
    onAdd: (name: String, value: String, category: FieldCategory, fieldType: FieldType) -> Unit,
    onCreateCategory: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(FieldCategory.OTHER) }
    var selectedCustomCategory by remember { mutableStateOf<CategoryInfo?>(null) }
    var fieldType by remember { mutableStateOf(FieldType.TEXT) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var pendingCategoryName by remember { mutableStateOf("") }

    // New Category Dialog
    if (showNewCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showNewCategoryDialog = false },
            title = { Text("New Category") },
            text = {
                OutlinedTextField(
                    value = pendingCategoryName,
                    onValueChange = { pendingCategoryName = it },
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
                        if (pendingCategoryName.isNotBlank()) {
                            // Call the callback to create and sync the category to vault
                            onCreateCategory(pendingCategoryName)
                            // Set to OTHER for the field, but the category name will be synced
                            category = FieldCategory.OTHER
                            selectedCustomCategory = CategoryInfo(
                                id = pendingCategoryName.lowercase().replace(" ", "_"),
                                name = pendingCategoryName,
                                icon = "more"
                            )
                            showNewCategoryDialog = false
                            pendingCategoryName = ""
                        }
                    },
                    enabled = pendingCategoryName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewCategoryDialog = false
                    pendingCategoryName = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Compute display value for category
    val categoryDisplayValue = selectedCustomCategory?.name
        ?: category.name.lowercase().replaceFirstChar { it.uppercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Field") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // 1. Category dropdown (FIRST)
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = it }
                ) {
                    OutlinedTextField(
                        value = categoryDisplayValue,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        // Predefined categories
                        FieldCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    category = cat
                                    selectedCustomCategory = null
                                    categoryExpanded = false
                                }
                            )
                        }
                        // Custom categories from vault
                        if (customCategories.isNotEmpty()) {
                            Divider()
                            customCategories.forEach { customCat ->
                                DropdownMenuItem(
                                    text = { Text(customCat.name) },
                                    onClick = {
                                        category = FieldCategory.OTHER
                                        selectedCustomCategory = customCat
                                        categoryExpanded = false
                                    }
                                )
                            }
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
                                categoryExpanded = false
                                showNewCategoryDialog = true
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Field Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Field Name") },
                    placeholder = { Text("e.g., Driver's License") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Field Type dropdown
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = fieldType.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Field Type") },
                        supportingText = { Text(fieldType.description) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        FieldType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(type.displayName)
                                        Text(
                                            text = type.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    fieldType = type
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Value input - varies based on field type
                when (fieldType) {
                    FieldType.NOTE -> {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = { Text("Value") },
                            minLines = 3,
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    FieldType.PASSWORD -> {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = { Text("Value") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    FieldType.NUMBER -> {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = { Text("Value") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    FieldType.EMAIL -> {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = { Text("Value") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    FieldType.PHONE -> {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = { Text("Value") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    FieldType.URL -> {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = { Text("Value") },
                            placeholder = { Text("https://...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = { Text("Value") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // If custom category, prefix it to the field name for identification
                    // Use format "CategoryName - FieldName" (no brackets, as validation doesn't allow them)
                    val finalName = if (selectedCustomCategory != null) {
                        "${selectedCustomCategory!!.name} - $name"
                    } else {
                        name
                    }
                    onAdd(finalName, value, category, fieldType)
                },
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
    customCategories: List<CategoryInfo> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (CustomField) -> Unit,
    onDelete: () -> Unit,
    onCreateCategory: (String) -> Unit = {}
) {
    var name by remember { mutableStateOf(field.name) }
    var value by remember { mutableStateOf(field.value) }
    var category by remember { mutableStateOf(field.category) }
    var selectedCustomCategory by remember { mutableStateOf<CategoryInfo?>(null) }
    var fieldType by remember { mutableStateOf(field.fieldType) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var pendingCategoryName by remember { mutableStateOf("") }

    // New Category Dialog
    if (showNewCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showNewCategoryDialog = false },
            title = { Text("New Category") },
            text = {
                OutlinedTextField(
                    value = pendingCategoryName,
                    onValueChange = { pendingCategoryName = it },
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
                        if (pendingCategoryName.isNotBlank()) {
                            onCreateCategory(pendingCategoryName)
                            category = FieldCategory.OTHER
                            selectedCustomCategory = CategoryInfo(
                                id = pendingCategoryName.lowercase().replace(" ", "_"),
                                name = pendingCategoryName,
                                icon = "more"
                            )
                            showNewCategoryDialog = false
                            pendingCategoryName = ""
                        }
                    },
                    enabled = pendingCategoryName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewCategoryDialog = false
                    pendingCategoryName = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Compute display value for category
    val categoryDisplayValue = selectedCustomCategory?.name
        ?: category.name.lowercase().replaceFirstChar { it.uppercase() }

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
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    // 1. Category dropdown (FIRST)
                    ExposedDropdownMenuBox(
                        expanded = categoryExpanded,
                        onExpandedChange = { categoryExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = categoryDisplayValue,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = categoryExpanded,
                            onDismissRequest = { categoryExpanded = false }
                        ) {
                            // Predefined categories
                            FieldCategory.entries.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    onClick = {
                                        category = cat
                                        selectedCustomCategory = null
                                        categoryExpanded = false
                                    }
                                )
                            }
                            // Custom categories
                            if (customCategories.isNotEmpty()) {
                                Divider()
                                customCategories.forEach { customCat ->
                                    DropdownMenuItem(
                                        text = { Text(customCat.name) },
                                        onClick = {
                                            category = FieldCategory.OTHER
                                            selectedCustomCategory = customCat
                                            categoryExpanded = false
                                        }
                                    )
                                }
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
                                    categoryExpanded = false
                                    showNewCategoryDialog = true
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. Field Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Field Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Field Type dropdown
                    ExposedDropdownMenuBox(
                        expanded = typeExpanded,
                        onExpandedChange = { typeExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = fieldType.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Field Type") },
                            supportingText = { Text(fieldType.description) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false }
                        ) {
                            FieldType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(type.displayName)
                                            Text(
                                                text = type.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        fieldType = type
                                        typeExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4. Value input - varies based on field type
                    when (fieldType) {
                        FieldType.NOTE -> {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                label = { Text("Value") },
                                minLines = 3,
                                maxLines = 5,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        FieldType.PASSWORD -> {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                label = { Text("Value") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        FieldType.NUMBER -> {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                label = { Text("Value") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        FieldType.EMAIL -> {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                label = { Text("Value") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        FieldType.PHONE -> {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                label = { Text("Value") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        FieldType.URL -> {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                label = { Text("Value") },
                                placeholder = { Text("https://...") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                label = { Text("Value") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
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
                        // If custom category, prefix it to the field name
                        val finalName = if (selectedCustomCategory != null) {
                            "${selectedCustomCategory!!.name} - $name"
                        } else {
                            name
                        }
                        onSave(field.copy(name = finalName, value = value, category = category, fieldType = fieldType))
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
