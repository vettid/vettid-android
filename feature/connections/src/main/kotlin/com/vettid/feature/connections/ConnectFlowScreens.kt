package com.vettid.feature.connections

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.core.data.social.InviteTtl
import com.vettid.core.data.social.RequestEnd
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.QrCode
import com.vettid.core.ui.components.SafetyCode
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** What the invite screen can ask for. */
data class InviteActions(
    val onBack: () -> Unit = {},
    val onTtl: (InviteTtl) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onTick: () -> Unit = {},
    val onApprove: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onAskBlock: (Boolean) -> Unit = {},
    val onBlock: () -> Unit = {},
    val onCancelInvite: () -> Unit = {},
    val onAgain: () -> Unit = {},
    val onMessage: (String) -> Unit = {},
)

/**
 * Invite a connection (VAULT-MESSAGING §6.4): lifetime, then the QR code and
 * link, then the request with its safety code, then the connection.
 */
@Composable
fun InviteScreen(state: InviteUiState, actions: InviteActions, modifier: Modifier = Modifier) {
    when (state.step) {
        InviteStep.CHOOSE -> ChooseTtl(state, actions, modifier)
        InviteStep.SHOWING -> ShowInvite(state, actions, modifier)
        InviteStep.REQUEST, InviteStep.CONNECTING -> Request(state, actions, modifier)
        InviteStep.CONNECTED -> Connected(
            name = state.connectionName,
            onMessage = { state.connectionId?.let(actions.onMessage) },
            onDone = actions.onBack,
            modifier = modifier,
        )
        InviteStep.EXPIRED -> FormScaffold(
            title = stringResource(R.string.connections_invite_expired_title),
            body = stringResource(R.string.connections_invite_expired_body),
            primaryLabel = stringResource(R.string.connections_invite_again),
            onPrimary = actions.onAgain,
            onBack = actions.onBack,
            modifier = modifier,
        ) {}
        // The other member declined (0.10.5, §6.4).
        InviteStep.DECLINED -> FormScaffold(
            title = peerDeclineText(outgoing = false, name = state.declinedName ?: state.request?.name),
            body = stringResource(R.string.connections_peer_declined_body),
            primaryLabel = stringResource(R.string.connections_done),
            onPrimary = actions.onBack,
            onBack = actions.onBack,
            modifier = modifier.testTag("invite_peer_declined"),
        ) {}
    }
    if (state.confirmBlock) {
        ConfirmDialog(
            title = stringResource(R.string.connections_block_request_title),
            text = stringResource(R.string.connections_block_request_body),
            confirmLabel = stringResource(R.string.connections_block),
            onConfirm = actions.onBlock,
            onDismiss = { actions.onAskBlock(false) },
            destructive = true,
        )
    }
}

@Composable
private fun ChooseTtl(state: InviteUiState, actions: InviteActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.connections_invite_title),
        body = stringResource(R.string.connections_invite_body),
        primaryLabel = stringResource(R.string.connections_invite_create),
        onPrimary = actions.onCreate,
        onBack = actions.onBack,
        busy = state.busy,
        modifier = modifier,
    ) {
        Column(Modifier.selectableGroup()) {
            state.ttls.forEach { ttl ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.touchTarget)
                        .selectable(selected = ttl == state.ttl, onClick = { actions.onTtl(ttl) }, role = Role.RadioButton)
                        .padding(vertical = Spacing.xs)
                        .testTag("ttl_${ttl.seconds}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = ttl == state.ttl, onClick = null)
                    Spacer(Modifier.size(Spacing.m))
                    Column {
                        Text(stringResource(ttlLabel(ttl)), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(
                                if (ttl.remote) R.string.connections_ttl_remote_hint else R.string.connections_ttl_in_person_hint,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (state.ttl.remote) {
            Spacer(Modifier.height(Spacing.l))
            NoticeCard(
                kind = NoticeKind.INFO,
                title = stringResource(R.string.connections_remote_title),
                body = stringResource(R.string.connections_remote_body),
            )
        }
        FailureText(state.error, Modifier.padding(top = Spacing.l))
    }
}

private fun ttlLabel(ttl: InviteTtl): Int = when (ttl) {
    InviteTtl.TEN_MINUTES -> R.string.connections_ttl_10m
    InviteTtl.ONE_HOUR -> R.string.connections_ttl_1h
    InviteTtl.ONE_DAY -> R.string.connections_ttl_24h
    InviteTtl.SEVEN_DAYS -> R.string.connections_ttl_7d
}

@Composable
private fun ShowInvite(state: InviteUiState, actions: InviteActions, modifier: Modifier) {
    val invite = state.invite ?: return
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(invite.inviteId) {
        while (true) {
            now = Instant.now()
            actions.onTick()
            delay(TICK_MS)
        }
    }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.connections_share_title)
    val shareText = stringResource(R.string.connections_share_text, invite.url)
    FormScaffold(
        title = stringResource(if (invite.remote) R.string.connections_show_remote_title else R.string.connections_show_title),
        body = stringResource(if (invite.remote) R.string.connections_show_remote_body else R.string.connections_show_body),
        primaryLabel = null,
        onPrimary = {},
        onBack = actions.onBack,
        secondaryLabel = stringResource(R.string.connections_invite_cancel),
        onSecondary = actions.onCancelInvite,
        busy = state.busy,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrCode(
                content = invite.qr,
                contentDescription = stringResource(R.string.connections_qr_cd),
                modifier = Modifier.testTag("invite_qr"),
            )
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(R.string.connections_expires_in, Times.minutesLeft(invite.exp, now), Times.time(invite.exp)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.l))
        Text(stringResource(R.string.connections_link_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(Spacing.xs))
        Surface(shape = VettIdShape.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    invite.url,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = LINK_LINES,
                    modifier = Modifier.padding(Spacing.m).testTag("invite_link"),
                )
            }
        }
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedButton(
                onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(shareTitle, invite.url))) }
                    copied = true
                },
                shape = VettIdShape.pill,
                modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(
                    stringResource(if (copied) R.string.connections_copied else R.string.connections_copy),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                    context.startActivity(Intent.createChooser(send, shareTitle))
                },
                shape = VettIdShape.pill,
                modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(stringResource(R.string.connections_share), color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(Spacing.xl))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(Spacing.m))
            Text(stringResource(R.string.connections_waiting_scan), style = MaterialTheme.typography.bodyMedium)
        }
        FailureText(state.error, Modifier.padding(top = Spacing.l))
    }
}

@Composable
private fun Request(state: InviteUiState, actions: InviteActions, modifier: Modifier) {
    val req = state.request ?: return
    ConnectionRequestContent(
        name = req.name,
        sas = req.sas,
        remote = req.remote,
        approved = state.step == InviteStep.CONNECTING,
        autoApproved = state.autoApproved,
        busy = state.busy,
        error = state.error,
        onApprove = actions.onApprove,
        onDecline = actions.onDecline,
        onBlock = { actions.onAskBlock(true) },
        onBack = actions.onBack,
        modifier = modifier,
    )
}

/** The safety code, large, in a bordered box (§6.3); `sas` test tag. */
@Composable
internal fun SasBox(sas: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, VettIdShape.card)
            .padding(vertical = Spacing.l)
            .testTag("sas"),
        contentAlignment = Alignment.Center,
    ) { SafetyCode(sas) }
}

@Composable
private fun WaitingRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("waiting_peer"),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(Spacing.m))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A connection request at the inviter (§6.4, 0.10.3): the name the requester
 * gave (self-asserted, labelled so), the safety code to compare with them, and
 * approve / decline / block. After this member's approval ([approved]) the code
 * stays on screen while the other member approves on their phone.
 */
@Composable
@Suppress("LongParameterList")
fun ConnectionRequestContent(
    name: String?,
    sas: String,
    remote: Boolean,
    busy: Boolean,
    error: com.vettid.core.data.vault.FailureKind?,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onBlock: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    approved: Boolean = false,
    autoApproved: Boolean = false,
) {
    val who = name ?: stringResource(R.string.connections_request_them)
    FormScaffold(
        title = stringResource(R.string.connections_request_title),
        body = stringResource(
            if (remote) R.string.connections_request_body_remote else R.string.connections_request_body,
            name ?: stringResource(R.string.connections_request_someone),
        ),
        primaryLabel = if (approved) null else stringResource(R.string.connections_request_approve),
        onPrimary = onApprove,
        onBack = onBack,
        busy = busy,
        secondaryLabel = stringResource(R.string.connections_request_decline),
        onSecondary = onDecline,
        modifier = modifier.testTag("connection_request"),
    ) {
        Text(stringResource(R.string.connections_request_name_label), style = MaterialTheme.typography.labelLarge)
        Text(
            name ?: stringResource(R.string.connections_request_no_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.connections_self_asserted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        Text(stringResource(R.string.connections_safety_code), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(Spacing.s))
        SasBox(sas)
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(if (remote) R.string.connections_sas_hint_remote else R.string.connections_sas_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (autoApproved) {
            Spacer(Modifier.height(Spacing.m))
            NoticeCard(
                kind = NoticeKind.INFO,
                title = stringResource(R.string.connections_auto_approved_title),
                body = stringResource(R.string.connections_auto_approved, who),
            )
        }
        if (approved) {
            Spacer(Modifier.height(Spacing.l))
            WaitingRow(stringResource(R.string.connections_waiting_peer, who))
        }
        Spacer(Modifier.height(Spacing.l))
        androidx.compose.material3.TextButton(
            onClick = onBlock,
            enabled = !busy,
            modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("block_request"),
        ) { Text(stringResource(R.string.connections_block_request), color = MaterialTheme.colorScheme.error) }
        FailureText(error, Modifier.padding(top = Spacing.s))
    }
}

@Composable
internal fun Connected(name: String?, onMessage: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    FormScaffold(
        title = stringResource(R.string.connections_connected_title),
        body = name?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.connections_connected_body, it) }
            ?: stringResource(R.string.connections_connected_body_unnamed),
        primaryLabel = stringResource(R.string.connections_send_message),
        onPrimary = onMessage,
        secondaryLabel = stringResource(R.string.connections_done),
        onSecondary = onDone,
        modifier = modifier.testTag("connected"),
        header = {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = com.vettid.core.ui.theme.VettIdTheme.colors.success,
                modifier = Modifier.size(56.dp),
            )
        },
    ) {}
}

/** What the accept screen can ask for. */
data class AcceptActions(
    val onBack: () -> Unit = {},
    val onInput: (String) -> Unit = {},
    val onAccept: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onMessage: (String) -> Unit = {},
    val onApprove: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onOpenConnection: (String) -> Unit = {},
)

/**
 * Accept an invitation (§6.4, 0.10.3): paste (or confirm an opened link), wait
 * for the handshake, compare the safety code with the inviter and approve,
 * then wait for the inviter's approval.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun AcceptScreen(state: AcceptUiState, actions: AcceptActions, modifier: Modifier = Modifier) {
    val who = state.name ?: state.connectionName ?: stringResource(R.string.connections_request_them)
    when (state.step) {
        AcceptStep.INPUT, AcceptStep.ACCEPTING -> FormScaffold(
            title = stringResource(if (state.fromLink) R.string.connections_open_link_title else R.string.connections_accept_title),
            body = stringResource(if (state.fromLink) R.string.connections_open_link_body else R.string.connections_accept_body),
            primaryLabel = stringResource(R.string.connections_accept_connect),
            onPrimary = actions.onAccept,
            primaryEnabled = state.input.isNotBlank(),
            busy = state.step == AcceptStep.ACCEPTING,
            onBack = actions.onBack,
            secondaryLabel = stringResource(R.string.connections_accept_scan_instead),
            onSecondary = actions.onScan,
            modifier = modifier,
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = actions.onInput,
                label = { Text(stringResource(R.string.connections_accept_field)) },
                isError = state.error != null,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag("invite_input"),
            )
            FailureText(state.error, Modifier.padding(top = Spacing.s))
        }
        AcceptStep.WAITING -> FormScaffold(
            title = state.name?.let { stringResource(R.string.connections_waiting_title, it) }
                ?: stringResource(R.string.connections_waiting_title_unnamed),
            body = stringResource(R.string.connections_waiting_body),
            primaryLabel = stringResource(R.string.connections_done),
            onPrimary = actions.onBack,
            onBack = actions.onBack,
            secondaryLabel = stringResource(R.string.connections_request_decline),
            onSecondary = actions.onDecline,
            busy = state.busy,
            modifier = modifier.testTag("accept_waiting"),
        ) {
            WaitingRow(stringResource(R.string.connections_waiting_handshake))
            FailureText(state.error, Modifier.padding(top = Spacing.s))
        }
        AcceptStep.COMPARE, AcceptStep.APPROVED -> {
            val approved = state.step == AcceptStep.APPROVED
            FormScaffold(
                title = stringResource(if (approved) R.string.connections_approved_title else R.string.connections_compare_title),
                body = if (approved) {
                    stringResource(R.string.connections_approved_body, who)
                } else {
                    stringResource(
                        if (state.remote) R.string.connections_sas_hint_invitee_remote else R.string.connections_sas_hint_invitee,
                        who,
                    )
                },
                primaryLabel = stringResource(if (approved) R.string.connections_done else R.string.connections_compare_approve),
                onPrimary = if (approved) actions.onBack else actions.onApprove,
                onBack = actions.onBack,
                busy = state.busy,
                secondaryLabel = stringResource(R.string.connections_request_decline),
                onSecondary = actions.onDecline,
                modifier = modifier.testTag(if (approved) "accept_approved" else "accept_compare"),
            ) {
                state.sas?.let {
                    Text(stringResource(R.string.connections_safety_code), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(Spacing.s))
                    SasBox(it)
                }
                if (approved) {
                    Spacer(Modifier.height(Spacing.xl))
                    WaitingRow(stringResource(R.string.connections_waiting_peer, who))
                }
                FailureText(state.error, Modifier.padding(top = Spacing.s))
            }
        }
        AcceptStep.CONNECTED -> Connected(
            name = state.connectionName,
            onMessage = { state.connectionId?.let(actions.onMessage) },
            onDone = actions.onBack,
            modifier = modifier,
        )
        AcceptStep.EXISTS -> FormScaffold(
            title = stringResource(R.string.connections_exists_title),
            body = stringResource(R.string.connections_exists_body, who),
            primaryLabel = stringResource(R.string.connections_exists_open),
            onPrimary = { state.connectionId?.let(actions.onOpenConnection) },
            onBack = actions.onBack,
            secondaryLabel = stringResource(R.string.connections_done),
            onSecondary = actions.onBack,
            modifier = modifier.testTag("accept_exists"),
        ) {}
        AcceptStep.ENDED -> {
            // The inviter declined (0.10.5, §6.4): "<name> declined your connection request".
            val peer = state.end == RequestEnd.PEER_DECLINED
            FormScaffold(
                title = if (peer) peerDeclineText(outgoing = true, name = state.name) else stringResource(R.string.connections_ended_title),
                body = stringResource(
                    when (state.end) {
                        RequestEnd.PEER_DECLINED -> R.string.connections_peer_declined_body
                        RequestEnd.DECLINED -> R.string.connections_ended_declined
                        RequestEnd.EXPIRED -> R.string.connections_ended_expired
                        else -> R.string.connections_ended_failed
                    },
                ),
                primaryLabel = stringResource(R.string.connections_done),
                onPrimary = actions.onBack,
                onBack = actions.onBack,
                modifier = modifier.testTag(if (peer) "accept_peer_declined" else "accept_ended"),
            ) {}
        }
    }
}

/** Scan an invitation's QR code; nothing is sent until it is a valid connection invitation. */
@Composable
fun ScanScreen(
    state: ScanUiState,
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
    onPaste: () -> Unit,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    camera: @Composable (Modifier) -> Unit = { m -> QrScanner(onText = onScanned, modifier = m) },
) {
    LaunchedEffect(state.link) { state.link?.let(onLink) }
    val permission = rememberCameraPermission()
    LaunchedEffect(Unit) { if (!permission.granted) permission.request() }
    FormScaffold(
        title = stringResource(R.string.connections_scan_title),
        body = stringResource(R.string.connections_scan_body),
        primaryLabel = if (permission.granted) null else stringResource(R.string.connections_scan_allow),
        onPrimary = permission.request,
        onBack = onBack,
        secondaryLabel = stringResource(R.string.connections_scan_paste_instead),
        onSecondary = onPaste,
        modifier = modifier,
    ) {
        if (permission.granted) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(SCANNER_HEIGHT)
                    .border(2.dp, MaterialTheme.colorScheme.primary, VettIdShape.card)
                    .padding(2.dp)
                    .clip(VettIdShape.card),
            ) {
                camera(Modifier.fillMaxSize())
            }
        } else {
            NoticeCard(
                kind = NoticeKind.INFO,
                title = stringResource(R.string.connections_scan_permission_title),
                body = stringResource(R.string.connections_scan_permission_body),
            )
        }
        FailureText(state.problem, Modifier.padding(top = Spacing.m))
        Spacer(Modifier.navigationBarsPadding())
    }
}

private const val TICK_MS = 1_000L
private const val LINK_LINES = 4
private val SCANNER_HEIGHT = 320.dp
