package com.vettid.feature.items

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.RuleOverlaps
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.TagMatch
import com.vettid.core.data.vault.VaultLimit
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.TagLabel
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun RuleEditRouteContent(host: ItemsHost) {
    val vm: RuleEditViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) host.onBack() }
    RuleEditScreen(
        state,
        RuleEditActions(
            onBack = host.onBack, onTag = vm::toggleTag, onMatch = vm::setMatch, onMode = vm::setMode,
            onIncludeExisting = vm::setIncludeExisting, onUses = vm::setUses, onExpiry = { vm.setExpiry(it) },
            onEndDate = vm::pickEndDate, onEndTime = { h, m -> vm.pickEndTime(h, m) }, onCancelEnd = vm::cancelEndPicker,
            onSave = vm::save, onAskDelete = vm::askDelete, onDelete = vm::delete, onDismissError = vm::dismissError,
        ),
    )
}

/** What the rule editor can ask for. */
data class RuleEditActions(
    val onBack: () -> Unit = {},
    val onTag: (String) -> Unit = {},
    val onMatch: (TagMatch) -> Unit = {},
    val onMode: (ShareMode) -> Unit = {},
    val onIncludeExisting: (Boolean) -> Unit = {},
    val onUses: (String) -> Unit = {},
    val onExpiry: (RuleExpiry) -> Unit = {},
    /** The custom end's day (UTC midnight millis, as the date picker gives it). */
    val onEndDate: (Long) -> Unit = {},
    /** The custom end's time, hour and minute in the member's zone. */
    val onEndTime: (Int, Int) -> Unit = { _, _ -> },
    val onCancelEnd: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onAskDelete: (Boolean) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * One share rule (§10.12): which tags, any or all of them, "Ask me each time" (the default) or "Share automatically",
 * fetches of each item, an end (presets, or "Custom…": a date and a time in the member's zone), and whether the items
 * that already match count. The connection's other rules covering the same tags or items are shown ("Also covered
 * by"), with how they combine; the vault's dry run lists what it matches before it is saved; critical items are never
 * readable, only usable.
 */
@Composable
fun RuleEditScreen(state: RuleEditUiState, actions: RuleEditActions, modifier: Modifier = Modifier) {
    val name = sharingNameOf(state.connectionName)
    FormScaffold(
        title = stringResource(if (state.isNew) R.string.items_rule_new_title else R.string.items_rule_edit_title),
        body = stringResource(R.string.items_rule_body, name),
        primaryLabel = stringResource(R.string.items_rule_save),
        onPrimary = actions.onSave,
        primaryEnabled = state.canSave,
        busy = state.busy,
        onBack = actions.onBack,
        secondaryLabel = if (state.isNew) null else stringResource(R.string.items_rule_delete),
        onSecondary = { actions.onAskDelete(true) },
        modifier = modifier.testTag("rule_edit"),
        header = { HeaderGlyph(Icons.Outlined.Share) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            SharingErrorNotice(state.error, state.limit, actions.onDismissError)
            if (state.atRuleLimit) RuleLimitNotice()
            TagsSection(state, actions)
            ModeSection(state.draft.mode, actions.onMode)
            LimitsSection(state, actions.onUses)
            EndsSection(state, actions.onExpiry)
            ExistingSwitch(state.draft.includeExisting, actions.onIncludeExisting)
            if (state.overlaps.isNotEmpty()) OverlapCard(state)
            Preview(state)
        }
    }
    RuleEditDialogs(state, name, actions)
}

/** "This connection has the most share rules it can have (64)": the named limit (§10.12 `share_rules_subject`). */
@Composable
internal fun RuleLimitNotice(modifier: Modifier = Modifier) {
    NoticeCard(
        NoticeKind.INFO,
        stringResource(R.string.items_rule_limit_title),
        ItemsText.limit(VaultLimit(RULE_LIMIT_NAME, RuleDraft.MAX_RULES_PER_SUBJECT.toLong())),
        modifier = modifier.testTag("rule_limit"),
    )
}

internal const val RULE_LIMIT_NAME = "share_rules_subject"

@Composable
private fun TagsSection(state: RuleEditUiState, actions: RuleEditActions) {
    val d = state.draft
    Text(stringResource(R.string.items_rule_tags), style = MaterialTheme.typography.titleSmall)
    if (state.tags.isEmpty() && !state.loading) {
        Text(stringResource(R.string.items_rule_no_tags), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("rule_tags")) {
        state.tags.forEach { t ->
            FilterChip(
                selected = t in d.tags,
                onClick = { actions.onTag(t) },
                label = { Text(t) },
                modifier = Modifier.testTag("rule_tag_$t"),
            )
        }
    }
    if (d.tags.size > 1) {
        Choice(R.string.items_rule_match_any, d.match == TagMatch.ANY, "rule_match_any") { actions.onMatch(TagMatch.ANY) }
        Choice(R.string.items_rule_match_all, d.match == TagMatch.ALL, "rule_match_all") { actions.onMatch(TagMatch.ALL) }
    }
}

@Composable
private fun ModeSection(mode: ShareMode, onMode: (ShareMode) -> Unit) {
    Text(stringResource(R.string.items_rule_mode), style = MaterialTheme.typography.titleSmall)
    Choice(R.string.items_rule_mode_ask, mode == ShareMode.ASK, "rule_mode_ask", R.string.items_rule_mode_ask_note) {
        onMode(ShareMode.ASK)
    }
    Choice(R.string.items_rule_mode_auto, mode == ShareMode.AUTO, "rule_mode_auto", R.string.items_rule_mode_auto_note) {
        onMode(ShareMode.AUTO)
    }
}

/** §10.12 `uses` (1–10,000) for a connection rule; `per_hour` and `per_day` are for agent rules only. */
@Composable
private fun LimitsSection(state: RuleEditUiState, onUses: (String) -> Unit) {
    Text(stringResource(R.string.items_rule_limits), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = state.usesText,
        onValueChange = onUses,
        label = { Text(stringResource(R.string.items_rule_uses)) },
        supportingText = { Text(stringResource(if (state.usesValid) R.string.items_rule_uses_note else R.string.items_rule_uses_bad)) },
        isError = !state.usesValid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("rule_uses"),
    )
}

@Composable
private fun EndsSection(state: RuleEditUiState, onExpiry: (RuleExpiry) -> Unit) {
    Text(stringResource(R.string.items_rule_expiry), style = MaterialTheme.typography.titleSmall)
    val choices = buildList {
        if (state.expiry == RuleExpiry.KEEP) add(RuleExpiry.KEEP)
        addAll(RuleExpiry.PRESETS)
        add(RuleExpiry.CUSTOM)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("rule_ends")) {
        choices.forEach { e ->
            FilterChip(
                selected = state.expiry == e,
                onClick = { onExpiry(e) },
                label = { Text(expiryLabel(e, state.draft.expiresAt)) },
                modifier = Modifier.testTag("rule_end_${e.name.lowercase()}"),
            )
        }
    }
    val at = state.draft.expiresAt
    Text(
        if (at == null) stringResource(R.string.items_rule_ends_never) else stringResource(R.string.items_rule_ends_at, Times.full(at)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("rule_ends_at"),
    )
    if (state.endInvalid) {
        Text(
            stringResource(R.string.items_rule_end_bad),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("rule_end_bad"),
        )
    }
}

@Composable
private fun ExistingSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.items_rule_existing), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.items_rule_existing_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.testTag("rule_existing"))
    }
}

@Composable
private fun expiryLabel(e: RuleExpiry, at: Instant?): String = when (e) {
    RuleExpiry.NEVER -> stringResource(R.string.items_rule_expiry_never)
    RuleExpiry.DAY -> stringResource(R.string.items_rule_expiry_day)
    RuleExpiry.WEEK -> stringResource(R.string.items_rule_expiry_week)
    RuleExpiry.MONTH -> stringResource(R.string.items_rule_expiry_month)
    RuleExpiry.THREE_MONTHS -> stringResource(R.string.items_rule_expiry_three_months)
    RuleExpiry.YEAR -> stringResource(R.string.items_rule_expiry_year)
    RuleExpiry.CUSTOM -> stringResource(R.string.items_rule_expiry_custom)
    RuleExpiry.KEEP -> at?.let { stringResource(R.string.items_rule_until, Times.dayLabel(Times.day(it))) }
        ?: stringResource(R.string.items_rule_expiry_never)
}

/**
 * "Also covered by": the connection's other rules naming one of these tags or holding one of the matched items. The
 * vault keeps no precedence between them (§10.12): each rule asks about and shares items on its own, so an item is
 * readable while any rule shares it.
 */
@Composable
private fun OverlapCard(state: RuleEditUiState) {
    val matched = state.preview?.matches?.map { it.itemId }.orEmpty()
    DetailCard(Modifier.testTag("rule_overlaps")) {
        Text(stringResource(R.string.items_rule_overlap_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.xs))
        state.overlaps.forEach { o ->
            Column(Modifier.padding(vertical = Spacing.xs).semantics(mergeDescendants = true) {}.testTag("rule_overlap_${o.ruleId}")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) { o.tags.forEach { TagLabel(it) } }
                val common = RuleOverlaps.sharedTags(state.draft.tags, o)
                val items = RuleOverlaps.sharedItems(matched, o).size
                Text(
                    listOfNotNull(
                        ruleModeText(o.mode),
                        common.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.items_rule_overlap_tags, it.joinToString(", ")) },
                        items.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.items_rule_overlap_items, it, it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            stringResource(R.string.items_rule_overlap_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Choice(label: Int, selected: Boolean, tag: String, note: Int? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.padding(start = Spacing.m))
        Column {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            note?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The dry run (§10.12): how many items the rule matches and which; critical ones are only usable. */
@Composable
private fun Preview(state: RuleEditUiState) {
    val p = state.preview
    DetailCard(Modifier.testTag("rule_preview")) {
        Text(stringResource(R.string.items_rule_preview), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.xs))
        when {
            state.previewing -> CircularProgressIndicator(Modifier.heightIn(max = 24.dp), color = MaterialTheme.colorScheme.primary)
            p == null -> Text(stringResource(R.string.items_rule_preview_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val mode = state.draft.mode
                Text(
                    pluralStringResource(
                        if (mode == ShareMode.ASK) R.plurals.items_rule_preview_ask else R.plurals.items_rule_preview_auto,
                        p.total,
                        p.total,
                    ),
                    modifier = Modifier.testTag("rule_preview_total"),
                )
                if (!state.draft.includeExisting && p.total > 0) {
                    Text(stringResource(R.string.items_rule_preview_later), style = MaterialTheme.typography.bodySmall)
                }
                val others = state.overlaps
                p.matches.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val label = if (m.sensitivity == Sensitivity.CRITICAL) {
                        stringResource(R.string.items_rule_preview_usable, m.name)
                    } else {
                        m.name
                    }
                    val covered = others.any { o -> m.itemId in o.included || m.itemId in o.pending }
                    val row = Modifier.padding(vertical = Spacing.xs).semantics(mergeDescendants = true) {}
                    Column(row.testTag("rule_match_${m.itemId}")) {
                        Text(label)
                        if (covered) {
                            Text(
                                stringResource(R.string.items_rule_preview_also),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (p.total > p.matches.size) Text(stringResource(R.string.items_rule_preview_more, p.total - p.matches.size))
            }
        }
    }
}

@Composable
private fun RuleEditDialogs(state: RuleEditUiState, name: String, actions: RuleEditActions) {
    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.items_rule_delete_title),
            text = stringResource(R.string.items_rule_delete_body, name),
            confirmLabel = stringResource(R.string.items_rule_delete),
            onConfirm = actions.onDelete,
            onDismiss = { actions.onAskDelete(false) },
            destructive = true,
        )
    }
    when (state.endPicker) {
        EndPicker.Date -> EndDateDialog(state.draft.expiresAt, actions)
        is EndPicker.Time -> EndTimeDialog(state.draft.expiresAt, actions)
        null -> Unit
    }
}

/** The custom end's day: the Material3 calendar, today up to 3,650 days ahead (§10.12) in the member's zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndDateDialog(current: Instant?, actions: RuleEditActions) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { Instant.now() }
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = (current?.takeIf { it.isAfter(now) } ?: now).let { RuleEnds.pickerDay(it, zone) },
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = RuleEnds.selectable(utcTimeMillis, now, zone)

            override fun isSelectableYear(year: Int): Boolean =
                year in now.atZone(zone).year..now.plus(java.time.Duration.ofDays(RuleDraft.MAX_EXPIRY_DAYS)).atZone(zone).year
        },
    )
    DatePickerDialog(
        onDismissRequest = actions.onCancelEnd,
        confirmButton = {
            TextButton(
                onClick = { picker.selectedDateMillis?.let(actions.onEndDate) },
                enabled = picker.selectedDateMillis != null,
                modifier = Modifier.testTag("rule_end_date_ok"),
            ) { Text(stringResource(R.string.items_rule_end_next)) }
        },
        dismissButton = { TextButton(onClick = actions.onCancelEnd) { Text(stringResource(R.string.items_cancel)) } },
    ) {
        DatePicker(state = picker, modifier = Modifier.testTag("rule_end_date"))
    }
}

/** The custom end's time on the picked day, in the member's zone (12- or 24-hour as the phone shows time). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndTimeDialog(current: Instant?, actions: RuleEditActions) {
    val context = LocalContext.current
    val initial = current?.atZone(ZoneId.systemDefault())
    val picker = rememberTimePickerState(
        initialHour = initial?.hour ?: DEFAULT_END_HOUR,
        initialMinute = initial?.minute ?: DEFAULT_END_MINUTE,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = actions.onCancelEnd,
        title = { Text(stringResource(R.string.items_rule_end_time)) },
        text = { TimePicker(state = picker, modifier = Modifier.testTag("rule_end_time")) },
        confirmButton = {
            TextButton(onClick = { actions.onEndTime(picker.hour, picker.minute) }, modifier = Modifier.testTag("rule_end_time_ok")) {
                Text(stringResource(R.string.items_ok))
            }
        },
        dismissButton = { TextButton(onClick = actions.onCancelEnd) { Text(stringResource(R.string.items_cancel)) } },
    )
}

/** The time a custom end starts at: the end of the picked day. */
private const val DEFAULT_END_HOUR = 23
private const val DEFAULT_END_MINUTE = 59
