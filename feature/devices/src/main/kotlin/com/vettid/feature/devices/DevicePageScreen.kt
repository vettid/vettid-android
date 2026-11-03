package com.vettid.feature.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.core.data.devices.AgentGrant
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.DeviceWire
import com.vettid.core.data.items.RuleNames
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.R as UiR
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.FloatingPillActionBar
import com.vettid.core.ui.components.InitialTile
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PillAction
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.format.durationText
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.CategoryHue
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.categoryColor
import java.time.Instant

/** What a device's page can ask for. */
data class DevicePageActions(
    val onBack: () -> Unit = {},
    val onEndAccess: () -> Unit = {},
    val onRemove: () -> Unit = {},
    /** The device's History (the search set to its name, §10.9). */
    val onHistory: (String) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onDismissFailure: () -> Unit = {},
    val onAccessEndedShown: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** AA4: What it may use. */
    val onNewRule: () -> Unit = {},
    val onOpenRule: (String) -> Unit = {},
    val onRetryRules: () -> Unit = {},
    val onRuleSavedShown: () -> Unit = {},
    /** AA3: Other permissions (a grant id) and the paused banner. */
    val onRemovePermission: (String) -> Unit = {},
    val onRetryPermissions: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onNoticeShown: () -> Unit = {},
)

/**
 * A device's page (ANDROID-PLAN 0.1.53 AA1; agents and desktops): its name as the device gave it (never renamed here),
 * its role, when it was paired and last active, its key fingerprint, and the access card with **End access**. The
 * pill holds **History** and **Remove**; both ending access and removing are confirmed.
 */
@Composable
@Suppress("LongMethod")
fun DevicePageScreen(state: DeviceUiState, actions: DevicePageActions, modifier: Modifier = Modifier, now: Instant = Instant.now()) {
    // One way out, however it came about (removed here, or no longer listed): a single step back.
    val leave = state.gone || state.removed
    LaunchedEffect(leave) { if (leave) actions.onBack() }
    val snackbar = remember { SnackbarHostState() }
    val ended = stringResource(R.string.devices_access_ended)
    LaunchedEffect(state.accessEnded) {
        if (!state.accessEnded) return@LaunchedEffect
        actions.onAccessEndedShown()
        snackbar.showSnackbar(ended)
    }
    val savedText = stringResource(R.string.devices_rule_saved)
    LaunchedEffect(state.ruleSaved) {
        if (!state.ruleSaved) return@LaunchedEffect
        actions.onRuleSavedShown()
        snackbar.showSnackbar(savedText)
    }
    val noticeText = state.notice?.let { stringResource(PermissionText.noticeRes(it)) }
    LaunchedEffect(state.notice) {
        if (noticeText == null) return@LaunchedEffect
        actions.onNoticeShown()
        snackbar.showSnackbar(noticeText)
    }
    val d = state.device?.takeIf { !it.thisPhone }
    DetailScaffold(
        onBackClick = actions.onBack,
        modifier = modifier,
        overlay = {
            if (d != null) {
                FloatingPillActionBar(
                    listOfNotNull(
                        // An empty name would open the unfiltered log: no History for it.
                        PillAction(Icons.Outlined.History, stringResource(R.string.devices_history), { actions.onHistory(d.name) })
                            .takeIf { d.name.isNotEmpty() },
                        PillAction(
                            Icons.Outlined.DeleteOutline,
                            stringResource(R.string.devices_remove),
                            actions.onRemove,
                            destructive = true,
                        ).takeIf { d.actionable },
                    ),
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
        },
    ) {
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                val e = state.loadError
                when {
                    e != null -> NoticeCard(
                        kind = NoticeKind.WARNING,
                        title = stringResource(R.string.devices_error_title),
                        body = failureText(e.messageRes(), e.vaultCode(state.loadErrorCode)),
                        modifier = Modifier.testTag("device_load_failed"),
                        actions = { TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.devices_retry)) } },
                    )
                    !state.loaded -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            PageContent(state, d, actions, now)
        }
    }
    if (d != null) PageDialogs(state, d, actions)
}

@Composable
private fun PageContent(state: DeviceUiState, d: DeviceInfo, actions: DevicePageActions, now: Instant) {
    val name = DeviceText.name(d)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = PILL_SPACE),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        if (d.isAgent && d.paused) PausedBanner(DeviceText.sentenceName(d), busy = state.busy, onResume = actions.onResume)
        Column(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
            InitialTile(
                name = name,
                size = 72,
                colors = categoryColor(if (d.isAgent) CategoryHue.ORANGE else CategoryHue.PURPLE),
            )
            Spacer(Modifier.height(Spacing.m))
            Text(
                name,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.gutter).semantics { heading() }.testTag("device_name"),
            )
            // The name is what the device says about itself: never verified, never edited here (decision 11).
            Text(
                stringResource(nameNote(d)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("device_name_note"),
            )
        }
        state.failure?.let { f -> FailureNotice(state, f, DeviceText.sentenceName(d), actions.onDismissFailure) }
        AccessCard(d, now, busy = state.busy, onEndAccess = actions.onEndAccess)
        if (d.isAgent) RulesCard(state, actions)
        if (d.isAgent) PermissionsCard(state, actions)
        DetailCard(Modifier.testTag("about_card")) {
            CardTitle(stringResource(R.string.devices_about))
            InfoLine(stringResource(R.string.devices_role), DeviceText.role(d))
            d.createdAt?.let { InfoLine(stringResource(R.string.devices_paired_on), Times.full(it)) }
            InfoLine(
                stringResource(R.string.devices_last_active_label),
                d.lastActiveAt?.let { DeviceText.ago(it, now) } ?: stringResource(R.string.devices_not_active_yet),
            )
            d.fingerprint?.let { fp ->
                Spacer(Modifier.height(Spacing.s))
                Text(stringResource(R.string.devices_key), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    fp,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("device_fingerprint"),
                )
            }
        }
    }
}

/** Whose word the name is: the agent's, the desktop's, or a device's of a kind this app does not know. */
private fun nameNote(d: DeviceInfo): Int = when {
    d.isAgent -> R.string.devices_name_by_agent
    d.kind == DeviceInfo.KIND_DESKTOP -> R.string.devices_name_by_desktop
    else -> R.string.devices_name_by_device
}

@Composable
private fun FailureNotice(state: DeviceUiState, f: DeviceFailure, name: String, onDismiss: () -> Unit) {
    val kind = state.error
    // A refused daily check says why in the vault's words; anything else says what did not happen.
    val body = if (kind == FailureKind.OWNER_CHECK_REQUIRED) {
        failureText(kind.messageRes(), kind.vaultCode(state.errorCode))
    } else {
        when (f) {
            DeviceFailure.END_ACCESS -> stringResource(UiR.string.devices_end_failed, name)
            DeviceFailure.REMOVE -> stringResource(R.string.devices_remove_failed, name)
            DeviceFailure.REMOVE_PERMISSION -> stringResource(R.string.devices_perm_remove_failed)
            DeviceFailure.RESUME -> stringResource(R.string.devices_resume_failed, name)
        }
    }
    NoticeCard(
        kind = NoticeKind.WARNING,
        title = stringResource(
            when (f) {
                DeviceFailure.END_ACCESS -> UiR.string.devices_end_failed_title
                DeviceFailure.REMOVE, DeviceFailure.REMOVE_PERMISSION -> R.string.devices_remove_failed_title
                DeviceFailure.RESUME -> R.string.devices_resume_failed_title
            },
        ),
        body = body,
        modifier = Modifier.padding(horizontal = Spacing.s).testTag("device_failure"),
        actions = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.devices_ok)) } },
    )
}

/** Access: its end and **End access**, or "No access" and how the device asks (§6.8); agents also read the rule. */
@Composable
private fun AccessCard(d: DeviceInfo, now: Instant, busy: Boolean, onEndAccess: () -> Unit) {
    DetailCard(Modifier.testTag("access_card")) {
        CardTitle(stringResource(R.string.devices_access))
        Text(DeviceText.access(d, now), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("access_status"))
        if (d.actionable && d.hasAccess(now)) {
            Spacer(Modifier.height(Spacing.m))
            SecondaryButton(
                stringResource(UiR.string.devices_end_access),
                onEndAccess,
                enabled = !busy,
                modifier = Modifier.testTag("end_access"),
            )
        } else if (d.actionable) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                stringResource(if (d.isAgent) R.string.devices_no_access_agent else R.string.devices_no_access_desktop),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (d.isAgent) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                stringResource(R.string.devices_agents_rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What it may use (AA4): the agent's share rules, each a row that opens its editor, and **Add a rule**. */
@Composable
private fun RulesCard(state: DeviceUiState, actions: DevicePageActions) {
    DetailCard(Modifier.testTag("rules_card")) {
        CardTitle(stringResource(R.string.devices_rules_title))
        when {
            state.rulesError != null && !state.rulesLoaded -> {
                Text(stringResource(R.string.devices_rules_failed), modifier = Modifier.testTag("rules_failed"))
                TextButton(onClick = actions.onRetryRules) { Text(stringResource(R.string.devices_retry)) }
            }
            state.rules.isEmpty() && state.rulesLoaded -> Text(
                stringResource(R.string.devices_rules_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rules_none"),
            )
            else -> state.rules.forEach { RuleRow(it, state.itemWays, actions.onOpenRule) }
        }
        if (state.atRuleLimit) {
            Text(
                stringResource(R.string.devices_rules_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rules_limit"),
            )
        }
        Spacer(Modifier.height(Spacing.s))
        SecondaryButton(
            stringResource(R.string.devices_rules_add),
            actions.onNewRule,
            enabled = !state.atRuleLimit,
            modifier = Modifier.testTag("rule_add"),
        )
    }
}

/** "Paused by your vault" (AA3): in the error colour, with **Resume**; the vault paused all of the agent's permissions. */
@Composable
private fun PausedBanner(name: String, busy: Boolean, onResume: () -> Unit) {
    NoticeCard(
        kind = NoticeKind.URGENT,
        title = stringResource(R.string.devices_paused_title),
        body = stringResource(R.string.devices_paused_body, name),
        modifier = Modifier.padding(horizontal = Spacing.s).testTag("paused_banner"),
        actions = {
            TextButton(onClick = onResume, enabled = !busy, modifier = Modifier.testTag("resume")) {
                Text(stringResource(R.string.devices_resume))
            }
        },
    )
}

/**
 * Other permissions (AA3): the agent's grants that are not share rules, each with a ⋯ menu to **Remove**. Shown only when
 * there are any (grants another device issued): there is no add action and no editor yet (decision 3a). A list that could
 * not be read is said, with Try again, until one was read.
 */
@Composable
private fun PermissionsCard(state: DeviceUiState, actions: DevicePageActions) {
    val failed = state.permissionsError != null && !state.permissionsLoaded
    if (state.permissions.isEmpty() && !failed) return
    DetailCard(Modifier.testTag("permissions_card")) {
        CardTitle(stringResource(R.string.devices_perms_title))
        if (failed) {
            Text(stringResource(R.string.devices_perms_failed), modifier = Modifier.testTag("permissions_failed"))
            TextButton(onClick = actions.onRetryPermissions) { Text(stringResource(R.string.devices_retry)) }
        }
        state.permissions.forEach { PermissionRow(it, state.connectionNames, state.busy, actions.onRemovePermission) }
    }
}

/** One permission: its scope's label, how it is allowed, and ⋯ → **Remove**. A tap opens nothing (no editor yet). */
@Composable
private fun PermissionRow(g: AgentGrant, names: Map<String, String>, busy: Boolean, onRemove: (String) -> Unit) {
    val label = PermissionText.label(g.scope)
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}.testTag("perm_${g.grantId}")) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                PermissionText.detail(g, names),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { open = true }, enabled = !busy, modifier = Modifier.testTag("perm_more_${g.grantId}")) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.devices_perm_more_cd, label))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.devices_remove)) },
                    onClick = {
                        open = false
                        onRemove(g.grantId)
                    },
                    modifier = Modifier.testTag("perm_remove_${g.grantId}"),
                )
            }
        }
    }
}

/**
 * One rule (§10.12 "Naming a rule"): its tags, its mode, its limit, its end, what it includes and what waits. Without
 * `item_ways` the row carries the warning line (decision 17).
 */
@Composable
private fun RuleRow(r: ShareRule, itemWays: Boolean, onOpen: (String) -> Unit) {
    val name = RuleNames.text(r)
    val cd = stringResource(R.string.devices_rule_row_cd, name)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button) { onOpen(r.ruleId) }
            .semantics(mergeDescendants = true) { contentDescription = cd }
            .padding(vertical = Spacing.xs)
            .testTag("rule_${r.ruleId}"),
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(if (r.mode == ShareMode.AUTO) R.string.devices_rule_auto else R.string.devices_rule_ask),
            style = MaterialTheme.typography.bodyMedium,
        )
        val parts = listOfNotNull(
            r.perHour?.let { pluralStringResource(R.plurals.devices_rule_per_hour, it, it) },
            r.expiresAt?.let { stringResource(R.string.devices_rule_ends, Times.dayLabel(Times.day(it))) }
                ?: stringResource(R.string.devices_rule_no_end),
            pluralStringResource(R.plurals.devices_rule_items, r.included.size, r.included.size),
            r.pending.size.takeIf { it > 0 }?.let { stringResource(R.string.devices_rule_waiting, it) },
        )
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!itemWays) {
            Text(
                stringResource(UiR.string.core_ui_agent_ways_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("rule_ways_warning_${r.ruleId}"),
            )
        }
    }
}

@Composable
private fun PageDialogs(state: DeviceUiState, d: DeviceInfo, actions: DevicePageActions) {
    val name = DeviceText.sentenceName(d)
    when (state.confirm) {
        DeviceConfirm.END_ACCESS -> ConfirmDialog(
            title = stringResource(UiR.string.devices_end_access_title, name),
            text = stringResource(UiR.string.devices_end_access_body),
            confirmLabel = stringResource(UiR.string.devices_end_access),
            onConfirm = actions.onConfirm,
            onDismiss = actions.onDismissConfirm,
        )
        DeviceConfirm.REMOVE -> {
            val bound = state.removalBoundSeconds
            val text = if (d.isAgent) {
                buildString {
                    append(stringResource(R.string.devices_remove_agent_body))
                    // "Services it showed a permission to may accept it for up to ...": only while it has a permission.
                    if (bound > 0) {
                        append(' ')
                        append(stringResource(R.string.devices_remove_agent_services, durationText(bound)))
                    }
                    append(' ')
                    append(stringResource(R.string.devices_remove_agent_again))
                }
            } else {
                stringResource(R.string.devices_remove_desktop_body)
            }
            ConfirmDialog(
                title = stringResource(R.string.devices_remove_title, name),
                text = text,
                confirmLabel = stringResource(R.string.devices_remove),
                onConfirm = actions.onConfirm,
                onDismiss = actions.onDismissConfirm,
                destructive = true,
            )
        }
        DeviceConfirm.REMOVE_PERMISSION -> state.confirmGrant?.let { g ->
            val agent = DeviceText.sentenceName(d)
            val bound = durationText(g.statusTtl + DeviceWire.SKEW_SECONDS)
            val labelRes = PermissionText.labelRes(g.scope)
            ConfirmDialog(
                title = stringResource(R.string.devices_perm_remove_title),
                text = if (labelRes != null) {
                    stringResource(R.string.devices_perm_remove_body, agent, stringResource(labelRes).lowercase(), bound)
                } else {
                    stringResource(R.string.devices_perm_remove_body_unknown, agent, bound)
                },
                confirmLabel = stringResource(R.string.devices_remove),
                onConfirm = actions.onConfirm,
                onDismiss = actions.onDismissConfirm,
                destructive = true,
            )
        }
        null -> Unit
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Spacing.s))
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** Room under the content for the floating pill. */
private val PILL_SPACE = 96.dp
