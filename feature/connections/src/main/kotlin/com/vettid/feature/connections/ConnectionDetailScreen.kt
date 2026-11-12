package com.vettid.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.social.AuthenticationState
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.FloatingPillActionBar
import com.vettid.core.ui.components.InitialTile
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PillAction
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.ServiceTag
import com.vettid.core.ui.components.TileStyle
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.R as UiR
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing

/** What the connection detail screen can ask for. */
data class DetailActions(
    val onBack: () -> Unit = {},
    val onMessage: () -> Unit = {},
    /** Calls the connection (builds and vaults with calls). */
    val onCall: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onAuthenticate: () -> Unit = {},
    val onAsk: (DetailConfirm?) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    /** The connection's History (ANDROID-PLAN 0.1.11: History with the connection preset). */
    val onHistory: () -> Unit = {},
    /** What this connection can see: share rules and grants (VAULT-ITEMS §6), to manage or revoke. */
    val onSharing: () -> Unit = {},
    /** What this connection shares with the member (received grants, §10.12), to fetch. */
    val onSharedWithYou: () -> Unit = {},
    /** "Share items…": a new share rule for this connection (§10.12). */
    val onShareItems: () -> Unit = {},
    /** A share rule of this connection's, to change. */
    val onOpenRule: (String) -> Unit = {},
    /** Asks to delete a share rule (null: never mind); [onDeleteRule] deletes it once confirmed. */
    val onAskDeleteRule: (ShareRule?) -> Unit = {},
    val onDeleteRule: () -> Unit = {},
    /** "Ask for something": a `grant.request` of this connection (§10.12). */
    val onAskForSomething: () -> Unit = {},
    /** Mutes or unmutes the connection's asks (0.23.0 §10.4.1). */
    val onMuteAsks: (Boolean) -> Unit = {},
    /** Resumes paused asks, or allows declined ones again (`connection.asks.resume`). */
    val onResumeAsks: () -> Unit = {},
    /** The votes from this service connection (ANDROID-PLAN 0.1.102 "Votes"). */
    val onOpenVotes: () -> Unit = {},
    /** The refresh icon beside the presence line (ANDROID-PLAN §4 "Presence"). */
    val onRefreshPresence: () -> Unit = {},
    /** The switch "Share my status with <First>". */
    val onSetPresenceShared: (Boolean) -> Unit = {},
    /** **Share my location**: opens the share sheet (ANDROID-PLAN §4 "Location"). */
    val onShareLocation: () -> Unit = {},
    /** **Stop** on "Sharing your location until …". */
    val onStopSharingLocation: () -> Unit = {},
    /** The incoming card (ANDROID-PLAN §4 "Location", L1b): the position as text. */
    val onOpenPosition: () -> Unit = {},
    /** **Stop receiving**. */
    val onStopReceiving: () -> Unit = {},
    /** **Ask for location** and its dialog. */
    val onAskLocation: () -> Unit = {},
    val onAskNote: (String) -> Unit = {},
    val onSendAsk: () -> Unit = {},
    val onCloseAsk: () -> Unit = {},
    /** (C2) The "Calls with <First>" switch. */
    val onSetCallsAllowed: (Boolean) -> Unit = {},
)

/**
 * A connection (ANDROID-PLAN §4, 0.1.10): titled "First Last" from the names on the peer's VettID account, the
 * display name secondary; the profile shared with you (the names labelled as the account's, never verified; the
 * extras as self-asserted), sharing both ways (two cards), member authentication and the vault key fingerprint, the lasting identity
 * check. The safety code belongs to the moment of connecting and is not shown here; the owner's alias and note, if a
 * vault holds any, are not shown either (owner decision 2026-10-08). The pill holds message, favourite, History and
 * remove (confirmed).
 */
@Composable
fun ConnectionDetailScreen(state: ConnectionDetailUiState, actions: DetailActions, modifier: Modifier = Modifier) {
    LaunchedEffect(state.gone) { if (state.gone) actions.onBack() }
    val c = state.connection
    val title = AccountNames.cleaned(c?.displayName) ?: stringResource(R.string.connections_name_not_shared)
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
                            // A service can't message or call (ANDROID-PLAN 0.1.90): the buttons are simply absent.
                            .takeIf { c.state == ConnectionState.ACTIVE && !c.service },
                        // Calls off: shown disabled, with where to turn them on (the hint below).
                        PillAction(
                            Icons.Outlined.Call,
                            stringResource(R.string.connections_detail_call),
                            actions.onCall,
                            enabled = state.canCall,
                            stateDescription = stringResource(R.string.connections_call_off_hint, firstOf(c, name))
                                .takeIf { state.callHintShown },
                        )
                            .takeIf { c.state == ConnectionState.ACTIVE && (state.canCall || state.callsOff) && !c.service },
                        PillAction(
                            if (c.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                            stringResource(
                                if (c.favorite) UiR.string.core_ui_favorite_remove else UiR.string.core_ui_favorite_add,
                                name,
                            ),
                            actions.onFavorite,
                        ),
                        PillAction(Icons.Outlined.History, stringResource(R.string.connections_detail_history), actions.onHistory),
                        PillAction(
                            Icons.Outlined.PersonRemove,
                            stringResource(R.string.connections_remove),
                            { actions.onAsk(DetailConfirm.REMOVE) },
                            destructive = true,
                        ),
                    ),
                    hint = stringResource(R.string.connections_call_off_hint, firstOf(c, name))
                        .takeIf { state.callHintShown },
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
    AskLocationDialog(state.locationAsk, c?.let { firstOf(it, name) } ?: name, actions.onAskNote, actions.onSendAsk, actions.onCloseAsk)
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun DetailContent(state: ConnectionDetailUiState, c: ConnectionInfo, title: String, name: String, actions: DetailActions) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // The hint is text: its room grows with the font size (two lines of small text and its padding).
            .padding(bottom = if (state.callHintShown) PILL_SPACE + hintSpace() else PILL_SPACE),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
            InitialTile(
                name = title,
                size = 72,
                style = if (c.favorite) TileStyle.Favorite else TileStyle.Connection,
                photo = com.vettid.core.ui.components.rememberProfilePhoto(c.photo),
                icon = if (c.service) Icons.Outlined.Dns else null,
            )
            Spacer(Modifier.height(Spacing.m))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }.testTag("detail_name"),
            )
            if (c.service) ServiceTag()
            c.secondaryName?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("detail_display_name"),
                )
            }
            state.presence?.let { PresenceLine(it.reading, sharingName(c), it.canRefresh, actions.onRefreshPresence) }
            Text(
                stateLabel(c.state) ?: stringResource(R.string.connections_state_active),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        state.notice?.let {
            NoticeCard(
                kind = NoticeKind.SUCCESS,
                title = stringResource(R.string.connections_auth_requested_title),
                body = stringResource(R.string.connections_auth_requested_body, name),
                modifier = Modifier.padding(horizontal = Spacing.s),
                actions = { TextButton(onClick = actions.onDismissNotice) { Text(stringResource(R.string.connections_ok)) } },
            )
        }
        state.error?.let { e ->
            // "Services can't do that." stands alone, without the screen's generic title.
            val refused = e == FailureKind.SERVICE_FORBIDDEN
            NoticeCard(
                kind = if (refused) NoticeKind.INFO else NoticeKind.WARNING,
                title = if (refused) "" else stringResource(R.string.connections_error_title),
                body = failureText(e.messageRes(), e.vaultCode(state.errorCode)),
                modifier = Modifier.padding(horizontal = Spacing.s),
                actions = { TextButton(onClick = actions.onDismissNotice) { Text(stringResource(R.string.connections_ok)) } },
            )
        }
        // A connection's asks (0.23.0 §10.4.1): paused or muted comes first, with the action that ends it.
        val asks = AsksView.of(c.asks)?.takeIf { c.state == ConnectionState.ACTIVE }
        val asksName = sharingName(c)?.let { AccountNames.isolateOrNull(it) }
        if (asks?.prominent == true) AsksCard(asks, asksName, state.busy, actions.onMuteAsks, actions.onResumeAsks)
        // A person connection that VettID's service list names as a service (made before it said so): it can't take part in
        // votes, and the app changes nothing about it on its own (ANDROID-PLAN 0.1.102 "Votes", screen 8).
        if (state.votes.reconnect) {
            NoticeCard(
                kind = NoticeKind.INFO,
                title = stringResource(R.string.connections_vote_reconnect_title),
                body = stringResource(R.string.connections_vote_reconnect_body, name),
                modifier = Modifier.padding(horizontal = Spacing.s).testTag("vote_reconnect"),
            )
        }
        ProfileCard(c)
        state.presence?.let { p ->
            val first = sharingName(c)?.let { AccountNames.isolate(it) }
            // Without the first name the switch and its note would have to say "this connection": not offered until it arrives.
            if (first != null && p.shared != null) PresenceShareCard(p.shared, p.invisible, first, state.busy, actions.onSetPresenceShared)
        }
        state.calls?.let { CallsCard(it.allowed, it.limited, firstOf(c, name), !state.callsChanging, actions.onSetCallsAllowed) }
        // Sharing the member's location (ANDROID-PLAN §4 "Location"): with a person who is connected, never with a service.
        if (c.state == ConnectionState.ACTIVE && !c.service && state.locationOffered) {
            LocationCard(
                state.location, state.locationMissed, state.busy, actions.onShareLocation, actions.onStopSharingLocation, firstOf(c, name),
            )
            // This connection shares with the member: the card with the latest position; Ask for location while it does not.
            state.incoming?.let {
                IncomingLocationCard(
                    it, firstOf(c, name), state.units, state.stoppingIncoming, actions.onOpenPosition, actions.onStopReceiving,
                )
            } ?: AskLocationCard(actions.onAskLocation, enabled = !state.busy)
        }
        if (c.service) {
            // The `ik` fingerprint is part of what the service shared (ANDROID-PLAN 0.1.90); the rest of the card stays at the end.
            c.keyFingerprint?.let { FingerprintCard(it) }
            CanAskCard(state.votes.available)
        }
        if (c.service && state.votes.count > 0) {
            DetailCard(Modifier.testTag("detail_votes")) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = actions.onOpenVotes),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.connections_vote_row, name),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        // A service shares nothing with the member and is asked for nothing (ANDROID-PLAN 0.1.90).
        if (c.state == ConnectionState.ACTIVE && !c.service) {
            // Two directions, two cards (owner request 2026-10-08): what goes out of the vault, what comes in.
            val first = sharingName(c)
            OutgoingSharingCard(state.sharing, first, actions.onShareItems, actions.onOpenRule, actions.onSharing, actions.onAskDeleteRule)
            IncomingSharingCard(state.sharing, first, actions.onAskForSomething, actions.onSharedWithYou)
        }
        if (asks != null && !asks.prominent) AsksCard(asks, asksName, state.busy, actions.onMuteAsks, actions.onResumeAsks)
        // The member does not challenge a service; nor can they once the vault said `forbidden` to it.
        if (!c.service && !state.authRefused) {
            AuthCard(state.auth, name, state.busy, c.state == ConnectionState.ACTIVE, actions.onAuthenticate)
        }
        DetailCard {
            CardTitle(stringResource(R.string.connections_detail_about))
            c.createdAt?.let { InfoLine(stringResource(R.string.connections_detail_since), Times.full(it)) }
            c.lastActiveAt?.let { InfoLine(stringResource(R.string.connections_detail_last_active), Times.full(it)) }
        }
        if (!c.service) c.keyFingerprint?.let { FingerprintCard(it) }
    }
}

/** "What this service can ask" and the vault's rule for this release (ANDROID-PLAN 0.1.90; the votes line where the vault has votes). */
@Composable
private fun CanAskCard(votes: Boolean) {
    DetailCard(Modifier.testTag("service_can_ask")) {
        CardTitle(stringResource(UiR.string.service_detail_can_ask_title))
        Text(
            stringResource(if (votes) UiR.string.service_detail_can_ask_body_votes else UiR.string.service_detail_can_ask_body),
            modifier = Modifier.testTag("service_can_ask_body"),
        )
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
@Suppress("CyclomaticComplexMethod") // one branch per line of the profile
private fun ProfileCard(c: ConnectionInfo) {
    DetailCard(Modifier.testTag("profile_card")) {
        CardTitle(stringResource(R.string.connections_detail_profile))
        val account = AccountNames.cleaned(c.accountName)
        val display = AccountNames.cleaned(c.name)
        if (account == null && display == null && c.sharedItems.isEmpty()) {
            Text(stringResource(R.string.connections_detail_profile_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (account != null) {
            // A service's names are its own: "Name given by the service", not the name on a VettID account.
            InfoLine(stringResource(if (c.service) UiR.string.service_name_label else R.string.connections_detail_account_name), account)
            if (!c.service) {
                Text(
                    stringResource(R.string.connections_names_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.s))
        }
        if (display != null || c.sharedItems.isNotEmpty()) {
            display?.let {
                // The service's label stands once, on the names row; the display name keeps "Display name" beside it.
                val alone = c.service && account == null
                InfoLine(stringResource(if (alone) UiR.string.service_name_label else R.string.connections_detail_profile_name), it)
            }
            if (c.sharedItems.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.s))
                Text(stringResource(R.string.connections_detail_shared_items), style = MaterialTheme.typography.labelLarge)
                c.sharedItems.forEach { item ->
                    Text(
                        AccountNames.cleaned(item.name).orEmpty(),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                    item.fields.forEach { (label, value) ->
                        InfoLine(AccountNames.cleaned(label).orEmpty(), AccountNames.cleaned(value).orEmpty())
                    }
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
private fun AuthCard(auth: AuthenticationState?, name: String, busy: Boolean, active: Boolean, onAuthenticate: () -> Unit) {
    DetailCard(Modifier.testTag("auth_card")) {
        CardTitle(stringResource(R.string.connections_auth_title))
        val verified = auth?.verifiedAt
        val waiting = auth?.waitingUntil != null
        val keyChanged = auth?.keyChanged == true
        val line = when {
            waiting -> stringResource(R.string.connections_auth_waiting, name)
            keyChanged -> stringResource(R.string.connections_auth_key_changed, name)
            auth?.lastResult == "denied" -> stringResource(R.string.connections_auth_denied, name)
            auth?.lastResult == "bad_signature" -> stringResource(R.string.connections_auth_bad, name)
            verified != null -> stringResource(R.string.connections_auth_ok, name, Times.full(verified))
            else -> stringResource(R.string.connections_auth_never, name)
        }
        Text(line, modifier = Modifier.testTag("auth_status"))
        // A key change must be shown (VAULT-MESSAGING §10.4): the waiting line of this member's own request does not
        // hide it, so with both the warning follows as a second line.
        if (waiting && keyChanged) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                stringResource(R.string.connections_auth_key_changed, name),
                modifier = Modifier.testTag("auth_key_changed"),
            )
        }
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
private fun DetailDialogs(state: ConnectionDetailUiState, name: String, actions: DetailActions) {
    when (state.confirm) {
        DetailConfirm.REMOVE -> ConfirmDialog(
            title = stringResource(R.string.connections_remove_title, name),
            // A service with a vote that is not finished: the member is told first that removing it ends their part in it.
            text = (if (state.votes.unfinished) stringResource(R.string.connections_vote_remove_warning, name) + "\n\n" else "") +
                stringResource(R.string.connections_remove_body, name),
            confirmLabel = stringResource(R.string.connections_remove),
            onConfirm = actions.onConfirm,
            onDismiss = { actions.onAsk(null) },
            destructive = true,
        )
        null -> Unit
    }
    state.deleteRule?.let {
        val first = state.connection?.let(::sharingName)?.let { n -> AccountNames.isolateOrNull(n) }
        ConfirmDialog(
            title = stringResource(R.string.connections_share_rule_delete_title),
            text = if (first != null) {
                stringResource(R.string.connections_share_rule_delete_body, first)
            } else {
                stringResource(R.string.connections_share_rule_delete_unnamed)
            },
            confirmLabel = stringResource(R.string.connections_share_rule_delete),
            onConfirm = actions.onDeleteRule,
            onDismiss = { actions.onAskDeleteRule(null) },
            destructive = true,
            modifier = Modifier.testTag("sharing_rule_delete_confirm"),
        )
    }
}

private val PILL_SPACE = 104.dp

/** Room for the pill's hint above it: two lines of small text, and its padding and gap. */
private val HINT_LINES = 32.sp
private val HINT_PADDING = 16.dp

@Composable
private fun hintSpace() = with(LocalDensity.current) { HINT_LINES.toDp() } + HINT_PADDING

/** (C2) Calls with this person are off: "Call" is disabled and the hint says so (owner decisions 2026-11-12). */
internal val ConnectionDetailUiState.callHintShown: Boolean
    get() = callsOff && connection?.state == ConnectionState.ACTIVE && connection?.service == false

/** The connection's first name for a sentence of the location texts (the card, the ask dialog), else [fallback] (isolated). */
internal fun firstOf(c: ConnectionInfo, fallback: String): String = sharingName(c)?.let { AccountNames.isolate(it) } ?: fallback
