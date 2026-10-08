package com.vettid.feature.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.OutstandingInvite
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.BottomFloatingControls
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.ConnectionRow
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.components.VettIdFab
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import kotlinx.serialization.Serializable

/** Type-safe navigation route of the Connections screen. */
@Serializable
data object ConnectionsRoute

/** A connection's detail screen. */
@Serializable
data class ConnectionDetailRoute(val connectionId: String) {
    companion object {
        const val ARG = "connectionId"
    }
}

/** Invite a connection (the drawer's create group, ANDROID-PLAN §4). */
@Serializable
data object InviteRoute

/** Scan an invitation's QR code. */
@Serializable
data object ScanRoute

/**
 * Accept an invitation: paste a link, or [link] from the scanner (accepted at
 * once) or from an opened link ([opened]: the member confirms first).
 */
@Serializable
data class AcceptRoute(val link: String? = null, val opened: Boolean = false) {
    companion object {
        const val ARG = "link"
        const val ARG_OPENED = "opened"
    }
}

/** What the Connections screens ask the app shell for. */
data class ConnectionsHost(
    val navigate: (Any) -> Unit,
    val onBack: () -> Unit,
    /** Replaces the current screen (scanner → accept). */
    val replace: (Any) -> Unit,
    /** The conversation with a connection (messages feature). */
    val onOpenConversation: (String) -> Unit,
    /** The connection's History (history feature). */
    val onOpenHistory: (String) -> Unit = {},
    /** What the connection can see of the member's vault (items feature). */
    val onOpenSharing: (String) -> Unit = {},
    /** What the connection shares with the member (items feature). */
    val onOpenShared: (String) -> Unit = {},
)

/** Registers the Connections destinations. */
@Suppress("LongMethod")
fun NavGraphBuilder.connectionsDestination(chrome: ShellChrome, host: ConnectionsHost) {
    composable<ConnectionsRoute> {
        val vm: ConnectionsViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ConnectionsScreen(
            state = state,
            chrome = chrome,
            actions = ConnectionsActions(
                onOpen = { host.navigate(ConnectionDetailRoute(it)) },
                onFavorite = vm::setFavorite,
                onAdd = { vm.showAddSheet(true) },
                onDismissAdd = { vm.showAddSheet(false) },
                onInvite = {
                    vm.showAddSheet(false)
                    host.navigate(InviteRoute)
                },
                onScan = {
                    vm.showAddSheet(false)
                    host.navigate(ScanRoute)
                },
                onPaste = {
                    vm.showAddSheet(false)
                    host.navigate(AcceptRoute())
                },
                onCancelInvite = vm::askCancel,
                onConfirmCancel = vm::confirmCancel,
                onRetry = vm::refresh,
                onDismissError = vm::dismissError,
                onDismissPeerDecline = vm::dismissPeerDecline,
            ),
        )
    }
    composable<ConnectionDetailRoute> {
        val vm: ConnectionDetailViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ConnectionDetailScreen(
            state = state,
            actions = DetailActions(
                onBack = host.onBack,
                onMessage = { host.onOpenConversation(state.connectionId) },
                onFavorite = vm::toggleFavorite,
                onAuthenticate = vm::requestAuthentication,
                onEdit = vm::edit,
                onAlias = vm::setAlias,
                onNote = vm::setNote,
                onSave = vm::saveNames,
                onAsk = vm::ask,
                onConfirm = vm::confirm,
                onDismissNotice = vm::dismissNotice,
                onHistory = { host.onOpenHistory(state.connectionId) },
                onSharing = { host.onOpenSharing(state.connectionId) },
                onSharedWithYou = { host.onOpenShared(state.connectionId) },
            ),
        )
    }
    composable<InviteRoute> {
        val vm: InviteViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        InviteScreen(
            state = state,
            actions = InviteActions(
                onBack = host.onBack,
                onTtl = vm::setTtl,
                onCreate = vm::create,
                onTick = { vm.tick() },
                onApprove = vm::approve,
                onDecline = vm::decline,
                onAskBlock = vm::askBlock,
                onBlock = vm::block,
                onCancelInvite = vm::cancelInvite,
                onAgain = vm::again,
                onMessage = { id -> host.onOpenConversation(id) },
            ),
        )
    }
    composable<ScanRoute> {
        val vm: ScanViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ScanScreen(
            state = state,
            onBack = host.onBack,
            onScanned = vm::onScanned,
            onPaste = { host.replace(AcceptRoute()) },
            onLink = { link ->
                vm.consumed()
                host.replace(AcceptRoute(link))
            },
        )
    }
    composable<AcceptRoute> {
        val vm: AcceptViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        AcceptScreen(
            state = state,
            actions = AcceptActions(
                onBack = host.onBack,
                onInput = vm::setInput,
                onAccept = vm::accept,
                onScan = { host.replace(ScanRoute) },
                onMessage = { id -> host.onOpenConversation(id) },
                onApprove = vm::approve,
                onDecline = vm::decline,
                onOpenConnection = { id -> host.replace(ConnectionDetailRoute(id)) },
            ),
        )
    }
}

/** What the Connections screen can ask for. */
data class ConnectionsActions(
    val onOpen: (String) -> Unit = {},
    val onFavorite: (String, Boolean) -> Unit = { _, _ -> },
    val onAdd: () -> Unit = {},
    val onDismissAdd: () -> Unit = {},
    val onInvite: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onCancelInvite: (OutstandingInvite?) -> Unit = {},
    val onConfirmCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onDismissPeerDecline: (String) -> Unit = {},
)

/**
 * Connections (ANDROID-PLAN §4, Proton's contacts): initial tiles (teal for
 * favourites), state, a star per row; outstanding invitations first; the add
 * button offers invite (QR or link), scan, or paste.
 */
@Composable
fun ConnectionsScreen(state: ConnectionsUiState, chrome: ShellChrome, actions: ConnectionsActions, modifier: Modifier = Modifier) {
    TopLevelScaffold(
        title = stringResource(R.string.connections_title),
        chrome = chrome,
        modifier = modifier,
        overlay = {
            BottomFloatingControls(
                end = {
                    VettIdFab(
                        icon = Icons.Outlined.PersonAdd,
                        // ANDROID-PLAN 0.1.11: the button invites (QR or link, or scan the other's QR).
                        contentDescription = stringResource(R.string.connections_invite_title),
                        onClick = actions.onAdd,
                        modifier = Modifier.testTag("add_connection"),
                    )
                },
            )
        },
    ) {
        val error = state.error
        when {
            state.loading && state.connections.isEmpty() -> Centered {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null && state.connections.isEmpty() && state.invites.isEmpty() && state.peerDeclines.isEmpty() -> Centered {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.connections_error_title),
                    body = stringResource(error.messageRes()),
                    modifier = Modifier.padding(Spacing.xl),
                    actions = { TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.connections_retry)) } },
                )
            }
            state.connections.isEmpty() && state.invites.isEmpty() && state.peerDeclines.isEmpty() -> EmptyState(
                icon = Icons.Outlined.People,
                title = stringResource(R.string.connections_empty_title),
                body = stringResource(R.string.connections_empty_body),
            )
            else -> ConnectionList(state, actions)
        }
    }
    if (state.addSheet) AddSheet(actions)
    if (state.cancelling != null) {
        ConfirmDialog(
            title = stringResource(R.string.connections_cancel_invite_title),
            text = stringResource(R.string.connections_cancel_invite_body),
            confirmLabel = stringResource(R.string.connections_cancel_invite_confirm),
            onConfirm = actions.onConfirmCancel,
            onDismiss = { actions.onCancelInvite(null) },
            destructive = true,
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ConnectionList(state: ConnectionsUiState, actions: ConnectionsActions) {
    LazyColumn(Modifier.fillMaxSize().testTag("connections"), contentPadding = PaddingValues(bottom = LIST_BOTTOM)) {
        val error = state.error
        if (error != null) {
            item {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.connections_error_title),
                    body = stringResource(error.messageRes()),
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                    actions = { TextButton(onClick = actions.onDismissError) { Text(stringResource(R.string.connections_ok)) } },
                )
            }
        }
        items(state.peerDeclines, key = { "declined-${it.requestId}" }) { d ->
            PeerDeclineNotice(
                d,
                onDismiss = { actions.onDismissPeerDecline(d.requestId) },
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
            )
        }
        if (state.invites.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.connections_invites_header)) }
            items(state.invites, key = { "invite-${it.inviteId}" }) { InviteRow(it, onCancel = { actions.onCancelInvite(it) }) }
            item { SectionHeader(stringResource(R.string.connections_header)) }
        }
        items(state.connections, key = { it.id }) { c -> ConnectionItem(c, actions) }
    }
}

@Composable
private fun ConnectionItem(c: ConnectionInfo, actions: ConnectionsActions) {
    val name = c.displayName.ifBlank { stringResource(R.string.connections_name_not_shared) }
    ConnectionRow(
        name = name,
        favorite = c.favorite,
        onFavoriteChange = { actions.onFavorite(c.id, it) },
        supporting = supportingLine(c),
        meta = c.lastActiveAt?.let { Times.short(it) },
        onClick = { actions.onOpen(c.id) },
        modifier = Modifier.testTag("connection_${c.id}"),
        photo = com.vettid.core.ui.components.rememberProfilePhoto(c.photo),
    )
}

@Composable
private fun supportingLine(c: ConnectionInfo): String = (listOfNotNull(stateLabel(c.state)) + secondaryNames(c))
    .joinToString(" · ")
    .ifEmpty { stringResource(R.string.connections_state_active) }

/**
 * What a connection's secondary text adds to its title (VAULT-MESSAGING 0.18.0 §10.8): under an alias, the names on
 * the peer's account; and the display name, if any, when it differs from the title.
 */
internal fun secondaryNames(c: ConnectionInfo): List<String> = listOfNotNull(
    c.accountName?.takeIf { c.alias != null && it != c.displayName },
    c.secondaryName,
)

/** The state as a short label; null for active (the usual case, left unsaid in rows). */
@Composable
internal fun stateLabel(s: ConnectionState): String? = when (s) {
    ConnectionState.ACTIVE -> null
    ConnectionState.PENDING -> stringResource(R.string.connections_state_pending)
    ConnectionState.STALE -> stringResource(R.string.connections_state_stale)
    ConnectionState.BLOCKED -> stringResource(R.string.connections_state_blocked)
    ConnectionState.OTHER -> stringResource(R.string.connections_state_other)
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.gutter + Spacing.xs, top = Spacing.l, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

@Composable
private fun InviteRow(invite: OutstandingInvite, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = Spacing.gutter, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (invite.remote) R.string.connections_invite_remote else R.string.connections_invite_in_person),
                style = MaterialTheme.typography.bodyLarge,
            )
            invite.exp?.let {
                Text(
                    stringResource(R.string.connections_invite_expires, Times.full(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.connections_cancel_invite_cd))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(actions: ConnectionsActions) {
    ModalBottomSheet(onDismissRequest = actions.onDismissAdd) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.l)) {
            Text(
                stringResource(R.string.connections_invite_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s).semantics { heading() },
            )
            SheetOption(
                Icons.Outlined.QrCode2,
                R.string.connections_add_invite,
                R.string.connections_add_invite_body,
                "add_invite",
                actions.onInvite,
            )
            SheetOption(
                Icons.Outlined.QrCodeScanner,
                R.string.connections_add_scan,
                R.string.connections_add_scan_body,
                "add_scan",
                actions.onScan,
            )
            SheetOption(
                Icons.Outlined.ContentPaste,
                R.string.connections_add_paste,
                R.string.connections_add_paste_body,
                "add_paste",
                actions.onPaste,
            )
        }
    }
}

@Composable
private fun SheetOption(icon: ImageVector, title: Int, body: Int, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.xl, vertical = Spacing.m)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A failure line under a form (shared by the connect screens). */
@Composable
internal fun FailureText(kind: FailureKind?, modifier: Modifier = Modifier) {
    if (kind == null) return
    Text(
        stringResource(kind.messageRes()),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.testTag("failure"),
    )
}

private val LIST_BOTTOM = 96.dp
