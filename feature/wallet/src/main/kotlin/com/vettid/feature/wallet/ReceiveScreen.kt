package com.vettid.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.QrCode
import com.vettid.core.ui.theme.Spacing

/** What Receive asks of its host. */
data class ReceiveActions(
    val onBack: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onNewAddress: () -> Unit = {},
    val onBackUp: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onChooseNetwork: (com.vettid.core.chain.Network) -> Unit = {},
)

/**
 * Receive (ANDROID-PLAN §4 screens table): the address as a QR code (a BIP21 URI in upper case) and as text in groups of four; Copy
 * (the address, marked sensitive, not cleared), Share (with the amount when one is typed; off while it is not valid); an optional
 * amount; "New address"; the account and "Only send … bitcoin to this address."
 * A wallet whose phrase is not confirmed shows the way to the check instead.
 */
@Composable
fun ReceiveScreen(state: ReceiveUiState, actions: ReceiveActions, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.wallet_receive)
    when {
        state.needsNetwork -> FormScaffold(
            title = title,
            primaryLabel = null,
            onPrimary = {},
            onBack = actions.onBack,
            modifier = modifier.testTag("wallet_receive_needs_network"),
        ) {
            NetworkQuestion(state.networkSaveFailed, actions.onChooseNetwork)
        }
        state.problem != null -> FormScaffold(
            title = title,
            body = stringResource(
                when {
                    state.problem == ReceiveProblem.MISMATCH -> R.string.wallet_address_mismatch
                    state.network == com.vettid.core.chain.Network.TESTNET -> R.string.wallet_testnet3_unsupported
                    else -> R.string.wallet_network_not_offered
                },
            ),
            primaryLabel = null,
            onPrimary = {},
            onBack = actions.onBack,
            modifier = modifier.testTag("wallet_receive_problem"),
        ) {}
        state.notBackedUp -> FormScaffold(
            title = title,
            body = stringResource(R.string.wallet_backup_body),
            primaryLabel = stringResource(R.string.wallet_backup_title),
            onPrimary = actions.onBackUp,
            onBack = actions.onBack,
            modifier = modifier.testTag("wallet_receive_not_backed_up"),
        ) {}
        state.address == null && state.error != null -> FormScaffold(
            title = title,
            body = stringResource(state.error.messageRes()),
            primaryLabel = stringResource(R.string.wallet_try_again),
            onPrimary = actions.onRetry,
            onBack = actions.onBack,
            modifier = modifier.testTag("wallet_receive_failed"),
        ) {}
        state.address == null -> FormScaffold(
            title = title,
            primaryLabel = null,
            onPrimary = {},
            onBack = actions.onBack,
            modifier = modifier,
        ) {
            Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        else -> AddressContent(state, actions, modifier)
    }
}

@Composable
private fun AddressContent(state: ReceiveUiState, actions: ReceiveActions, modifier: Modifier) {
    val address = state.address ?: return
    FormScaffold(
        title = stringResource(R.string.wallet_receive),
        primaryLabel = null,
        onPrimary = {},
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_receive_screen"),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            state.uri?.let { uri ->
                QrCode(uri, stringResource(R.string.wallet_qr_description), Modifier.testTag("wallet_receive_qr"))
            }
            Text(
                Bip21.groups(address.address),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("wallet_receive_address"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(onClick = actions.onCopy, modifier = Modifier.testTag("wallet_receive_copy")) {
                    Text(stringResource(R.string.wallet_copy))
                }
                OutlinedButton(
                    onClick = actions.onShare,
                    enabled = state.shareText != null,
                    modifier = Modifier.testTag("wallet_receive_share"),
                ) {
                    Text(stringResource(R.string.wallet_share))
                }
            }
        }
        val bad = state.amount == BtcFormat.Amount.Invalid
        OutlinedTextField(
            value = state.amountText,
            onValueChange = actions.onAmount,
            label = { Text(stringResource(R.string.wallet_amount_label)) },
            isError = bad,
            supportingText = if (bad) {
                { Text(stringResource(R.string.wallet_amount_bad)) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("wallet_receive_amount"),
        )
        TextButton(onClick = actions.onNewAddress, enabled = !state.busy, modifier = Modifier.testTag("wallet_receive_new")) {
            Text(stringResource(R.string.wallet_new_address))
        }
        state.error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
        Text(
            stringResource(if (address.type == AddressTypes.SEGWIT) R.string.wallet_account_p2wpkh else R.string.wallet_account_p2tr),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("wallet_receive_account"),
        )
        Text(
            stringResource(R.string.wallet_only_send, networkLabel(state.network).orEmpty()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("wallet_receive_only_send"),
        )
    }
}
