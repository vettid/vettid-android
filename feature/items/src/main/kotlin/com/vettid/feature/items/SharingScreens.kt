package com.vettid.feature.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharedContent
import com.vettid.core.data.items.TagMatch
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.components.TagLabel
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import kotlinx.serialization.Serializable

/** What one connection can see of the member's vault (its share rules and grants). */
@Serializable
data class ConnectionSharingRoute(val connectionId: String) {
    companion object {
        const val ARG = "connectionId"
    }
}

/** A share rule for a connection; [ruleId] null for a new one. */
@Serializable
data class RuleEditRoute(val connectionId: String, val ruleId: String? = null) {
    companion object {
        const val ARG_CONNECTION = "connectionId"
        const val ARG_RULE = "ruleId"
    }
}

/** What a connection shares with the member. */
@Serializable
data class SharedWithYouRoute(val connectionId: String) {
    companion object {
        const val ARG = "connectionId"
    }
}

/** The connection's name inside a sentence (bidi-isolated, §10.8), or a neutral placeholder. */
@Composable
private fun nameOf(n: String?): String = n?.let { AccountNames.isolate(it) } ?: stringResource(R.string.items_sharing_this_connection)

@Composable
private fun Section(text: String) = SettingsSectionHeader(text)

@Composable
private fun ErrorNotice(e: FailureKind?, limit: com.vettid.core.data.vault.VaultLimit?, onDismiss: () -> Unit) {
    e ?: return
    NoticeCard(
        NoticeKind.WARNING,
        stringResource(R.string.items_error_item),
        ItemsText.failure(e, limit = limit),
        modifier = Modifier.padding(horizontal = Spacing.s).testTag("sharing_error"),
        actions = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.items_ok)) } },
    )
}

@Composable
internal fun ConnectionSharingRouteContent(host: ItemsHost) {
    val vm: ConnectionSharingViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    ConnectionSharingScreen(
        state,
        ConnectionSharingActions(
            onBack = host.onBack,
            onNewRule = { host.navigate(RuleEditRoute(state.connectionId)) },
            onOpenRule = { host.navigate(RuleEditRoute(state.connectionId, it)) },
            onOpenItem = { host.navigate(ItemDetailRoute(it)) },
            onAskRevoke = vm::askRevoke,
            onRevoke = vm::revoke,
            onDismissError = vm::dismissError,
        ),
    )
}

/** What a connection's sharing screen can ask for. */
data class ConnectionSharingActions(
    val onBack: () -> Unit = {},
    val onNewRule: () -> Unit = {},
    val onOpenRule: (String) -> Unit = {},
    val onOpenItem: (String) -> Unit = {},
    val onAskRevoke: (GrantView?) -> Unit = {},
    val onRevoke: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * "What <name> can see" (VAULT-ITEMS §6): the share rules by tag with their mode, then what the connection can fetch
 * now (each grant, from a rule or a one-off request, revocable) and the critical items it can only ask to use.
 */
@Composable
fun ConnectionSharingScreen(state: ConnectionSharingUiState, actions: ConnectionSharingActions, modifier: Modifier = Modifier) {
    val name = nameOf(state.connectionName)
    DetailScaffold(onBackClick = actions.onBack, modifier = modifier.testTag("connection_sharing")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
            LargeTitle(stringResource(R.string.items_sharing_title, name))
            Text(
                stringResource(R.string.items_sharing_body, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
            ErrorNotice(state.error, state.limit, actions.onDismissError)
            if (state.loading && state.rules.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            Section(stringResource(R.string.items_sharing_rules))
            if (!state.loading && state.rules.isEmpty()) {
                Text(
                    stringResource(R.string.items_sharing_no_rules, name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("sharing_no_rules"),
                )
            }
            state.rules.forEach { r -> RuleCard(r, actions.onOpenRule) }
            SecondaryButton(
                stringResource(R.string.items_sharing_new_rule),
                actions.onNewRule,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("sharing_new_rule"),
            )
            if (state.pendingCount > 0) {
                NoticeCard(
                    NoticeKind.INFO,
                    stringResource(R.string.items_sharing_pending_title),
                    pluralStringResource(R.plurals.items_sharing_pending_body, state.pendingCount, state.pendingCount),
                    modifier = Modifier.padding(horizontal = Spacing.s),
                )
            }
            Section(stringResource(R.string.items_sharing_can_see, name))
            if (state.active.isEmpty() && state.usable.isEmpty()) {
                Text(
                    stringResource(R.string.items_sharing_nothing, name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("sharing_nothing"),
                )
            }
            state.active.forEach { g -> GrantRow(g, actions) }
            state.usable.forEach { id ->
                val i = state.items[id]
                Text(
                    stringResource(R.string.items_sharing_usable, i?.name ?: stringResource(R.string.items_deleted_item)),
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
                )
            }
        }
    }
    state.confirmRevoke?.let { g ->
        ConfirmDialog(
            title = stringResource(R.string.items_sharing_revoke_title, g.name),
            text = stringResource(
                if (g.ruleId != null) R.string.items_sharing_revoke_body_rule else R.string.items_sharing_revoke_body,
                name,
            ),
            confirmLabel = stringResource(R.string.items_sharing_revoke),
            onConfirm = actions.onRevoke,
            onDismiss = { actions.onAskRevoke(null) },
            destructive = true,
        )
    }
}

@Composable
private fun RuleCard(r: ShareRule, onOpen: (String) -> Unit) {
    DetailCard(Modifier.padding(vertical = Spacing.xs).testTag("rule_${r.ruleId}")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) { r.tags.forEach { TagLabel(it) } }
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(if (r.match == TagMatch.ALL) R.string.items_rule_match_all_short else R.string.items_rule_match_any_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(if (r.mode == ShareMode.ASK) R.string.items_rule_mode_ask else R.string.items_rule_mode_auto),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            listOf(
                pluralStringResource(R.plurals.items_rule_included, r.included.size, r.included.size),
                pluralStringResource(R.plurals.items_rule_pending, r.pending.size, r.pending.size),
                pluralStringResource(R.plurals.items_rule_declined, r.declined.size, r.declined.size),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        val limits = listOfNotNull(
            r.uses?.let { pluralStringResource(R.plurals.items_rule_uses, it, it) },
            r.expiresAt?.let { stringResource(R.string.items_rule_until, Times.full(it)) },
        )
        if (limits.isNotEmpty()) Text(limits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onOpen(r.ruleId) }, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
            Text(stringResource(R.string.items_rule_change))
        }
    }
}

@Composable
private fun GrantRow(g: GrantView, actions: ConnectionSharingActions) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.s).testTag("grant_${g.grantId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(g.name.ifBlank { stringResource(R.string.items_deleted_item) }, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    stringResource(if (g.ruleId != null) R.string.items_grant_by_rule else R.string.items_grant_one_off),
                    g.usesLeft?.let { pluralStringResource(R.plurals.items_grant_uses_left, it, it) },
                    g.expiresAt?.let { stringResource(R.string.items_rule_until, Times.full(it)) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { actions.onAskRevoke(g) }) {
            Text(stringResource(R.string.items_sharing_revoke), color = MaterialTheme.colorScheme.error)
        }
    }
}

// --- the rule editor ---

@Composable
internal fun RuleEditRouteContent(host: ItemsHost) {
    val vm: RuleEditViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) host.onBack() }
    RuleEditScreen(
        state,
        RuleEditActions(
            onBack = host.onBack, onTag = vm::toggleTag, onMatch = vm::setMatch, onMode = vm::setMode,
            onIncludeExisting = vm::setIncludeExisting, onUses = vm::setUses, onExpiry = { vm.setExpiry(it) }, onSave = vm::save,
            onAskDelete = vm::askDelete, onDelete = vm::delete, onDismissError = vm::dismissError,
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
    val onSave: () -> Unit = {},
    val onAskDelete: (Boolean) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * A share rule (§10.12): which tags, any or all of them, ask or automatic (ask by default), counted uses, an end, and
 * whether the items that already match count. The vault's dry run lists what it matches before it is saved; critical
 * items are never readable, only usable.
 */
@Composable
@Suppress("CyclomaticComplexMethod")
fun RuleEditScreen(state: RuleEditUiState, actions: RuleEditActions, modifier: Modifier = Modifier) {
    val name = nameOf(state.connectionName)
    val d = state.draft
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
            ErrorNotice(state.error, state.limit, actions.onDismissError)
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
            Text(stringResource(R.string.items_rule_mode), style = MaterialTheme.typography.titleSmall)
            Choice(R.string.items_rule_mode_ask, d.mode == ShareMode.ASK, "rule_mode_ask", R.string.items_rule_mode_ask_note) {
                actions.onMode(ShareMode.ASK)
            }
            Choice(R.string.items_rule_mode_auto, d.mode == ShareMode.AUTO, "rule_mode_auto", R.string.items_rule_mode_auto_note) {
                actions.onMode(ShareMode.AUTO)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.items_rule_existing), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.items_rule_existing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = d.includeExisting,
                    onCheckedChange = actions.onIncludeExisting,
                    modifier = Modifier.testTag("rule_existing"),
                )
            }
            OutlinedTextField(
                value = state.usesText,
                onValueChange = actions.onUses,
                label = { Text(stringResource(R.string.items_rule_uses)) },
                supportingText = {
                    Text(stringResource(if (state.usesValid) R.string.items_rule_uses_note else R.string.items_rule_uses_bad))
                },
                isError = !state.usesValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("rule_uses"),
            )
            Text(stringResource(R.string.items_rule_expiry), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                RuleExpiry.entries.filter { it != RuleExpiry.KEEP || state.expiry == RuleExpiry.KEEP }.forEach { e ->
                    FilterChip(
                        selected = state.expiry == e,
                        onClick = { actions.onExpiry(e) },
                        label = { Text(expiryLabel(e, d.expiresAt)) },
                    )
                }
            }
            Preview(state)
        }
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
}

@Composable
private fun expiryLabel(e: RuleExpiry, at: java.time.Instant?): String = when (e) {
    RuleExpiry.NEVER -> stringResource(R.string.items_rule_expiry_never)
    RuleExpiry.MONTH -> stringResource(R.string.items_rule_expiry_month)
    RuleExpiry.YEAR -> stringResource(R.string.items_rule_expiry_year)
    RuleExpiry.KEEP -> at?.let { stringResource(R.string.items_rule_until, Times.full(it)) }
        ?: stringResource(R.string.items_rule_expiry_never)
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
                p.matches.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        if (m.sensitivity == Sensitivity.CRITICAL) stringResource(R.string.items_rule_preview_usable, m.name) else m.name,
                        modifier = Modifier.padding(vertical = Spacing.xs),
                    )
                }
                if (p.total > p.matches.size) Text(stringResource(R.string.items_rule_preview_more, p.total - p.matches.size))
            }
        }
    }
}

// --- shared with you ---

@Composable
internal fun SharedWithYouRouteContent(host: ItemsHost) {
    val vm: SharedWithYouViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.hide() }
    SharedWithYouScreen(
        state,
        SharedWithYouActions(
            onBack = host.onBack, onRetry = vm::load, onFetch = vm::fetch, onAskGiveUp = vm::askGiveUp, onGiveUp = vm::giveUp,
            onDismissError = vm::dismissError, onOpenAsk = vm::openAsk, onAsk = vm::setAsk, onSendAsk = vm::sendAsk,
        ),
    )
}

/** What the shared-with-you screen can ask for. */
data class SharedWithYouActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onFetch: (String) -> Unit = {},
    val onAskGiveUp: (GrantView?) -> Unit = {},
    val onGiveUp: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onOpenAsk: (Boolean) -> Unit = {},
    val onAsk: (GrantAskForm) -> Unit = {},
    val onSendAsk: () -> Unit = {},
)

/**
 * What a connection shares with the member (§10.12 received grants), read-only and labelled as shared by them: the
 * connection's self-asserted data, fetched on purpose and kept only while the screen is in front.
 */
@Composable
fun SharedWithYouScreen(state: SharedWithYouUiState, actions: SharedWithYouActions, modifier: Modifier = Modifier) {
    val name = nameOf(state.connectionName)
    DetailScaffold(onBackClick = actions.onBack, modifier = modifier.testTag("shared_with_you")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
            LargeTitle(stringResource(R.string.items_shared_title, name))
            Text(
                stringResource(R.string.items_shared_body, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
            ErrorNotice(state.error, state.limit, actions.onDismissError)
            Spacer(Modifier.height(Spacing.m))
            when {
                state.loading && state.received.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(Spacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                state.received.isEmpty() -> Text(
                    stringResource(R.string.items_shared_none, name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("shared_none"),
                )
            }
            state.received.forEach { g -> ReceivedCard(g, state, name, actions) }
            AskSection(state, name, actions)
        }
    }
    state.ask?.let { AskDialog(it, name, actions) }
    state.confirmGiveUp?.let { g ->
        ConfirmDialog(
            title = stringResource(R.string.items_shared_give_up_title, g.name),
            text = stringResource(R.string.items_shared_give_up_body, name),
            confirmLabel = stringResource(R.string.items_shared_give_up),
            onConfirm = actions.onGiveUp,
            onDismiss = { actions.onAskGiveUp(null) },
            destructive = true,
        )
    }
}

@Composable
private fun ReceivedCard(g: GrantView, state: SharedWithYouUiState, name: String, actions: SharedWithYouActions) {
    val content = state.opened[g.grantId]
    DetailCard(Modifier.padding(vertical = Spacing.xs).testTag("received_${g.grantId}")) {
        Text(content?.name ?: g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                ItemsText.category(content?.category ?: g.category),
                stringResource(R.string.items_shared_by, name),
                g.usesLeft?.let { pluralStringResource(R.plurals.items_grant_uses_left, it, it) },
                g.expiresAt?.let { stringResource(R.string.items_rule_until, Times.full(it)) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("received_label_${g.grantId}"),
        )
        // What the grant holds, as the connection's vault described it (`grant.list` labels, 0.21.0), before a fetch.
        if (content == null && g.labels.isNotEmpty()) {
            Text(
                stringResource(R.string.items_shared_holds, g.labels.joinToString(", ") { it.label }),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("received_fields_${g.grantId}"),
            )
        }
        state.refused[g.grantId]?.let { r ->
            Text(refusalText(r), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (content != null) {
            Spacer(Modifier.height(Spacing.s))
            SharedFields(content)
        } else if (g.active) {
            TextButton(
                onClick = { actions.onFetch(g.grantId) },
                enabled = state.fetching == null,
                modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("fetch_${g.grantId}"),
            ) { Text(stringResource(if (state.fetching == g.grantId) R.string.items_shared_fetching else R.string.items_shared_fetch)) }
        } else {
            Text(stringResource(R.string.items_shared_ended), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (g.active) {
            TextButton(onClick = { actions.onAskGiveUp(g) }) {
                Text(stringResource(R.string.items_shared_give_up), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** What the member asked this connection for (§10.12), and "Ask for something". */
@Composable
private fun AskSection(state: SharedWithYouUiState, name: String, actions: SharedWithYouActions) {
    Section(stringResource(R.string.items_ask_title))
    if (state.asked) {
        Text(
            stringResource(R.string.items_ask_sent, name),
            modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("ask_sent"),
        )
    }
    state.requested.forEach { r ->
        Text(
            stringResource(R.string.items_ask_row, r.entries.map { askEntry(it) }.joinToString(", ").ifEmpty { "?" }, askState(r.state)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
        )
    }
    SecondaryButton(
        stringResource(R.string.items_ask_button, name),
        { actions.onOpenAsk(true) },
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("ask_open"),
    )
}

/** An entry as the member asked for it (§10.12 `requested`, 0.21.0: exactly as sent): their own words, else the category. */
@Composable
private fun askEntry(e: com.vettid.core.data.items.GrantAskEntry): String = e.label ?: when (e.kind) {
    "category" -> stringResource(R.string.items_ask_entry_category, ItemsText.category(e.ref))
    else -> stringResource(R.string.items_ask_entry_item)
}

@Composable
private fun askState(s: String): String = when (s) {
    "granted", "approved" -> stringResource(R.string.items_ask_state_granted)
    "denied" -> stringResource(R.string.items_ask_state_denied)
    else -> stringResource(R.string.items_ask_state_pending)
}

@Composable
private fun AskDialog(f: GrantAskForm, name: String, actions: SharedWithYouActions) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { actions.onOpenAsk(false) },
        title = { Text(stringResource(R.string.items_ask_button, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Box {
                    TextButton(onClick = { open = true }, modifier = Modifier.testTag("ask_category")) {
                        Text(stringResource(R.string.items_ask_category, ItemsText.category(f.category)))
                    }
                    androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        ItemTemplates.categories.forEach { c ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(c.label)) }, onClick = {
                                open = false
                                actions.onAsk(f.copy(category = c.id))
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = f.label,
                    onValueChange = { actions.onAsk(f.copy(label = it.take(ASK_LABEL_CHARS))) },
                    label = { Text(stringResource(R.string.items_ask_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ask_label"),
                )
                OutlinedTextField(
                    value = f.reason,
                    onValueChange = { actions.onAsk(f.copy(reason = it.take(ASK_REASON_CHARS))) },
                    label = { Text(stringResource(R.string.items_ask_reason)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.items_ask_note, name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSendAsk, enabled = !f.busy, modifier = Modifier.testTag("ask_send")) {
                Text(stringResource(R.string.items_ask_send))
            }
        },
        dismissButton = { TextButton(onClick = { actions.onOpenAsk(false) }) { Text(stringResource(R.string.items_cancel)) } },
    )
}

/** §10.12: a label at most 128 bytes, a reason at most 256; the fields stop at as many characters, the vault checks bytes. */
private const val ASK_LABEL_CHARS = 128
private const val ASK_REASON_CHARS = 256

@Composable
private fun SharedFields(c: SharedContent) {
    c.fields.forEach { f ->
        val text = when (val v = f.value) {
            is FieldValue.Text -> v.text
            is FieldValue.Address -> v.address.lines().joinToString("\n")
            null -> ""
        }
        Text(f.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text.ifEmpty { stringResource(R.string.items_empty_value) },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = Spacing.s),
        )
    }
    c.notes?.let {
        Text(stringResource(R.string.items_notes), style = MaterialTheme.typography.labelMedium)
        Text(it, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun refusalText(r: String): String = when (r) {
    "revoked" -> stringResource(R.string.items_shared_refused_revoked)
    "expired" -> stringResource(R.string.items_shared_refused_expired)
    "exhausted" -> stringResource(R.string.items_shared_refused_exhausted)
    else -> stringResource(R.string.items_shared_refused_unavailable)
}
