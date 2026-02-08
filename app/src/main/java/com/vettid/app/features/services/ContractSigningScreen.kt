package com.vettid.app.features.services

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.crypto.UnsignedContract

/**
 * Screen for contract signing flow.
 *
 * Shows progress through the signing steps:
 * 1. Generating secure keys
 * 2. Sending to vault
 * 3. Processing response
 * 4. Success or error
 *
 * Issue #24 [AND-003] - Contract signing flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractSigningScreen(
    contract: UnsignedContract,
    viewModel: ContractSigningViewModel = hiltViewModel(),
    onSuccess: (contractId: String) -> Unit = {},
    onCancel: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()

    // Start signing when screen loads
    LaunchedEffect(contract) {
        if (state is ContractSigningState.Idle) {
            viewModel.signContract(contract)
        }
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ContractSigningEffect.SigningComplete -> {
                    onSuccess(effect.contractId)
                }
                is ContractSigningEffect.Cancelled -> {
                    onCancel()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connecting to Service") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.cancel()
                            onCancel()
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = state,
                label = "signing_state"
            ) { currentState ->
                when (currentState) {
                    is ContractSigningState.Idle -> {
                        // Brief loading before signing starts
                        CircularProgressIndicator()
                    }
                    is ContractSigningState.Signing -> {
                        SigningContent(
                            step = currentState.step,
                            serviceName = currentState.serviceName
                        )
                    }
                    is ContractSigningState.Success -> {
                        SuccessContent(
                            serviceName = currentState.contract.serviceName,
                            onContinue = { onSuccess(currentState.contract.contractId) }
                        )
                    }
                    is ContractSigningState.Error -> {
                        ErrorContent(
                            message = currentState.message,
                            canRetry = currentState.canRetry,
                            onRetry = { viewModel.retry() },
                            onCancel = {
                                viewModel.cancel()
                                onCancel()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SigningContent(
    step: SigningStep,
    serviceName: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Progress indicator
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(80.dp),
                strokeWidth = 6.dp
            )
            Icon(
                imageVector = when (step) {
                    SigningStep.GENERATING_KEYS -> Icons.Outlined.Key
                    SigningStep.SENDING_REQUEST -> Icons.AutoMirrored.Outlined.Send
                    SigningStep.PROCESSING_RESPONSE -> Icons.Outlined.Sync
                },
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Connecting to $serviceName",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = step.displayText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Step indicators
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SigningStep.entries.forEachIndexed { _, s ->
                val isComplete = s.ordinal < step.ordinal
                val isCurrent = s == step

                Box(
                    modifier = Modifier
                        .size(if (isCurrent) 12.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isComplete -> MaterialTheme.colorScheme.primary
                                isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun SuccessContent(
    serviceName: String,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Success icon
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50).copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color(0xFF4CAF50)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Connected!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "You're now connected to $serviceName",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "End-to-end encrypted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Error icon
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Connection Failed",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (canRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Try Again")
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }
    }
}
