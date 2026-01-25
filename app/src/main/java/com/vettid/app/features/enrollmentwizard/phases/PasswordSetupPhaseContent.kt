package com.vettid.app.features.enrollmentwizard.phases

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.app.R
import com.vettid.app.features.enrollment.PasswordStrength

/**
 * Password setup phase content.
 */
@Composable
fun PasswordSetupPhaseContent(
    password: String = "",
    confirmPassword: String = "",
    strength: PasswordStrength = PasswordStrength.WEAK,
    isSubmitting: Boolean = false,
    error: String? = null,
    isCreating: Boolean = false,
    creatingMessage: String = "",
    creatingProgress: Float = 0f,
    onPasswordChange: (String) -> Unit = {},
    onConfirmPasswordChange: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    if (isCreating) {
        CreatingCredentialContent(
            message = creatingMessage,
            progress = creatingProgress
        )
    } else {
        PasswordEntryContent(
            password = password,
            confirmPassword = confirmPassword,
            strength = strength,
            isSubmitting = isSubmitting,
            error = error,
            onPasswordChange = onPasswordChange,
            onConfirmPasswordChange = onConfirmPasswordChange,
            onSubmit = onSubmit,
            onCancel = onCancel
        )
    }
}

@Composable
private fun PasswordEntryContent(
    password: String,
    confirmPassword: String,
    strength: PasswordStrength,
    isSubmitting: Boolean,
    error: String?,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
            Text(
                text = "Create Password",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(32.dp))

        // App icon
        Icon(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "VettID",
            modifier = Modifier.size(100.dp),
            tint = Color.Unspecified
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Protean Credential Password",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This password protects your Protean Credential and is required for sensitive operations.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Password field
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            placeholder = { Text("Minimum 12 characters") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting,
            singleLine = true
        )

        // Password strength indicator
        if (password.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            PasswordStrengthIndicator(strength = strength)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Confirm password field
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = onConfirmPasswordChange,
            label = { Text("Confirm Password") },
            visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSubmit() }
            ),
            trailingIcon = {
                IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                    Icon(
                        if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting,
            singleLine = true,
            isError = confirmPassword.isNotEmpty() && password != confirmPassword
        )

        // Error message
        if (error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Submit button
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isSubmitting && password.length >= 12 && password == confirmPassword
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun PasswordStrengthIndicator(strength: PasswordStrength) {
    val (color, label) = when (strength) {
        PasswordStrength.WEAK -> Color.Red to "Weak"
        PasswordStrength.FAIR -> Color(0xFFFF9800) to "Fair"
        PasswordStrength.GOOD -> Color(0xFF4CAF50) to "Good"
        PasswordStrength.STRONG -> Color(0xFF2E7D32) to "Strong"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinearProgressIndicator(
            progress = (strength.ordinal + 1) / 4f,
            modifier = Modifier
                .weight(1f)
                .height(4.dp),
            color = color,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun CreatingCredentialContent(
    message: String,
    progress: Float
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App icon
        Icon(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "VettID",
            modifier = Modifier.size(120.dp),
            tint = Color.Unspecified
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Creating Your Protean Credential",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your credential is being securely generated inside the hardware-protected enclave.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Progress indicator
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
