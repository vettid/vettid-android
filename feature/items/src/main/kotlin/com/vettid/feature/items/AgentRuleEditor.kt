package com.vettid.feature.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.RulePreview
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultLimit
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.format.durationText
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.data.items.RuleMatch
import java.time.Instant
import com.vettid.core.ui.R as UiR

/**
 * The parts of the share-rule editor that are an agent's (ANDROID-PLAN 0.1.116 §4 AA4); the rest of the editor is the
 * connection's ([RuleEditScreen]).
 */

/** The wording of an agent rule's mode (the connection editor's "Share automatically" is worded so for agents). */
@Composable
internal fun AgentModeSection(state: RuleEditUiState, onMode: (ShareMode) -> Unit) {
    Text(stringResource(R.string.items_rule_mode), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.items_agent_rule_critical),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("agent_rule_critical"),
    )
    val ask = state.draft.mode == ShareMode.ASK
    Choice(R.string.items_agent_rule_mode_ask, ask, "rule_mode_ask", R.string.items_agent_rule_mode_ask_note) { onMode(ShareMode.ASK) }
    // Decision 17: offered only when the vault lists `item_ways` (an existing `auto` rule keeps showing, selected).
    if (state.autoShown) {
        val auto = state.draft.mode == ShareMode.AUTO
        Choice(R.string.items_agent_rule_mode_auto, auto, "rule_mode_auto", R.string.items_agent_rule_mode_auto_note, state.autoEnabled) {
            onMode(ShareMode.AUTO)
        }
    }
    if (state.autoDemoted) {
        Text(
            stringResource(R.string.items_agent_rule_auto_demoted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("agent_rule_auto_demoted"),
        )
    }
    // What a "use" can reveal, always (decision 14 item g).
    Text(
        stringResource(R.string.items_agent_rule_reveal),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("agent_rule_reveal"),
    )
    if (state.waysWarning) AgentWaysWarning(Modifier.testTag("agent_rule_ways_warning"))
}

/** "On this vault, the agent may receive the values of the items it uses, and its app may keep them in its history." */
@Composable
internal fun AgentWaysWarning(modifier: Modifier = Modifier) {
    NoticeCard(NoticeKind.WARNING, "", stringResource(UiR.string.core_ui_agent_ways_warning), modifier = modifier)
}

/** `per_hour` and `per_day` (always present for an agent rule) and `uses` (optional). */
@Composable
internal fun AgentLimitsSection(state: RuleEditUiState, actions: RuleEditActions) {
    Text(stringResource(R.string.items_agent_rule_limits), style = MaterialTheme.typography.titleSmall)
    val hour = state.perHourText.ifBlank { "…" }
    val day = state.perDayText.ifBlank { "…" }
    Text(
        stringResource(R.string.items_agent_rule_per_note, hour, day),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("agent_rule_per_note"),
    )
    AgentNumber(
        state.perHourText, actions.onPerHour, R.string.items_agent_rule_per_hour, state.perHourValid,
        R.string.items_rule_per_hour_bad, "rule_per_hour",
    )
    AgentNumber(
        state.perDayText, actions.onPerDay, R.string.items_agent_rule_per_day, state.perDayValid,
        R.string.items_rule_per_day_bad, "rule_per_day",
    )
    AgentNumber(
        state.usesText, actions.onUses, R.string.items_agent_rule_uses, state.usesValid, R.string.items_rule_uses_bad, "rule_uses",
        // "Stop after [n] uses of each item.": only once there is a number.
        note = state.usesText.takeIf { it.isNotBlank() }?.let { stringResource(R.string.items_agent_rule_uses_note, it) },
    )
}

@Composable
@Suppress("LongParameterList")
private fun AgentNumber(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    valid: Boolean,
    bad: Int,
    tag: String,
    note: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = if (!valid) {
            { Text(stringResource(bad)) }
        } else {
            note?.let { { Text(it) } }
        },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** More options: the status lifetime (decision 8), 15 minutes by default. */
@Composable
internal fun AgentMoreOptions(state: RuleEditUiState, actions: RuleEditActions) {
    TextButton(onClick = actions.onMoreOptions, modifier = Modifier.testTag("rule_more_options")) {
        Text(stringResource(R.string.items_agent_rule_more))
    }
    if (!state.moreOptions) return
    Text(stringResource(R.string.items_agent_rule_status_ttl), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("rule_status_ttl")) {
        TTL_CHOICES.forEach { (seconds, label) ->
            FilterChip(
                selected = (state.draft.statusTtl ?: RuleDraft.DEFAULT_STATUS_TTL) == seconds,
                onClick = { actions.onStatusTtl(seconds) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("rule_ttl_$seconds"),
            )
        }
    }
    Text(
        stringResource(R.string.items_agent_rule_status_ttl_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val TTL_CHOICES = listOf(
    60 to R.string.items_agent_rule_ttl_1m,
    300 to R.string.items_agent_rule_ttl_5m,
    900 to R.string.items_agent_rule_ttl_15m,
    3_600 to R.string.items_agent_rule_ttl_1h,
)

/** "<n> items: <k> will be asked about, <m> included now". */
@Composable
internal fun agentPreviewTotal(p: RulePreview): String {
    val include = p.matches.count { it.outcome == RuleMatch.OUTCOME_INCLUDE }
    val ask = p.matches.count { it.outcome == RuleMatch.OUTCOME_ASK }
    // An answer without outcomes (an older vault) says how many match and no more: nothing is guessed from the mode.
    return pluralStringResource(R.plurals.items_agent_rule_preview, p.total, p.total, ask, include)
}

/** The answers' words for an agent rule (the plan's drafts), or null to use the generic failure text. */
@Composable
internal fun agentErrorText(kind: FailureKind, limit: VaultLimit?): String? = when {
    kind == FailureKind.LIMIT && limit?.name == "agent_grants" -> stringResource(R.string.items_agent_limit_grants)
    kind == FailureKind.LIMIT && limit?.name == "share_rules_subject" -> stringResource(R.string.items_agent_limit_subject)
    kind == FailureKind.LIMIT && limit?.name == "share_rules" -> stringResource(R.string.items_agent_limit_rules)
    kind == FailureKind.NOT_FOUND -> stringResource(R.string.items_agent_not_paired)
    else -> null
}

/** The notices over the form: a conflict, a second `credential_locked`, a window that stayed open. */
@Composable
internal fun AgentNotices(state: RuleEditUiState, actions: RuleEditActions) {
    if (state.conflict) {
        NoticeCard(
            NoticeKind.INFO, "", stringResource(R.string.items_agent_conflict),
            modifier = Modifier.testTag("agent_rule_conflict"),
            actions = { TextButton(onClick = actions.onDismissConflict) { Text(stringResource(R.string.items_ok)) } },
        )
    }
    if (state.lockedAgain) {
        NoticeCard(
            NoticeKind.WARNING, "", stringResource(R.string.items_agent_locked_again),
            modifier = Modifier.testTag("agent_rule_locked_again"),
            actions = { TextButton(onClick = actions.onDismissLockedAgain) { Text(stringResource(R.string.items_ok)) } },
        )
    }
    state.windowNotClosed?.let { w ->
        val failure = state.windowCloseError?.let { failureText(it.messageRes(), it.vaultCode(null)) }
        NoticeCard(
            NoticeKind.WARNING,
            stringResource(R.string.items_agent_window_title),
            (
                w.ttlSeconds?.let {
                    // Whole minutes, rounded up ("1 minute" up to 60 s).
                    val minutes = ((it + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).coerceAtLeast(1).toInt()
                    pluralStringResource(R.plurals.items_agent_window_open, minutes, minutes)
                } ?: stringResource(R.string.items_agent_window_plain)
                ) + (failure?.let { "\n$it" } ?: ""),
            modifier = Modifier.testTag("agent_rule_window_not_closed"),
            actions = {
                TextButton(
                    onClick = actions.onCloseWindow,
                    enabled = !state.windowClosing,
                    modifier = Modifier.testTag("agent_rule_window_close"),
                ) { Text(stringResource(R.string.items_agent_window_close)) }
            },
        )
    }
}

private const val SECONDS_PER_MINUTE = 60L
private const val TICK_MS = 1_000L

/** The unlock window prompt (ANDROID-PLAN §4), asked for every save of an agent's rule (decision 4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPasswordSheet(state: RuleEditUiState, name: String, actions: RuleEditActions, start: Instant = Instant.now()) {
    // A clock that ticks while a backoff runs, so Confirm enables when it ends.
    var now by remember { mutableStateOf(start) }
    LaunchedEffect(state.retryUntil) {
        val until = state.retryUntil ?: return@LaunchedEffect
        while (now.isBefore(until)) {
            delay(TICK_MS)
            now = Instant.now()
        }
    }
    val waiting = state.retryUntil?.isAfter(now) == true
    val sending = state.prompt == RulePrompt.SENDING
    ModalBottomSheet(onDismissRequest = actions.onDismissPrompt, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(bottom = Spacing.xl).testTag("agent_rule_password_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                stringResource(R.string.items_agent_password_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.items_agent_password_body, name), style = MaterialTheme.typography.bodyMedium)
            val e = state.passwordError
            val alarm = e == FailureKind.CREDENTIAL_FROZEN || e == FailureKind.ROTATION_REQUIRED
            SecretField(
                value = state.password,
                onValueChange = actions.onPassword,
                label = stringResource(R.string.items_agent_password_label),
                error = e?.let {
                    if (alarm) {
                        stringResource(R.string.items_agent_alarm)
                    } else {
                        failureText(it.messageRes(), it.vaultCode(state.passwordErrorCode))
                    }
                },
                onImeAction = actions.onSubmitPassword,
                modifier = Modifier.fillMaxWidth().testTag("agent_rule_password"),
            )
            if (alarm) {
                TextButton(onClick = actions.onOpenAlarm, modifier = Modifier.testTag("agent_rule_alarm")) {
                    Text(stringResource(R.string.items_agent_alarm_open))
                }
            }
            PrimaryButton(
                stringResource(R.string.items_agent_password_confirm),
                actions.onSubmitPassword,
                enabled = state.password.isNotEmpty() && !waiting && !sending,
                busy = sending,
                modifier = Modifier.testTag("agent_rule_password_confirm"),
            )
            SecondaryButton(stringResource(R.string.items_agent_rule_cancel), actions.onDismissPrompt, enabled = !sending)
        }
    }
}

/** The status lifetime plus one minute, in words (§10.11: a removed rule's last acceptance). */
@Composable
internal fun statusBoundText(statusTtl: Int?): String = durationText((statusTtl ?: RuleDraft.DEFAULT_STATUS_TTL) + 60L)
