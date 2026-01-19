package com.vettid.app.features.migration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Emergency Recovery screen for disaster scenarios.
 *
 * Only shown when BOTH old and new enclaves are unavailable.
 * User must provide their master PIN to re-derive the DEK
 * and restore vault access.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyRecoveryScreen(
    onRecoveryComplete: () -> Unit,
    viewModel: EmergencyRecoveryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var pin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }

    // Navigate on success
    LaunchedEffect(state) {
        if (state is EmergencyRecoveryState.Success) {
            onRecoveryComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emergency Recovery") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    titleContentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Warning icon
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                "Emergency Recovery Required",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Description
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "A system issue requires you to re-authorize your vault.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Enter your master PIN to restore access to your data. " +
                        "This is a rare event that occurs only during major system updates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // PIN input
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    // Only allow digits
                    if (it.all { char -> char.isDigit() }) {
                        pin = it
                    }
                },
                label = { Text("Master PIN") },
                placeholder = { Text("Enter your PIN") },
                singleLine = true,
                visualTransformation = if (showPin) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showPin = !showPin }) {
                        Icon(
                            if (showPin) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPin) "Hide PIN" else "Show PIN"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                enabled = state !is EmergencyRecoveryState.Recovering,
                isError = state is EmergencyRecoveryState.Error &&
                        (state as EmergencyRecoveryState.Error).isPasswordError,
                modifier = Modifier.fillMaxWidth()
            )

            // Error message
            if (state is EmergencyRecoveryState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    (state as EmergencyRecoveryState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Recovery button
            Button(
                onClick = { viewModel.performRecovery(pin) },
                enabled = pin.length >= 4 && state !is EmergencyRecoveryState.Recovering,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (state is EmergencyRecoveryState.Recovering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Recovering...")
                } else {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Recover Vault")
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Help text
            Text(
                "If you've forgotten your PIN, please contact support.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
        }
    }
}
