package com.vettid.app.features.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.migration.VaultUpdateCard
import com.vettid.app.features.migration.VaultUpdateState
import com.vettid.app.features.migration.VaultUpdateSuccessCard
import com.vettid.app.features.migration.VaultUpdateViewModel
import com.vettid.app.features.migration.VaultUpdatingInlineCard

/**
 * Surface for service-originated messages from VettID: vault security
 * updates, alerts, and other operator-initiated notifications. Opened
 * from the VettID system connection's "Messages" action button. For
 * now the only live content is the pending vault update (reusing the
 * existing VaultUpdateCard). Security alerts and announcements will
 * plug in here as the system.* audit taxonomy is wired.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultMessagesScreen(
    onBack: () -> Unit,
    onOpenDetailsUrl: (String) -> Unit,
    viewModel: VaultUpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault Messages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            when (val s = state) {
                is VaultUpdateState.UpdateAvailable -> {
                    VaultUpdateCard(
                        config = s.config,
                        isMandatory = s.isMandatory,
                        isUpdating = false,
                        onUpdateNow = { viewModel.startUpdate() },
                        onRemindLater = { viewModel.remindLater() },
                        onReviewDetails = {
                            s.config.detailsUrl?.takeIf { it.isNotBlank() }?.let(onOpenDetailsUrl)
                        },
                    )
                }
                is VaultUpdateState.Updating -> {
                    if (s.autoApplied) {
                        VaultUpdatingInlineCard()
                    } else {
                        VaultUpdateCard(
                            config = s.config,
                            isMandatory = true,
                            isUpdating = true,
                            onUpdateNow = {},
                            onRemindLater = {},
                            onReviewDetails = {},
                        )
                    }
                }
                is VaultUpdateState.Updated -> {
                    VaultUpdateSuccessCard(onDismiss = { viewModel.dismissSuccess() })
                }
                is VaultUpdateState.Error -> {
                    VaultUpdateCard(
                        config = s.config,
                        isMandatory = true,
                        isUpdating = false,
                        onUpdateNow = { viewModel.retry() },
                        onRemindLater = {},
                        onReviewDetails = {},
                    )
                }
                else -> {
                    // No pending vault update — show an empty-state
                    // placeholder. Real security-alert entries will
                    // render here once the system.* audit taxonomy is
                    // wired into this screen.
                    EmptyState()
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Nothing from VettID right now",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Vault security updates and service announcements will show up here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
