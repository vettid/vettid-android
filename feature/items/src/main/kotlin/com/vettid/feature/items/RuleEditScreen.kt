package com.vettid.feature.items

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import com.vettid.core.data.items.RuleMatch
import com.vettid.core.data.items.RulePreview
import com.vettid.core.ui.components.TagChip
import com.vettid.core.ui.components.TopBarTextAction
import com.vettid.core.ui.theme.VettIdShape
import com.vettid.core.ui.theme.tagColor
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
            onBack = host.onBack, onTag = vm::selectTag, onMode = vm::setMode,
            onIncludeExisting = vm::setIncludeExisting, onUses = vm::setUses, onPerHour = vm::setPerHour, onPerDay = vm::setPerDay,
            onExpiry = { vm.setExpiry(it) }, onEndDate = vm::pickEndDate, onEndTime = { h, m -> vm.pickEndTime(h, m) },
            onCancelEnd = vm::cancelEndPicker, onSave = vm::save, onAskDelete = vm::askDelete, onDelete = vm::delete,
            onDismissError = vm::dismissError,
            onOpenRule = { host.navigate(RuleEditRoute(state.draft.connectionId, it)) },
            onAskDiscard = { vm.askDiscard(true) },
            onDiscard = {
                vm.askDiscard(false)
                host.onBack()
            },
            onKeepEditing = { vm.askDiscard(false) },
        ),
    )
}

/** What the rule editor can ask for. */
data class RuleEditActions(
    val onBack: () -> Unit = {},
    /** Chooses the rule's one tag (owner decision 2026-10-09). */
    val onTag: (String) -> Unit = {},
    val onMode: (ShareMode) -> Unit = {},
    val onIncludeExisting: (Boolean) -> Unit = {},
    val onUses: (String) -> Unit = {},
    /** 0.23.0 §10.12: fetches per hour and per day of all the rule's items together. */
    val onPerHour: (String) -> Unit = {},
    val onPerDay: (String) -> Unit = {},
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
    /** "Already shared — edit its rule": the rule of this connection that shares that tag. */
    val onOpenRule: (String) -> Unit = {},
    /** Back or up with changes: "Discard changes to this rule?". */
    val onAskDiscard: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onKeepEditing: () -> Unit = {},
)

/**
 * One share rule (§10.12): the one tag it shares (owner decision 2026-10-09; the chosen chip in its colour with a gold
 * outline and a check mark, a tag another rule already shares opens that rule), "Ask me each time" (the default) or
 * "Share automatically", fetches of each item and (0.23.0) per hour and per day of all its items, an end (presets, or
 * "Custom…": a date and a time in the member's zone), and whether the items that already match count. The
 * connection's other rules covering the same items are named by their tags, with the 0.23.0 rule: asking wins. The
 * vault's dry run lists what it matches and what saving does to each item. "Save rule" is in the top bar (as the item
 * editor's "Save item"); leaving with changes asks first. A rule that names several tags is shown read-only and can be
 * deleted.
 */
@Composable
fun RuleEditScreen(state: RuleEditUiState, actions: RuleEditActions, modifier: Modifier = Modifier) {
    val name = sharingNameOf(state.connectionName)
    val leave = { if (state.dirty) actions.onAskDiscard() else actions.onBack() }
    BackHandler(enabled = state.dirty && !state.confirmDiscard) { actions.onAskDiscard() }
    FormScaffold(
        title = stringResource(if (state.isNew) R.string.items_rule_new_title else R.string.items_rule_edit_title),
        body = stringResource(R.string.items_rule_body, name),
        primaryLabel = null,
        onPrimary = {},
        onBack = leave,
        modifier = modifier.testTag("rule_edit"),
        header = { HeaderGlyph(Icons.Outlined.Share) },
        topActions = {
            if (!state.readOnly) {
                TopBarTextAction(
                    label = stringResource(R.string.items_rule_save),
                    onClick = actions.onSave,
                    enabled = state.canSave,
                    busy = state.busy,
                    modifier = Modifier.testTag("rule_save"),
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            SharingErrorNotice(state.error, state.limit, actions.onDismissError)
            if (state.atRuleLimit) RuleLimitNotice()
            if (state.readOnly) {
                ReadOnlyRule(state)
            } else {
                TagsSection(state, name, actions)
                ModeSection(state.draft.mode, actions.onMode)
                LimitsSection(state, actions)
                EndsSection(state, actions.onExpiry)
                ExistingSwitch(state.draft.includeExisting, actions.onIncludeExisting)
            }
            if (state.overlaps.isNotEmpty()) OverlapCard(state)
            Preview(state)
            if (!state.isNew) {
                TextButton(
                    onClick = { actions.onAskDelete(true) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).testTag("rule_delete"),
                ) { Text(stringResource(R.string.items_rule_delete), color = MaterialTheme.colorScheme.error) }
            }
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

/** "Select the tag to share": one tag per rule; tags another rule of this connection shares open that rule. */
@Composable
private fun TagsSection(state: RuleEditUiState, name: String, actions: RuleEditActions) {
    Text(stringResource(R.string.items_rule_tag_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.items_rule_tag_hint, name),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.tags.isEmpty() && !state.loading) {
        Text(stringResource(R.string.items_rule_no_tags), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier.selectableGroup().testTag("rule_tags"),
    ) {
        state.freeTags.forEach { t -> TagChoice(t, selected = t == state.tag) { actions.onTag(t) } }
    }
    val taken = state.taken.filterKeys { it in state.tags }
    if (taken.isNotEmpty()) {
        Text(stringResource(R.string.items_rule_tag_taken_title), style = MaterialTheme.typography.labelLarge)
        taken.forEach { (t, ruleId) ->
            val cd = stringResource(R.string.items_rule_tag_taken_cd, t)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchTarget)
                    .clickable(role = Role.Button) { actions.onOpenRule(ruleId) }
                    .semantics(mergeDescendants = true) { contentDescription = cd }
                    .testTag("rule_tag_taken_$t"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagChip(t)
                Spacer(Modifier.width(Spacing.m))
                Text(
                    stringResource(R.string.items_rule_tag_taken),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * A tag to choose: a pill in the tag's own colour ([tagColor]); chosen, it gets a gold outline and a check mark
 * (owner request 2026-10-09). A radio choice for accessibility, at least 48dp tall to touch.
 */
@Composable
internal fun TagChoice(tag: String, selected: Boolean, onClick: () -> Unit) {
    val c = tagColor(tag)
    Box(
        Modifier
            .heightIn(min = Spacing.touchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("rule_tag_$tag"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(VettIdShape.pill)
                .background(c.container)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, VettIdShape.pill) else Modifier)
                .padding(horizontal = Spacing.m, vertical = Spacing.xs + Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = c.onContainer,
                    modifier = Modifier.size(16.dp).testTag("rule_tag_check_$tag"),
                )
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(tag, style = MaterialTheme.typography.labelLarge, color = c.onContainer)
        }
    }
}

/** A rule that names several tags (before one tag per rule): what it is, and that it can only be deleted. */
@Composable
private fun ReadOnlyRule(state: RuleEditUiState) {
    NoticeCard(
        NoticeKind.INFO,
        stringResource(R.string.items_rule_multi_title),
        stringResource(R.string.items_rule_multi_body),
        modifier = Modifier.testTag("rule_read_only"),
    )
    val r = state.rules.firstOrNull { it.ruleId == state.draft.ruleId }
    DetailCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            state.draft.tags.forEach { TagChip(it) }
        }
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(
                if (state.draft.match == TagMatch.ALL) R.string.items_rule_match_all_short else R.string.items_rule_match_any_short,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(ruleModeText(state.draft.mode), style = MaterialTheme.typography.bodyMedium)
        if (r != null) Text(ruleLimitsText(r), style = MaterialTheme.typography.bodySmall)
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

/**
 * §10.12 `uses` (1–10,000, fetches of each item) and, since 0.23.0, `per_hour` (1–3,600) and `per_day` (1–86,400) for
 * a connection rule: the connection's fetches of all the rule's items together; each optional.
 */
@Composable
private fun LimitsSection(state: RuleEditUiState, actions: RuleEditActions) {
    Text(stringResource(R.string.items_rule_limits), style = MaterialTheme.typography.titleSmall)
    NumberField(
        state.usesText, actions.onUses, R.string.items_rule_uses, state.usesValid,
        R.string.items_rule_uses_note, R.string.items_rule_uses_bad, "rule_uses",
    )
    NumberField(
        state.perHourText, actions.onPerHour, R.string.items_rule_per_hour, state.perHourValid,
        R.string.items_rule_per_note, R.string.items_rule_per_hour_bad, "rule_per_hour",
    )
    NumberField(
        state.perDayText, actions.onPerDay, R.string.items_rule_per_day, state.perDayValid,
        R.string.items_rule_per_note, R.string.items_rule_per_day_bad, "rule_per_day",
    )
}

@Composable
@Suppress("LongParameterList")
private fun NumberField(value: String, onChange: (String) -> Unit, label: Int, valid: Boolean, note: Int, bad: Int, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(if (valid) note else bad)) },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
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
 * The connection's other rules covering the same tags or matched items, named by their tags (§10.12, owner's review
 * of #181), with the 0.23.0 rule: asking wins (an item any of them asks about is shared only after the member
 * approves it), one answer counts for all of them, and the strictest fetch limits apply.
 */
@Composable
private fun OverlapCard(state: RuleEditUiState) {
    val matched = state.preview?.matches?.map { it.itemId }.orEmpty()
    DetailCard(Modifier.testTag("rule_overlaps")) {
        Text(stringResource(R.string.items_rule_overlap_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.xs))
        state.overlaps.forEach { o ->
            Column(Modifier.padding(vertical = Spacing.xs).semantics(mergeDescendants = true) {}.testTag("rule_overlap_${o.ruleId}")) {
                Text(ruleName(o, state.names), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
            modifier = Modifier.testTag("rule_overlap_note"),
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

/**
 * The dry run (§10.12): how many items the rule matches and which; critical ones are only usable. Since 0.23.0 each
 * item says what saving does to it (`outcome`): shared at once, or asked, and why ("Asks you first because your
 * “medical” rule covers it", `ask_rule_id`); an item whose state does not change says what it is now.
 */
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
                Text(previewTotal(p, state.draft.mode), modifier = Modifier.testTag("rule_preview_total"))
                if (!state.draft.includeExisting && p.total > 0) {
                    Text(stringResource(R.string.items_rule_preview_later), style = MaterialTheme.typography.bodySmall)
                }
                p.matches.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val label = if (m.sensitivity == Sensitivity.CRITICAL) {
                        stringResource(R.string.items_rule_preview_usable, m.name)
                    } else {
                        m.name
                    }
                    val row = Modifier.padding(vertical = Spacing.xs).semantics(mergeDescendants = true) {}
                    Column(row.testTag("rule_match_${m.itemId}")) {
                        Text(label)
                        matchNote(m, state)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("rule_match_note_${m.itemId}"),
                            )
                        }
                    }
                }
                if (p.total > p.matches.size) Text(stringResource(R.string.items_rule_preview_more, p.total - p.matches.size))
            }
        }
    }
}

/** "3 items match: 1 shared at once, 2 ask you first." (0.23.0 `outcome`s), or as before from the rule's mode. */
@Composable
private fun previewTotal(p: RulePreview, mode: ShareMode): String {
    val include = p.matches.count { it.outcome == RuleMatch.OUTCOME_INCLUDE }
    val ask = p.matches.count { it.outcome == RuleMatch.OUTCOME_ASK }
    if (include + ask == 0) {
        val res = if (mode == ShareMode.ASK) R.plurals.items_rule_preview_ask else R.plurals.items_rule_preview_auto
        return pluralStringResource(res, p.total, p.total)
    }
    val parts = listOfNotNull(
        include.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.items_rule_preview_outcome_include, it, it) },
        ask.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.items_rule_preview_outcome_ask, it, it) },
    ).joinToString(", ")
    val total = pluralStringResource(R.plurals.items_rule_preview_match, p.total, p.total)
    return stringResource(R.string.items_rule_preview_outcomes, total, parts)
}

/** What saving does to one matched item (0.23.0), or what it is now in this rule. */
@Composable
private fun matchNote(m: RuleMatch, state: RuleEditUiState): String? {
    val asker = m.askRuleId?.let { id -> state.rules.firstOrNull { it.ruleId == id } }
    return when {
        m.outcome == RuleMatch.OUTCOME_ASK && asker != null ->
            stringResource(R.string.items_rule_outcome_ask_because, ruleName(asker, state.names))
        m.outcome == RuleMatch.OUTCOME_ASK -> stringResource(R.string.items_rule_outcome_ask)
        m.outcome == RuleMatch.OUTCOME_INCLUDE -> stringResource(R.string.items_rule_outcome_include)
        m.state == "included" -> stringResource(R.string.items_rule_state_included)
        m.state == "pending" -> stringResource(R.string.items_rule_state_pending)
        m.state == "declined" -> stringResource(R.string.items_rule_state_declined)
        else -> null
    }
}

@Composable
private fun RuleEditDialogs(state: RuleEditUiState, name: String, actions: RuleEditActions) {
    if (state.confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.items_rule_discard_title),
            text = stringResource(R.string.items_discard_body),
            confirmLabel = stringResource(R.string.items_discard),
            onConfirm = actions.onDiscard,
            onDismiss = actions.onKeepEditing,
            destructive = true,
            dismissLabel = stringResource(R.string.items_keep_editing),
            modifier = Modifier.testTag("rule_edit_discard"),
        )
    }
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
