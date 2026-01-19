package com.vettid.app.features.enrollment

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.R
import com.vettid.app.ui.components.QrCodeScanner

/**
 * Main enrollment screen container
 * Animates between different enrollment states
 */
@Composable
fun EnrollmentScreen(
    onEnrollmentComplete: () -> Unit,
    onCancel: () -> Unit,
    startWithManualEntry: Boolean = false,
    initialCode: String? = null,
    viewModel: EnrollmentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is EnrollmentEffect.NavigateToMain -> onEnrollmentComplete()
                is EnrollmentEffect.ShowToast -> { /* Handle toast */ }
                else -> {}
            }
        }
    }

    // Auto-start scanning, manual entry, or process initial code when screen opens
    LaunchedEffect(startWithManualEntry, initialCode) {
        if (state is EnrollmentState.Initial) {
            if (initialCode != null) {
                // Process the code from deep link directly
                viewModel.onEvent(EnrollmentEvent.QRCodeScanned(initialCode))
            } else if (startWithManualEntry) {
                viewModel.onEvent(EnrollmentEvent.SwitchToManualEntry)
            } else {
                viewModel.onEvent(EnrollmentEvent.StartScanning)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { targetState ->
                // Use class name as key so updates within the same state don't trigger animation
                targetState::class.simpleName
            },
            label = "enrollment_transition"
        ) { currentState ->
            when (currentState) {
                is EnrollmentState.Initial -> {
                    LoadingContent("Initializing...")
                }
                is EnrollmentState.ScanningQR -> {
                    QRScannerContent(
                        error = currentState.error,
                        onCodeScanned = { code ->
                            viewModel.onEvent(EnrollmentEvent.QRCodeScanned(code))
                        },
                        onManualEntry = {
                            viewModel.onEvent(EnrollmentEvent.SwitchToManualEntry)
                        },
                        onCancel = onCancel
                    )
                }
                is EnrollmentState.ManualEntry -> {
                    ManualEntryContent(
                        inviteCode = currentState.inviteCode,
                        error = currentState.error,
                        onInviteCodeChange = { code ->
                            viewModel.onEvent(EnrollmentEvent.InviteCodeChanged(code))
                        },
                        onSubmit = {
                            viewModel.onEvent(EnrollmentEvent.SubmitInviteCode)
                        },
                        onScanQR = {
                            viewModel.onEvent(EnrollmentEvent.SwitchToScanning)
                        },
                        onCancel = onCancel
                    )
                }
                is EnrollmentState.ProcessingInvite -> {
                    EnrollmentProgressContent(
                        currentStep = EnrollmentStep.AUTHENTICATING,
                        message = "Validating your invitation code...",
                        onCancel = onCancel
                    )
                }
                is EnrollmentState.Attesting -> {
                    EnrollmentProgressContent(
                        currentStep = EnrollmentStep.DEVICE_ATTESTATION,
                        message = "Generating hardware attestation certificate...",
                        onCancel = onCancel
                    )
                }
                is EnrollmentState.ConnectingToNats -> {
                    EnrollmentProgressContent(
                        currentStep = EnrollmentStep.CONNECTING,
                        message = currentState.message,
                        onCancel = onCancel
                    )
                }
                is EnrollmentState.RequestingAttestation -> {
                    EnrollmentProgressContent(
                        currentStep = EnrollmentStep.ENCLAVE_VERIFICATION,
                        message = currentState.message,
                        onCancel = onCancel
                    )
                }
                is EnrollmentState.AttestationVerified -> {
                    AttestationVerifiedContent(
                        attestationInfo = currentState.attestationInfo
                    )
                }
                is EnrollmentState.SettingPin -> {
                    PinSetupContent(
                        pin = currentState.pin,
                        confirmPin = currentState.confirmPin,
                        isSubmitting = currentState.isSubmitting,
                        error = currentState.error,
                        attestationInfo = currentState.attestationInfo,
                        onPinChange = { viewModel.onEvent(EnrollmentEvent.PinChanged(it)) },
                        onConfirmPinChange = { viewModel.onEvent(EnrollmentEvent.ConfirmPinChanged(it)) },
                        onSubmit = { viewModel.onEvent(EnrollmentEvent.SubmitPin) },
                        onCancel = onCancel
                    )
                }
                is EnrollmentState.WaitingForVault -> {
                    LoadingContent(currentState.message, currentState.progress)
                }
                is EnrollmentState.CreatingCredential -> {
                    CreatingCredentialContent(
                        message = currentState.message,
                        progress = currentState.progress
                    )
                }
                is EnrollmentState.VerifyingEnrollment -> {
                    LoadingContent(currentState.message, currentState.progress)
                }
                is EnrollmentState.SettingPassword -> {
                    PasswordSetupContent(
                        password = currentState.password,
                        confirmPassword = currentState.confirmPassword,
                        strength = currentState.strength,
                        isSubmitting = currentState.isSubmitting,
                        error = currentState.error,
                        onPasswordChange = { viewModel.onEvent(EnrollmentEvent.PasswordChanged(it)) },
                        onConfirmPasswordChange = { viewModel.onEvent(EnrollmentEvent.ConfirmPasswordChanged(it)) },
                        onSubmit = { viewModel.onEvent(EnrollmentEvent.SubmitPassword) },
                        onCancel = onCancel
                    )
                }
                is EnrollmentState.Finalizing -> {
                    FinalizingContent(progress = currentState.progress)
                }
                is EnrollmentState.Complete -> {
                    EnrollmentCompleteContent(
                        userGuid = currentState.userGuid,
                        onContinue = onEnrollmentComplete
                    )
                }
                is EnrollmentState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        retryable = currentState.retryable,
                        onRetry = { viewModel.onEvent(EnrollmentEvent.Retry) },
                        onCancel = onCancel
                    )
                }
            }
        }
    }
}

// MARK: - QR Scanner Content

@Composable
private fun QRScannerContent(
    error: String?,
    onCodeScanned: (String) -> Unit,
    onManualEntry: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
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
                text = "Scan Invitation",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onManualEntry) {
                Icon(Icons.Default.Edit, contentDescription = "Enter code manually")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Instructions
        Text(
            text = "Scan the QR code from your VettID web portal to begin enrollment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // QR Scanner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            QrCodeScanner(
                onQrCodeScanned = { code ->
                    // Pass raw QR data to be parsed by ViewModel
                    if (code.isNotEmpty()) {
                        onCodeScanned(code)
                    }
                },
                onError = { /* Error handled via error parameter */ },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Error message
        if (error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Manual entry option
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = onManualEntry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enter code manually")
        }
    }
}

// MARK: - Manual Entry Content

@Composable
private fun ManualEntryContent(
    inviteCode: String,
    error: String?,
    onInviteCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanQR: () -> Unit,
    onCancel: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
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
                text = "Enter Invitation Code",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onScanQR) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR code")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Icon
        Icon(
            imageVector = Icons.Default.VpnKey,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .align(Alignment.CenterHorizontally),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Instructions
        Text(
            text = "Enter the invitation code from your VettID web portal or paste the enrollment link.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Invite code input
        OutlinedTextField(
            value = inviteCode,
            onValueChange = onInviteCodeChange,
            label = { Text("Invitation Code") },
            placeholder = { Text("Enter code or paste link") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    onSubmit()
                }
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null
        )

        // Error message
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
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
            enabled = inviteCode.isNotBlank()
        ) {
            Text("Continue")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Scan QR option
        TextButton(
            onClick = onScanQR,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan QR code instead")
        }
    }
}

// MARK: - Attestation Content

@Composable
private fun AttestationContent(
    progress: Float,
    message: String = "Verifying device security...",
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Verifying Device Security",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Performing hardware attestation to verify device integrity...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        TextButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

// MARK: - Attestation Verified Content

@Composable
private fun AttestationVerifiedContent(
    attestationInfo: AttestationInfo
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.VerifiedUser,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Enclave Verified",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Secure connection established with AWS Nitro Enclave",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Show enclave info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "All security checks passed",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Module: ${attestationInfo.moduleId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )

                if (attestationInfo.pcrVersion != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "PCR Version: ${attestationInfo.pcrVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Preparing PIN setup...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// MARK: - PIN Setup Content (Nitro Flow)

@Composable
private fun PinSetupContent(
    pin: String,
    confirmPin: String,
    isSubmitting: Boolean,
    error: String?,
    attestationInfo: AttestationInfo?,
    onPinChange: (String) -> Unit,
    onConfirmPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
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
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Title and description
        Icon(
            imageVector = Icons.Default.Dialpad,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Set Your PIN",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Create a 6-digit PIN to secure your vault. This PIN is encrypted and sent directly to the secure enclave.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // PIN input
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 6) onPinChange(it) },
            label = { Text("PIN") },
            placeholder = { Text("Enter 6-digit PIN") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Confirm PIN input with match indicator
        val pinsMatch = pin.length == 6 && confirmPin.length == 6 && pin == confirmPin
        val pinsMismatch = pin.length == 6 && confirmPin.length == 6 && pin != confirmPin

        OutlinedTextField(
            value = confirmPin,
            onValueChange = { if (it.length <= 6) onConfirmPinChange(it) },
            label = { Text("Confirm PIN") },
            placeholder = { Text("Re-enter PIN") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (pinsMatch) {
                        onSubmit()
                    }
                }
            ),
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = {
                when {
                    pinsMatch -> Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "PINs match",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    pinsMismatch -> Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "PINs don't match",
                        tint = MaterialTheme.colorScheme.error
                    )
                    else -> null
                }
            },
            isError = pinsMismatch
        )

        // PIN match feedback
        if (pinsMatch) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "PINs match",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else if (pinsMismatch) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "PINs don't match",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

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
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting && pin.length == 6 && confirmPin.length == 6
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Continue")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Attestation info section
        if (attestationInfo != null) {
            var expanded by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (attestationInfo.pcrsVerified)
                                    Icons.Default.VerifiedUser
                                else
                                    Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (attestationInfo.pcrsVerified)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (attestationInfo.pcrsVerified)
                                    "Enclave Verified"
                                else
                                    "Verification Warning",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Icon(
                            imageVector = if (expanded)
                                Icons.Default.ExpandLess
                            else
                                Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand"
                        )
                    }

                    if (expanded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // Module ID
                        Text(
                            text = "Module ID",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = attestationInfo.moduleId,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // PCR Version
                        if (attestationInfo.pcrVersion != null) {
                            Text(
                                text = "PCR Version",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = attestationInfo.pcrVersion,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // PCR Description (#44)
                        if (attestationInfo.pcrDescription != null) {
                            Text(
                                text = "Changes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = attestationInfo.pcrDescription,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // PCR0 (enclave image hash)
                        Text(
                            text = "Enclave Image (PCR0)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = attestationInfo.pcr0Short,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Timestamp
                        Text(
                            text = "Attested At",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm:ss",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date(attestationInfo.timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Security note
        Text(
            text = "Your PIN is protected by hardware encryption",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// MARK: - Password Setup Content

@Composable
private fun PasswordSetupContent(
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

        // Tower logo
        Icon(
            painter = painterResource(id = R.drawable.vettid_logo_gold),
            contentDescription = "VettID Vault",
            modifier = Modifier.size(80.dp),
            tint = Color.Unspecified  // Use original colors from vector
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
            text = "This password protects your Protean Credential and is required for secure operations.",
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

// MARK: - Finalizing Content

@Composable
private fun FinalizingContent(progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Completing Enrollment",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Creating your secure credential...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(8.dp),
        )
    }
}

// MARK: - Enrollment Complete Content

@Composable
private fun EnrollmentCompleteContent(
    userGuid: String,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = Color(0xFF4CAF50)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Enrollment Complete!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your vault credentials have been securely stored on this device.",
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
            Text("Continue to VettID")
        }
    }
}

// MARK: - Error Content

@Composable
private fun ErrorContent(
    message: String,
    retryable: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Enrollment Error",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (retryable) {
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

            Spacer(modifier = Modifier.height(16.dp))
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

// MARK: - Loading Content

@Composable
private fun LoadingContent(message: String, progress: Float? = null) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (progress != null) {
            CircularProgressIndicator(progress = progress)
        } else {
            CircularProgressIndicator()
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// MARK: - Creating Credential Content

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
        // Tower logo
        Icon(
            painter = painterResource(id = R.drawable.vettid_logo_gold),
            contentDescription = "VettID Vault",
            modifier = Modifier.size(100.dp),
            tint = Color.Unspecified
        )

        Spacer(modifier = Modifier.height(32.dp))

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
                .padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// MARK: - Enrollment Progress Content

/**
 * Shows enrollment progress with step indicators
 */
@Composable
private fun EnrollmentProgressContent(
    currentStep: EnrollmentStep,
    message: String,
    onCancel: () -> Unit
) {
    val steps = listOf(
        EnrollmentStep.AUTHENTICATING to "Authenticating",
        EnrollmentStep.DEVICE_ATTESTATION to "Device Attestation",
        EnrollmentStep.CONNECTING to "Connecting to Vault",
        EnrollmentStep.ENCLAVE_VERIFICATION to "Enclave Verification",
        EnrollmentStep.SETTING_UP to "Setting Up Credentials"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Header
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Setting Up Your Vault",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Step indicators
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            steps.forEach { (step, label) ->
                EnrollmentStepRow(
                    label = label,
                    state = when {
                        step.ordinal < currentStep.ordinal -> StepState.COMPLETED
                        step.ordinal == currentStep.ordinal -> StepState.IN_PROGRESS
                        else -> StepState.PENDING
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Current action message
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        TextButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

private enum class StepState { PENDING, IN_PROGRESS, COMPLETED }

@Composable
private fun EnrollmentStepRow(
    label: String,
    state: StepState
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Step indicator icon
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                StepState.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                StepState.IN_PROGRESS -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                StepState.PENDING -> {
                    Icon(
                        imageVector = Icons.Default.RadioButtonUnchecked,
                        contentDescription = "Pending",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = when (state) {
                StepState.COMPLETED -> MaterialTheme.colorScheme.primary
                StepState.IN_PROGRESS -> MaterialTheme.colorScheme.onSurface
                StepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
            fontWeight = if (state == StepState.IN_PROGRESS) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/**
 * Enrollment steps for progress tracking
 */
enum class EnrollmentStep {
    AUTHENTICATING,
    DEVICE_ATTESTATION,
    CONNECTING,
    ENCLAVE_VERIFICATION,
    SETTING_UP
}
