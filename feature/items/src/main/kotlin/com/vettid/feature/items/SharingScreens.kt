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
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharedContent
import com.vettid.core.data.items.TagMatch
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.DetailScaffold
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

/** What a connection shares with the member; [ask] opens "Ask for something" at once (from the connection detail). */
@Serializable
data class SharedWithYouRoute(val connectionId: String, val ask: Boolean = false) {
    companion object {
        const val ARG = "connectionId"
        const val ARG_ASK = "ask"
    }
}

/** The connection's name inside a sentence (bidi-isolated, §10.8), or a neutral placeholder. */
@Composable
internal fun sharingNameOf(n: String?): String =
    n?.let { AccountNames.isolate(it) } ?: stringResource(R.string.items_sharing_this_connection)

@Composable
private fun Section(text: String) = SettingsSectionHeader(text)

@Composable
internal fun SharingErrorNotice(e: FailureKind?, limit: com.vettid.core.data.vault.VaultLimit?, onDismiss: () -> Unit) {
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
    val name = sharingNameOf(state.connectionName)
    DetailScaffold(onBackClick = actions.onBack, modifier = modifier.testTag("connection_sharing")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
            LargeTitle(stringResource(R.string.items_sharing_title, name))
            Text(
                stringResource(R.string.items_sharing_body, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
            SharingErrorNotice(state.error, state.limit, actions.onDismissError)
            if (state.loading && state.rules.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            RulesSection(state, name, actions)
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

/** The connection's rules, each with its own settings (§10.12), and "Add a rule" up to 64. */
@Composable
private fun RulesSection(state: ConnectionSharingUiState, name: String, actions: ConnectionSharingActions) {
    Section(stringResource(R.string.items_sharing_rules))
    if (!state.loading && state.rules.isEmpty()) {
        Text(
            stringResource(R.string.items_sharing_no_rules, name),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("sharing_no_rules"),
        )
    }
    state.rules.forEach { r -> RuleCard(r, state.overlaps[r.ruleId].orEmpty(), actions.onOpenRule) }
    if (state.atRuleLimit) RuleLimitNotice(Modifier.padding(horizontal = Spacing.s))
    SecondaryButton(
        stringResource(R.string.items_sharing_new_rule),
        actions.onNewRule,
        enabled = !state.atRuleLimit,
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("sharing_new_rule"),
    )
}

/** A rule of this connection: its tags, mode, limits, end, what it shares now and the other rules covering the same. */
@Composable
private fun RuleCard(r: ShareRule, overlaps: List<ShareRule>, onOpen: (String) -> Unit) {
    DetailCard(Modifier.padding(vertical = Spacing.xs).semantics(mergeDescendants = true) {}.testTag("rule_${r.ruleId}")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) { r.tags.forEach { TagLabel(it) } }
        Spacer(Modifier.height(Spacing.s))
        if (r.tags.size > 1) {
            Text(
                stringResource(if (r.match == TagMatch.ALL) R.string.items_rule_match_all_short else R.string.items_rule_match_any_short),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(ruleModeText(r.mode), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(ruleLimitsText(r), style = MaterialTheme.typography.bodySmall)
        Text(
            listOf(
                pluralStringResource(R.plurals.items_rule_included, r.included.size, r.included.size),
                pluralStringResource(R.plurals.items_rule_pending, r.pending.size, r.pending.size),
                pluralStringResource(R.plurals.items_rule_declined, r.declined.size, r.declined.size),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        if (overlaps.isNotEmpty()) {
            Text(
                stringResource(R.string.items_rule_also_covered, overlaps.joinToString("; ") { it.tags.joinToString(", ") }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rule_also_${r.ruleId}"),
            )
        }
        TextButton(onClick = { onOpen(r.ruleId) }, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
            Text(stringResource(R.string.items_rule_change))
        }
    }
}

/** "Shared automatically" or "Asks you each time" (§10.12 `mode`). */
@Composable
internal fun ruleModeText(m: ShareMode): String =
    stringResource(if (m == ShareMode.AUTO) R.string.items_rule_summary_auto else R.string.items_rule_summary_ask)

/** The rule's limit and end: "up to 5 fetches of each item · until Dec 31, 2026", or "no fetch limit · no end date". */
@Composable
internal fun ruleLimitsText(r: ShareRule): String = listOf(
    r.uses?.let { pluralStringResource(R.plurals.items_rule_uses, it, it) } ?: stringResource(R.string.items_rule_no_limit),
    r.expiresAt?.let { stringResource(R.string.items_rule_until, Times.dayLabel(Times.day(it))) }
        ?: stringResource(R.string.items_rule_no_end),
).joinToString(" · ")

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
    val name = sharingNameOf(state.connectionName)
    DetailScaffold(onBackClick = actions.onBack, modifier = modifier.testTag("shared_with_you")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
            LargeTitle(stringResource(R.string.items_shared_title, name))
            Text(
                stringResource(R.string.items_shared_body, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
            SharingErrorNotice(state.error, state.limit, actions.onDismissError)
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
