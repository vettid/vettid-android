package com.vettid.app.features.auth

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * Main authentication screen with state-driven content
 */
@Composable
fun AuthenticationScreen(
    action: String,
    onAuthComplete: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: AuthenticationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Start auth when screen loads
    LaunchedEffect(action) {
        viewModel.onEvent(AuthenticationEvent.StartAuth(action))
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is AuthenticationEffect.AuthComplete -> onAuthComplete(effect.actionToken)
                is AuthenticationEffect.NavigateBack -> onCancel()
                else -> { /* Handle other effects */ }
            }
        }
    }

    Scaffold(
        topBar = {
            AuthTopBar(
                title = when (state) {
                    is AuthenticationState.Initial,
                    is AuthenticationState.RequestingAction -> "Authenticating"
                    is AuthenticationState.VerifyingLAT -> "Verify Server"
                    is AuthenticationState.EnteringPassword -> "Enter Password"
                    is AuthenticationState.Executing -> "Authenticating"
                    is AuthenticationState.Success -> "Success"
                    is AuthenticationState.Error -> "Error"
                },
                onCancel = { viewModel.onEvent(AuthenticationEvent.Cancel) },
                showCancel = state !is AuthenticationState.Success
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                fadeIn() + slideInHorizontally() togetherWith
                    fadeOut() + slideOutHorizontally()
            },
            label = "auth_state_transition"
        ) { targetState ->
            when (targetState) {
                is AuthenticationState.Initial,
                is AuthenticationState.RequestingAction -> {
                    RequestingActionContent(
                        state = targetState as? AuthenticationState.RequestingAction
                    )
                }
                is AuthenticationState.VerifyingLAT -> {
                    VerifyingLATContent(
                        state = targetState,
                        onConfirm = { viewModel.onEvent(AuthenticationEvent.ConfirmLAT) },
                        onReject = { viewModel.onEvent(AuthenticationEvent.RejectLAT) }
                    )
                }
                is AuthenticationState.EnteringPassword -> {
                    EnteringPasswordContent(
                        state = targetState,
                        onPasswordChanged = { viewModel.onEvent(AuthenticationEvent.PasswordChanged(it)) },
                        onSubmit = { viewModel.onEvent(AuthenticationEvent.SubmitPassword) }
                    )
                }
                is AuthenticationState.Executing -> {
                    ExecutingContent(state = targetState)
                }
                is AuthenticationState.Success -> {
                    SuccessContent(
                        state = targetState,
                        onProceed = { viewModel.onEvent(AuthenticationEvent.Proceed) }
                    )
                }
                is AuthenticationState.Error -> {
                    ErrorContent(
                        state = targetState,
                        onRetry = { viewModel.onEvent(AuthenticationEvent.Retry) },
                        onCancel = { viewModel.onEvent(AuthenticationEvent.Cancel) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthTopBar(
    title: String,
    onCancel: () -> Unit,
    showCancel: Boolean
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (showCancel) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            }
        }
    )
}

@Composable
private fun RequestingActionContent(
    state: AuthenticationState.RequestingAction?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            progress = { state?.progress ?: 0f }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Requesting authentication...",
            style = MaterialTheme.typography.titleMedium
        )

        state?.action?.let { action ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Action: $action",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VerifyingLATContent(
    state: AuthenticationState.VerifyingLAT,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Security icon
        Icon(
            imageVector = if (state.latMatch) Icons.Default.VerifiedUser else Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = if (state.latMatch)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (state.latMatch)
                "Server Verified"
            else
                "Security Warning",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (state.latMatch)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (state.latMatch)
                "The server's identity has been verified. You can safely proceed with authentication."
            else
                "The server's identity token does not match what was expected. This could indicate a phishing attack.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // LAT Display Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Ledger Auth Token",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Show first/last 8 chars of token
                val displayToken = state.serverLat.token.let { token ->
                    if (token.length > 16) {
                        "${token.take(8)}...${token.takeLast(8)}"
                    } else token
                }

                Text(
                    text = displayToken,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (state.latMatch) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (state.latMatch)
                            Color(0xFF4CAF50)
                        else
                            MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (state.latMatch) "Matches stored token" else "Does NOT match",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.latMatch)
                            Color(0xFF4CAF50)
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        if (state.latMatch) {
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Continue to Password")
            }
        } else {
            // Warning box for mismatch
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Do NOT enter your password. Cancel and report this incident.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onReject,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Cancel, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel Authentication")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun EnteringPasswordContent(
    state: AuthenticationState.EnteringPassword,
    onPasswordChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Enter Your Password",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Your password will be encrypted before sending",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChanged,
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (passwordVisible)
                VisualTransformation.None
            else
                PasswordVisualTransformation(),
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
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible)
                            Icons.Default.VisibilityOff
                        else
                            Icons.Default.Visibility,
                        contentDescription = if (passwordVisible)
                            "Hide password"
                        else
                            "Show password"
                    )
                }
            },
            isError = state.error != null,
            supportingText = state.error?.let { { Text(it) } },
            enabled = !state.isSubmitting
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                focusManager.clearFocus()
                onSubmit()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = state.password.isNotEmpty() && !state.isSubmitting
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Login, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Authenticate")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ExecutingContent(
    state: AuthenticationState.Executing
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(80.dp),
            progress = { state.progress }
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = state.statusMessage,
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${(state.progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuccessContent(
    state: AuthenticationState.Success,
    onProceed: () -> Unit
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
            modifier = Modifier.size(96.dp),
            tint = Color(0xFF4CAF50)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Authentication Successful",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (state.keysReplenished > 0) {
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${state.keysReplenished} transaction keys replenished",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onProceed,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Continue")
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun ErrorContent(
    state: AuthenticationState.Error,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = when (state.code) {
                AuthErrorCode.LAT_MISMATCH -> Icons.Default.GppBad
                AuthErrorCode.INVALID_CREDENTIALS -> Icons.Default.LockOpen
                AuthErrorCode.NO_TRANSACTION_KEYS -> Icons.Default.VpnKeyOff
                else -> Icons.Default.Error
            },
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = when (state.code) {
                AuthErrorCode.LAT_MISMATCH -> "Security Alert"
                AuthErrorCode.INVALID_CREDENTIALS -> "Wrong Password"
                AuthErrorCode.NO_TRANSACTION_KEYS -> "No Keys Available"
                AuthErrorCode.CONCURRENT_SESSION -> "Session Conflict"
                AuthErrorCode.SESSION_EXPIRED -> "Session Expired"
                else -> "Authentication Failed"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (state.retryable) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Try Again")
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Cancel")
        }
    }
}
