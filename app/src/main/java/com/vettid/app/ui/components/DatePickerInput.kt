package com.vettid.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Date picker input with Material 3 DatePicker dialog.
 *
 * Features:
 * - Displays date in localized format
 * - Opens Material 3 DatePicker dialog on click
 * - Stores date in ISO format (YYYY-MM-DD)
 * - Supports year range restrictions (for birthdate)
 *
 * @param value The date value in ISO format (YYYY-MM-DD) or empty
 * @param onValueChange Callback when date changes (provides ISO format)
 * @param modifier Modifier for the component
 * @param label Label for the field
 * @param placeholder Placeholder text when empty
 * @param minYear Minimum selectable year (default: 1900)
 * @param maxYear Maximum selectable year (default: current year)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Date",
    placeholder: String = "Select date",
    minYear: Int = 1900,
    maxYear: Int = LocalDate.now().year
) {
    var showDatePicker by remember { mutableStateOf(false) }

    // Parse the current value
    val currentDate = remember(value) {
        if (value.isNotBlank()) {
            try {
                LocalDate.parse(value)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    // Format for display
    val displayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val displayText = currentDate?.format(displayFormat) ?: ""

    // Create date picker state
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = currentDate?.let {
            it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        },
        yearRange = minYear..maxYear
    )

    // Clickable field that shows current value and opens picker
    OutlinedTextField(
        value = displayText,
        onValueChange = { /* Read-only, changes come from picker */ },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
        trailingIcon = {
            Row {
                if (currentDate != null) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear date",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(
                        Icons.Default.EditCalendar,
                        contentDescription = "Select date"
                    )
                }
            }
        },
        readOnly = true,
        singleLine = true,
        modifier = modifier.clickable { showDatePicker = true }
    )

    // Date picker dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            onValueChange(selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(
                state = datePickerState,
                title = {
                    Text(
                        text = "Select $label",
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp)
                    )
                },
                headline = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        Text(
                            text = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)
                        )
                    } ?: Text(
                        text = placeholder,
                        modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)
                    )
                }
            )
        }
    }
}

/**
 * Birthday picker specialized for selecting birth dates.
 *
 * Features:
 * - Restricted to reasonable birth year range
 * - Shows age in helper text
 * - Validates that date is in the past
 *
 * @param value The birthdate in ISO format (YYYY-MM-DD) or empty
 * @param onValueChange Callback when date changes
 * @param modifier Modifier for the component
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayPickerInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDatePicker by remember { mutableStateOf(false) }

    val currentYear = LocalDate.now().year
    val minYear = currentYear - 120  // Reasonable minimum age
    val maxYear = currentYear - 13   // Minimum age of 13 for privacy compliance

    // Parse the current value
    val currentDate = remember(value) {
        if (value.isNotBlank()) {
            try {
                LocalDate.parse(value)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    // Calculate age for display
    val age = currentDate?.let {
        val today = LocalDate.now()
        var years = today.year - it.year
        if (today.dayOfYear < it.dayOfYear) years--
        years
    }

    // Format for display
    val displayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val displayText = currentDate?.format(displayFormat) ?: ""

    // Create date picker state - default to ~25 years ago for better UX
    val defaultDate = LocalDate.now().minusYears(25)
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = currentDate?.let {
            it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } ?: defaultDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        yearRange = minYear..maxYear
    )

    Column(modifier = modifier) {
        // Wrap in a Box with clickable to ensure clicks are captured
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDatePicker = true }
        ) {
            OutlinedTextField(
                value = displayText,
                onValueChange = { /* Read-only */ },
                label = { Text("Birthday") },
                placeholder = { Text("Select your birthday") },
                leadingIcon = { Icon(Icons.Default.Cake, contentDescription = null) },
                trailingIcon = {
                    Row {
                        if (currentDate != null) {
                            IconButton(onClick = { onValueChange("") }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(
                                Icons.Default.EditCalendar,
                                contentDescription = "Select birthday"
                            )
                        }
                    }
                },
                supportingText = age?.let {
                    { Text("Age: $it years") }
                },
                enabled = false, // Disable to prevent focus issues, clicks handled by Box
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            // Validate the date is within range
                            if (selectedDate.year in minYear..maxYear) {
                                onValueChange(selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
                            }
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(
                state = datePickerState,
                title = {
                    Text(
                        text = "Select Birthday",
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp)
                    )
                },
                headline = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        val calculatedAge = run {
                            val today = LocalDate.now()
                            var years = today.year - date.year
                            if (today.dayOfYear < date.dayOfYear) years--
                            years
                        }
                        Column(
                            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)
                        ) {
                            Text(
                                text = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = "Age: $calculatedAge years",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } ?: Text(
                        text = "Select your birthday",
                        modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)
                    )
                }
            )
        }
    }
}
