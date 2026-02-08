package com.vettid.app.features.applock

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
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

/**
 * App Lock screen shown when the app is locked.
 * Per mobile-ui-plan.md Section 3.3.2
 */
@Composable
fun AppLockScreen(
    onUnlock: () -> Unit,
    onBiometricAuth: () -> Unit,
    showBiometricOption: Boolean = true,
    errorMessage: String? = null
) {
    var enteredPin by remember { mutableStateOf("") }
    var shake by remember { mutableStateOf(false) }

    // Handle PIN completion
    LaunchedEffect(enteredPin) {
        if (enteredPin.length == 4) {
            // In production, validate PIN
            // For demo, accept any 4-digit PIN
            onUnlock()
        }
    }

    // Reset shake after animation
    LaunchedEffect(shake) {
        if (shake) {
            kotlinx.coroutines.delay(500)
            shake = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(80.dp))

        // Logo/Icon
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "VettID is Locked",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter your PIN to unlock",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // PIN dots
        PinDots(
            length = enteredPin.length,
            maxLength = 4,
            shake = shake
        )

        // Error message
        AnimatedVisibility(visible = errorMessage != null) {
            Text(
                text = errorMessage ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Number pad
        NumberPad(
            onNumberClick = { number ->
                if (enteredPin.length < 4) {
                    enteredPin += number
                }
            },
            onDeleteClick = {
                if (enteredPin.isNotEmpty()) {
                    enteredPin = enteredPin.dropLast(1)
                }
            },
            onBiometricClick = if (showBiometricOption) onBiometricAuth else null
        )
    }
}

@Composable
private fun PinDots(
    length: Int,
    maxLength: Int,
    shake: Boolean
) {
    val offsetX by animateDpAsState(
        targetValue = if (shake) 10.dp else 0.dp,
        label = "shake"
    )

    Row(
        modifier = Modifier.offset(x = offsetX),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(maxLength) { index ->
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < length) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        }
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
private fun NumberPad(
    onNumberClick: (String) -> Unit,
    onDeleteClick: () -> Unit,
    onBiometricClick: (() -> Unit)?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Row 1: 1-2-3
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            NumberButton("1", onClick = { onNumberClick("1") })
            NumberButton("2", onClick = { onNumberClick("2") })
            NumberButton("3", onClick = { onNumberClick("3") })
        }

        // Row 2: 4-5-6
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            NumberButton("4", onClick = { onNumberClick("4") })
            NumberButton("5", onClick = { onNumberClick("5") })
            NumberButton("6", onClick = { onNumberClick("6") })
        }

        // Row 3: 7-8-9
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            NumberButton("7", onClick = { onNumberClick("7") })
            NumberButton("8", onClick = { onNumberClick("8") })
            NumberButton("9", onClick = { onNumberClick("9") })
        }

        // Row 4: Biometric/Empty - 0 - Delete
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (onBiometricClick != null) {
                IconActionButton(
                    icon = Icons.Default.Fingerprint,
                    contentDescription = "Use Biometrics",
                    onClick = onBiometricClick
                )
            } else {
                Spacer(modifier = Modifier.size(72.dp))
            }
            NumberButton("0", onClick = { onNumberClick("0") })
            IconActionButton(
                icon = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Delete",
                onClick = onDeleteClick
            )
        }
    }
}

@Composable
private fun NumberButton(
    number: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = number,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun IconActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * PIN Setup screen for creating a new PIN.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSetupScreen(
    onPinCreated: (String) -> Unit,
    onBack: () -> Unit
) {
    var step by remember { mutableStateOf(PinSetupStep.ENTER) }
    var firstPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Handle step transitions
    LaunchedEffect(firstPin) {
        if (firstPin.length == 4) {
            step = PinSetupStep.CONFIRM
        }
    }

    LaunchedEffect(confirmPin) {
        if (confirmPin.length == 4) {
            if (confirmPin == firstPin) {
                onPinCreated(confirmPin)
            } else {
                errorMessage = "PINs don't match. Try again."
                confirmPin = ""
                step = PinSetupStep.ENTER
                firstPin = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set Up PIN") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
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
            Spacer(modifier = Modifier.height(48.dp))

            // Icon
            Icon(
                imageVector = Icons.Default.Pin,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = when (step) {
                    PinSetupStep.ENTER -> "Create a PIN"
                    PinSetupStep.CONFIRM -> "Confirm your PIN"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (step) {
                    PinSetupStep.ENTER -> "Enter a 4-digit PIN to secure your app"
                    PinSetupStep.CONFIRM -> "Enter the same PIN again to confirm"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // PIN dots
            PinDots(
                length = when (step) {
                    PinSetupStep.ENTER -> firstPin.length
                    PinSetupStep.CONFIRM -> confirmPin.length
                },
                maxLength = 4,
                shake = false
            )

            // Error message
            AnimatedVisibility(visible = errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Number pad
            NumberPad(
                onNumberClick = { number ->
                    when (step) {
                        PinSetupStep.ENTER -> {
                            if (firstPin.length < 4) {
                                firstPin += number
                                errorMessage = null
                            }
                        }
                        PinSetupStep.CONFIRM -> {
                            if (confirmPin.length < 4) {
                                confirmPin += number
                            }
                        }
                    }
                },
                onDeleteClick = {
                    when (step) {
                        PinSetupStep.ENTER -> {
                            if (firstPin.isNotEmpty()) {
                                firstPin = firstPin.dropLast(1)
                            }
                        }
                        PinSetupStep.CONFIRM -> {
                            if (confirmPin.isNotEmpty()) {
                                confirmPin = confirmPin.dropLast(1)
                            }
                        }
                    }
                },
                onBiometricClick = null
            )
        }
    }
}

private enum class PinSetupStep {
    ENTER,
    CONFIRM
}
