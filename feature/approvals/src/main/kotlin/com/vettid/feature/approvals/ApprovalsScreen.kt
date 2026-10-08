package com.vettid.feature.approvals

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.RequestState
import com.vettid.core.data.social.needsDecision
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SafetyCode
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.components.VettIdListRow
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import kotlinx.serialization.Serializable

/** Type-safe navigation route of the Approvals screen. */
@Serializable
data object ApprovalsRoute

/** One approval, by its [Approval.key]. */
@Serializable
data class ApprovalDetailRoute(val key: String) {
    companion object {
        const val ARG = "key"
    }
}

/** Registers the Approvals destinations. */
fun NavGraphBuilder.approvalsDestination(chrome: ShellChrome, navigate: (Any) -> Unit, onBack: () -> Unit) {
    composable<ApprovalsRoute> {
        val vm: ApprovalsViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ApprovalsScreen(
            state,
            chrome,
            onOpen = { navigate(ApprovalDetailRoute(it)) },
            onRetry = vm::refresh,
            onDismissPeerDecline = vm::dismissPeerDecline,
        )
    }
    composable<ApprovalDetailRoute> {
        val vm: ApprovalDetailViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ApprovalDetailScreen(
            state,
            DecisionActions(
                onBack = onBack,
                onPassword = vm::setPassword,
                onApprove = vm::approve,
                onDeny = vm::deny,
                onAskBlock = vm::askBlock,
                onBlock = vm::block,
                onToggleShareItem = vm::toggleShareItem,
            ),
        )
    }
}

/** Approvals (ANDROID-PLAN §4): one typed row per request. */
@Composable
fun ApprovalsScreen(
    state: ApprovalsUiState,
    chrome: ShellChrome,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onDismissPeerDecline: (String) -> Unit = {},
) {
    TopLevelScaffold(title = stringResource(R.string.approvals_title), chrome = chrome, modifier = modifier) {
        val error = state.error
        val nothing = state.approvals.isEmpty() && state.peerDeclines.isEmpty()
        when {
            state.loading && nothing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            nothing && error != null -> Column(
                Modifier.fillMaxSize().padding(Spacing.xl),
                verticalArrangement = Arrangement.Center,
            ) {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.approvals_error_title),
                    body = stringResource(error.messageRes()),
                    actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.approvals_retry)) } },
                )
            }
            nothing -> EmptyState(
                icon = Icons.Outlined.TaskAlt,
                title = stringResource(R.string.approvals_empty_title),
                body = stringResource(R.string.approvals_empty_body),
            )
            else -> LazyColumn(Modifier.fillMaxSize().testTag("approvals"), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
                // The other member declined a request (0.10.5): it has left the list; told once.
                items(state.peerDeclines, key = { "declined-${it.requestId}" }) { d ->
                    PeerDeclineNotice(
                        d,
                        onDismiss = { onDismissPeerDecline(d.requestId) },
                        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                    )
                }
                items(state.approvals, key = { it.key }) { a ->
                    val who = whoOf(a)
                    VettIdListRow(
                        title = titleOf(a),
                        supporting = listOfNotNull(who, summaryOf(a)).joinToString(" · "),
                        meta = Times.short(a.receivedAt),
                        tileName = who ?: "?",
                        emphasized = a.needsDecision,
                        onClick = { onOpen(a.key) },
                        modifier = Modifier.testTag("approval_${a.key}"),
                    )
                }
            }
        }
    }
}

/** The kind of request, as a row title. */
@Composable
fun titleOf(a: Approval): String = stringResource(
    when (a) {
        is Approval.ConnectionRequest -> if (a.remote) R.string.approvals_type_connection_remote else R.string.approvals_type_connection
        is Approval.OutgoingRequest -> R.string.approvals_type_outgoing
        is Approval.Authentication -> R.string.approvals_type_authentication
        is Approval.GrantRequest -> R.string.approvals_type_grant
        is Approval.CriticalUse -> R.string.approvals_type_critical
        is Approval.ShareDecision -> R.string.approvals_type_share
        is Approval.DeviceRequest ->
            if (a.type == "device.session.pending") R.string.approvals_type_session else R.string.approvals_type_device
    },
)

@Composable
private fun whoOf(a: Approval): String? = when (a) {
    is Approval.ConnectionRequest -> a.name ?: stringResource(R.string.approvals_name_not_shared)
    is Approval.OutgoingRequest -> a.name ?: stringResource(R.string.approvals_name_not_shared)
    is Approval.DeviceRequest -> a.deviceName
    is Approval.ShareDecision -> a.connectionName ?: a.subjectAgentId?.let { stringResource(R.string.approvals_an_agent) }
    else -> a.connectionName ?: stringResource(R.string.approvals_a_connection)
}

@Composable
private fun summaryOf(a: Approval): String? = when (a) {
    is Approval.ConnectionRequest -> stringResource(
        if (a.state == RequestState.APPROVED) R.string.approvals_summary_waiting_peer else R.string.approvals_summary_sas,
        a.sas,
    )
    is Approval.OutgoingRequest -> {
        val sas = a.sas
        when {
            sas == null -> stringResource(R.string.approvals_summary_handshake)
            a.state == RequestState.APPROVED -> stringResource(R.string.approvals_summary_waiting_peer, sas)
            else -> stringResource(R.string.approvals_summary_sas, sas)
        }
    }
    is Approval.Authentication -> a.context
    is Approval.GrantRequest -> pluralStringResource(R.plurals.approvals_summary_items, a.entries.size, a.entries.size)
    is Approval.CriticalUse -> "${a.itemName} · ${a.fieldLabel}"
    is Approval.ShareDecision -> pluralStringResource(R.plurals.approvals_summary_items, a.items.size, a.items.size)
    is Approval.DeviceRequest -> a.requestType
}

/** What the decision screen can ask for. */
data class DecisionActions(
    val onBack: () -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onApprove: () -> Unit = {},
    val onDeny: () -> Unit = {},
    val onAskBlock: (Boolean) -> Unit = {},
    val onBlock: () -> Unit = {},
    /** Ticks or unticks an item of a share decision. */
    val onToggleShareItem: (String) -> Unit = {},
)

/** One approval: what is asked and by whom, then approve or deny (the password when it is a critical action). */
@Composable
fun ApprovalDetailScreen(state: ApprovalDetailUiState, actions: DecisionActions, modifier: Modifier = Modifier) {
    LaunchedEffect(state.done) { if (state.done) actions.onBack() }
    val a = state.approval
    if (a == null || state.gone) {
        FormScaffold(
            title = stringResource(R.string.approvals_gone_title),
            body = stringResource(R.string.approvals_gone_body),
            primaryLabel = stringResource(R.string.approvals_back),
            onPrimary = actions.onBack,
            onBack = actions.onBack,
            modifier = modifier,
        ) {}
        return
    }
    FormScaffold(
        title = titleOf(a),
        body = bodyOf(a),
        primaryLabel = approveLabel(a),
        onPrimary = actions.onApprove,
        primaryEnabled = state.canApprove,
        busy = state.busy,
        secondaryLabel = stringResource(if (a is Approval.ShareDecision) R.string.approvals_share_decline else R.string.approvals_deny),
        onSecondary = actions.onDeny,
        onBack = actions.onBack,
        modifier = modifier.testTag("approval_detail"),
    ) {
        if (a is Approval.ShareDecision) ShareFacts(a, state.shareExcluded, actions.onToggleShareItem) else Facts(a)
        if (state.needs == Needs.PASSWORD) {
            Spacer(Modifier.height(Spacing.l))
            SecretField(
                value = state.password,
                onValueChange = actions.onPassword,
                label = stringResource(R.string.approvals_password),
                supporting = stringResource(R.string.approvals_password_hint),
                onImeAction = actions.onApprove,
                modifier = Modifier.testTag("approval_password"),
            )
        }
        if (a is Approval.ConnectionRequest) {
            Spacer(Modifier.height(Spacing.m))
            TextButton(
                onClick = { actions.onAskBlock(true) },
                enabled = !state.busy,
                modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("block_request"),
            ) { Text(stringResource(R.string.approvals_block), color = MaterialTheme.colorScheme.error) }
        }
        state.error?.let {
            Spacer(Modifier.height(Spacing.m))
            Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("failure"))
        }
    }
    if (state.confirmBlock) {
        ConfirmDialog(
            title = stringResource(R.string.approvals_block_title),
            text = stringResource(R.string.approvals_block_body),
            confirmLabel = stringResource(R.string.approvals_block),
            onConfirm = actions.onBlock,
            onDismiss = { actions.onAskBlock(false) },
            destructive = true,
        )
    }
}

/**
 * A share rule's items waiting for the member (§10.12 `share.pending`): ticked items are shared, unticked ones
 * declined (and not asked again until they gain the rule again); the rule's tags say why they were asked.
 */
@Composable
private fun ShareFacts(a: Approval.ShareDecision, excluded: Set<String>, onToggle: (String) -> Unit) {
    if (a.tags.isNotEmpty()) {
        Label(stringResource(R.string.approvals_share_tags))
        Value(a.tags.joinToString(", "))
    }
    a.reason?.let {
        Text(
            stringResource(if (it == "rule") R.string.approvals_share_reason_rule else R.string.approvals_share_reason_tagged),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.s))
    }
    Label(stringResource(R.string.approvals_share_items))
    a.items.forEach { i ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .toggleable(value = i.itemId !in excluded, role = Role.Checkbox, onValueChange = { onToggle(i.itemId) })
                .testTag("share_item_${i.itemId}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = i.itemId !in excluded, onCheckedChange = null)
            Spacer(Modifier.width(Spacing.m))
            Column {
                Text(i.name.ifBlank { stringResource(R.string.approvals_share_unnamed) }, style = MaterialTheme.typography.bodyLarge)
                if (i.sensitivity == "critical") {
                    Text(
                        stringResource(R.string.approvals_share_usable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(Spacing.s))
    Text(
        stringResource(R.string.approvals_share_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun outgoingBody(a: Approval.OutgoingRequest): String = if (a.sas == null) {
    stringResource(R.string.approvals_outgoing_waiting)
} else {
    stringResource(
        if (a.remote) R.string.approvals_outgoing_body_remote else R.string.approvals_outgoing_body,
        a.name ?: stringResource(R.string.approvals_them),
    )
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun bodyOf(a: Approval): String = when (a) {
    is Approval.ConnectionRequest -> stringResource(
        if (a.remote) R.string.approvals_connection_body_remote else R.string.approvals_connection_body,
        a.name ?: stringResource(R.string.approvals_someone),
    )
    is Approval.OutgoingRequest -> outgoingBody(a)
    is Approval.Authentication -> stringResource(
        R.string.approvals_auth_body,
        a.connectionName ?: stringResource(R.string.approvals_a_connection),
    )
    is Approval.GrantRequest -> stringResource(
        R.string.approvals_grant_body,
        a.connectionName ?: stringResource(R.string.approvals_a_connection),
    )
    is Approval.CriticalUse -> stringResource(
        R.string.approvals_critical_body,
        a.connectionName ?: stringResource(R.string.approvals_a_connection),
    )
    is Approval.ShareDecision -> stringResource(
        R.string.approvals_share_body,
        a.connectionName
            ?: a.subjectAgentId?.let { stringResource(R.string.approvals_an_agent) }
            ?: stringResource(R.string.approvals_a_connection),
    )
    is Approval.DeviceRequest -> stringResource(R.string.approvals_device_body)
}

@Composable
private fun approveLabel(a: Approval): String? = when (a) {
    is Approval.ConnectionRequest ->
        if (a.state == RequestState.PENDING) stringResource(R.string.approvals_connection_approve) else null
    is Approval.OutgoingRequest ->
        if (a.state == RequestState.PENDING && a.sas != null) stringResource(R.string.approvals_outgoing_approve) else null
    is Approval.Authentication -> stringResource(R.string.approvals_auth_approve)
    is Approval.GrantRequest -> stringResource(R.string.approvals_grant_approve)
    is Approval.CriticalUse -> stringResource(R.string.approvals_critical_approve)
    is Approval.ShareDecision -> stringResource(R.string.approvals_share_approve)
    is Approval.DeviceRequest -> null
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A requester's name inside a sentence (bidi-isolated, §10.8), or "them". */
@Composable
private fun themOf(name: String?): String = name?.let { AccountNames.isolate(it) } ?: stringResource(R.string.approvals_them)

@Composable
private fun Value(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier.padding(bottom = Spacing.m))
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun Facts(a: Approval) {
    when (a) {
        is Approval.ConnectionRequest -> {
            Label(stringResource(R.string.approvals_name_given))
            Text(
                a.name ?: stringResource(R.string.approvals_name_not_shared),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("request_name"),
            )
            if (a.name != null) {
                Text(
                    stringResource(R.string.approvals_names_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            a.displayName?.let {
                Spacer(Modifier.height(Spacing.s))
                Text(stringResource(R.string.approvals_display_name, AccountNames.isolate(it)), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.approvals_self_asserted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            a.introducedBy?.let {
                Spacer(Modifier.height(Spacing.s))
                Text(stringResource(R.string.approvals_introduced), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(Spacing.l))
            Label(stringResource(R.string.approvals_safety_code))
            Spacer(Modifier.height(Spacing.s))
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, VettIdShape.card)
                    .padding(vertical = Spacing.l)
                    .testTag("sas"),
                contentAlignment = Alignment.Center,
            ) { SafetyCode(a.sas) }
            Spacer(Modifier.height(Spacing.s))
            Text(
                stringResource(if (a.remote) R.string.approvals_sas_hint_remote else R.string.approvals_sas_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (a.state == RequestState.APPROVED) {
                Spacer(Modifier.height(Spacing.m))
                Text(
                    stringResource(R.string.approvals_waiting_peer, themOf(a.name)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("waiting_peer"),
                )
            }
            a.exp?.let { Text(stringResource(R.string.approvals_expires, Times.full(it)), style = MaterialTheme.typography.bodySmall) }
        }
        is Approval.OutgoingRequest -> {
            Label(stringResource(R.string.approvals_outgoing_name))
            Text(
                a.name ?: stringResource(R.string.approvals_name_not_shared),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            a.sas?.let { sas ->
                Spacer(Modifier.height(Spacing.l))
                Label(stringResource(R.string.approvals_safety_code))
                Spacer(Modifier.height(Spacing.s))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, VettIdShape.card)
                        .padding(vertical = Spacing.l)
                        .testTag("sas"),
                    contentAlignment = Alignment.Center,
                ) { SafetyCode(sas) }
            }
            if (a.state == RequestState.APPROVED) {
                Spacer(Modifier.height(Spacing.m))
                Text(
                    stringResource(R.string.approvals_waiting_peer, themOf(a.name)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("waiting_peer"),
                )
            }
            a.exp?.let { Text(stringResource(R.string.approvals_expires, Times.full(it)), style = MaterialTheme.typography.bodySmall) }
        }
        is Approval.Authentication -> {
            Label(stringResource(R.string.approvals_from))
            Value(a.connectionName ?: stringResource(R.string.approvals_a_connection))
            a.context?.let {
                Label(stringResource(R.string.approvals_context))
                Value(it)
            }
            a.exp?.let { Text(stringResource(R.string.approvals_expires, Times.full(it)), style = MaterialTheme.typography.bodySmall) }
        }
        is Approval.GrantRequest -> {
            Label(stringResource(R.string.approvals_from))
            Value(a.connectionName ?: stringResource(R.string.approvals_a_connection))
            a.reason?.let {
                Label(stringResource(R.string.approvals_reason))
                Value(it)
            }
            Label(stringResource(R.string.approvals_items_asked))
            a.entries.forEach { e ->
                val text = when {
                    e.kind == "category" -> stringResource(R.string.approvals_grant_category, e.label ?: e.ref)
                    e.available -> stringResource(R.string.approvals_grant_item, e.label ?: e.ref)
                    else -> stringResource(R.string.approvals_grant_item_missing, e.label ?: e.ref)
                }
                Text("• $text", style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(Spacing.s))
            Text(
                pluralStringResource(R.plurals.approvals_grant_uses, a.uses ?: 1, a.uses ?: 1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (a.grantable.size < a.entries.size) {
                Spacer(Modifier.height(Spacing.m))
                NoticeCard(
                    kind = NoticeKind.INFO,
                    title = stringResource(R.string.approvals_grant_partial_title),
                    body = stringResource(R.string.approvals_grant_partial_body),
                )
            }
        }
        is Approval.CriticalUse -> {
            Label(stringResource(R.string.approvals_from))
            Value(a.connectionName ?: stringResource(R.string.approvals_a_connection))
            Label(stringResource(R.string.approvals_critical_item))
            Value("${a.itemName} · ${a.fieldLabel}")
            Label(stringResource(R.string.approvals_critical_operation))
            Value(stringResource(if (a.operation == "auth") R.string.approvals_operation_auth else R.string.approvals_operation_sign))
            a.context?.let {
                Label(stringResource(R.string.approvals_context))
                Value(it)
            }
            // §10.13: the payload is shown only once SHA-256(payload) matches payload_sha256.
            when {
                a.payloadVerified -> {
                    Label(stringResource(R.string.approvals_payload))
                    Surface(
                        shape = VettIdShape.card,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            a.payload,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(Spacing.m).testTag("critical_payload"),
                        )
                    }
                }
                a.payload.isEmpty() -> Text(
                    stringResource(R.string.approvals_payload_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.approvals_payload_mismatch_title),
                    body = stringResource(R.string.approvals_payload_mismatch_body),
                    modifier = Modifier.testTag("payload_mismatch"),
                )
            }
            a.exp?.let { Text(stringResource(R.string.approvals_expires, Times.full(it)), style = MaterialTheme.typography.bodySmall) }
        }
        is Approval.ShareDecision -> {
            Label(stringResource(R.string.approvals_items_asked))
            a.items.forEach { Text("• ${it.name} (${it.category})", style = MaterialTheme.typography.bodyLarge) }
        }
        is Approval.DeviceRequest -> {
            Label(stringResource(R.string.approvals_device))
            Value(listOfNotNull(a.deviceName, a.role).joinToString(" · ").ifEmpty { "?" })
            a.requestType?.let {
                Label(stringResource(R.string.approvals_request))
                Value(it)
            }
            Text(stringResource(R.string.approvals_device_v1), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
        }
    }
}
