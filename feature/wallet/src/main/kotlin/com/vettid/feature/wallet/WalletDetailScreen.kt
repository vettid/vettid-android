package com.vettid.feature.wallet

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.CenteredTitle
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.VettIdBackTopBar
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.wallet.LookupResult
import com.vettid.core.wallet.WalletLookup

/** What the wallet detail asks of its host. */
data class WalletDetailActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onReceive: () -> Unit = {},
    val onBackUp: () -> Unit = {},
    val onAcknowledge: () -> Unit = {},
    val onChooseNetwork: (Network) -> Unit = {},
    val onChainServer: () -> Unit = {},
    val onAddressType: (String) -> Unit = {},
    val onDetails: () -> Unit = {},
    val onAskDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onContinueDelete: () -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismissTypeError: () -> Unit = {},
)

/**
 * The wallet detail (ANDROID-PLAN §4 screens table): the balance, the last look-up, Receive, ⋯. Every state of the look-up shows as
 * what it is, with its way out. The delete asks twice: the warning, then the password.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletDetailScreen(state: WalletDetailUiState, actions: WalletDetailActions, modifier: Modifier = Modifier) {
    if (state.delete == DeleteStep.PASSWORD) {
        DeletePassword(state, actions, modifier)
        return
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            VettIdBackTopBar(onBackClick = actions.onBack, actions = { WalletMenu(state, actions) })
            CenteredTitle(state.name, Modifier.testTag("wallet_detail_title"))
            if (!state.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                PullToRefreshBox(isRefreshing = state.lookingUp, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(Spacing.l),
                    ) {
                        DetailBody(state, actions)
                    }
                }
            }
        }
    }
    if (state.delete == DeleteStep.CONFIRM) {
        ConfirmDialog(
            title = stringResource(R.string.wallet_delete_title, state.name),
            text = stringResource(R.string.wallet_delete_body),
            confirmLabel = stringResource(R.string.wallet_menu_delete),
            onConfirm = actions.onContinueDelete,
            onDismiss = actions.onCancelDelete,
            destructive = true,
        )
    }
}

@Composable
private fun DetailBody(state: WalletDetailUiState, actions: WalletDetailActions) {
    if (!state.backedUp) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("wallet_detail_not_backed_up"),
        ) {
            Column(Modifier.padding(Spacing.m)) {
                Text(stringResource(R.string.wallet_backup_body))
                TextButton(onClick = actions.onBackUp, modifier = Modifier.testTag("wallet_detail_back_up")) {
                    Text(stringResource(R.string.wallet_backup_title))
                }
            }
        }
    }
    state.typeError?.let { e ->
        Text(
            stringResource(e.messageRes()),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("wallet_detail_type_error"),
        )
    }
    if (state.noteNeeded) {
        NoticeCard(
            kind = NoticeKind.INFO,
            title = stringResource(R.string.wallet_menu_chain_server),
            body = stringResource(R.string.wallet_privacy_note),
            modifier = Modifier.testTag("wallet_privacy_note"),
            actions = {
                TextButton(onClick = actions.onAcknowledge, modifier = Modifier.testTag("wallet_privacy_continue")) {
                    Text(stringResource(R.string.wallet_continue))
                }
            },
        )
    } else {
        Balance(state)
        LookupProblem(state, actions)
    }
    PrimaryButton(stringResource(R.string.wallet_receive), actions.onReceive, Modifier.testTag("wallet_receive"))
}

/** Confirmed, in bitcoin with satoshis underneath; "+ … incoming" (or "- … outgoing") while unconfirmed; the last look-up. */
@Composable
private fun Balance(state: WalletDetailUiState) {
    val last = state.last
    if (last == null) {
        if (state.lookingUp) Text(stringResource(R.string.wallet_server_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.wallet_balance_btc, BtcFormat.btc(last.confirmedSats)),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("wallet_balance_btc"),
        )
        Text(
            stringResource(R.string.wallet_balance_sats, BtcFormat.sats(last.confirmedSats)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("wallet_balance_sats"),
        )
        if (last.unconfirmedSats != 0L) {
            val incoming = last.unconfirmedSats > 0
            Text(
                stringResource(
                    if (incoming) R.string.wallet_incoming else R.string.wallet_outgoing,
                    BtcFormat.btc(kotlin.math.abs(last.unconfirmedSats)),
                ),
                modifier = Modifier.padding(top = Spacing.xs).testTag("wallet_unconfirmed"),
            )
        }
        Text(
            stringResource(R.string.wallet_last_lookup, lookupTime(last), last.host),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.s).testTag("wallet_last_lookup"),
        )
    }
}

@Composable
private fun lookupTime(last: WalletLookup): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        last.at.toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

/** A look-up that did not end with a balance, shown as what it is with its way out. */
@Composable
private fun LookupProblem(state: WalletDetailUiState, actions: WalletDetailActions) {
    val chain = stringResource(R.string.wallet_menu_chain_server) to actions.onChainServer
    val again = stringResource(R.string.wallet_try_again) to actions.onRefresh
    when (val l = state.lookup) {
        null, is LookupResult.Done, LookupResult.Cleared -> Unit
        LookupResult.NeedsNetwork -> NeedsNetwork(state, actions)
        LookupResult.NetworkNotOffered -> Problem(
            stringResource(
                if (state.network == Network.TESTNET) R.string.wallet_testnet3_unsupported else R.string.wallet_network_not_offered,
            ),
            "wallet_problem_not_offered",
        )
        is LookupResult.NoServer -> Problem(
            stringResource(
                when (l.server) {
                    ChainServer.Unreadable -> R.string.wallet_server_unreadable
                    ChainServer.NotOffered -> R.string.wallet_network_not_offered
                    else -> R.string.wallet_server_not_set
                },
            ),
            "wallet_problem_no_server",
            listOf(chain),
        )
        is LookupResult.ServerWrongNetwork -> Problem(
            stringResource(R.string.wallet_server_wrong_network, networkLabel(state.network).orEmpty()),
            "wallet_problem_wrong_network",
            listOf(chain),
        )
        is LookupResult.ServerFailed -> Problem(
            stringResource(R.string.wallet_server_failed),
            "wallet_problem_failed",
            listOf(again, chain),
        )
        LookupResult.AddressMismatch -> Problem(stringResource(R.string.wallet_address_mismatch), "wallet_problem_mismatch")
        is LookupResult.VaultFailed -> Problem(stringResource(l.failure.kind.messageRes()), "wallet_problem_vault", listOf(again))
    }
}

/** "Which test network is this wallet on?": Testnet4 or Testnet3 (the staging app does not connect to testnet3). */
@Composable
private fun NeedsNetwork(state: WalletDetailUiState, actions: WalletDetailActions) =
    NetworkQuestion(state.networkSaveFailed, actions.onChooseNetwork, Modifier.testTag("wallet_problem_needs_network"))

/** "Which test network is this wallet on?" with its two answers (the detail and Receive ask it the same way). */
@Composable
internal fun NetworkQuestion(saveFailed: Boolean, onChoose: (Network) -> Unit, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = modifier) {
        Text(stringResource(R.string.wallet_network_ask), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedButton(onClick = { onChoose(Network.TESTNET4) }, modifier = Modifier.testTag("wallet_choose_testnet4")) {
                Text(stringResource(R.string.wallet_network_testnet4))
            }
            OutlinedButton(onClick = { onChoose(Network.TESTNET) }, modifier = Modifier.testTag("wallet_choose_testnet3")) {
                Text(stringResource(R.string.wallet_network_testnet3))
            }
        }
        if (saveFailed) {
            Text(stringResource(R.string.wallet_network_save_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Problem(text: String, tag: String, ways: List<Pair<String, () -> Unit>> = emptyList()) {
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(text, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            for ((label, onClick) in ways) TextButton(onClick = onClick) { Text(label) }
        }
    }
}

/** ⋯: Receiving address type, Chain server, Details, Delete wallet. (Recovery phrase and Spending limits come with W1d.) */
@Composable
private fun WalletMenu(state: WalletDetailUiState, actions: WalletDetailActions) {
    var open by remember { mutableStateOf(false) }
    var typeDialog by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("wallet_menu")) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.wallet_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(R.string.wallet_menu_address_type, "wallet_menu_address_type", enabled = state.loaded) {
                open = false
                typeDialog = true
            }
            MenuItem(R.string.wallet_menu_chain_server, "wallet_menu_chain_server", enabled = state.network != null) {
                open = false
                actions.onChainServer()
            }
            MenuItem(R.string.wallet_menu_details, "wallet_menu_details", enabled = state.loaded) {
                open = false
                actions.onDetails()
            }
            MenuItem(R.string.wallet_menu_delete, "wallet_menu_delete", enabled = state.loaded) {
                open = false
                actions.onAskDelete()
            }
        }
    }
    if (typeDialog) {
        AddressTypeDialog(state.addressType, onPick = { actions.onAddressType(it) }, onDismiss = { typeDialog = false })
    }
}

@Composable
private fun MenuItem(label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag))
}

/** "Receiving address type": Taproot (recommended) or Native SegWit. */
@Composable
fun AddressTypeDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wallet_menu_address_type)) },
        text = {
            Column {
                TypeChoice(R.string.wallet_type_taproot, AddressTypes.TAPROOT, current, onPick, onDismiss)
                TypeChoice(R.string.wallet_type_segwit, AddressTypes.SEGWIT, current, onPick, onDismiss)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wallet_cancel)) } },
        modifier = Modifier.testTag("wallet_type_dialog"),
    )
}

@Composable
private fun TypeChoice(label: Int, type: String, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val selected = type == current
    TextButton(
        onClick = {
            if (!selected) onPick(type)
            onDismiss()
        },
        modifier = Modifier.fillMaxWidth().testTag("wallet_type_$type"),
    ) {
        Text(
            stringResource(label),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The password for the delete: blanked in the state as soon as it is sent. */
@Composable
private fun DeletePassword(state: WalletDetailUiState, actions: WalletDetailActions, modifier: Modifier) {
    // Back cancels the password step, not the whole screen; while the vault deletes it does nothing.
    BackHandler { if (!state.deleting) actions.onCancelDelete() }
    FormScaffold(
        title = stringResource(R.string.wallet_menu_delete),
        body = stringResource(R.string.wallet_delete_password, state.name),
        primaryLabel = stringResource(R.string.wallet_menu_delete),
        onPrimary = actions.onDelete,
        primaryEnabled = state.password.isNotEmpty(),
        busy = state.deleting,
        destructive = true,
        onBack = actions.onCancelDelete,
        modifier = modifier.testTag("wallet_delete_password"),
    ) {
        SecretField(
            value = state.password,
            onValueChange = actions.onPassword,
            label = stringResource(R.string.wallet_password_label),
            error = state.deleteError?.let { stringResource(it.messageRes()) },
            onImeAction = actions.onDelete,
            enabled = !state.deleting,
            modifier = Modifier.fillMaxWidth().testTag("wallet_password"),
        )
    }
}
