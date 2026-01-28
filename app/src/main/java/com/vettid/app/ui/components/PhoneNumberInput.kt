package com.vettid.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Phone number input with country code selector and automatic formatting.
 *
 * Features:
 * - Country selector dropdown with search
 * - Automatic phone number formatting based on country
 * - Stores full international format (e.g., "+1 555 123 4567")
 *
 * @param value The full phone number value (stored in international format)
 * @param onValueChange Callback when phone number changes
 * @param modifier Modifier for the component
 * @param label Label for the text field
 * @param imeAction IME action for keyboard
 * @param onImeAction Callback for IME action
 */
@Composable
fun PhoneNumberInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Phone",
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {}
) {
    // Parse existing value to extract country and number
    val (selectedCountry, localNumber) = remember(value) {
        parsePhoneNumber(value)
    }

    var showCountryPicker by remember { mutableStateOf(false) }
    var currentCountry by remember(selectedCountry) { mutableStateOf(selectedCountry) }
    var numberInput by remember(localNumber) { mutableStateOf(localNumber) }

    // Update the full value when country or number changes
    fun updateValue() {
        val digitsOnly = numberInput.filter { it.isDigit() }
        if (digitsOnly.isNotEmpty()) {
            val formatted = formatPhoneNumber(digitsOnly, currentCountry)
            onValueChange("${currentCountry.dialCode} $formatted")
        } else {
            onValueChange("")
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top
    ) {
        // Country code selector
        @OptIn(ExperimentalMaterial3Api::class)
        OutlinedCard(
            onClick = { showCountryPicker = true },
            modifier = Modifier.width(100.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${currentCountry.flag} ${currentCountry.dialCode}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Select country",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Phone number input
        OutlinedTextField(
            value = numberInput,
            onValueChange = { newValue ->
                // Only allow digits
                val filtered = newValue.filter { it.isDigit() }
                // Limit to reasonable length
                if (filtered.length <= currentCountry.maxDigits) {
                    numberInput = filtered
                    updateValue()
                }
            },
            label = { Text(label) },
            placeholder = { Text(currentCountry.placeholder) },
            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
            visualTransformation = PhoneVisualTransformation(currentCountry),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onNext = { onImeAction() },
                onDone = { onImeAction() }
            ),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }

    // Country picker dialog
    if (showCountryPicker) {
        CountryPickerDialog(
            selectedCountry = currentCountry,
            onCountrySelected = { country ->
                currentCountry = country
                showCountryPicker = false
                updateValue()
            },
            onDismiss = { showCountryPicker = false }
        )
    }
}

@Composable
private fun CountryPickerDialog(
    selectedCountry: Country,
    onCountrySelected: (Country) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredCountries = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            countries
        } else {
            countries.filter { country ->
                country.name.contains(searchQuery, ignoreCase = true) ||
                country.dialCode.contains(searchQuery) ||
                country.code.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Country",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search countries...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Country list
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredCountries) { country ->
                        Surface(
                            onClick = { onCountrySelected(country) },
                            color = if (country == selectedCountry) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = country.flag,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = country.name,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                Text(
                                    text = country.dialCode,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (country == selectedCountry) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Visual transformation for phone number formatting.
 */
private class PhoneVisualTransformation(private val country: Country) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text.filter { it.isDigit() }
        val formatted = formatPhoneNumber(digits, country)

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                // Map digit position to formatted position
                var digitCount = 0
                var formattedPos = 0
                for (char in formatted) {
                    if (digitCount == offset) return formattedPos
                    if (char.isDigit()) digitCount++
                    formattedPos++
                }
                return formattedPos
            }

            override fun transformedToOriginal(offset: Int): Int {
                // Map formatted position to digit position
                var digitCount = 0
                for (i in 0 until minOf(offset, formatted.length)) {
                    if (formatted[i].isDigit()) digitCount++
                }
                return digitCount
            }
        }

        return TransformedText(AnnotatedString(formatted), offsetMapping)
    }
}

/**
 * Format phone number based on country.
 */
private fun formatPhoneNumber(digits: String, country: Country): String {
    if (digits.isEmpty()) return ""

    return when (country.code) {
        "US", "CA" -> formatNANP(digits)  // North American Numbering Plan
        "GB" -> formatUK(digits)
        "DE" -> formatGerman(digits)
        "FR" -> formatFrench(digits)
        "AU" -> formatAustralian(digits)
        "JP" -> formatJapanese(digits)
        "CN" -> formatChinese(digits)
        "IN" -> formatIndian(digits)
        "BR" -> formatBrazilian(digits)
        "MX" -> formatMexican(digits)
        else -> formatGeneric(digits)
    }
}

// North American format: (555) 123-4567
private fun formatNANP(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        when (index) {
            0 -> sb.append("($c")
            2 -> sb.append("$c) ")
            5 -> sb.append("$c-")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// UK format: 7911 123456
private fun formatUK(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        when (index) {
            4 -> sb.append(" $c")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// German format: 170 1234567
private fun formatGerman(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        when (index) {
            3 -> sb.append(" $c")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// French format: 6 12 34 56 78
private fun formatFrench(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        if (index > 0 && index % 2 == 1) {
            sb.append(" $c")
        } else {
            sb.append(c)
        }
    }
    return sb.toString()
}

// Australian format: 412 345 678
private fun formatAustralian(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        when (index) {
            3, 6 -> sb.append(" $c")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// Japanese format: 90-1234-5678
private fun formatJapanese(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        when (index) {
            2, 6 -> sb.append("-$c")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// Chinese format: 138 1234 5678
private fun formatChinese(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        when (index) {
            3, 7 -> sb.append(" $c")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// Indian format: 98765 43210
private fun formatIndian(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        when (index) {
            5 -> sb.append(" $c")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// Brazilian format: 11 98765-4321
private fun formatBrazilian(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        when (index) {
            2 -> sb.append(" $c")
            7 -> sb.append("-$c")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// Mexican format: 55 1234 5678
private fun formatMexican(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        when (index) {
            2, 6 -> sb.append(" $c")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// Generic format: groups of 3-4
private fun formatGeneric(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        if (index > 0 && index % 4 == 0) {
            sb.append(" $c")
        } else {
            sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * Parse an existing phone number to extract country and local number.
 */
private fun parsePhoneNumber(value: String): Pair<Country, String> {
    if (value.isBlank()) {
        return Pair(countries.first { it.code == "US" }, "")
    }

    // Try to match country code
    val cleaned = value.replace(Regex("[^+\\d]"), "")

    // Find matching country by dial code
    for (country in countries.sortedByDescending { it.dialCode.length }) {
        val dialCodeDigits = country.dialCode.replace("+", "")
        if (cleaned.startsWith("+$dialCodeDigits") || cleaned.startsWith(dialCodeDigits)) {
            val localPart = cleaned.removePrefix("+").removePrefix(dialCodeDigits)
            return Pair(country, localPart)
        }
    }

    // Default to US if no match
    return Pair(countries.first { it.code == "US" }, cleaned.removePrefix("+"))
}

/**
 * Country data for phone number input.
 */
data class Country(
    val code: String,        // ISO 3166-1 alpha-2
    val name: String,
    val dialCode: String,
    val flag: String,
    val placeholder: String,
    val maxDigits: Int
)

/**
 * List of supported countries.
 */
val countries = listOf(
    Country("US", "United States", "+1", "\uD83C\uDDFA\uD83C\uDDF8", "(555) 123-4567", 10),
    Country("CA", "Canada", "+1", "\uD83C\uDDE8\uD83C\uDDE6", "(555) 123-4567", 10),
    Country("GB", "United Kingdom", "+44", "\uD83C\uDDEC\uD83C\uDDE7", "7911 123456", 10),
    Country("AU", "Australia", "+61", "\uD83C\uDDE6\uD83C\uDDFA", "412 345 678", 9),
    Country("DE", "Germany", "+49", "\uD83C\uDDE9\uD83C\uDDEA", "170 1234567", 11),
    Country("FR", "France", "+33", "\uD83C\uDDEB\uD83C\uDDF7", "6 12 34 56 78", 9),
    Country("IT", "Italy", "+39", "\uD83C\uDDEE\uD83C\uDDF9", "312 345 6789", 10),
    Country("ES", "Spain", "+34", "\uD83C\uDDEA\uD83C\uDDF8", "612 345 678", 9),
    Country("NL", "Netherlands", "+31", "\uD83C\uDDF3\uD83C\uDDF1", "6 12345678", 9),
    Country("BE", "Belgium", "+32", "\uD83C\uDDE7\uD83C\uDDEA", "470 12 34 56", 9),
    Country("CH", "Switzerland", "+41", "\uD83C\uDDE8\uD83C\uDDED", "78 123 45 67", 9),
    Country("AT", "Austria", "+43", "\uD83C\uDDE6\uD83C\uDDF9", "664 1234567", 10),
    Country("SE", "Sweden", "+46", "\uD83C\uDDF8\uD83C\uDDEA", "70 123 45 67", 9),
    Country("NO", "Norway", "+47", "\uD83C\uDDF3\uD83C\uDDF4", "412 34 567", 8),
    Country("DK", "Denmark", "+45", "\uD83C\uDDE9\uD83C\uDDF0", "20 12 34 56", 8),
    Country("FI", "Finland", "+358", "\uD83C\uDDEB\uD83C\uDDEE", "41 2345678", 9),
    Country("IE", "Ireland", "+353", "\uD83C\uDDEE\uD83C\uDDEA", "85 123 4567", 9),
    Country("PT", "Portugal", "+351", "\uD83C\uDDF5\uD83C\uDDF9", "912 345 678", 9),
    Country("PL", "Poland", "+48", "\uD83C\uDDF5\uD83C\uDDF1", "512 345 678", 9),
    Country("CZ", "Czech Republic", "+420", "\uD83C\uDDE8\uD83C\uDDFF", "601 234 567", 9),
    Country("JP", "Japan", "+81", "\uD83C\uDDEF\uD83C\uDDF5", "90-1234-5678", 10),
    Country("CN", "China", "+86", "\uD83C\uDDE8\uD83C\uDDF3", "138 1234 5678", 11),
    Country("KR", "South Korea", "+82", "\uD83C\uDDF0\uD83C\uDDF7", "10-1234-5678", 10),
    Country("IN", "India", "+91", "\uD83C\uDDEE\uD83C\uDDF3", "98765 43210", 10),
    Country("SG", "Singapore", "+65", "\uD83C\uDDF8\uD83C\uDDEC", "9123 4567", 8),
    Country("HK", "Hong Kong", "+852", "\uD83C\uDDED\uD83C\uDDF0", "9123 4567", 8),
    Country("TW", "Taiwan", "+886", "\uD83C\uDDF9\uD83C\uDDFC", "912 345 678", 9),
    Country("TH", "Thailand", "+66", "\uD83C\uDDF9\uD83C\uDDED", "81 234 5678", 9),
    Country("MY", "Malaysia", "+60", "\uD83C\uDDF2\uD83C\uDDFE", "12 345 6789", 9),
    Country("PH", "Philippines", "+63", "\uD83C\uDDF5\uD83C\uDDED", "917 123 4567", 10),
    Country("ID", "Indonesia", "+62", "\uD83C\uDDEE\uD83C\uDDE9", "812 3456 7890", 11),
    Country("VN", "Vietnam", "+84", "\uD83C\uDDFB\uD83C\uDDF3", "91 234 56 78", 9),
    Country("BR", "Brazil", "+55", "\uD83C\uDDE7\uD83C\uDDF7", "11 98765-4321", 11),
    Country("MX", "Mexico", "+52", "\uD83C\uDDF2\uD83C\uDDFD", "55 1234 5678", 10),
    Country("AR", "Argentina", "+54", "\uD83C\uDDE6\uD83C\uDDF7", "11 1234-5678", 10),
    Country("CL", "Chile", "+56", "\uD83C\uDDE8\uD83C\uDDF1", "9 1234 5678", 9),
    Country("CO", "Colombia", "+57", "\uD83C\uDDE8\uD83C\uDDF4", "310 123 4567", 10),
    Country("PE", "Peru", "+51", "\uD83C\uDDF5\uD83C\uDDEA", "912 345 678", 9),
    Country("ZA", "South Africa", "+27", "\uD83C\uDDFF\uD83C\uDDE6", "71 123 4567", 9),
    Country("EG", "Egypt", "+20", "\uD83C\uDDEA\uD83C\uDDEC", "100 123 4567", 10),
    Country("NG", "Nigeria", "+234", "\uD83C\uDDF3\uD83C\uDDEC", "803 123 4567", 10),
    Country("KE", "Kenya", "+254", "\uD83C\uDDF0\uD83C\uDDEA", "712 345678", 9),
    Country("AE", "UAE", "+971", "\uD83C\uDDE6\uD83C\uDDEA", "50 123 4567", 9),
    Country("SA", "Saudi Arabia", "+966", "\uD83C\uDDF8\uD83C\uDDE6", "50 123 4567", 9),
    Country("IL", "Israel", "+972", "\uD83C\uDDEE\uD83C\uDDF1", "50 123 4567", 9),
    Country("TR", "Turkey", "+90", "\uD83C\uDDF9\uD83C\uDDF7", "532 123 45 67", 10),
    Country("RU", "Russia", "+7", "\uD83C\uDDF7\uD83C\uDDFA", "912 345-67-89", 10),
    Country("UA", "Ukraine", "+380", "\uD83C\uDDFA\uD83C\uDDE6", "50 123 4567", 9),
    Country("NZ", "New Zealand", "+64", "\uD83C\uDDF3\uD83C\uDDFF", "21 123 4567", 9)
)
