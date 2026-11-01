package com.vettid.feature.wallet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.SecureWindow
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.wallet.WalletsManager

/** What the Spending limits screen asks of its host. */
data class SpendingLimitsActions(
    val onBack: () -> Unit = {},
    val onLower: () -> Unit = {},
    val onRaise: () -> Unit = {},
    val onPerTx: (String) -> Unit = {},
    val onPerPeriod: (String) -> Unit = {},
    val onRemoveTx: (Boolean) -> Unit = {},
    val onRemovePeriod: (Boolean) -> Unit = {},
    val onToggleUnit: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onConfirmSet: () -> Unit = {},
    val onConfirmRaise: () -> Unit = {},
    val onPin: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** Back inside the screen (a confirm to its form, a form to the overview). */
    val onStepBack: () -> Unit = {},
)

/**
 * Spending limits (ANDROID-PLAN §4 "Spending limits", "The screen"): the two limits as they stand in sats ("No limit" when there is
 * none), the 24-hour total when there is a per-period limit, and the two ways to change them. Lowering is a form and a question
 * with no password; raising or removing a form and a question with the PIN and the password.
 */
@Composable
fun SpendingLimitsScreen(state: SpendingLimitsUiState, actions: SpendingLimitsActions, modifier: Modifier = Modifier) {
    BackHandler(enabled = state.step != LimitsStep.OVERVIEW) { actions.onStepBack() }
    when (state.step) {
        LimitsStep.OVERVIEW -> Overview(state, actions, modifier)
        LimitsStep.LOWER, LimitsStep.RAISE -> CapsFormScreen(state, actions, modifier)
        LimitsStep.CONFIRM_SET -> {
            CapsFormScreen(state, actions, modifier)
            ConfirmSet(state, actions)
        }
        LimitsStep.CONFIRM_RAISE -> ConfirmRaise(state, actions, modifier)
    }
}

/** A whole number of sats with its thousands separator and unit, or "No limit". */
@Composable
private fun satsOrNone(sats: Long?): String =
    if (sats == null) stringResource(R.string.wallet_limits_none) else stringResource(R.string.wallet_balance_sats, BtcFormat.sats(sats))

@Composable
private fun Overview(state: SpendingLimitsUiState, actions: SpendingLimitsActions, modifier: Modifier) {
    val canChange = state.loaded && state.supported && state.idle
    FormScaffold(
        title = stringResource(R.string.wallet_menu_limits),
        primaryLabel = if (state.supported) stringResource(R.string.wallet_limits_lower_button) else null,
        onPrimary = actions.onLower,
        primaryEnabled = canChange,
        // Nothing to raise or remove while there is no limit.
        secondaryLabel = stringResource(R.string.wallet_limits_raise_button)
            .takeIf { state.supported && (state.perTx != null || state.perPeriod != null) },
        onSecondary = { if (canChange) actions.onRaise() },
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_limits"),
    ) {
        when {
            !state.loaded -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !state.supported ->
                Text(stringResource(R.string.wallet_caps_unsupported), modifier = Modifier.testTag("wallet_limits_unsupported"))
            else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                LimitValue(R.string.wallet_limit_per_tx, state.perTx, "limits_now_tx")
                LimitValue(R.string.wallet_limit_per_period, state.perPeriod, "limits_now_period")
                val spent = state.spent
                if (state.perPeriod != null && spent != null) {
                    Text(
                        stringResource(R.string.wallet_limits_used, satsOrNone(spent), satsOrNone(state.perPeriod)),
                        modifier = Modifier.testTag("limits_used"),
                    )
                }
                if (state.capsPending) {
                    Text(
                        stringResource(R.string.wallet_receive_caps_pending),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("limits_pending"),
                    )
                }
                ErrorLine(state, actions)
            }
        }
    }
}

@Composable
private fun LimitValue(label: Int, sats: Long?, tag: String) {
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(satsOrNone(sats), style = MaterialTheme.typography.titleMedium)
    }
}

/** The error of the last try, plain, with "Nothing was changed." (the limits are as they were), and a way to dismiss it. */
@Composable
private fun ErrorLine(state: SpendingLimitsUiState, actions: SpendingLimitsActions) {
    val kind = state.error ?: return
    Column(Modifier.fillMaxWidth().testTag("limits_error"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(errorText(kind, state.errorCode), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.wallet_limits_unchanged), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = actions.onDismissError, modifier = Modifier.testTag("limits_error_dismiss")) {
            Text(stringResource(R.string.wallet_dismiss))
        }
    }
}

@Composable
private fun errorText(kind: FailureKind, code: String?): String = stringResource(
    when (code) {
        WalletsManager.CAPS_PENDING -> R.string.wallet_limits_pending_first
        WalletsManager.CAPS_SETTLED -> R.string.wallet_limits_settled
        WalletsManager.SETTINGS_UNREADABLE -> R.string.wallet_limits_unreadable
        WalletsManager.DUPLICATE_WALLET -> R.string.wallet_limits_duplicate
        else -> kind.messageRes()
    },
)

/** "Lower a limit" / "Raise or remove a limit": each limit with its number (empty: as it is), in sats with a toggle to bitcoin. */
@Composable
private fun CapsFormScreen(state: SpendingLimitsUiState, actions: SpendingLimitsActions, modifier: Modifier) {
    val plan = state.plan
    val raise = state.raiseForm
    FormScaffold(
        title = stringResource(if (raise) R.string.wallet_limits_raise_button else R.string.wallet_limits_lower_button),
        body = stringResource(if (raise) R.string.wallet_limits_raise_body else R.string.wallet_limits_lower_body),
        primaryLabel = stringResource(R.string.wallet_limits_next),
        onPrimary = actions.onNext,
        primaryEnabled = plan.ok && state.idle,
        busy = state.busy,
        onBack = actions.onStepBack,
        modifier = modifier.testTag(if (raise) "wallet_limits_raise_form" else "wallet_limits_lower_form"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            val form = state.form
            MemberField(
                value = form.perTx, label = R.string.wallet_limit_per_tx, now = state.perTx, remove = form.removeTx, raise = raise,
                problem = plan.txProblem, onValue = actions.onPerTx, onRemove = actions.onRemoveTx, btc = form.btc,
                fieldTag = "limit_per_tx", removeTag = "limit_remove_tx",
            )
            MemberField(
                value = form.perPeriod, label = R.string.wallet_limit_per_period, now = state.perPeriod, remove = form.removePeriod,
                raise = raise, problem = plan.periodProblem, onValue = actions.onPerPeriod, onRemove = actions.onRemovePeriod,
                btc = form.btc, fieldTag = "limit_per_period", removeTag = "limit_remove_period",
            )
            val formProblem = plan.problem
            if (formProblem != null && form.touched) {
                val text = if (formProblem == CapFormProblem.ABOVE_PERIOD) R.string.wallet_limit_order else R.string.wallet_limits_nothing
                Text(
                    stringResource(text),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("limits_form_problem"),
                )
            }
            TextButton(onClick = actions.onToggleUnit, modifier = Modifier.testTag("limit_unit_toggle")) {
                Text(stringResource(if (form.btc) R.string.wallet_limits_show_sats else R.string.wallet_limits_show_btc))
            }
            ErrorLine(state, actions)
        }
    }
}

@Composable
private fun MemberField(
    value: String,
    label: Int,
    now: Long?,
    remove: Boolean,
    raise: Boolean,
    problem: CapFieldProblem?,
    onValue: (String) -> Unit,
    onRemove: (Boolean) -> Unit,
    btc: Boolean,
    fieldTag: String,
    removeTag: String,
) {
    val message = if (problem == CapFieldProblem.NOT_LOWER) stringResource(R.string.wallet_limits_not_lower) else null
    LimitField(
        value = value,
        btc = btc,
        label = label,
        problem = problem?.takeIf { it == CapFieldProblem.OUT_OF_RANGE }?.let { LimitProblem.OUT_OF_RANGE },
        onChange = onValue,
        tag = fieldTag,
        message = message,
        note = stringResource(R.string.wallet_limits_now, satsOrNone(now)),
        enabled = !remove,
    )
    // Only a limit that is there can be removed.
    if (raise && now != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wallet_limits_remove_switch), modifier = Modifier.weight(1f))
            Switch(checked = remove, onCheckedChange = onRemove, modifier = Modifier.testTag(removeTag))
        }
    }
}

/** One limit named in a question: "{amount} per payment" or "{amount} in any 24 hours". */
@Composable
private fun phrase(perTx: Boolean, sats: Long?): String =
    stringResource(if (perTx) R.string.wallet_limits_phrase_tx else R.string.wallet_limits_phrase_period, satsOrNone(sats))

/** What a question names: the limits that change (those in [moves]), as [value] gives them, joined with "and". */
@Composable
private fun named(state: SpendingLimitsUiState, moves: Set<CapMove>, value: (tx: Boolean) -> Long?): String {
    val plan = state.plan
    val parts = buildList {
        if (plan.txMove in moves) add(phrase(true, value(true)))
        if (plan.periodMove in moves) add(phrase(false, value(false)))
    }
    return if (parts.size == 2) stringResource(R.string.wallet_limits_and, parts[0], parts[1]) else parts.joinToString()
}

@Composable
private fun newNamed(state: SpendingLimitsUiState, moves: Set<CapMove>): String =
    named(state, moves) { tx -> if (tx) state.plan.newPerTx else state.plan.newPerPeriod }

@Composable
private fun oldNamed(state: SpendingLimitsUiState, moves: Set<CapMove>): String =
    named(state, moves) { tx -> if (tx) state.perTx else state.perPeriod }

/** Whether the plan only removes limits (every member that changes goes to "No limit"): "Remove the limit?". */
private fun removesOnly(plan: CapsPlan): Boolean {
    val tx = plan.txMove == CapMove.SAME || (plan.txMove == CapMove.RAISE && plan.newPerTx == null)
    val period = plan.periodMove == CapMove.SAME || (plan.periodMove == CapMove.RAISE && plan.newPerPeriod == null)
    return tx && period
}

private val LOWERING = setOf(CapMove.LOWER)
private val CHANGING = setOf(CapMove.LOWER, CapMove.RAISE)

/** "Lower the limit to {new}?" over the form: no password. */
@Composable
private fun ConfirmSet(state: SpendingLimitsUiState, actions: SpendingLimitsActions) {
    ConfirmDialog(
        title = stringResource(R.string.wallet_limits_lower_title, newNamed(state, LOWERING)),
        text = stringResource(R.string.wallet_limits_now, oldNamed(state, LOWERING)),
        confirmLabel = stringResource(R.string.wallet_limits_lower_action),
        onConfirm = actions.onConfirmSet,
        onDismiss = actions.onStepBack,
        modifier = Modifier.testTag("wallet_limits_confirm"),
    )
}

/** "Raise the limit from {old} to {new}?", "Remove the limit?" or, when both go, "Remove the limits?". */
@Composable
private fun raiseTitle(state: SpendingLimitsUiState, remove: Boolean): String {
    val plan = state.plan
    val both = plan.txMove == CapMove.RAISE && plan.periodMove == CapMove.RAISE && plan.newPerTx == null && plan.newPerPeriod == null
    return when {
        !remove -> stringResource(R.string.wallet_limits_raise_title, oldNamed(state, CHANGING), newNamed(state, CHANGING))
        both -> stringResource(R.string.wallet_limits_remove_title_both)
        else -> stringResource(R.string.wallet_limits_remove_title)
    }
}

/** The question of a raise, with the PIN and password, and "Enter your PIN and password.": one credential operation. */
@Composable
private fun ConfirmRaise(state: SpendingLimitsUiState, actions: SpendingLimitsActions, modifier: Modifier) {
    val plan = state.plan
    val remove = removesOnly(plan)
    val title = raiseTitle(state, remove)
    val kind = state.error
    SecureWindow()
    FormScaffold(
        title = title,
        body = stringResource(R.string.wallet_limits_enter_secrets),
        primaryLabel = stringResource(if (remove) R.string.wallet_limits_remove_action else R.string.wallet_limits_raise_action),
        onPrimary = actions.onConfirmRaise,
        primaryEnabled = state.secretsReady && state.idle,
        busy = state.busy,
        destructive = remove,
        onBack = actions.onStepBack,
        modifier = modifier.testTag("wallet_limits_secrets"),
    ) {
        if (remove) {
            Text(oldNamed(state, CHANGING), modifier = Modifier.testTag("limits_remove_names"))
        }
        SecretField(
            value = state.pin,
            onValueChange = actions.onPin,
            label = stringResource(R.string.wallet_pin_label),
            isPin = true,
            error = kind?.takeIf { it == FailureKind.BAD_PIN }?.let { stringResource(it.messageRes()) },
            enabled = state.idle,
            modifier = Modifier.fillMaxWidth().testTag("wallet_limits_pin"),
        )
        SecretField(
            value = state.password,
            onValueChange = actions.onPassword,
            label = stringResource(R.string.wallet_password_label),
            error = kind?.takeIf { it == FailureKind.BAD_PASSWORD }?.let { stringResource(it.messageRes()) },
            onImeAction = actions.onConfirmRaise,
            enabled = state.idle,
            modifier = Modifier.fillMaxWidth().testTag("wallet_limits_password"),
        )
        // Every other refusal (a wait, a conflict, a limit still to be set) in words of its own, with what it means for the limits.
        if (kind != null && kind != FailureKind.BAD_PIN && kind != FailureKind.BAD_PASSWORD) ErrorLine(state, actions)
        if (kind == FailureKind.BAD_PIN || kind == FailureKind.BAD_PASSWORD) {
            Text(stringResource(R.string.wallet_limits_unchanged), style = MaterialTheme.typography.bodySmall)
        }
    }
}
