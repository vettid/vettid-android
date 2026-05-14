package com.vettid.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Wheel-style birthday picker with scrollable year, month, and day columns.
 *
 * Features:
 * - Three scrollable wheels for year, month, and day
 * - Smooth snapping to values
 * - Haptic feedback when scrolling
 * - Shows age calculation
 * - Dialog-based selection
 *
 * @param value The birthdate in ISO format (YYYY-MM-DD) or empty
 * @param onValueChange Callback when date changes
 * @param modifier Modifier for the component
 */
@Composable
fun WheelBirthdayPicker(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }

    val currentYear = LocalDate.now().year
    val minYear = currentYear - 120
    val maxYear = currentYear - 13  // Minimum age 13

    // Parse current value
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

    // Calculate age
    val age = currentDate?.let {
        val today = LocalDate.now()
        var years = today.year - it.year
        if (today.dayOfYear < it.dayOfYear) years--
        years
    }

    // Format for display
    val displayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val displayText = currentDate?.format(displayFormat) ?: ""

    Column(modifier = modifier) {
        // Input field that opens the picker
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
                    IconButton(onClick = { showPicker = true }) {
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
            readOnly = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Date picker dialog
    if (showPicker) {
        WheelDatePickerDialog(
            initialDate = currentDate ?: LocalDate.now().minusYears(25),
            minYear = minYear,
            maxYear = maxYear,
            onDismiss = { showPicker = false },
            onConfirm = { selectedDate ->
                onValueChange(selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
                showPicker = false
            }
        )
    }
}

@Composable
private fun WheelDatePickerDialog(
    initialDate: LocalDate,
    minYear: Int,
    maxYear: Int,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    var selectedYear by remember { mutableIntStateOf(initialDate.year.coerceIn(minYear, maxYear)) }
    var selectedMonth by remember { mutableIntStateOf(initialDate.monthValue) }
    var selectedDay by remember { mutableIntStateOf(initialDate.dayOfMonth) }

    // Calculate max days for selected month/year
    val maxDaysInMonth = remember(selectedYear, selectedMonth) {
        LocalDate.of(selectedYear, selectedMonth, 1).lengthOfMonth()
    }

    // Adjust day if it exceeds max for new month
    LaunchedEffect(maxDaysInMonth) {
        if (selectedDay > maxDaysInMonth) {
            selectedDay = maxDaysInMonth
        }
    }

    // Calculate age for preview
    val selectedDate = remember(selectedYear, selectedMonth, selectedDay) {
        val day = selectedDay.coerceIn(1, maxDaysInMonth)
        LocalDate.of(selectedYear, selectedMonth, day)
    }
    val age = remember(selectedDate) {
        val today = LocalDate.now()
        var years = today.year - selectedDate.year
        if (today.dayOfYear < selectedDate.dayOfYear) years--
        years
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Birthday",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Date preview
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = selectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Age: $age years",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Wheel pickers
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),  // 5 items * 48dp
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Month picker
                    WheelPicker(
                        items = (1..12).map { month ->
                            Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        },
                        selectedIndex = selectedMonth - 1,
                        onSelectedIndexChange = { selectedMonth = it + 1 },
                        modifier = Modifier.weight(1.2f)
                    )

                    // Day picker
                    WheelPicker(
                        items = (1..maxDaysInMonth).map { it.toString() },
                        selectedIndex = (selectedDay - 1).coerceIn(0, maxDaysInMonth - 1),
                        onSelectedIndexChange = { selectedDay = it + 1 },
                        modifier = Modifier.weight(0.8f)
                    )

                    // Year picker
                    WheelPicker(
                        items = (maxYear downTo minYear).map { it.toString() },
                        selectedIndex = maxYear - selectedYear,
                        onSelectedIndexChange = { selectedYear = maxYear - it },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text(
                        text = "Month",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1.2f),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Day",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.8f),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Year",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedDate) }) {
                Text("OK")
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
 * Wheel-style date picker dialog for secret / personal-data template
 * fields. A rolling control, not a calendar grid.
 *
 * Two shapes, switched by [monthYearOnly]:
 *  - true  → MM/YYYY (credit-card expiry, where a day makes no sense)
 *  - false → MM/DD/YYYY (passport dates and other full dates)
 *
 * [initialValue] is the field's current text in whichever of those two
 * formats applies; the picker opens on it. [onConfirm] hands back the
 * formatted string in the same format.
 *
 * Reuses [WheelPicker] so it shares the snap-scroll + haptics behaviour
 * of the birthday picker. No third-party wheel dependency.
 */
@Composable
fun FieldDatePickerDialog(
    monthYearOnly: Boolean,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val today = remember { LocalDate.now() }
    // Wide enough for past passport-issue dates and future expiries
    // without being unwieldy. Birth dates use WheelBirthdayPicker.
    val minYear = today.year - 100
    val maxYear = today.year + 30

    val parsed = remember(initialValue, monthYearOnly) {
        parseFieldDate(initialValue, monthYearOnly, today)
    }

    var selectedYear by remember { mutableIntStateOf(parsed.year.coerceIn(minYear, maxYear)) }
    var selectedMonth by remember { mutableIntStateOf(parsed.monthValue) }
    var selectedDay by remember { mutableIntStateOf(parsed.dayOfMonth) }

    val maxDaysInMonth = remember(selectedYear, selectedMonth) {
        LocalDate.of(selectedYear, selectedMonth, 1).lengthOfMonth()
    }
    // Clamp the day if the selected month shrank under it (e.g. Mar 31 → Feb).
    LaunchedEffect(maxDaysInMonth) {
        if (selectedDay > maxDaysInMonth) selectedDay = maxDaysInMonth
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (monthYearOnly) "Expiration date" else "Select date") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),  // 5 items * 48dp
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelPicker(
                        items = (1..12).map { month ->
                            Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        },
                        selectedIndex = selectedMonth - 1,
                        onSelectedIndexChange = { selectedMonth = it + 1 },
                        modifier = Modifier.weight(1.2f)
                    )
                    if (!monthYearOnly) {
                        WheelPicker(
                            items = (1..maxDaysInMonth).map { it.toString() },
                            selectedIndex = (selectedDay - 1).coerceIn(0, maxDaysInMonth - 1),
                            onSelectedIndexChange = { selectedDay = it + 1 },
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                    WheelPicker(
                        items = (maxYear downTo minYear).map { it.toString() },
                        selectedIndex = maxYear - selectedYear,
                        onSelectedIndexChange = { selectedYear = maxYear - it },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text(
                        text = "Month",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1.2f),
                        textAlign = TextAlign.Center
                    )
                    if (!monthYearOnly) {
                        Text(
                            text = "Day",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                    Text(
                        text = "Year",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val day = selectedDay.coerceIn(1, maxDaysInMonth)
                val formatted = if (monthYearOnly) {
                    "%02d/%04d".format(selectedMonth, selectedYear)
                } else {
                    "%02d/%02d/%04d".format(selectedMonth, day, selectedYear)
                }
                onConfirm(formatted)
            }) {
                Text("OK")
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
 * Parse an existing field value into a LocalDate. Accepts the two
 * formats [FieldDatePickerDialog] emits — MM/yyyy and MM/dd/yyyy —
 * and falls back to today for blank or unrecognised input.
 */
private fun parseFieldDate(value: String, monthYearOnly: Boolean, fallback: LocalDate): LocalDate {
    val parts = value.trim().split("/")
    return try {
        when {
            monthYearOnly && parts.size == 2 ->
                LocalDate.of(parts[1].toInt(), parts[0].toInt(), 1)
            !monthYearOnly && parts.size == 3 ->
                LocalDate.of(parts[2].toInt(), parts[0].toInt(), parts[1].toInt())
            else -> fallback
        }
    } catch (e: Exception) {
        fallback
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val itemHeight = 48.dp
    val visibleItems = 5  // Number of visible items (odd number for symmetry)
    val centerOffset = visibleItems / 2  // Items above the center

    // Calculate padding to center items
    val verticalPadding = with(density) {
        (itemHeight * centerOffset).toPx().toInt()
    }

    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val coroutineScope = rememberCoroutineScope()

    // Initial scroll to selected index
    LaunchedEffect(Unit) {
        if (selectedIndex in items.indices) {
            listState.scrollToItem(selectedIndex)
        }
    }

    // Track the center item based on scroll position
    val centerItemIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty()) {
                selectedIndex
            } else {
                // The center of the viewport
                val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2

                // Find the item closest to center
                layoutInfo.visibleItemsInfo.minByOrNull { item ->
                    val itemCenter = item.offset + item.size / 2
                    kotlin.math.abs(itemCenter - viewportCenter)
                }?.index ?: selectedIndex
            }
        }
    }

    // Update selection when scrolling stops
    LaunchedEffect(listState.isScrollInProgress, centerItemIndex) {
        if (!listState.isScrollInProgress && centerItemIndex != selectedIndex && centerItemIndex in items.indices) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSelectedIndexChange(centerItemIndex)
        }
    }

    // Scroll to selected index when it changes externally
    LaunchedEffect(selectedIndex) {
        if (!listState.isScrollInProgress && selectedIndex in items.indices) {
            coroutineScope.launch {
                listState.animateScrollToItem(selectedIndex)
            }
        }
    }

    Box(
        modifier = modifier.height((itemHeight * visibleItems)),
        contentAlignment = Alignment.Center
    ) {
        // Selection highlight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .padding(horizontal = 4.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxSize()
            ) {}
        }

        // Scrollable list
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = itemHeight * centerOffset)
        ) {
            items(items.size) { index ->
                val isSelected = index == centerItemIndex
                val distance = kotlin.math.abs(index - centerItemIndex)
                val alpha = when (distance) {
                    0 -> 1f
                    1 -> 0.6f
                    2 -> 0.3f
                    else -> 0.15f
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = items[index],
                        style = if (isSelected) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.alpha(alpha),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
