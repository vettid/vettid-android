package com.vettid.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.theme.Spacing

/** What the details ask of their host. */
data class WalletDetailsActions(
    val onBack: () -> Unit = {},
    /** A value to copy (the clipboard marks it sensitive: it identifies the wallet's every address). */
    val onCopy: (value: String) -> Unit = {},
    val onChainServer: () -> Unit = {},
)

/**
 * Details (ANDROID-PLAN §4 screens table): the fingerprint, the network, and for each account its type, path and receive and
 * change descriptors, each copyable; "Using {host}" opens the Chain server.
 */
@Composable
fun WalletDetailsScreen(state: WalletDetailsUiState, actions: WalletDetailsActions, modifier: Modifier = Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_menu_details),
        primaryLabel = null,
        onPrimary = {},
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_details"),
    ) {
        if (state.loaded) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Value(stringResource(R.string.wallet_details_fingerprint), state.fingerprint, "wallet_details_fingerprint", actions)
                networkLabel(state.network)?.let {
                    Value(stringResource(R.string.wallet_details_network), it, "wallet_details_network", null)
                }
                for (a in state.accounts) Account(a, actions)
                SettingsGroup {
                    SettingsRow(
                        label = state.host?.let { stringResource(R.string.wallet_details_using, it) }
                            ?: stringResource(R.string.wallet_menu_chain_server),
                        onClick = actions.onChainServer,
                        modifier = Modifier.testTag("wallet_details_using"),
                    )
                }
            }
        }
    }
}

@Composable
private fun Account(a: AccountDetails, actions: WalletDetailsActions) {
    val type = stringResource(if (a.type == AddressTypes.SEGWIT) R.string.wallet_account_p2wpkh else R.string.wallet_account_p2tr)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("wallet_account_${a.type}")) {
        Text(type, style = MaterialTheme.typography.titleMedium)
        Value(stringResource(R.string.wallet_details_path), a.path, "wallet_path_${a.type}", actions)
        Value(stringResource(R.string.wallet_details_receive_descriptor), a.receive, "wallet_receive_descriptor_${a.type}", actions)
        Value(stringResource(R.string.wallet_details_change_descriptor), a.change, "wallet_change_descriptor_${a.type}", actions)
    }
}

/** A labelled value; with [actions], a copy button. Tagged by role, never by the value. */
@Composable
private fun Value(label: String, value: String, tag: String, actions: WalletDetailsActions?) {
    Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
        if (actions != null) {
            IconButton(onClick = { actions.onCopy(value) }, modifier = Modifier.padding(start = Spacing.s).testTag("${tag}_copy")) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.wallet_details_copy, label))
            }
        }
    }
}
