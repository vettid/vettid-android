package com.vettid.feature.wallet

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.CenteredTitle
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.VettIdBackTopBar
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.wallet.TransactionsResult
import com.vettid.core.wallet.TxDirection
import com.vettid.core.wallet.TxStatus
import com.vettid.core.wallet.WalletTransaction
import java.time.Instant

// The Transactions screens (W2-3; ANDROID-PLAN §4 "Wallet", "Transactions"). The texts are the plan's drafts or marked new in strings.xml.

/** What the list asks of its host. */
data class TransactionsActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpen: (WalletTransaction) -> Unit = {},
    val onChainServer: () -> Unit = {},
)

/** The wallet's transactions, newest first: a row each, with empty, loading and error states and Try again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(state: TransactionsUiState, actions: TransactionsActions, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize().testTag("wallet_transactions"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            VettIdBackTopBar(onBackClick = actions.onBack)
            CenteredTitle(stringResource(R.string.wallet_tx_title), Modifier.testTag("wallet_transactions_title"))
            val list = state.list
            val problem = state.problem
            when {
                problem != null -> Box(Modifier.fillMaxSize().padding(horizontal = Spacing.xl)) {
                    TransactionsProblem(problem, state.network, actions.onRefresh, actions.onChainServer)
                }
                list == null -> Box(Modifier.fillMaxSize().testTag("wallet_transactions_loading"), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> PullToRefreshBox(isRefreshing = state.loading, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                    if (list.items.isEmpty()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl)) {
                            Text(
                                stringResource(R.string.wallet_tx_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("wallet_transactions_empty"),
                            )
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(list.items, key = { it.txid }) { tx -> TransactionRow(tx, state.network, actions.onOpen) }
                        }
                    }
                }
            }
        }
    }
}

/** A row: "Sent to tb1q…" or "Received", the status and the date, the amount. Tagged by position, never by the txid. */
@Composable
private fun TransactionRow(tx: WalletTransaction, network: Network?, onOpen: (WalletTransaction) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(tx) }.testTag("wallet_tx_row")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Column(Modifier.weight(1f)) {
                Text(transactionTitle(tx), fontWeight = FontWeight.Medium, modifier = Modifier.testTag("wallet_tx_row_title"))
                Text(
                    statusLine(tx),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("wallet_tx_row_status"),
                )
            }
            Text(signedAmount(tx, network), modifier = Modifier.testTag("wallet_tx_row_amount"))
        }
        HorizontalDivider()
    }
}

/** "Sent to tb1q…" (the largest payee, shortened), "Received", or "Moved within this wallet". */
@Composable
internal fun transactionTitle(tx: WalletTransaction): String = when (tx.direction) {
    TxDirection.RECEIVED -> stringResource(R.string.wallet_tx_received)
    TxDirection.SELF -> stringResource(R.string.wallet_tx_self)
    TxDirection.SENT -> tx.payees.firstOrNull()?.address?.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.wallet_tx_sent_to, shortAddress(it)) }
        ?: stringResource(R.string.wallet_tx_sent)
}

/** The start and the end of an address, the middle left out: "tb1qpa…x7k2". */
internal fun shortAddress(address: String): String =
    if (address.length <= SHORT_KEEP * 2 + 1) address else address.take(SHORT_KEEP) + "…" + address.takeLast(SHORT_TAIL)

private const val SHORT_KEEP = 6
private const val SHORT_TAIL = 4

@Composable
internal fun statusText(status: TxStatus): String = stringResource(
    when (status) {
        is TxStatus.Confirmed -> R.string.wallet_tx_confirmed
        TxStatus.Waiting -> R.string.wallet_tx_waiting
        TxStatus.NotBroadcast -> R.string.wallet_tx_not_broadcast
    },
)

@Composable
private fun statusLine(tx: WalletTransaction): String {
    val status = statusText(tx.status)
    val time = (tx.blockTime ?: tx.signedAt)?.let { dateText(it) } ?: return status
    return "$status · $time"
}

@Composable
internal fun dateText(at: Instant): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        at.toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

/** "+ 0.001 tBTC" received, "− 0.001 tBTC" sent, no sign for a transfer to oneself. */
@Composable
internal fun signedAmount(tx: WalletTransaction, network: Network?): String {
    val amount = amountLabel(tx.amountSats, network)
    return when (tx.direction) {
        TxDirection.RECEIVED -> "+ $amount"
        TxDirection.SENT -> "− $amount"
        TxDirection.SELF -> amount
    }
}

/** A look-up that did not end with a list, as what it is, with its way out (the same states as the wallet detail's). */
@Composable
internal fun TransactionsProblem(problem: TransactionsResult, network: Network?, onRetry: () -> Unit, onChainServer: () -> Unit) {
    val again = stringResource(R.string.wallet_try_again) to onRetry
    val chain = stringResource(R.string.wallet_menu_chain_server) to onChainServer
    val (text, ways) = when (problem) {
        is TransactionsResult.Done, TransactionsResult.Cleared -> return
        TransactionsResult.NeedsNetwork, TransactionsResult.NoteNeeded -> stringResource(R.string.wallet_send_blocked_note) to emptyList()
        TransactionsResult.NetworkNotOffered ->
            stringResource(if (network == Network.TESTNET) R.string.wallet_testnet3_unsupported else R.string.wallet_network_not_offered) to
                emptyList()
        is TransactionsResult.NoServer -> stringResource(
            when (problem.server) {
                ChainServer.Unreadable -> R.string.wallet_server_unreadable
                ChainServer.NotOffered -> R.string.wallet_network_not_offered
                else -> R.string.wallet_server_not_set
            },
        ) to listOf(chain)
        is TransactionsResult.ServerWrongNetwork ->
            stringResource(R.string.wallet_server_wrong_network, networkLabel(network).orEmpty()) to listOf(chain)
        is TransactionsResult.ServerFailed -> stringResource(R.string.wallet_server_failed) to listOf(again, chain)
        TransactionsResult.AddressMismatch -> stringResource(R.string.wallet_address_mismatch) to emptyList()
        is TransactionsResult.VaultFailed -> stringResource(problem.failure.kind.messageRes()) to listOf(again)
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = Spacing.l).testTag("wallet_transactions_problem"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(text, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            for ((label, onClick) in ways) TextButton(onClick = onClick) { Text(label) }
        }
    }
}

/** What one transaction asks of its host. */
data class TransactionActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onExplorer: () -> Unit = {},
    val onCancelExplorer: () -> Unit = {},
    val onConfirmExplorer: () -> Unit = {},
    val onChainServer: () -> Unit = {},
)

/**
 * One transaction: the amounts, the fee (a payment the wallet sent), the confirmations, the block time and the transaction ID with
 * Copy. "Open in a block explorer" is there only when the chain server has a site, and asks first: it leaves VettID.
 */
@Composable
fun TransactionScreen(state: TransactionUiState, actions: TransactionActions, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize().testTag("wallet_transaction"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            VettIdBackTopBar(onBackClick = actions.onBack)
            val tx = state.tx
            val problem = state.problem
            val heading = tx?.let { transactionTitle(it) } ?: stringResource(R.string.wallet_tx_title)
            CenteredTitle(heading, Modifier.testTag("wallet_transaction_title"))
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                if (problem != null) TransactionsProblem(problem, state.network, actions.onRefresh, actions.onChainServer)
                when {
                    tx != null -> Details(tx, state, actions)
                    state.notFound -> Text(
                        stringResource(R.string.wallet_tx_not_found),
                        modifier = Modifier.testTag("wallet_transaction_not_found"),
                    )
                    state.loading -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.testTag("wallet_transaction_loading"))
                    }
                }
            }
        }
    }
    val url = state.explorerUrl
    if (state.explorerAsk && url != null) {
        ConfirmDialog(
            title = stringResource(R.string.wallet_tx_leave_title),
            text = stringResource(R.string.wallet_tx_leave_body, hostOf(url)),
            confirmLabel = stringResource(R.string.wallet_tx_leave_open),
            onConfirm = actions.onConfirmExplorer,
            onDismiss = actions.onCancelExplorer,
            modifier = Modifier.testTag("wallet_transaction_leave"),
        )
    }
}

/** The site's host, for the confirm. */
internal fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url

@Composable
private fun Details(tx: WalletTransaction, state: TransactionUiState, actions: TransactionActions) {
    val network = state.network
    Field(stringResource(R.string.wallet_tx_status), statusText(tx.status), "wallet_transaction_status")
    if (tx.status == TxStatus.NotBroadcast) {
        Text(
            stringResource(R.string.wallet_tx_not_broadcast_note),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("wallet_transaction_not_broadcast_note"),
        )
    }
    if (tx.direction == TxDirection.SENT) {
        for (p in tx.payees) Field(stringResource(R.string.wallet_send_review_to), p.address, "wallet_transaction_payee", mono = true)
    }
    Field(stringResource(R.string.wallet_send_review_amount), amountLabel(tx.amountSats, network), "wallet_transaction_amount")
    tx.feeSats?.let { Field(stringResource(R.string.wallet_send_review_fee), amountLabel(it, network), "wallet_transaction_fee") }
    if (tx.direction == TxDirection.SENT) {
        Field(stringResource(R.string.wallet_tx_total_out), amountLabel(-tx.netSats, network), "wallet_transaction_total")
    }
    (tx.status as? TxStatus.Confirmed)?.let {
        Field(stringResource(R.string.wallet_tx_confirmations), it.confirmations.toString(), "wallet_transaction_confirmations")
    }
    tx.blockTime?.let { Field(stringResource(R.string.wallet_tx_block_time), dateText(it), "wallet_transaction_block_time") }
    tx.signedAt?.let { Field(stringResource(R.string.wallet_tx_signed_at), dateText(it), "wallet_transaction_signed_at") }
    // The transaction ID is public (it is on the chain): shown whole, and copied like any text, not as a secret.
    Row(Modifier.fillMaxWidth().testTag("wallet_transaction_txid"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.wallet_tx_id),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(tx.txid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = { actions.onCopy(tx.txid) }, modifier = Modifier.testTag("wallet_transaction_copy")) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.wallet_tx_copy))
        }
    }
    if (state.explorerUrl != null) {
        OutlinedButton(onClick = actions.onExplorer, modifier = Modifier.fillMaxWidth().testTag("wallet_transaction_explorer")) {
            Text(stringResource(R.string.wallet_tx_explorer))
        }
    }
}

@Composable
private fun Field(label: String, value: String, tag: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else null, style = MaterialTheme.typography.bodyMedium)
    }
}
