package com.vettid.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Dropdown picker field with search/filter support.
 * Shared between Secrets and Personal Data template forms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    leadingIcon: ImageVector
) {
    var expanded by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }

    val filteredOptions = remember(filterText, options) {
        if (filterText.isBlank()) options
        else options.filter { it.contains(filterText, ignoreCase = true) }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                filterText = it
                expanded = true
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words
            ),
            leadingIcon = {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 250.dp)
        ) {
            filteredOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        filterText = ""
                        expanded = false
                    }
                )
            }
        }
    }
}

// MARK: - Picker Data

val commonCountries = listOf(
    "United States", "Canada", "United Kingdom", "Australia", "Germany",
    "France", "Japan", "South Korea", "India", "Brazil",
    "Mexico", "Italy", "Spain", "Netherlands", "Switzerland",
    "Sweden", "Norway", "Denmark", "Finland", "Ireland",
    "New Zealand", "Singapore", "Israel", "South Africa", "Argentina",
    "Chile", "Colombia", "Peru", "Philippines", "Thailand",
    "Vietnam", "Indonesia", "Malaysia", "Taiwan", "Hong Kong",
    "China", "Russia", "Turkey", "Poland", "Czech Republic",
    "Austria", "Belgium", "Portugal", "Greece", "Romania",
    "Hungary", "Ukraine", "Egypt", "Nigeria", "Kenya",
    "Saudi Arabia", "UAE", "Pakistan", "Bangladesh"
)

val usStatesAndTerritories = listOf(
    "Alabama", "Alaska", "Arizona", "Arkansas", "California",
    "Colorado", "Connecticut", "Delaware", "Florida", "Georgia",
    "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa",
    "Kansas", "Kentucky", "Louisiana", "Maine", "Maryland",
    "Massachusetts", "Michigan", "Minnesota", "Mississippi", "Missouri",
    "Montana", "Nebraska", "Nevada", "New Hampshire", "New Jersey",
    "New Mexico", "New York", "North Carolina", "North Dakota", "Ohio",
    "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island", "South Carolina",
    "South Dakota", "Tennessee", "Texas", "Utah", "Vermont",
    "Virginia", "Washington", "West Virginia", "Wisconsin", "Wyoming",
    "District of Columbia", "Puerto Rico", "Guam", "U.S. Virgin Islands",
    // Canadian provinces
    "Alberta", "British Columbia", "Manitoba", "New Brunswick",
    "Newfoundland and Labrador", "Nova Scotia", "Ontario",
    "Prince Edward Island", "Quebec", "Saskatchewan"
)
