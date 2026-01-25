package com.vettid.app.features.enrollmentwizard.phases

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.app.features.enrollment.AttestationInfo

/**
 * Mode for attestation phase display
 */
enum class AttestationPhaseMode {
    PROCESSING,
    CONNECTING,
    VERIFYING,
    VERIFIED
}

/**
 * Attestation phase content - shows progress through attestation verification.
 */
@Composable
fun AttestationPhaseContent(
    mode: AttestationPhaseMode,
    message: String = "",
    progress: Float = 0f,
    attestationInfo: AttestationInfo? = null,
    onCancel: () -> Unit = {}
) {
    when (mode) {
        AttestationPhaseMode.PROCESSING -> ProcessingContent(
            message = message,
            onCancel = onCancel
        )
        AttestationPhaseMode.CONNECTING -> ConnectingContent(
            message = message,
            onCancel = onCancel
        )
        AttestationPhaseMode.VERIFYING -> VerifyingContent(
            message = message,
            progress = progress,
            onCancel = onCancel
        )
        AttestationPhaseMode.VERIFIED -> VerifiedContent(
            attestationInfo = attestationInfo
        )
    }
}

@Composable
private fun ProcessingContent(
    message: String,
    onCancel: () -> Unit
) {
    EnrollmentProgressContent(
        currentStep = EnrollmentStep.AUTHENTICATING,
        message = message,
        onCancel = onCancel
    )
}

@Composable
private fun ConnectingContent(
    message: String,
    onCancel: () -> Unit
) {
    EnrollmentProgressContent(
        currentStep = EnrollmentStep.CONNECTING,
        message = message,
        onCancel = onCancel
    )
}

@Composable
private fun VerifyingContent(
    message: String,
    progress: Float,
    onCancel: () -> Unit
) {
    EnrollmentProgressContent(
        currentStep = EnrollmentStep.ENCLAVE_VERIFICATION,
        message = message,
        onCancel = onCancel
    )
}

@Composable
private fun VerifiedContent(
    attestationInfo: AttestationInfo?
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

        if (attestationInfo != null) {
            Spacer(modifier = Modifier.height(32.dp))

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

/**
 * Enrollment progress with step indicators
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

private enum class EnrollmentStep {
    AUTHENTICATING,
    DEVICE_ATTESTATION,
    CONNECTING,
    ENCLAVE_VERIFICATION,
    SETTING_UP
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
