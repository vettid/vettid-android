package com.vettid.feature.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.TagMatch
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.vault.VaultLimit
import com.vettid.core.data.vault.message
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.TagLabel
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

/**
 * How the sharing cards name the connection: its first name from the names on its VettID account (§10.8), else the
 * whole account name; null before the names arrived ("Name not shared yet"), for which the cards say "this connection".
 */
internal fun sharingName(c: ConnectionInfo): String? =
    c.firstName?.trim()?.takeIf { it.isNotEmpty() } ?: c.accountName?.takeIf { it.isNotBlank() }

/**
 * "You share with <First>": what goes out of the member's vault to this connection (VAULT-ITEMS §6, §10.12): every
 * share rule for it as a row of its own (tags, ask or automatic, its fetch limit and end, what it shares now, the
 * other rules covering the same tags or items), each opening its editor or deleted (confirmed); "Add a rule" (up to
 * 64, §10.12 `share_rules_subject`); and the items it can fetch now. Gold, as the member's own avatar: it is the
 * member's data.
 */
@Composable
@Suppress("LongParameterList")
internal fun OutgoingSharingCard(
    sharing: DetailSharing,
    first: String?,
    onShare: () -> Unit,
    onOpenRule: (String) -> Unit,
    onManage: () -> Unit,
    onDeleteRule: (ShareRule) -> Unit = {},
) {
    val name = first?.let { AccountNames.isolate(it) }
    DirectionCard(
        accent = MaterialTheme.colorScheme.primary,
        badgeFill = MaterialTheme.colorScheme.primaryContainer,
        onBadge = MaterialTheme.colorScheme.onPrimaryContainer,
        icon = Icons.Outlined.Outbox,
        title = if (name != null) {
            stringResource(R.string.connections_share_out_title, name)
        } else {
            stringResource(R.string.connections_share_out_title_unnamed)
        },
        count = sharing.outgoingCount,
        countLabel = pluralStringResource(R.plurals.connections_share_out_count, sharing.outgoingCount, sharing.outgoingCount),
        tag = "sharing_out",
    ) {
        when {
            sharing.failed -> Muted(stringResource(R.string.connections_share_unavailable), "sharing_out_failed")
            sharing.loaded && sharing.outgoingEmpty -> Muted(
                if (name != null) {
                    stringResource(R.string.connections_share_out_empty, name)
                } else {
                    stringResource(R.string.connections_share_out_empty_unnamed)
                },
                "sharing_out_empty",
            )
        }
        if (sharing.rules.isNotEmpty()) {
            SubHeading(stringResource(R.string.connections_share_out_rules))
            val overlaps = sharing.overlaps
            sharing.rules.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RuleLine(r, overlaps[r.ruleId].orEmpty(), onOpenRule, onDeleteRule)
            }
        }
        if (sharing.given.isNotEmpty()) {
            SubHeading(stringResource(R.string.connections_share_out_items))
            sharing.given.forEach { GivenLine(it) }
        }
        Spacer(Modifier.height(Spacing.s))
        if (sharing.atRuleLimit) {
            Muted(VaultLimit(RULE_LIMIT, RuleDraft.MAX_RULES_PER_SUBJECT.toLong()).message(LocalResources.current), "sharing_out_limit")
        }
        SecondaryButton(
            stringResource(R.string.connections_share_out_action),
            onShare,
            enabled = !sharing.atRuleLimit,
            modifier = Modifier.testTag("sharing_out_share"),
        )
        if (!sharing.outgoingEmpty) {
            TextButton(onClick = onManage, modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("sharing_out_manage")) {
                Text(stringResource(R.string.connections_share_out_manage))
            }
        }
    }
}

/**
 * "<First> shares with you": what comes in from the connection's vault (§10.12 received grants): read-only and
 * labelled as theirs, in a neutral accent. "Ask for something" is the `grant.request` flow.
 */
@Composable
internal fun IncomingSharingCard(sharing: DetailSharing, first: String?, onAsk: () -> Unit, onOpen: () -> Unit) {
    val name = first?.let { AccountNames.isolate(it) }
    DirectionCard(
        accent = MaterialTheme.colorScheme.secondary,
        badgeFill = MaterialTheme.colorScheme.secondaryContainer,
        onBadge = MaterialTheme.colorScheme.onSecondaryContainer,
        icon = Icons.Outlined.MoveToInbox,
        title = if (name != null) {
            stringResource(R.string.connections_share_in_title, name)
        } else {
            stringResource(R.string.connections_share_in_title_unnamed)
        },
        count = sharing.incomingCount,
        countLabel = pluralStringResource(R.plurals.connections_share_in_count, sharing.incomingCount, sharing.incomingCount),
        tag = "sharing_in",
    ) {
        when {
            sharing.failed -> Muted(stringResource(R.string.connections_share_unavailable), "sharing_in_failed")
            sharing.loaded && sharing.incomingEmpty -> Muted(
                if (name != null) {
                    stringResource(R.string.connections_share_in_empty, name)
                } else {
                    stringResource(R.string.connections_share_in_empty_unnamed)
                },
                "sharing_in_empty",
            )
        }
        sharing.received.forEach { ReceivedLine(it, name, onOpen) }
        if (sharing.asked.isNotEmpty()) {
            Muted(pluralStringResource(R.plurals.connections_share_in_asked, sharing.asked.size, sharing.asked.size), "sharing_in_asked")
        }
        Spacer(Modifier.height(Spacing.s))
        SecondaryButton(stringResource(R.string.connections_share_in_action), onAsk, modifier = Modifier.testTag("sharing_in_ask"))
        if (!sharing.incomingEmpty) {
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("sharing_in_open")) {
                Text(stringResource(R.string.connections_share_in_open))
            }
        }
    }
}

/** A card with a direction: an accent edge, the direction's icon, a heading and the count of items it holds. */
@Composable
@Suppress("LongParameterList")
private fun DirectionCard(
    accent: Color,
    badgeFill: Color,
    onBadge: Color,
    icon: ImageVector,
    title: String,
    count: Int,
    countLabel: String,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = VettIdShape.card,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, accent.copy(alpha = ACCENT_BORDER_ALPHA)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s).testTag(tag),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(ACCENT_EDGE).fillMaxHeight().background(accent).testTag("${tag}_edge"))
            Column(Modifier.weight(1f).padding(Spacing.l)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(ICON_BOX).clip(VettIdShape.pill).background(accent.copy(alpha = ICON_FILL_ALPHA)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(ICON_SIZE))
                    }
                    Spacer(Modifier.width(Spacing.m))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).semantics { heading() }.testTag("${tag}_title"),
                    )
                    Spacer(Modifier.width(Spacing.s))
                    Box(
                        Modifier
                            .defaultMinSize(minWidth = BADGE_MIN, minHeight = BADGE_MIN)
                            .clip(VettIdShape.pill)
                            .background(badgeFill)
                            .padding(horizontal = Spacing.s)
                            .clearAndSetSemantics {
                                contentDescription = countLabel
                                testTag = "${tag}_count"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (count > MAX_COUNT) "$MAX_COUNT+" else count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = onBadge,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                content()
            }
        }
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.xs),
    )
}

@Composable
private fun Muted(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Spacing.xs).testTag(tag),
    )
}

/**
 * A share rule for this connection, one row: its tags (any or all), ask or automatic, its fetch limit and end, how
 * many items it shares now (and waiting), and the other rules covering the same tags or items (§10.12: each rule
 * applies on its own). The row opens the rule editor; the bin deletes it after a confirmation.
 */
@Composable
private fun RuleLine(r: ShareRule, overlaps: List<ShareRule>, onOpen: (String) -> Unit, onDelete: (ShareRule) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("sharing_rule_row_${r.ruleId}"), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clip(VettIdShape.card)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.connections_share_rule_open)) { onOpen(r.ruleId) }
                .heightIn(min = Spacing.touchTarget)
                .padding(vertical = Spacing.s)
                .testTag("sharing_rule_${r.ruleId}"),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                r.tags.forEach { TagLabel(it) }
            }
            if (r.tags.size > 1) {
                Text(
                    stringResource(
                        if (r.match == TagMatch.ALL) R.string.connections_share_rule_all else R.string.connections_share_rule_any,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
            Text(
                stringResource(if (r.mode == ShareMode.AUTO) R.string.connections_share_rule_auto else R.string.connections_share_rule_ask),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
            Text(
                listOf(
                    r.uses?.let { pluralStringResource(R.plurals.connections_share_uses, it, it) }
                        ?: stringResource(R.string.connections_share_no_limit),
                    r.expiresAt?.let { stringResource(R.string.connections_share_until, Times.dayLabel(Times.day(it))) }
                        ?: stringResource(R.string.connections_share_no_end),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.connections_share_rule_included, r.included.size, r.included.size),
                    r.pending.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.connections_share_rule_pending, it, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (overlaps.isNotEmpty()) {
                Text(
                    stringResource(R.string.connections_share_rule_also, overlaps.joinToString("; ") { it.tags.joinToString(", ") }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("sharing_rule_also_${r.ruleId}"),
                )
            }
        }
        IconButton(onClick = { onDelete(r) }, modifier = Modifier.testTag("sharing_rule_delete_${r.ruleId}")) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.connections_share_rule_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** An item of the member's the connection can fetch now, how it was given and what is left of it. */
@Composable
private fun GivenLine(g: GrantView) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs).semantics(mergeDescendants = true) {}.testTag("sharing_given_${g.grantId}"),
    ) {
        Text(g.name.ifBlank { stringResource(R.string.connections_share_deleted_item) }, style = MaterialTheme.typography.bodyLarge)
        Text(
            listOfNotNull(
                stringResource(if (g.ruleId != null) R.string.connections_share_by_rule else R.string.connections_share_one_off),
                g.usesLeft?.let { pluralStringResource(R.plurals.connections_share_uses_left, it, it) },
                g.expiresAt?.let { stringResource(R.string.connections_share_until, Times.full(it)) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An item the connection shares with the member: read-only, marked as theirs. */
@Composable
private fun ReceivedLine(g: GrantView, name: String?, onOpen: () -> Unit) {
    val theirs = if (name != null) {
        stringResource(R.string.connections_share_theirs, name)
    } else {
        stringResource(R.string.connections_share_theirs_unnamed)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(VettIdShape.card)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.connections_share_in_open), onClick = onOpen)
            .heightIn(min = Spacing.touchTarget)
            .padding(vertical = Spacing.xs)
            .semantics(mergeDescendants = true) {}
            .testTag("sharing_received_${g.grantId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                g.name.ifBlank { g.label ?: stringResource(R.string.connections_share_deleted_item) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                listOfNotNull(
                    theirs,
                    g.usesLeft?.let { pluralStringResource(R.plurals.connections_share_uses_left, it, it) },
                    g.expiresAt?.let { stringResource(R.string.connections_share_until, Times.full(it)) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ACCENT_EDGE = 4.dp
private val ICON_BOX = 36.dp
private val ICON_SIZE = 20.dp
private val BADGE_MIN = 24.dp
private const val ACCENT_BORDER_ALPHA = 0.5f
private const val ICON_FILL_ALPHA = 0.14f
private const val MAX_COUNT = 99

/** The named limit of rules per connection (§10.12). */
private const val RULE_LIMIT = "share_rules_subject"
