package com.vettid.app.features.enrollmentwizard.phases

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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.app.features.enrollment.AttestationInfo

/**
 * PIN setup phase content.
 */
@Composable
fun PinSetupPhaseContent(
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
            AttestationInfoCard(attestationInfo = attestationInfo)
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

@Composable
private fun AttestationInfoCard(attestationInfo: AttestationInfo) {
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
                HorizontalDivider()
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

                // PCR Description
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

                // PCR0
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
}
