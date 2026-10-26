package com.vettid.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConversationSummary
import com.vettid.core.data.social.MessageInfo
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.BottomFloatingControls
import com.vettid.core.ui.components.CenteredTitle
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DayDivider
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.FloatingFilterChip
import com.vettid.core.ui.components.MessageBubble
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TileStyle
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.components.rememberTopBarSearch
import com.vettid.core.ui.components.VettIdFab
import com.vettid.core.ui.components.VettIdListRow
import com.vettid.core.data.calls.CallHistoryEntry
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** Type-safe navigation route of the Messages screen. */
@Serializable
data object MessagesRoute

/** A conversation with one connection. */
@Serializable
data class ConversationRoute(val connectionId: String) {
    companion object {
        /** The SavedStateHandle key of [connectionId]. */
        const val ARG = "connectionId"
    }
}

/** The compose button's connection picker. */
@Serializable
data object NewMessageRoute

/** What the Messages screens ask the app shell for. */
data class MessagesHost(
    val navigate: (Any) -> Unit,
    val onBack: () -> Unit,
    /** The connection's detail screen (connections feature). */
    val onOpenConnection: (String) -> Unit,
    /** Invite a connection (connections feature). */
    val onInvite: () -> Unit,
)

/** Registers the Messages destinations. */
fun NavGraphBuilder.messagesDestination(chrome: ShellChrome, host: MessagesHost) {
    composable<MessagesRoute> {
        val vm: MessagesViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        MessagesScreen(
            state = state,
            chrome = chrome,
            onUnreadOnly = vm::setUnreadOnly,
            onQuery = vm::setQuery,
            onOpen = { host.navigate(ConversationRoute(it)) },
            onCompose = { host.navigate(NewMessageRoute) },
            onInvite = host.onInvite,
            onRetry = vm::refresh,
        )
    }
    composable<ConversationRoute> {
        val vm: ConversationViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ConversationScreen(
            state = state,
            actions = ConversationActions(
                onBack = host.onBack,
                onDraft = vm::setDraft,
                onSend = vm::send,
                onLongPress = vm::askDelete,
                onConfirmDelete = vm::confirmDelete,
                onDismissError = vm::dismissError,
                onDetails = { host.onOpenConnection(state.connectionId) },
                onCall = vm::call,
                onDismissCallNotice = vm::dismissCallNotice,
                onLoadOlder = vm::loadOlder,
                photos = PhotoActions(
                    onTakePhoto = vm::startCapture,
                    onPicked = vm::onPicked,
                    onShot = vm::onShot,
                    onCloseCamera = vm::stopCapture,
                    onCaption = vm::setPhotoCaption,
                    onSend = vm::sendPhotos,
                    onCancel = vm::cancelPhotos,
                    onDismissProblem = vm::dismissPhotoProblem,
                    onOpen = { host.navigate(PhotoViewerRoute(state.connectionId, it)) },
                    thumbnail = vm::thumbnail,
                    generation = state.photoGeneration,
                ),
            ),
        )
    }
    composable<PhotoViewerRoute> {
        val vm: PhotoViewerViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        PhotoViewerScreen(state, onBack = host.onBack, onRetry = vm::load)
    }
    composable<NewMessageRoute> {
        val vm: NewMessageViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        NewMessageScreen(state, onBack = host.onBack, onPick = { host.navigate(ConversationRoute(it)) }, onInvite = host.onInvite)
    }
}

/** Messages (ANDROID-PLAN §4, Proton's inbox): one row per connection with its latest message. */
@Composable
@Suppress("CyclomaticComplexMethod") // one branch per list state
fun MessagesScreen(
    state: MessagesUiState,
    chrome: ShellChrome,
    modifier: Modifier = Modifier,
    onUnreadOnly: (Boolean) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onCompose: () -> Unit = {},
    onInvite: () -> Unit = {},
    onRetry: () -> Unit = {},
    onQuery: (String) -> Unit = {},
) {
    val search = rememberTopBarSearch(
        state.query,
        onQuery,
        label = stringResource(R.string.messages_search_open),
        placeholder = stringResource(R.string.messages_search),
    )
    TopLevelScaffold(
        title = stringResource(R.string.messages_title),
        chrome = chrome,
        modifier = modifier,
        search = search.takeIf { state.searchable },
        overlay = {
            BottomFloatingControls(
                start = {
                    FloatingFilterChip(
                        label = stringResource(R.string.messages_filter),
                        selected = state.unreadOnly,
                        onSelectedChange = onUnreadOnly,
                    )
                },
                end = {
                    VettIdFab(
                        icon = if (state.noConnections) Icons.Outlined.PersonAdd else Icons.Outlined.Edit,
                        contentDescription = stringResource(if (state.noConnections) R.string.messages_invite else R.string.messages_add),
                        onClick = if (state.noConnections) onInvite else onCompose,
                    )
                },
            )
        },
    ) {
        val error = state.error
        when {
            state.loading && state.conversations.isEmpty() -> Loading()
            error != null && state.conversations.isEmpty() -> ErrorState(error, onRetry, state.errorCode)
            state.conversations.isEmpty() && state.query.isNotBlank() -> EmptyState(
                icon = Icons.Outlined.Search,
                title = stringResource(R.string.messages_no_match_title),
                body = stringResource(R.string.messages_no_match_body),
                modifier = Modifier.testTag("messages_no_match"),
            )
            state.conversations.isEmpty() -> EmptyState(
                icon = Icons.Outlined.ChatBubbleOutline,
                title = stringResource(if (state.unreadOnly) R.string.messages_empty_unread_title else R.string.messages_empty_title),
                body = stringResource(
                    when {
                        state.unreadOnly -> R.string.messages_empty_unread_body
                        state.noConnections -> R.string.messages_empty_no_connections
                        else -> R.string.messages_empty_body
                    },
                ),
            )
            else -> LazyColumn(Modifier.fillMaxSize().testTag("conversations"), contentPadding = PaddingValues(bottom = LIST_BOTTOM)) {
                items(state.conversations, key = { it.connection.id }) { c -> ConversationRow(c, onClick = { onOpen(c.connection.id) }) }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: ConversationSummary, onClick: () -> Unit) {
    val name = c.connection.displayName.ifBlank { stringResource(R.string.messages_name_not_shared) }
    val preview = c.last?.let { m ->
        if (m.outgoing) stringResource(R.string.messages_preview_you, m.text) else m.text
    } ?: stringResource(R.string.messages_preview_none)
    val unread = c.unread > 0
    VettIdListRow(
        title = name,
        supporting = if (unread) {
            pluralStringResource(R.plurals.messages_row_unread, c.unread, c.unread, preview.lines().first())
        } else {
            preview.lines().first()
        },
        meta = c.last?.let { Times.short(it.sentAt) },
        tileName = name,
        tileStyle = if (c.connection.favorite) TileStyle.Favorite else TileStyle.Connection,
        emphasized = unread,
        onClick = onClick,
        modifier = Modifier.testTag("conversation_${c.connection.id}"),
        tilePhoto = com.vettid.core.ui.components.rememberProfilePhoto(c.connection.photo),
    )
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun ErrorState(kind: FailureKind, onRetry: () -> Unit, shownCode: String? = null) {
    Column(Modifier.fillMaxSize().padding(Spacing.xl), verticalArrangement = Arrangement.Center) {
        NoticeCard(
            kind = NoticeKind.WARNING,
            title = stringResource(R.string.messages_error_title),
            body = failureText(kind.messageRes(), kind.vaultCode(shownCode)),
            actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.messages_retry)) } },
        )
    }
}

/** What the conversation screen can ask for. */
data class ConversationActions(
    val onBack: () -> Unit = {},
    val onDraft: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onLongPress: (MessageInfo?) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onDetails: () -> Unit = {},
    /** The call button (builds and vaults with calls). */
    val onCall: () -> Unit = {},
    val onDismissCallNotice: () -> Unit = {},
    /** The list reached its top while older messages remain (§10.5.2). */
    val onLoadOlder: () -> Unit = {},
    /** Photo attachments (ANDROID-PLAN 0.1.39). */
    val photos: PhotoActions = PhotoActions(),
)

/** A conversation (ANDROID-PLAN §4): bubbles by day, the reply field at the bottom. */
@Composable
fun ConversationScreen(state: ConversationUiState, actions: ConversationActions, modifier: Modifier = Modifier) {
    val name = state.connection?.displayName?.ifBlank { null } ?: stringResource(R.string.messages_name_not_shared)
    val listState = rememberLazyListState()
    // To the newest message when one is added at the end, not when an older page is added at the top (§10.5.2):
    // the list keeps its place then, by the items' keys.
    LaunchedEffect(state.messages.lastOrNull()?.messageId) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last > 0) listState.animateScrollToItem(last)
    }
    DetailScaffold(
        onBackClick = actions.onBack,
        modifier = modifier.imePadding(),
        actions = {
            if (state.callShown) {
                IconButton(onClick = actions.onCall, enabled = state.callEnabled, modifier = Modifier.testTag("conversation_call")) {
                    Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.messages_call))
                }
            }
            IconButton(onClick = actions.onDetails) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.messages_cd_details, name))
            }
        },
        overlay = {
            // Photos picked or taken, then the camera (ANDROID-PLAN 0.1.39): over the whole conversation.
            val draft = state.photoDraft
            when {
                draft != null -> PhotoDraftOverlay(draft, name, actions.photos)
                state.capturing -> PhotoCaptureOverlay(actions.photos)
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            CenteredTitle(name)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading && state.messages.isEmpty() -> Loading()
                    state.messages.isEmpty() && state.calls.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = stringResource(R.string.messages_conversation_empty_title),
                        body = stringResource(R.string.messages_conversation_empty_body, name),
                    )
                    else -> MessageList(state, name, listState, actions)
                }
            }
            if (state.callsNotAvailable) {
                NoticeCard(
                    kind = NoticeKind.INFO,
                    title = stringResource(R.string.messages_call),
                    body = stringResource(R.string.messages_call_not_in_vault),
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("conversation_call_notice"),
                    actions = { TextButton(onClick = actions.onDismissCallNotice) { Text(stringResource(R.string.messages_ok)) } },
                )
            }
            val error = state.error
            if (error != null) {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.messages_error_title),
                    body = failureText(error.messageRes(), error.vaultCode(state.errorCode)),
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("conversation_error"),
                    actions = { TextButton(onClick = actions.onDismissError) { Text(stringResource(R.string.messages_ok)) } },
                )
            }
            Composer(state, name, actions)
        }
    }
    if (state.deleting != null) {
        ConfirmDialog(
            title = stringResource(R.string.messages_delete_title),
            text = stringResource(R.string.messages_delete_body, name),
            confirmLabel = stringResource(R.string.messages_delete_confirm),
            onConfirm = actions.onConfirmDelete,
            onDismiss = { actions.onLongPress(null) },
            destructive = true,
        )
    }
}

@Suppress("CyclomaticComplexMethod") // messages, calls and photo bubbles of one list
@Composable
private fun MessageList(state: ConversationUiState, name: String, listState: LazyListState, actions: ConversationActions) {
    val onLongPress = actions.onLongPress
    val rows = CallEntries.rows(
        state.messages,
        state.calls,
        if (state.olderAvailable) state.messages.firstOrNull()?.sentAt else null,
    )
    val byDay = rows.groupBy { Times.day(it.at) }
    val older by rememberUpdatedState(state.olderAvailable)
    val onLoadOlder by rememberUpdatedState(actions.onLoadOlder)
    LaunchedEffect(listState) {
        // Armed by the member's first drag (not the scroll to the end on opening): a short conversation that shows
        // its top at once does not load by itself.
        listState.interactionSource.interactions.filterIsInstance<DragInteraction.Start>().first()
        // "Load older" on reaching the top (§10.5.2): the first item visible while the vault announced older messages.
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.layoutInfo.totalItemsCount > 0 }
            .distinctUntilChanged()
            .filter { it && older }
            .collect { onLoadOlder() }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("messages"), state = listState, contentPadding = PaddingValues(vertical = Spacing.s)) {
        if (state.olderAvailable) {
            item(key = "older") { OlderRow(state.loadingOlder, actions.onLoadOlder) }
        }
        byDay.forEach { (day, list) ->
            item(key = "day-$day") { DayDivider(Times.dayLabel(day)) }
            items(list, key = { it.key }) { row ->
                if (row is ConversationRow.Call) {
                    CallEntry(row.call)
                    return@items
                }
                val m = (row as ConversationRow.Message).message
                if (m.attachment != null) {
                    PhotoBubble(m, name, actions.photos, onLongPress)
                    return@items
                }
                val time = Times.time(m.sentAt)
                val status = when {
                    !m.outgoing -> null
                    m.read -> stringResource(R.string.messages_status_read)
                    m.delivered -> stringResource(R.string.messages_status_delivered)
                    else -> stringResource(R.string.messages_status_sent)
                }
                val label = if (status != null) {
                    stringResource(R.string.messages_cd_outgoing, m.text, time, status)
                } else {
                    stringResource(R.string.messages_cd_incoming, name, m.text, time)
                }
                MessageBubble(
                    text = m.text,
                    outgoing = m.outgoing,
                    meta = status?.let { "$time · $it" } ?: time,
                    accessibilityLabel = label,
                    onLongClick = { onLongPress(m) },
                )
            }
        }
    }
}

/** A call in the conversation: what became of it, and when (the vault's history; no content). */
@Composable
private fun CallEntry(call: CallHistoryEntry) {
    val text = CallEntries.text(call.outcome) ?: return
    val duration = call.duration?.let { d ->
        val p = CallEntries.parts(d)
        when {
            p.hours > 0 -> stringResource(CallEntries.durationFormat(p), p.hours, p.minutes)
            p.minutes > 0 -> stringResource(CallEntries.durationFormat(p), p.minutes, p.seconds)
            else -> stringResource(CallEntries.durationFormat(p), p.seconds)
        }
    }.orEmpty()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("conversation_call_entry"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (call.outgoing) Icons.AutoMirrored.Outlined.CallMade else Icons.AutoMirrored.Outlined.CallReceived,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(Spacing.xs))
        Text(
            "${stringResource(text, duration)} · ${Times.time(call.startedAt)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The top of a conversation with older messages in the vault: loading, or a button to load them. */
@Composable
private fun OlderRow(loading: Boolean, onLoad: () -> Unit) {
    val loadingLabel = stringResource(R.string.messages_loading_older)
    Box(Modifier.fillMaxWidth().padding(Spacing.s).testTag("older"), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(24.dp).semantics { contentDescription = loadingLabel },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onLoad) { Text(stringResource(R.string.messages_load_older)) }
        }
    }
}

@Composable
private fun Composer(state: ConversationUiState, name: String, actions: ConversationActions) {
    if (state.connection != null && !state.active) {
        Text(
            stringResource(R.string.messages_inactive, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.l),
        )
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = Spacing.gutter, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = actions.onDraft,
            placeholder = { Text(stringResource(R.string.messages_reply_hint, name)) },
            isError = state.tooLong,
            supportingText = if (state.tooLong) ({ Text(stringResource(R.string.messages_too_long)) }) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = COMPOSER_LINES,
            modifier = Modifier.weight(1f).testTag("composer"),
        )
        // The photo button only for a vault of release A (§10.5.1 "Compatibility").
        if (state.photosAvailable) PhotoButton(actions.photos, enabled = state.photoDraft == null)
        Spacer(Modifier.width(Spacing.s))
        FilledIconButton(
            onClick = actions.onSend,
            enabled = state.canSend,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = Modifier.size(Spacing.touchTarget).testTag("send"),
        ) {
            if (state.sending) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.messages_send))
            }
        }
    }
}

/** The compose button's picker: active connections, favourites first. */
@Composable
fun NewMessageScreen(
    state: NewMessageUiState,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
    onInvite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DetailScaffold(onBackClick = onBack, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            CenteredTitle(stringResource(R.string.messages_new_title))
            when {
                state.loading && state.connections.isEmpty() -> Loading()
                state.connections.isEmpty() -> Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        icon = Icons.Outlined.PersonAdd,
                        title = stringResource(R.string.messages_new_empty_title),
                        body = stringResource(R.string.messages_empty_no_connections),
                    )
                    TextButton(
                        onClick = onInvite,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Spacing.xl),
                    ) { Text(stringResource(R.string.messages_invite)) }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.connections, key = { it.id }) { c -> PickRow(c) { onPick(c.id) } }
                }
            }
        }
    }
}

@Composable
private fun PickRow(c: ConnectionInfo, onClick: () -> Unit) {
    val name = c.displayName.ifBlank { stringResource(R.string.messages_name_not_shared) }
    VettIdListRow(
        title = name,
        supporting = c.secondaryName,
        tileName = name,
        tileStyle = if (c.favorite) TileStyle.Favorite else TileStyle.Connection,
        onClick = onClick,
        tilePhoto = com.vettid.core.ui.components.rememberProfilePhoto(c.photo),
    )
}

private val LIST_BOTTOM = 96.dp
private const val COMPOSER_LINES = 5
