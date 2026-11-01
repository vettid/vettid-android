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
import com.vettid.core.ui.components.ConfirmDialog
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
    val onLimitsContinue: () -> Unit = {},
    val limits: LimitsActions = LimitsActions(),
    val onAskReset: () -> Unit = {},
    val onCancelReset: () -> Unit = {},
    val onReset: () -> Unit = {},
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
        state.problem == ReceiveProblem.LIMITS_ASK -> LimitsAsk(state, actions, modifier)
        state.problem != null -> ProblemScreen(state, actions, modifier)
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

/** "Set a spending limit?" asked again before Receive, after the wallet settings were reset on this phone. */
@Composable
private fun LimitsAsk(state: ReceiveUiState, actions: ReceiveActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_limits_title),
        body = stringResource(R.string.wallet_limits_ask_reset),
        primaryLabel = stringResource(R.string.wallet_continue),
        onPrimary = actions.onLimitsContinue,
        primaryEnabled = state.limitsChosen && state.limitsParse.ok && !state.busy,
        busy = state.busy,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_receive_limits"),
    ) {
        Text(stringResource(R.string.wallet_limits_body))
        LimitsFields(state.limits, state.limitsParse, actions.limits, noneSelected = state.limitsChosen && !state.limits.limit)
        if (state.limitsNotSaved) {
            Text(stringResource(R.string.wallet_limits_ask_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Why there is no address (and, while the settings cannot be read, the way to reset them). */
@Composable
private fun ProblemScreen(state: ReceiveUiState, actions: ReceiveActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_receive),
        body = stringResource(
            when {
                state.problem == ReceiveProblem.CAPS_PENDING -> R.string.wallet_receive_caps_pending
                state.problem == ReceiveProblem.CAPS_DUPLICATE -> R.string.wallet_limits_duplicate
                state.problem == ReceiveProblem.SETTINGS_UNREADABLE -> R.string.wallet_settings_unreadable
                state.problem == ReceiveProblem.MISMATCH -> R.string.wallet_address_mismatch
                state.network == com.vettid.core.chain.Network.TESTNET -> R.string.wallet_testnet3_unsupported
                else -> R.string.wallet_network_not_offered
            },
        ),
        // The limit chosen is sent again from here; the other problems have no way out on this screen.
        primaryLabel = if (state.problem in RETRYABLE) stringResource(R.string.wallet_try_again) else null,
        onPrimary = actions.onRetry,
        // Only while the settings cannot be read: forget them on this phone (the vault is not touched).
        secondaryLabel = stringResource(R.string.wallet_reset_settings).takeIf { state.problem == ReceiveProblem.SETTINGS_UNREADABLE },
        onSecondary = actions.onAskReset,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_receive_problem"),
    ) {
        if (state.resetFailed) Text(stringResource(R.string.wallet_reset_failed), color = MaterialTheme.colorScheme.error)
        if (state.resetAsk) {
            ConfirmDialog(
                title = stringResource(R.string.wallet_reset_title),
                text = stringResource(R.string.wallet_reset_body),
                confirmLabel = stringResource(R.string.wallet_reset_confirm),
                onConfirm = actions.onReset,
                onDismiss = actions.onCancelReset,
                destructive = true,
                modifier = Modifier.testTag("wallet_reset_confirm"),
            )
        }
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

/** The problems whose way out is to try again from this screen. */
private val RETRYABLE = setOf(ReceiveProblem.CAPS_PENDING, ReceiveProblem.CAPS_DUPLICATE, ReceiveProblem.SETTINGS_UNREADABLE)
