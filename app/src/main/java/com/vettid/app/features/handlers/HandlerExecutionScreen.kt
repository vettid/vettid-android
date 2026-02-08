package com.vettid.app.features.handlers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.collectLatest

/**
 * Screen for executing handlers with dynamic input forms.
 *
 * Features:
 * - Dynamic form generation from JSON schema
 * - Input validation
 * - Execution progress
 * - Output display
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandlerExecutionScreen(
    viewModel: HandlerExecutionViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onRequireAuth: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()
    val formValues by viewModel.formValues.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is HandlerExecutionEffect.RequireAuth -> onRequireAuth()
                is HandlerExecutionEffect.NavigateBack -> onNavigateBack()
                is HandlerExecutionEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is HandlerExecutionEffect.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        actionLabel = "Dismiss"
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val currentState = state) {
                            is HandlerExecutionState.Ready -> "Execute ${currentState.handler.name}"
                            is HandlerExecutionState.Completed -> "Execution Complete"
                            is HandlerExecutionState.Failed -> "Execution Failed"
                            else -> "Execute Handler"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (val currentState = state) {
            is HandlerExecutionState.Loading -> LoadingContent(
                modifier = Modifier.padding(padding)
            )
            is HandlerExecutionState.Ready -> ReadyContent(
                inputFields = currentState.inputFields,
                formValues = formValues,
                isExecuting = isExecuting,
                onFieldChange = { name, value -> viewModel.updateFieldValue(name, value) },
                onExecute = { viewModel.executeHandler() },
                modifier = Modifier.padding(padding)
            )
            is HandlerExecutionState.Completed -> CompletedContent(
                output = currentState.output,
                executionTimeMs = currentState.executionTimeMs,
                onReset = { viewModel.resetExecution() },
                onNavigateBack = onNavigateBack,
                modifier = Modifier.padding(padding)
            )
            is HandlerExecutionState.Failed -> FailedContent(
                error = currentState.error,
                onRetry = { viewModel.resetExecution() },
                onNavigateBack = onNavigateBack,
                modifier = Modifier.padding(padding)
            )
            is HandlerExecutionState.Error -> ErrorContent(
                message = currentState.message,
                onNavigateBack = onNavigateBack,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading handler...")
        }
    }
}

@Composable
private fun ReadyContent(
    inputFields: List<InputField>,
    formValues: Map<String, Any>,
    isExecuting: Boolean,
    onFieldChange: (String, Any) -> Unit,
    onExecute: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (inputFields.isEmpty()) {
            // No input required
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No input required",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "This handler can be executed without any input parameters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(inputFields) { field ->
                    InputFieldComposable(
                        field = field,
                        value = formValues[field.name],
                        onValueChange = { onFieldChange(field.name, it) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Execute button
        Button(
            onClick = onExecute,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isExecuting
        ) {
            if (isExecuting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Executing...")
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Execute Handler")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputFieldComposable(
    field: InputField,
    value: Any?,
    onValueChange: (Any) -> Unit
) {
    Column {
        when (field.type) {
            is InputFieldType.String -> {
                OutlinedTextField(
                    value = (value as? String) ?: (field.defaultValue as? String) ?: "",
                    onValueChange = { onValueChange(it) },
                    label = {
                        Text(
                            if (field.required) "${field.label} *" else field.label
                        )
                    },
                    supportingText = field.description?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            is InputFieldType.Integer -> {
                OutlinedTextField(
                    value = value?.toString() ?: field.defaultValue?.toString() ?: "",
                    onValueChange = { onValueChange(it) },
                    label = {
                        Text(
                            if (field.required) "${field.label} *" else field.label
                        )
                    },
                    supportingText = field.description?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            is InputFieldType.Number -> {
                OutlinedTextField(
                    value = value?.toString() ?: field.defaultValue?.toString() ?: "",
                    onValueChange = { onValueChange(it) },
                    label = {
                        Text(
                            if (field.required) "${field.label} *" else field.label
                        )
                    },
                    supportingText = field.description?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            is InputFieldType.Boolean -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = field.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        field.description?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = (value as? Boolean)
                            ?: (field.defaultValue as? Boolean)
                            ?: false,
                        onCheckedChange = { onValueChange(it) }
                    )
                }
            }

            is InputFieldType.Select -> {
                var expanded by remember { mutableStateOf(false) }
                val selectedValue = (value as? String)
                    ?: (field.defaultValue as? String)
                    ?: field.type.options.firstOrNull()
                    ?: ""

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedValue,
                        onValueChange = {},
                        readOnly = true,
                        label = {
                            Text(
                                if (field.required) "${field.label} *" else field.label
                            )
                        },
                        supportingText = field.description?.let { { Text(it) } },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        field.type.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    onValueChange(option)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            is InputFieldType.Array -> {
                // Simple comma-separated input for arrays
                val arrayValue = when (value) {
                    is List<*> -> value.joinToString(", ")
                    else -> ""
                }

                OutlinedTextField(
                    value = arrayValue,
                    onValueChange = { input ->
                        val items = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        onValueChange(items)
                    },
                    label = {
                        Text(
                            if (field.required) "${field.label} *" else field.label
                        )
                    },
                    supportingText = {
                        Text(field.description ?: "Enter comma-separated values")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CompletedContent(
    output: JsonObject?,
    executionTimeMs: Long,
    onReset: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gson = remember { GsonBuilder().setPrettyPrinting().create() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Success header
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
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Execution Successful",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Completed in ${executionTimeMs}ms",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Output section
        if (output != null && output.size() > 0) {
            Text(
                text = "Output",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Text(
                    text = gson.toJson(output),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No output returned",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Run Again")
            }

            Button(
                onClick = onNavigateBack,
                modifier = Modifier.weight(1f)
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun FailedContent(
    error: String,
    onRetry: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Execution Failed",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.weight(1f)
            ) {
                Text("Go Back")
            }

            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Try Again")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Failed to load handler",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Go Back")
        }
    }
}
