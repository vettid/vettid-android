package com.vettid.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.WaitingCounts
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.theme.Spacing

/**
 * The owner-check screen for [mode], driven by [OwnerCheckViewModel]. [onDone] after a passed check; [onCancel]
 * (VOLUNTARY and HOLD_OFF only: a gated check is never dismissible, §3.6.5).
 */
@Composable
fun OwnerCheckRoute(
    mode: OwnerCheckMode,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: OwnerCheckViewModel = hiltViewModel(),
) {
    LaunchedEffect(mode) { viewModel.start(mode) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.passed) {
        if (state.passed) {
            viewModel.cancel() // nothing of this check stays for the next one
            onDone()
        }
    }
    val cancel = {
        viewModel.cancel()
        onCancel()
    }
    // Back leaves a voluntary check; past the deadline it does nothing (the check is never dismissible).
    BackHandler { if (mode != OwnerCheckMode.GATED) cancel() }
    OwnerCheckContent(state.copy(mode = mode), viewModel, onCancel = cancel)
}

/** Hours of an interval, for the texts. */
@Composable
private fun hoursText(seconds: Long): String {
    val h = (seconds / SECONDS_PER_HOUR).toInt().coerceAtLeast(1)
    return pluralStringResource(R.plurals.ownercheck_hours, h, h)
}

private const val SECONDS_PER_HOUR = 3_600L

/** The owner-check screen for [state] (stateless). */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun OwnerCheckContent(state: OwnerCheckUiState, actions: OwnerCheckActions, onCancel: () -> Unit) {
    val v = state.view
    val interval = hoursText(v?.intervalSeconds ?: OwnerCheckView.MAX_INTERVAL_S)
    val (title, body) = when (state.mode) {
        OwnerCheckMode.GATED -> stringResource(R.string.ownercheck_title_gated) to stringResource(R.string.ownercheck_body_gated, interval)
        OwnerCheckMode.VOLUNTARY -> stringResource(R.string.ownercheck_title_voluntary) to
            stringResource(R.string.ownercheck_body_voluntary, interval)
        OwnerCheckMode.HOLD_OFF -> stringResource(R.string.ownercheck_title_hold_off) to stringResource(R.string.ownercheck_body_hold_off)
    }
    FormScaffold(
        title = title,
        body = body,
        primaryLabel = stringResource(
            if (state.mode == OwnerCheckMode.HOLD_OFF) R.string.ownercheck_submit_hold_off else R.string.ownercheck_submit,
        ),
        onPrimary = actions::submit,
        primaryEnabled = state.submitAllowed,
        busy = state.busy || state.locking,
        secondaryLabel = stringResource(if (state.mode == OwnerCheckMode.GATED) R.string.ownercheck_lock else R.string.ownercheck_cancel),
        onSecondary = if (state.mode == OwnerCheckMode.GATED) actions::lockVault else onCancel,
        modifier = Modifier.testTag("owner_check"),
        header = {
            Spacer(Modifier.height(Spacing.l))
            Icon(
                Icons.Outlined.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(40.dp),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            if (state.mode == OwnerCheckMode.GATED && v != null && v.state != OwnerCheckState.OK) {
                WaitingCard(v.waiting ?: WaitingCounts(), held = v.state == OwnerCheckState.HELD)
            }
            if (state.mode == OwnerCheckMode.HOLD_OFF) {
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.ownercheck_hold_off_warning_title),
                    stringResource(R.string.ownercheck_hold_off_warning),
                    modifier = Modifier.testTag("hold_off_warning"),
                )
                HoldOffChoices(state.holdOff, actions::setHoldOff)
            }
            val m = state.message
            val left = when (m) {
                is OwnerCheckMessage.BadPin -> m.checksLeft
                is OwnerCheckMessage.BadPassword -> m.checksLeft
                else -> v?.takeIf { it.failures > 0 }?.checksLeft
            }
            SecretField(
                value = state.pin,
                onValueChange = actions::setPin,
                label = stringResource(R.string.ownercheck_pin_label),
                isPin = true,
                enabled = !state.busy && state.waitSeconds == 0L,
                error = if (m is OwnerCheckMessage.BadPin) stringResource(R.string.ownercheck_bad_pin) else null,
                imeAction = ImeAction.Next,
                modifier = Modifier.testTag("owner_check_pin"),
            )
            SecretField(
                value = state.password,
                onValueChange = actions::setPassword,
                label = stringResource(R.string.ownercheck_password_label),
                enabled = !state.busy && state.waitSeconds == 0L,
                error = if (m is OwnerCheckMessage.BadPassword) stringResource(R.string.ownercheck_bad_password) else null,
                onImeAction = actions::submit,
                modifier = Modifier.testTag("owner_check_password"),
            )
            if (left != null) {
                Text(
                    pluralStringResource(R.plurals.ownercheck_checks_left, left, left),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (left <= WARN_LEFT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("owner_check_left"),
                )
            }
            when {
                state.waitSeconds > 0 -> Text(
                    stringResource(R.string.ownercheck_backoff_wait, formatWait(state.waitSeconds)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("owner_check_wait"),
                )
                m is OwnerCheckMessage.Backoff ->
                    Text(stringResource(R.string.ownercheck_backoff), style = MaterialTheme.typography.bodyMedium)
                m is OwnerCheckMessage.Failed -> NoticeCard(
                    NoticeKind.URGENT,
                    title,
                    stringResource(m.kind.messageRes()),
                    modifier = Modifier.testTag("owner_check_failed"),
                )
                else -> Unit
            }
        }
    }
}

private const val WARN_LEFT = 3

/** `vault.held`'s counts (§3.6.5: "3 new messages waiting"): no names, no content. */
@Composable
private fun WaitingCard(w: WaitingCounts, held: Boolean) {
    val lines = buildList {
        if (w.messages > 0) add(pluralStringResource(R.plurals.ownercheck_waiting_messages, w.messages, w.messages))
        if (w.requests > 0) add(pluralStringResource(R.plurals.ownercheck_waiting_requests, w.requests, w.requests))
        if (w.calls > 0) add(pluralStringResource(R.plurals.ownercheck_waiting_calls, w.calls, w.calls))
        if (w.other > 0) add(pluralStringResource(R.plurals.ownercheck_waiting_other, w.other, w.other))
    }
    val counts = if (lines.isEmpty()) stringResource(R.string.ownercheck_waiting_none) else lines.joinToString("\n")
    val note = stringResource(if (held) R.string.ownercheck_held_note else R.string.ownercheck_due_note)
    NoticeCard(
        NoticeKind.INFO,
        stringResource(R.string.ownercheck_waiting_title),
        "$counts\n\n$note",
        modifier = Modifier.testTag("owner_check_waiting"),
    )
}

@Composable
private fun HoldOffChoices(selected: HoldOffChoice, onPick: (HoldOffChoice) -> Unit) {
    Text(stringResource(R.string.ownercheck_hold_off_until), style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        HoldOffChoice.entries.forEach { c ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchTarget)
                    .selectable(selected = c == selected, role = Role.RadioButton, onClick = { onPick(c) })
                    .testTag("hold_off_${c.name.lowercase()}"),
            ) {
                RadioButton(selected = c == selected, onClick = null)
                Spacer(Modifier.width(Spacing.m))
                Text(holdOffLabel(c), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun holdOffLabel(c: HoldOffChoice): String = stringResource(
    when (c) {
        HoldOffChoice.UNTIL_TURNED_ON -> R.string.ownercheck_hold_off_never
        HoldOffChoice.ONE_DAY -> R.string.ownercheck_hold_off_1d
        HoldOffChoice.THREE_DAYS -> R.string.ownercheck_hold_off_3d
        HoldOffChoice.ONE_WEEK -> R.string.ownercheck_hold_off_1w
        HoldOffChoice.THIRTY_DAYS -> R.string.ownercheck_hold_off_30d
    },
)
