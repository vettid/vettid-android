package com.vettid.app.features.devices

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
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun DeviceApprovalScreen(
    onDismiss: () -> Unit,
    viewModel: DeviceApprovalViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val s = state) {
            is DeviceApprovalState.Idle -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Waiting for approval requests...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is DeviceApprovalState.Ready -> {
                ApprovalRequestCard(
                    request = s.request,
                    elapsedSeconds = s.elapsedSeconds,
                    onApprove = { viewModel.approve() },
                    onDeny = { viewModel.deny() }
                )
            }

            is DeviceApprovalState.ProcessingApproval -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Approving...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is DeviceApprovalState.ProcessingDenial -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Denying...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is DeviceApprovalState.Approved -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Approved", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    viewModel.dismiss()
                    onDismiss()
                }) {
                    Text("Done")
                }
            }

            is DeviceApprovalState.Denied -> {
                Icon(
                    Icons.Default.Cancel,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Denied", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    viewModel.dismiss()
                    onDismiss()
                }) {
                    Text("Done")
                }
            }

            is DeviceApprovalState.Timeout -> {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Request Expired", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "The approval request has timed out.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    viewModel.dismiss()
                    onDismiss()
                }) {
                    Text("Dismiss")
                }
            }

            is DeviceApprovalState.Error -> {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Error", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    viewModel.dismiss()
                    onDismiss()
                }) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
fun ApprovalRequestCard(
    request: DeviceApprovalRequest,
    elapsedSeconds: Int,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Phone icon
            Icon(
                Icons.Default.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Approve on Your Phone",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Device info
            DetailRow(label = "Device", value = request.deviceName)
            if (request.hostname != null) {
                DetailRow(label = "Hostname", value = request.hostname)
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            // Operation info
            DetailRow(label = "Operation", value = formatOperation(request.operation))
            if (!request.secretName.isNullOrEmpty()) {
                DetailRow(label = "Secret", value = request.secretName)
            }
            if (!request.category.isNullOrEmpty()) {
                DetailRow(label = "Category", value = request.category)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Elapsed time
            Text(
                "Waiting... ${elapsedSeconds}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Deny")
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Approve")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatOperation(operation: String): String {
    return when (operation) {
        "secrets.retrieve" -> "Retrieve Secret"
        "secrets.add" -> "Add Secret"
        "secrets.delete" -> "Delete Secret"
        "connection.create" -> "Create Connection"
        "connection.revoke" -> "Revoke Connection"
        "profile.update" -> "Update Profile"
        "personal-data.get" -> "Access Personal Data"
        "personal-data.update" -> "Update Personal Data"
        "credential.get" -> "Access Credential"
        "credential.update" -> "Update Credential"
        "service.auth.request" -> "Service Authentication"
        "agent.approve" -> "Approve Agent"
        else -> operation.replace(".", " ").replaceFirstChar { it.uppercase() }
    }
}
