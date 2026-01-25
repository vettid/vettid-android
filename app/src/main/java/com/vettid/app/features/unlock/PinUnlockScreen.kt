package com.vettid.app.features.unlock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * PIN unlock screen for app authentication.
 *
 * Shows a 6-digit PIN entry with numeric keypad.
 * Verifies PIN against the vault enclave via NATS.
 */
@Composable
fun PinUnlockScreen(
    onUnlocked: () -> Unit,
    viewModel: PinUnlockViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is PinUnlockEffect.UnlockSuccess -> onUnlocked()
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // App icon/logo
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = "Unlock VettID",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle based on state
            Text(
                text = when (state) {
                    is PinUnlockState.Idle, is PinUnlockState.EnteringPin -> "Enter your 6-digit PIN"
                    is PinUnlockState.Connecting -> "Connecting to vault..."
                    is PinUnlockState.Verifying -> "Verifying PIN..."
                    is PinUnlockState.Success -> "Unlocked!"
                    is PinUnlockState.Error -> "Error occurred"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            when (val currentState = state) {
                is PinUnlockState.EnteringPin -> {
                    PinEntryContent(
                        pin = currentState.pin,
                        error = currentState.error,
                        onPinChanged = { viewModel.onEvent(PinUnlockEvent.PinChanged(it)) },
                        onSubmit = { viewModel.onEvent(PinUnlockEvent.SubmitPin) }
                    )
                }

                is PinUnlockState.Connecting, is PinUnlockState.Verifying -> {
                    LoadingContent()
                }

                is PinUnlockState.Success -> {
                    SuccessContent()
                }

                is PinUnlockState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = { viewModel.onEvent(PinUnlockEvent.Retry) }
                    )
                }

                else -> {}
            }
        }
    }
}

@Composable
private fun PinEntryContent(
    pin: String,
    error: String?,
    onPinChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // PIN dots display
        PinDotsDisplay(
            pinLength = pin.length,
            maxLength = PinUnlockViewModel.PIN_LENGTH
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Error message
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Numeric keypad
        NumericKeypad(
            onDigit = { digit ->
                if (pin.length < PinUnlockViewModel.PIN_LENGTH) {
                    val newPin = pin + digit
                    onPinChanged(newPin)
                    // Auto-submit when PIN is complete
                    if (newPin.length == PinUnlockViewModel.PIN_LENGTH) {
                        onSubmit()
                    }
                }
            },
            onBackspace = {
                if (pin.isNotEmpty()) {
                    onPinChanged(pin.dropLast(1))
                }
            }
        )
    }
}

@Composable
private fun PinDotsDisplay(
    pinLength: Int,
    maxLength: Int
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(maxLength) { index ->
            val isFilled = index < pinLength
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFilled) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun NumericKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Row 1: 1 2 3
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeypadButton("1") { onDigit("1") }
            KeypadButton("2") { onDigit("2") }
            KeypadButton("3") { onDigit("3") }
        }

        // Row 2: 4 5 6
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeypadButton("4") { onDigit("4") }
            KeypadButton("5") { onDigit("5") }
            KeypadButton("6") { onDigit("6") }
        }

        // Row 3: 7 8 9
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeypadButton("7") { onDigit("7") }
            KeypadButton("8") { onDigit("8") }
            KeypadButton("9") { onDigit("9") }
        }

        // Row 4: empty 0 backspace
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // Empty space
            Box(modifier = Modifier.size(72.dp))

            KeypadButton("0") { onDigit("0") }

            // Backspace button
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .clickable { onBackspace() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Backspace,
                    contentDescription = "Backspace",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun KeypadButton(
    digit: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = digit,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LoadingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
private fun SuccessContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color(0xFF4CAF50)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Welcome back!",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Try Again")
        }
    }
}
