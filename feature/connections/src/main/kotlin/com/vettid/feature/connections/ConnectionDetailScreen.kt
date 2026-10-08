package com.vettid.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.social.AuthenticationState
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.FloatingPillActionBar
import com.vettid.core.ui.components.InitialTile
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PillAction
import com.vettid.core.ui.components.SafetyCode
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.TileStyle
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.R as UiR
import com.vettid.core.ui.theme.Spacing

/** What the connection detail screen can ask for. */
data class DetailActions(
    val onBack: () -> Unit = {},
    val onMessage: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onAuthenticate: () -> Unit = {},
    val onEdit: (Boolean) -> Unit = {},
    val onAlias: (String) -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onAsk: (DetailConfirm?) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    /** The connection's History (ANDROID-PLAN 0.1.11: History with the connection preset). */
    val onHistory: () -> Unit = {},
    /** What this connection can see: share rules and grants (VAULT-ITEMS §6). */
    val onSharing: () -> Unit = {},
    /** What this connection shares with the member (received grants, §10.12). */
    val onSharedWithYou: () -> Unit = {},
)

/**
 * A connection (ANDROID-PLAN §4, 0.1.10): titled "First Last" from the names on the peer's VettID account (the
 * owner's alias may replace it), the display name secondary; the profile shared with you (the names labelled as the
 * account's, never verified; the extras as self-asserted), the vault key fingerprint, safety code, member
 * authentication, alias and note; the pill holds message, favourite, edit, block and remove (both confirmed).
 */
@Composable
fun ConnectionDetailScreen(state: ConnectionDetailUiState, actions: DetailActions, modifier: Modifier = Modifier) {
    LaunchedEffect(state.gone) { if (state.gone) actions.onBack() }
    val c = state.connection
    val title = c?.displayName?.ifBlank { null } ?: stringResource(R.string.connections_name_not_shared)
    // In sentences the name is bidi-isolated (§10.8); the heading and the tile take it as is.
    val name = AccountNames.isolate(title)
    DetailScaffold(
        onBackClick = actions.onBack,
        modifier = modifier,
        overlay = {
            if (c != null) {
                FloatingPillActionBar(
                    listOfNotNull(
                        PillAction(
                            Icons.AutoMirrored.Outlined.Chat,
                            stringResource(R.string.connections_detail_message, name),
                            actions.onMessage,
                        )
                            .takeIf { c.state == ConnectionState.ACTIVE },
                        PillAction(
                            if (c.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                            stringResource(
                                if (c.favorite) UiR.string.core_ui_favorite_remove else UiR.string.core_ui_favorite_add,
                                name,
                            ),
                            actions.onFavorite,
                        ),
                        PillAction(Icons.Outlined.Edit, stringResource(R.string.connections_detail_edit), { actions.onEdit(true) }),
                        PillAction(Icons.Outlined.History, stringResource(R.string.connections_detail_history), actions.onHistory),
                        PillAction(
                            Icons.Outlined.Block,
                            stringResource(R.string.connections_block),
                            { actions.onAsk(DetailConfirm.BLOCK) },
                            destructive = true,
                        ),
                        PillAction(
                            Icons.Outlined.PersonRemove,
                            stringResource(R.string.connections_remove),
                            { actions.onAsk(DetailConfirm.REMOVE) },
                            destructive = true,
                        ),
                    ),
                )
            }
        },
    ) {
        if (c == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            DetailContent(state, c, title, name, actions)
        }
    }
    DetailDialogs(state, name, actions)
}

@Composable
private fun DetailContent(state: ConnectionDetailUiState, c: ConnectionInfo, title: String, name: String, actions: DetailActions) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = PILL_SPACE),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
            InitialTile(
                name = title,
                size = 72,
                style = if (c.favorite) TileStyle.Favorite else TileStyle.Connection,
                photo = com.vettid.core.ui.components.rememberProfilePhoto(c.photo),
            )
            Spacer(Modifier.height(Spacing.m))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }.testTag("detail_name"),
            )
            // Under the owner's alias the names on the peer's account are still shown (§10.8).
            c.accountName?.takeIf { c.alias != null && it != title }?.let {
                Text(
                    stringResource(R.string.connections_detail_their_name, AccountNames.isolate(it)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("detail_account_name"),
                )
            }
            c.secondaryName?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("detail_display_name"),
                )
            }
            Text(
                stateLabel(c.state) ?: stringResource(R.string.connections_state_active),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        state.notice?.let { n ->
            NoticeCard(
                kind = NoticeKind.SUCCESS,
                title = stringResource(
                    if (n == DetailNotice.SAVED) R.string.connections_detail_saved else R.string.connections_auth_requested_title,
                ),
                body = stringResource(
                    if (n == DetailNotice.SAVED) R.string.connections_detail_saved_body else R.string.connections_auth_requested_body,
                    name,
                ),
                modifier = Modifier.padding(horizontal = Spacing.s),
                actions = { TextButton(onClick = actions.onDismissNotice) { Text(stringResource(R.string.connections_ok)) } },
            )
        }
        state.error?.let { e ->
            NoticeCard(
                kind = NoticeKind.WARNING,
                title = stringResource(R.string.connections_error_title),
                body = stringResource(e.messageRes()),
                modifier = Modifier.padding(horizontal = Spacing.s),
                actions = { TextButton(onClick = actions.onDismissNotice) { Text(stringResource(R.string.connections_ok)) } },
            )
        }
        ProfileCard(c)
        if (c.state == ConnectionState.ACTIVE) SharingCard(name, actions)
        SafetyCard(state)
        AuthCard(state.auth, name, state.busy, c.state == ConnectionState.ACTIVE, actions.onAuthenticate)
        NotesCard(c, actions)
        DetailCard {
            CardTitle(stringResource(R.string.connections_detail_about))
            c.createdAt?.let { InfoLine(stringResource(R.string.connections_detail_since), Times.full(it)) }
            c.lastActiveAt?.let { InfoLine(stringResource(R.string.connections_detail_last_active), Times.full(it)) }
        }
        c.keyFingerprint?.let { FingerprintCard(it) }
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

@Composable
private fun ProfileCard(c: ConnectionInfo) {
    DetailCard(Modifier.testTag("profile_card")) {
        CardTitle(stringResource(R.string.connections_detail_profile))
        val account = c.accountName
        if (account == null && c.name.isBlank() && c.sharedItems.isEmpty()) {
            Text(stringResource(R.string.connections_detail_profile_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (account != null) {
            InfoLine(stringResource(R.string.connections_detail_account_name), account)
            Text(
                stringResource(R.string.connections_names_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.s))
        }
        if (c.name.isNotBlank() || c.sharedItems.isNotEmpty()) {
            if (c.name.isNotBlank()) InfoLine(stringResource(R.string.connections_detail_profile_name), c.name)
            if (c.sharedItems.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.s))
                Text(stringResource(R.string.connections_detail_shared_items), style = MaterialTheme.typography.labelLarge)
                c.sharedItems.forEach { item ->
                    Text(item.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Spacing.xs))
                    item.fields.forEach { (label, value) -> InfoLine(label, value) }
                }
            }
            Spacer(Modifier.height(Spacing.s))
            Text(
                stringResource(R.string.connections_self_asserted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Sharing with this connection (VAULT-ITEMS §6): what it can see of the vault, and what it shares with the member. */
@Composable
private fun SharingCard(name: String, actions: DetailActions) {
    DetailCard(Modifier.testTag("sharing_card")) {
        CardTitle(stringResource(R.string.connections_detail_sharing))
        TextButton(onClick = actions.onSharing, modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("open_sharing")) {
            Text(stringResource(R.string.connections_detail_sharing_mine, name))
        }
        TextButton(onClick = actions.onSharedWithYou, modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("open_shared")) {
            Text(stringResource(R.string.connections_detail_sharing_theirs, name))
        }
    }
}

/** The fingerprint of the peer vault's pinned identity key (§10.8): identifies the vault, not a person. */
@Composable
private fun FingerprintCard(fingerprint: String) {
    DetailCard(Modifier.testTag("fingerprint_card")) {
        CardTitle(stringResource(R.string.connections_detail_key))
        Text(
            fingerprint,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.testTag("detail_fingerprint"),
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            stringResource(R.string.connections_detail_key_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SafetyCard(state: ConnectionDetailUiState) {
    DetailCard {
        CardTitle(stringResource(R.string.connections_safety_code))
        val rec = state.safetyCode
        if (rec != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SafetyCode(rec.sas) }
            Spacer(Modifier.height(Spacing.s))
            Text(
                stringResource(R.string.connections_detail_sas_when, Times.full(rec.at)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(stringResource(R.string.connections_detail_sas_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AuthCard(auth: AuthenticationState?, name: String, busy: Boolean, active: Boolean, onAuthenticate: () -> Unit) {
    DetailCard(Modifier.testTag("auth_card")) {
        CardTitle(stringResource(R.string.connections_auth_title))
        val verified = auth?.verifiedAt
        val line = when {
            auth?.waitingUntil != null -> stringResource(R.string.connections_auth_waiting, name)
            auth?.keyChanged == true -> stringResource(R.string.connections_auth_key_changed, name)
            auth?.lastResult == "denied" -> stringResource(R.string.connections_auth_denied, name)
            auth?.lastResult == "bad_signature" -> stringResource(R.string.connections_auth_bad, name)
            verified != null -> stringResource(R.string.connections_auth_ok, name, Times.full(verified))
            else -> stringResource(R.string.connections_auth_never, name)
        }
        Text(line, modifier = Modifier.testTag("auth_status"))
        Spacer(Modifier.height(Spacing.xs))
        Text(
            stringResource(R.string.connections_auth_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (active) {
            Spacer(Modifier.height(Spacing.m))
            SecondaryButton(
                stringResource(R.string.connections_auth_request),
                onAuthenticate,
                enabled = !busy && auth?.waitingUntil == null,
                modifier = Modifier.testTag("auth_request"),
            )
        }
    }
}

@Composable
private fun NotesCard(c: ConnectionInfo, actions: DetailActions) {
    DetailCard {
        CardTitle(stringResource(R.string.connections_detail_yours))
        InfoLine(stringResource(R.string.connections_detail_alias), c.alias ?: stringResource(R.string.connections_detail_none))
        InfoLine(stringResource(R.string.connections_detail_note), c.note ?: stringResource(R.string.connections_detail_none))
        Spacer(Modifier.height(Spacing.xs))
        Text(
            stringResource(R.string.connections_detail_yours_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { actions.onEdit(true) }, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
            Text(stringResource(R.string.connections_detail_edit))
        }
    }
}

@Composable
private fun DetailDialogs(state: ConnectionDetailUiState, name: String, actions: DetailActions) {
    if (state.editing) {
        AlertDialog(
            onDismissRequest = { actions.onEdit(false) },
            title = { Text(stringResource(R.string.connections_detail_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    OutlinedTextField(
                        value = state.aliasInput,
                        onValueChange = actions.onAlias,
                        label = { Text(stringResource(R.string.connections_detail_alias)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("alias_input"),
                    )
                    OutlinedTextField(
                        value = state.noteInput,
                        onValueChange = actions.onNote,
                        label = { Text(stringResource(R.string.connections_detail_note)) },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = actions.onSave) { Text(stringResource(R.string.connections_save)) } },
            dismissButton = {
                TextButton(onClick = { actions.onEdit(false) }) { Text(stringResource(UiR.string.core_ui_cancel)) }
            },
        )
    }
    when (state.confirm) {
        DetailConfirm.REMOVE -> ConfirmDialog(
            title = stringResource(R.string.connections_remove_title, name),
            text = stringResource(R.string.connections_remove_body, name),
            confirmLabel = stringResource(R.string.connections_remove),
            onConfirm = actions.onConfirm,
            onDismiss = { actions.onAsk(null) },
            destructive = true,
        )
        DetailConfirm.BLOCK -> ConfirmDialog(
            title = stringResource(R.string.connections_block_title, name),
            text = stringResource(R.string.connections_block_body, name),
            confirmLabel = stringResource(R.string.connections_block),
            onConfirm = actions.onConfirm,
            onDismiss = { actions.onAsk(null) },
            destructive = true,
        )
        null -> Unit
    }
}

private val PILL_SPACE = 104.dp
