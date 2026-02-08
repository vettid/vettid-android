package com.vettid.app.features.enrollmentwizard.phases

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Mode for verify phase display
 */
enum class VerifyPhaseMode {
    PASSWORD_ENTRY,
    AUTHENTICATING,
    SUCCESS
}

/**
 * Verify phase content - password verification after enrollment.
 */
@Composable
fun VerifyPhaseContent(
    mode: VerifyPhaseMode,
    password: String = "",
    isPasswordVisible: Boolean = false,
    isSubmitting: Boolean = false,
    error: String? = null,
    progress: Float = 0f,
    statusMessage: String = "",
    successMessage: String = "",
    onPasswordChange: (String) -> Unit = {},
    onToggleVisibility: () -> Unit = {},
    onSubmit: () -> Unit = {},
    onSkip: () -> Unit = {},
    onContinue: () -> Unit = {}
) {
    when (mode) {
        VerifyPhaseMode.PASSWORD_ENTRY -> PasswordEntryContent(
            password = password,
            isPasswordVisible = isPasswordVisible,
            isSubmitting = isSubmitting,
            error = error,
            onPasswordChange = onPasswordChange,
            onToggleVisibility = onToggleVisibility,
            onSubmit = onSubmit,
            onSkip = onSkip
        )
        VerifyPhaseMode.AUTHENTICATING -> AuthenticatingContent(
            progress = progress,
            statusMessage = statusMessage
        )
        VerifyPhaseMode.SUCCESS -> SuccessContent(
            message = successMessage,
            onContinue = onContinue
        )
    }
}

@Composable
private fun PasswordEntryContent(
    password: String,
    isPasswordVisible: Boolean,
    isSubmitting: Boolean,
    error: String?,
    onPasswordChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Icon
        Icon(
            imageVector = Icons.Default.VerifiedUser,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = "Verify Your Credential",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Subtitle
        Text(
            text = "Enter your password to verify that your credential was created successfully.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Password field
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            placeholder = { Text("Enter your password") },
            singleLine = true,
            visualTransformation = if (isPasswordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        imageVector = if (isPasswordVisible) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = if (isPasswordVisible) {
                            "Hide password"
                        } else {
                            "Show password"
                        }
                    )
                }
            },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    onSubmit()
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            enabled = !isSubmitting
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Verify button
        Button(
            onClick = {
                focusManager.clearFocus()
                onSubmit()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = password.isNotBlank() && !isSubmitting
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Verify Credential")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Skip button
        TextButton(
            onClick = onSkip,
            enabled = !isSubmitting
        ) {
            Text("Skip for now")
        }
    }
}

@Composable
private fun AuthenticatingContent(
    progress: Float,
    statusMessage: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(80.dp),
            strokeWidth = 6.dp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = statusMessage,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun SuccessContent(
    message: String,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Credential Verified!",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Continue")
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}
