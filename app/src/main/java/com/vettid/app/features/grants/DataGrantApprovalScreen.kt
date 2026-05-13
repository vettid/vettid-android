package com.vettid.app.features.grants

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen approval prompt for an incoming data or minor-secret
 * request. Fired automatically from VettIDApp on
 * GrantEvent.RequestReceived so the user gets the same heads-up
 * experience as critical-secret use and identity-verify.
 *
 * No password — the vault has already authorized the peer to issue
 * requests against this connection; approval here just consents to the
 * specific item + expiry + max-uses. The peer cannot fetch the value
 * until this approval lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataGrantApprovalScreen(
    viewModel: DataGrantApprovalViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state) {
        if (state is DataGrantApprovalViewModel.State.Approved ||
            state is DataGrantApprovalViewModel.State.Denied) {
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Approve access request") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Inbox,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            Text(
                "${viewModel.peerName} wants access to ${viewModel.itemLabel.ifBlank { "an item" }}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "If you approve, ${viewModel.peerName} can fetch this item under the limits below. Their vault never sees the underlying credential — only the requested value at fetch time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            // Request details card.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DetailRow("From", viewModel.peerName)
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Item", viewModel.itemLabel.ifBlank { viewModel.itemRef })
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Type", prettyKind(viewModel.itemKind))
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Mode", prettyMode(viewModel.requestedMode))
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Expires", formatExpiry(viewModel.requestedExpiresAt))
                    if (viewModel.requestedMode != GrantModes.ONE_SHOT) {
                        Spacer(Modifier.height(8.dp))
                        DetailRow("Max uses", if (viewModel.requestedMaxUses == 0) "Unlimited" else viewModel.requestedMaxUses.toString())
                    }
                    if (viewModel.reason.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        DetailRow("Reason", viewModel.reason)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Privacy note — minor secrets / data are NEVER stored on
            // the peer device. The vault releases just-in-time at fetch.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Lock,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "End-to-end encrypted",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "The value travels encrypted between vaults and is never written to ${viewModel.peerName}'s device. You can revoke this grant at any time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val errorMessage = (state as? DataGrantApprovalViewModel.State.Error)?.message
            if (errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state is DataGrantApprovalViewModel.State.Submitting) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { viewModel.deny() },
                    enabled = state !is DataGrantApprovalViewModel.State.Submitting,
                    modifier = Modifier.weight(1f),
                ) { Text("Deny") }
                Button(
                    onClick = { viewModel.approve() },
                    enabled = state !is DataGrantApprovalViewModel.State.Submitting,
                    modifier = Modifier.weight(1f),
                ) { Text("Approve") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun prettyKind(kind: String): String = when (kind) {
    GrantItemKinds.DATA -> "Profile data"
    GrantItemKinds.SECRET -> "Secret"
    else -> kind.ifBlank { "Item" }
}

private fun prettyMode(mode: String): String = when (mode) {
    GrantModes.ONE_SHOT -> "One-shot (single fetch)"
    GrantModes.RENEWABLE -> "Renewable (multiple fetches)"
    GrantModes.AGENT_RENEWABLE -> "Agent-renewable"
    else -> mode.ifBlank { "One-shot" }
}

private fun formatExpiry(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "Until revoked"
    val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return sdf.format(Date(epochSeconds * 1000))
}
