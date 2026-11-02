package com.vettid.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vettid.core.chain.AddressFamily
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.QrScanner
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.SecureWindow
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.wallet.CapReason
import com.vettid.core.wallet.ChainOpen
import com.vettid.core.wallet.RecentSends

/** What Send asks of its host. */
@Suppress("LongParameterList")
data class SendActions(
    val onBack: () -> Unit = {},
    val onLoad: () -> Unit = {},
    val onBackUp: () -> Unit = {},
    val onChooseNetwork: (Network) -> Unit = {},
    val onAddress: (String) -> Unit = {},
    val onScan: () -> Unit = {},
    val onStopScan: () -> Unit = {},
    val onScanned: (String) -> Unit = {},
    val onScanUnavailable: () -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onUnit: (AmountUnit) -> Unit = {},
    val onSendAll: (Boolean) -> Unit = {},
    val onFee: (FeeChoice) -> Unit = {},
    val onCustomFee: (String) -> Unit = {},
    val onUnconfirmed: (Boolean) -> Unit = {},
    val onRetryFees: () -> Unit = {},
    val onReview: () -> Unit = {},
    val onConfirmBigFee: () -> Unit = {},
    val onCancelBigFee: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onApprove: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onChainServer: () -> Unit = {},
    val onLimits: () -> Unit = {},
    val onAskDiscard: () -> Unit = {},
    val onCancelDiscard: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onStartOver: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onAcknowledgeRecent: () -> Unit = {},
)

/**
 * Send (ANDROID-PLAN §4 "Sending"): one screen with three steps. The screen is `SecureWindow()` ([secure]; off only in the debug
 * catalog): it shows a payment and takes the password.
 */
@Composable
fun SendScreen(state: SendUiState, actions: SendActions, modifier: Modifier = Modifier, secure: Boolean = true) {
    if (secure) SecureWindow()
    when {
        state.closed || state.loading && state.block == null && state.network == null -> FormScaffold(
            title = stringResource(R.string.wallet_send),
            primaryLabel = null,
            onPrimary = {},
            onBack = actions.onBack,
            modifier = modifier.testTag("wallet_send_loading"),
        ) {}
        state.scanning -> ScanStep(state, actions, modifier)
        state.block != null -> BlockStep(state, state.block, actions, modifier)
        state.step == SendStep.FORM && state.recentNotice != null -> RecentStep(state, state.recentNotice, actions, modifier)
        state.step == SendStep.FORM -> FormStep(state, actions, modifier)
        state.step == SendStep.REVIEW -> ReviewStep(state, actions, modifier)
        else -> DoneStep(state, actions, modifier)
    }
}

@Composable
private fun ScanStep(state: SendUiState, actions: SendActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_send_scan_title),
        primaryLabel = null,
        onPrimary = {},
        onBack = actions.onStopScan,
        modifier = modifier.testTag("wallet_send_scan"),
    ) {
        if (state.scanUnavailable) {
            Text(stringResource(R.string.wallet_send_scan_unavailable), modifier = Modifier.testTag("wallet_send_scan_unavailable"))
        }
        QrScanner(
            onText = actions.onScanned,
            modifier = Modifier.fillMaxWidth().height(SCAN_HEIGHT),
            onUnavailable = actions.onScanUnavailable,
        )
    }
}

private val SCAN_HEIGHT = 360.dp

/** A notice instead of the form: Receive's holds, and the holds of a signing that may still apply. */
@Suppress("CyclomaticComplexMethod") // one branch per state
@Composable
private fun BlockStep(state: SendUiState, block: SendBlock, actions: SendActions, modifier: Modifier) {
    val text = when (block) {
        SendBlock.NOT_BACKED_UP -> R.string.wallet_backup_body
        SendBlock.SETTINGS_UNREADABLE -> R.string.wallet_send_blocked_settings
        SendBlock.CAPS_PENDING -> R.string.wallet_send_blocked_caps_pending
        SendBlock.CAPS_DUPLICATE -> R.string.wallet_limits_duplicate
        SendBlock.SIGN_UNSETTLED -> R.string.wallet_send_blocked_sign_unsettled
        SendBlock.NEEDS_NETWORK -> R.string.wallet_network_ask
        SendBlock.NOT_OFFERED ->
            if (state.network == Network.TESTNET) R.string.wallet_testnet3_unsupported else R.string.wallet_network_not_offered
        SendBlock.MISMATCH -> R.string.wallet_address_mismatch
        SendBlock.NOTE_NEEDED -> R.string.wallet_send_blocked_note
    }
    val retry = block in setOf(
        SendBlock.SETTINGS_UNREADABLE, SendBlock.CAPS_PENDING, SendBlock.CAPS_DUPLICATE, SendBlock.SIGN_UNSETTLED, SendBlock.NOTE_NEEDED,
    )
    FormScaffold(
        title = stringResource(R.string.wallet_send),
        body = if (block == SendBlock.NEEDS_NETWORK) null else stringResource(text),
        primaryLabel = when {
            block == SendBlock.NOT_BACKED_UP -> stringResource(R.string.wallet_backup_title)
            retry -> stringResource(R.string.wallet_try_again)
            else -> null
        },
        onPrimary = if (block == SendBlock.NOT_BACKED_UP) actions.onBackUp else actions.onLoad,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_send_blocked"),
    ) {
        if (block == SendBlock.NEEDS_NETWORK) NetworkQuestion(state.networkSaveFailed, actions.onChooseNetwork)
    }
}

@Composable
private fun FormStep(state: SendUiState, actions: SendActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_send),
        primaryLabel = stringResource(R.string.wallet_send_review),
        onPrimary = actions.onReview,
        primaryEnabled = state.canReview,
        busy = state.busy,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_send_form"),
    ) {
        if (state.recentChecking) {
            Text(stringResource(R.string.wallet_send_recent_checking), modifier = Modifier.testTag("wallet_send_recent_checking"))
        }
        PayeeField(state, actions)
        AmountField(state, actions)
        FeeChoices(state, actions)
        ToggleRow(
            stringResource(R.string.wallet_send_unconfirmed), state.includeUnconfirmed, actions.onUnconfirmed, "wallet_send_unconfirmed",
        )
        state.problem?.let { ProblemText(state, it, actions) }
        BigFeeDialog(state, actions)
    }
}

/** One dialog for both fee guards: much higher than needed right now, or more than 10% of what is sent. */
@Composable
private fun BigFeeDialog(state: SendUiState, actions: SendActions) {
    val ask = state.bigFeeAsk ?: return
    ConfirmDialog(
        title = stringResource(R.string.wallet_send_fee),
        text = if (ask == BigFee.TEN_PERCENT) {
            stringResource(R.string.wallet_send_fee_ten_percent, BtcFormat.sats(state.bigFeeSats))
        } else {
            stringResource(R.string.wallet_send_fee_much_higher)
        },
        confirmLabel = stringResource(R.string.wallet_send_fee_pay_anyway),
        onConfirm = actions.onConfirmBigFee,
        onDismiss = actions.onCancelBigFee,
        modifier = Modifier.testTag("wallet_send_big_fee"),
    )
}

/** A payment the vault signed lately may still be on its way: the notice naming it, and "Send anyway" to go on. */
@Composable
private fun RecentStep(state: SendUiState, notice: RecentSends, actions: SendActions, modifier: Modifier) {
    val latest = state.recentReport?.latest
    val detail = latest?.let { tx ->
        val amount = amountLabel(tx.sendingSats, state.network)
        val payee = tx.payees.firstOrNull()?.address.orEmpty()
        val time = tx.at?.let { SendFormat.whenAt(it) } ?: stringResource(R.string.wallet_send_recent_unknown_time)
        Triple(amount, payee, time)
    }
    val more = state.recentReport?.more ?: 0
    val text = when {
        notice == RecentSends.WAITING && detail != null ->
            stringResource(R.string.wallet_send_recent_waiting, detail.first, detail.second, detail.third) + moreText(more)
        notice == RecentSends.MAY_HAVE_GONE && detail != null ->
            stringResource(R.string.wallet_send_recent_gone, detail.first, detail.second, detail.third) + moreText(more) +
                "\n\n" + stringResource(R.string.wallet_send_recent_gone_more)
        else -> stringResource(R.string.wallet_send_recent_unreadable)
    }
    FormScaffold(
        title = stringResource(R.string.wallet_send),
        body = text,
        primaryLabel = stringResource(R.string.wallet_send_anyway),
        onPrimary = actions.onAcknowledgeRecent,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_send_recent"),
    ) {}
}

@Composable
private fun moreText(more: Int): String =
    if (more > 0) " " + pluralStringResource(R.plurals.wallet_send_recent_more, more, more) else ""

@Composable
private fun PayeeField(state: SendUiState, actions: SendActions) {
    val issue = state.addressIssue
    OutlinedTextField(
        value = state.address,
        onValueChange = actions.onAddress,
        label = { Text(stringResource(R.string.wallet_send_address_label)) },
        placeholder = { Text(stringResource(R.string.wallet_send_paste_scan)) },
        isError = issue != null,
        supportingText = issue?.let {
            { Text(addressIssueText(state.network, it), modifier = Modifier.testTag("wallet_send_address_issue")) }
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        minLines = 1,
        modifier = Modifier.fillMaxWidth().testTag("wallet_send_address"),
    )
    if (state.linkRefused) {
        Text(
            stringResource(R.string.wallet_send_link_refused),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("wallet_send_link_refused"),
        )
    }
    TextButton(onClick = actions.onScan, modifier = Modifier.testTag("wallet_send_scan_button")) {
        Text(stringResource(R.string.wallet_send_scan))
    }
}

@Composable
private fun addressIssueText(network: Network?, issue: AddressIssue): String = when (issue) {
    AddressIssue.NotAnAddress -> stringResource(R.string.wallet_send_not_an_address)
    is AddressIssue.WrongNetwork -> stringResource(
        R.string.wallet_send_wrong_network,
        stringResource(
            when (issue.found) {
                AddressFamily.MAINNET -> R.string.wallet_send_family_mainnet
                AddressFamily.TEST -> R.string.wallet_send_family_test
                AddressFamily.REGTEST -> R.string.wallet_send_family_regtest
            },
        ),
        networkLabel(network) ?: stringResource(R.string.wallet_send_family_mainnet),
    )
}

@Composable
private fun AmountField(state: SendUiState, actions: SendActions) {
    val bad = !state.sendAll && state.amount == BtcFormat.Amount.Invalid
    OutlinedTextField(
        value = if (state.sendAll) "" else state.amountText,
        onValueChange = actions.onAmount,
        enabled = !state.sendAll,
        label = { Text(stringResource(R.string.wallet_send_amount_label)) },
        isError = bad,
        supportingText = if (bad) {
            { Text(stringResource(if (state.unit == AmountUnit.BTC) R.string.wallet_amount_bad else R.string.wallet_send_amount_sats_bad)) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (state.unit == AmountUnit.BTC) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("wallet_send_amount"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        UnitButton(R.string.wallet_send_unit_btc, state.unit == AmountUnit.BTC, "wallet_send_unit_btc") { actions.onUnit(AmountUnit.BTC) }
        UnitButton(R.string.wallet_send_unit_sats, state.unit == AmountUnit.SATS, "wallet_send_unit_sats") {
            actions.onUnit(AmountUnit.SATS)
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(state.sendAll, role = Role.Checkbox) { actions.onSendAll(!state.sendAll) }
            .testTag("wallet_send_all"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.sendAll, onCheckedChange = null)
        Text(stringResource(R.string.wallet_send_all), modifier = Modifier.padding(start = Spacing.s))
    }
    if (state.sendAll) Text(stringResource(R.string.wallet_send_all_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun UnitButton(label: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    if (selected) {
        androidx.compose.material3.Button(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(stringResource(label)) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(stringResource(label)) }
    }
}

@Composable
private fun FeeChoices(state: SendUiState, actions: SendActions) {
    Text(stringResource(R.string.wallet_send_fee), style = MaterialTheme.typography.titleMedium)
    if (state.estimatesFailed) {
        Text(
            stringResource(R.string.wallet_send_fees_failed),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("wallet_send_fees_failed"),
        )
        TextButton(onClick = actions.onRetryFees, modifier = Modifier.testTag("wallet_send_fees_retry")) {
            Text(stringResource(R.string.wallet_try_again))
        }
    }
    val choices = listOf(
        Triple(FeeChoice.SLOW, R.string.wallet_send_fee_slow, R.string.wallet_send_fee_slow_time),
        Triple(FeeChoice.NORMAL, R.string.wallet_send_fee_normal, R.string.wallet_send_fee_normal_time),
        Triple(FeeChoice.FAST, R.string.wallet_send_fee_fast, R.string.wallet_send_fee_fast_time),
    )
    for ((choice, label, time) in choices) {
        val rate = SendFees.rate(choice, state.estimates, "")
        FeeRow(
            selected = state.feeChoice == choice,
            enabled = rate != null,
            tag = "wallet_send_fee_${choice.name.lowercase()}",
            onClick = { actions.onFee(choice) },
        ) {
            Column {
                Text(stringResource(label))
                Text(
                    listOfNotNull(stringResource(time), rate?.let { stringResource(R.string.wallet_send_fee_rate, SendFees.show(it)) })
                        .joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    FeeRow(state.feeChoice == FeeChoice.CUSTOM, true, "wallet_send_fee_custom", { actions.onFee(FeeChoice.CUSTOM) }) {
        Text(stringResource(R.string.wallet_send_fee_custom))
    }
    if (state.feeChoice == FeeChoice.CUSTOM) {
        OutlinedTextField(
            value = state.customFee,
            onValueChange = actions.onCustomFee,
            label = { Text(stringResource(R.string.wallet_send_fee_custom_label)) },
            isError = state.customFeeBad,
            supportingText = if (state.customFeeBad) {
                { Text(stringResource(R.string.wallet_send_fee_custom_bad)) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("wallet_send_fee_custom_field"),
        )
    }
}

@Composable
private fun FeeRow(selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/** What stopped Review: under the form. */
@Composable
private fun ProblemText(state: SendUiState, problem: SendProblem, actions: SendActions) {
    Column(Modifier.fillMaxWidth().testTag("wallet_send_problem"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        when (problem) {
            is SendProblem.Refused -> PsbtProblem(problem.reason, problem.alarm, problem.byVault)
            else -> Text(problemText(state, problem), color = MaterialTheme.colorScheme.error)
        }
        if (problem is SendProblem.NoChain || problem == SendProblem.ServerFailed) {
            TextButton(onClick = actions.onChainServer, modifier = Modifier.testTag("wallet_send_problem_chain_server")) {
                Text(stringResource(R.string.wallet_send_chain_server))
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod") // one branch per state
@Composable
private fun problemText(state: SendUiState, problem: SendProblem): String = when (problem) {
    SendProblem.SelfSend -> stringResource(R.string.wallet_send_self_send)
    SendProblem.BadAmount -> stringResource(R.string.wallet_send_bad_amount)
    SendProblem.BadFee -> stringResource(R.string.wallet_send_bad_fee)
    SendProblem.TooManyInputs -> stringResource(R.string.wallet_send_too_many_inputs)
    is SendProblem.NotEnough -> stringResource(
        R.string.wallet_send_not_enough, BtcFormat.sats(problem.availableSats), BtcFormat.sats(problem.neededSats),
    ) + if (state.includeUnconfirmed) "" else " " + stringResource(R.string.wallet_send_not_enough_unconfirmed)
    SendProblem.ServerFailed -> stringResource(R.string.wallet_send_server_failed)
    is SendProblem.NoChain -> when (val open = problem.open) {
        is ChainOpen.NoServer -> stringResource(
            when (open.server) {
                ChainServer.Unreadable -> R.string.wallet_server_unreadable
                ChainServer.NotOffered -> R.string.wallet_network_not_offered
                else -> R.string.wallet_server_not_set
            },
        )
        is ChainOpen.WrongNetwork -> stringResource(R.string.wallet_server_wrong_network, networkLabel(state.network).orEmpty())
        is ChainOpen.Unchecked, is ChainOpen.Ready -> stringResource(R.string.wallet_server_failed)
    }
    SendProblem.Different -> stringResource(R.string.wallet_send_mismatch)
    is SendProblem.Failed -> stringResource(problem.kind.messageRes())
    is SendProblem.Refused -> ""
}

/** The `invalid_psbt` texts (ANDROID-PLAN §4): each reason's own text; the technical ones give the reason under "Details". */
@Composable
private fun PsbtProblem(reason: String, alarm: Boolean, byVault: Boolean) {
    val kind = PsbtTexts.of(reason, byVault)
    val text = when (kind) {
        PsbtText.FEE_RATE -> stringResource(R.string.wallet_psbt_fee_rate)
        PsbtText.OUTPUT_AMOUNT -> stringResource(R.string.wallet_psbt_output_amount)
        PsbtText.TECHNICAL -> stringResource(R.string.wallet_psbt_technical)
        PsbtText.WRONG_SEED -> stringResource(R.string.wallet_psbt_wrong_seed)
        PsbtText.OTHER -> stringResource(R.string.wallet_psbt_other, reason)
    }
    if (alarm || kind == PsbtText.WRONG_SEED) {
        NoticeCard(NoticeKind.URGENT, stringResource(R.string.wallet_send), text, Modifier.testTag("wallet_send_psbt_alarm"))
    } else {
        Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("wallet_send_psbt"))
    }
    if (kind == PsbtText.TECHNICAL) {
        Text(stringResource(R.string.wallet_psbt_details), style = MaterialTheme.typography.labelLarge)
        Text(reason, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("wallet_send_psbt_details"))
    }
}

/** The `cap_exceeded` texts (ANDROID-PLAN §4), word for word, with the time and the remaining allowance. */
@Composable
internal fun capMessage(cap: CapText): String {
    val limit = cap.limitSats
    return when {
        cap.reason == CapReason.PER_TX && limit != null -> stringResource(R.string.wallet_cap_per_tx, sats(limit))
        cap.reason == CapReason.PER_PERIOD && limit != null -> {
            val remaining = cap.remainingSats
            val room = if (remaining != null && remaining > 0) {
                stringResource(R.string.wallet_cap_remaining, sats(remaining))
            } else {
                stringResource(R.string.wallet_cap_none)
            }
            val at = cap.allowedAfter
            val at2 = if (at != null) {
                stringResource(R.string.wallet_cap_after, SendFormat.whenAt(at))
            } else {
                stringResource(R.string.wallet_cap_never)
            }
            stringResource(R.string.wallet_cap_per_period, sats(limit)) + " " + room + " " + at2
        }
        else -> stringResource(R.string.wallet_cap_other, cap.other ?: cap.reason.name.lowercase())
    }
}

@Composable
private fun amountLabel(sats: Long, network: Network?): String {
    val btc = BtcFormat.btc(sats)
    return stringResource(if (network == Network.MAINNET) R.string.wallet_balance_btc else R.string.wallet_send_tbtc, btc)
}

@Composable
private fun ReviewStep(state: SendUiState, actions: SendActions, modifier: Modifier) {
    val review = state.review ?: return
    val cap = review.capRefusal
    FormScaffold(
        title = stringResource(R.string.wallet_send_review_title),
        primaryLabel = if (cap == null) stringResource(R.string.wallet_send_approve_button) else null,
        onPrimary = actions.onApprove,
        primaryEnabled = state.password.isNotEmpty() && !state.busy,
        busy = state.busy,
        onBack = actions.onEdit,
        modifier = modifier.testTag("wallet_send_review"),
    ) {
        BigFeeDialog(state, actions)
        SummaryRow(R.string.wallet_send_review_to, review.address, "wallet_send_review_address", mono = true)
        SummaryRow(R.string.wallet_send_review_amount, amountLabel(review.payeeSats, state.network), "wallet_send_review_amount")
        if (review.changeSats > 0) {
            SummaryRow(R.string.wallet_send_review_change, amountLabel(review.changeSats, state.network), "wallet_send_review_change")
        }
        SummaryRow(
            R.string.wallet_send_review_fee,
            stringResource(R.string.wallet_send_review_fee_value, BtcFormat.sats(review.feeSats), SendFees.show(review.feeRate)),
            "wallet_send_review_fee",
        )
        SummaryRow(R.string.wallet_send_review_total, amountLabel(review.totalOutSats, state.network), "wallet_send_review_total")
        if (review.toFeeSats > 0) {
            Text(
                stringResource(R.string.wallet_send_review_dust, BtcFormat.sats(review.toFeeSats)),
                modifier = Modifier.testTag("wallet_send_review_dust"),
            )
        }
        if (review.sendAll && review.leftBehind > 0) {
            Text(
                pluralStringResource(R.plurals.wallet_send_review_left_behind, review.leftBehind, review.leftBehind),
                modifier = Modifier.testTag("wallet_send_review_left"),
            )
        }
        review.limit?.let {
            Text(
                stringResource(R.string.wallet_send_limit_review, sats(it.outflowSats), sats(it.leftSats)),
                modifier = Modifier.testTag("wallet_send_review_limit"),
            )
        }
        if (cap != null) {
            NoticeCard(
                NoticeKind.URGENT, stringResource(R.string.wallet_send), capMessage(cap), Modifier.testTag("wallet_send_review_cap"),
                actions = { CapButtons(actions, actions.onEdit) },
            )
        } else {
            Text(
                stringResource(
                    R.string.wallet_send_approve,
                    amountLabel(review.payeeSats, state.network),
                    review.address,
                    stringResource(R.string.wallet_balance_sats, BtcFormat.sats(review.feeSats)),
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("wallet_send_review_approve"),
            )
            SecretField(
                value = state.password,
                onValueChange = actions.onPassword,
                label = stringResource(R.string.wallet_send_password_label),
                error = when {
                    state.askAgain -> stringResource(R.string.wallet_send_ask_again)
                    else -> state.error?.let { stringResource(it.messageRes()) }
                },
                onImeAction = actions.onApprove,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("wallet_send_password"),
            )
        }
    }
}

@Composable
private fun sats(v: Long) = stringResource(R.string.wallet_balance_sats, BtcFormat.sats(v))

@Composable
private fun SummaryRow(label: Int, value: String, tag: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.testTag(tag),
        )
    }
}

/** **Change the amount** and **Spending limits**: the two ways out of a refusal by a limit. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.CapButtons(actions: SendActions, change: () -> Unit) {
    TextButton(onClick = change, modifier = Modifier.testTag("wallet_send_cap_change")) {
        Text(stringResource(R.string.wallet_cap_change_amount))
    }
    TextButton(onClick = actions.onLimits, modifier = Modifier.testTag("wallet_send_cap_limits")) {
        Text(stringResource(R.string.wallet_menu_limits))
    }
}

/** What each ending offers: the primary, the secondary and the tertiary button (a label and what it does). */
private class Ways(
    val primary: Pair<Int, () -> Unit>?,
    val secondary: Pair<Int, () -> Unit>? = null,
    val tertiary: Pair<Int, () -> Unit>? = null,
)

@Suppress("CyclomaticComplexMethod") // one branch per state
@Composable
private fun DoneStep(state: SendUiState, actions: SendActions, modifier: Modifier) {
    val outcome = state.outcome ?: return
    // Discard is off while a signed payment may be on the network and no server has said it is not (the view model enforces it too).
    val discard = (R.string.wallet_send_discard to actions.onAskDiscard).takeIf { !state.discardLocked }
    val again = R.string.wallet_try_again to actions.onRetry
    val over = R.string.wallet_send_start_over to actions.onStartOver
    val ways = when (outcome) {
        is SendOutcome.Sent -> Ways(R.string.wallet_send_done to actions.onDone)
        is SendOutcome.BroadcastFailed -> Ways(again, R.string.wallet_send_choose_server to actions.onChainServer, discard)
        is SendOutcome.CapExceeded ->
            Ways(R.string.wallet_cap_change_amount to actions.onStartOver, R.string.wallet_menu_limits to actions.onLimits)
        is SendOutcome.InvalidPsbt -> Ways(over)
        SendOutcome.Unanswered, SendOutcome.Unsent -> Ways(again, discard)
        // Discard is on offer once something may have been signed, unless the payment may be on the network: the member is not stuck.
        SendOutcome.SignedDiffers, SendOutcome.AlreadySigned, SendOutcome.Lapsed, SendOutcome.SignedUnreadable ->
            if (discard != null) Ways(discard, over) else Ways(again, over)
        SendOutcome.Superseded, SendOutcome.DuplicateWallet -> Ways(over, discard)
    }
    FormScaffold(
        title = stringResource(R.string.wallet_send),
        primaryLabel = ways.primary?.let { stringResource(it.first) },
        onPrimary = ways.primary?.second ?: {},
        primaryEnabled = !state.busy && !state.checking,
        busy = state.busy,
        secondaryLabel = ways.secondary?.let { stringResource(it.first) },
        onSecondary = ways.secondary?.second ?: {},
        tertiaryLabel = ways.tertiary?.let { stringResource(it.first) },
        onTertiary = ways.tertiary?.second ?: {},
        onBack = if (outcome is SendOutcome.Sent) null else actions.onBack,
        modifier = modifier.testTag("wallet_send_done_screen"),
    ) {
        OutcomeText(outcome, state.discardLocked)
        if (state.checking) Text(stringResource(R.string.wallet_send_checking), modifier = Modifier.testTag("wallet_send_checking"))
        if (state.discardLocked && !state.checking) {
            Text(stringResource(R.string.wallet_send_may_be_sent), modifier = Modifier.testTag("wallet_send_may_be_sent"))
        }
        state.error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
    }
    if (state.discardAsk) {
        ConfirmDialog(
            title = stringResource(R.string.wallet_send_discard_title),
            text = stringResource(R.string.wallet_send_discard_body),
            confirmLabel = stringResource(R.string.wallet_send_discard),
            onConfirm = actions.onDiscard,
            onDismiss = actions.onCancelDiscard,
            destructive = true,
            modifier = Modifier.testTag("wallet_send_discard_confirm"),
        )
    }
}

@Suppress("CyclomaticComplexMethod") // one branch per state
@Composable
private fun OutcomeText(outcome: SendOutcome, discardLocked: Boolean) {
    when (outcome) {
        is SendOutcome.Sent -> NoticeCard(
            NoticeKind.SUCCESS,
            stringResource(R.string.wallet_send),
            stringResource(
                R.string.wallet_send_sent,
                stringResource(
                    when (outcome.time) {
                        ConfirmTime.MINUTES_20 -> R.string.wallet_send_time_20
                        ConfirmTime.HOUR -> R.string.wallet_send_time_hour
                        ConfirmTime.DAY -> R.string.wallet_send_time_day
                    },
                ),
            ),
            Modifier.testTag("wallet_send_sent"),
        )
        is SendOutcome.BroadcastFailed -> {
            val host = outcome.host ?: stringResource(R.string.wallet_send_host_none)
            Text(
                if (outcome.mayBeSent) {
                    stringResource(R.string.wallet_send_broadcast_no_answer, host)
                } else {
                    stringResource(R.string.wallet_send_broadcast_failed, host, stringResource(reasonText(outcome.reason)))
                },
                modifier = Modifier.testTag("wallet_send_broadcast_failed"),
            )
        }
        is SendOutcome.CapExceeded -> Text(capMessage(outcome.cap), modifier = Modifier.testTag("wallet_send_cap"))
        is SendOutcome.InvalidPsbt -> PsbtProblem(outcome.reason, outcome.alarm, outcome.byVault)
        SendOutcome.SignedDiffers -> Note(R.string.wallet_send_signed_differs, "wallet_send_differs")
        SendOutcome.AlreadySigned -> Text(
            stringResource(R.string.wallet_send_already_signed) + " " +
                stringResource(
                    if (discardLocked) R.string.wallet_send_already_signed_wait else R.string.wallet_send_already_signed_discard,
                ),
            modifier = Modifier.testTag("wallet_send_already"),
        )
        SendOutcome.Unanswered -> Note(R.string.wallet_send_unanswered, "wallet_send_unanswered")
        SendOutcome.Lapsed -> Note(R.string.wallet_send_lapsed, "wallet_send_lapsed")
        SendOutcome.SignedUnreadable -> Note(R.string.wallet_send_unreadable, "wallet_send_unreadable")
        SendOutcome.Superseded -> Note(R.string.wallet_send_superseded, "wallet_send_superseded")
        SendOutcome.DuplicateWallet -> Note(R.string.wallet_send_duplicate, "wallet_send_duplicate")
        SendOutcome.Unsent -> Note(R.string.wallet_send_unsent, "wallet_send_unsent")
    }
}

@Composable
private fun Note(text: Int, tag: String) = Text(stringResource(text), modifier = Modifier.testTag(tag))

private fun reasonText(reason: BroadcastReason): Int = when (reason) {
    BroadcastReason.NO_ANSWER -> R.string.wallet_send_reason_other
    BroadcastReason.NO_SERVER -> R.string.wallet_send_reason_no_server
    BroadcastReason.INPUTS_SPENT -> R.string.wallet_send_reason_inputs_spent
    BroadcastReason.FEE_TOO_LOW -> R.string.wallet_send_reason_fee_low
    BroadcastReason.CONFLICT -> R.string.wallet_send_reason_conflict
    BroadcastReason.DUST -> R.string.wallet_send_reason_dust
    BroadcastReason.SCRIPT_FAILED -> R.string.wallet_send_reason_script
    BroadcastReason.MALFORMED -> R.string.wallet_send_reason_malformed
    BroadcastReason.OTHER -> R.string.wallet_send_reason_other
}
