package com.vettid.feature.notifications

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.data.feed.FeedManager
import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.feed.FeedTarget
import com.vettid.core.data.feed.FeedText
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.AuditCategory
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ClearFiltersChip
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.FloatingFilterChip
import com.vettid.core.ui.components.InitialTile
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.TileStyle
import com.vettid.core.ui.components.BottomFloatingControls
import com.vettid.core.ui.components.rememberProfilePhoto
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.CategoryHue
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.categoryColor
import com.vettid.core.vault.FeedItem
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

/** The Notifications screen, opened from the bell (ANDROID-PLAN 0.1.23); [archived]: its Archived view. */
@Serializable
data class NotificationsRoute(val archived: Boolean = false)

/** What the app gives the screen: back, the Archived view, and the screen an item opens ([FeedTarget]). */
data class NotificationsHost(
    val onBack: () -> Unit,
    val onOpenArchived: () -> Unit,
    val onOpen: (FeedTarget) -> Unit,
)

/** Registers the Notifications screen and its Archived view. */
fun NavGraphBuilder.notificationsDestination(host: NotificationsHost) {
    composable<NotificationsRoute> {
        val vm: NotificationsViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        LaunchedEffect(state.open) {
            val t = state.open ?: return@LaunchedEffect
            vm.targetTaken()
            host.onOpen(t)
        }
        NotificationsScreen(
            state,
            NotificationsActions(
                onBack = host.onBack,
                onOpen = vm::open,
                onArchive = vm::archive,
                onToggleRead = vm::toggleRead,
                onMoveBack = vm::moveToNotifications,
                onAskDelete = vm::askDelete,
                onDelete = vm::delete,
                onUndo = vm::undoArchive,
                onUndoShown = vm::undoShown,
                onMarkAllRead = vm::markAllRead,
                onArchived = host.onOpenArchived,
                onUnreadOnly = vm::setUnreadOnly,
                onCloseSheet = vm::closeSheet,
                onRetry = vm::refresh,
                onErrorShown = vm::errorShown,
            ),
        )
    }
}

/** The screen's callbacks (no-ops by default, for the screen catalog). */
data class NotificationsActions(
    val onBack: () -> Unit = {},
    val onOpen: (FeedItem) -> Unit = {},
    val onArchive: (FeedItem) -> Unit = {},
    val onToggleRead: (FeedItem) -> Unit = {},
    val onMoveBack: (FeedItem) -> Unit = {},
    val onAskDelete: (FeedItem?) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onUndoShown: () -> Unit = {},
    val onMarkAllRead: () -> Unit = {},
    val onArchived: () -> Unit = {},
    val onUnreadOnly: (Boolean) -> Unit = {},
    val onCloseSheet: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onErrorShown: () -> Unit = {},
)

/** The resolved text of a [FeedText] in the current configuration. */
@Composable
fun FeedText.text(): String = resolve(LocalResources.current)

/**
 * Notifications (ANDROID-PLAN 0.1.23, 3): Needs attention, then the day groups; unread rows bold with a gold dot;
 * swipe to archive (Undo) or to mark read and unread, each also a TalkBack action and in the long-press menu; the
 * Unread chip; ⋯ → Mark all as read, Archived. The Archived view moves items back or deletes them (confirmed).
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun NotificationsScreen(state: NotificationsUiState, actions: NotificationsActions, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    val archivedText = stringResource(R.string.notifications_archived_snackbar)
    val undoText = stringResource(R.string.notifications_undo)
    val errorText = state.error?.let { stringResource(it.messageRes()) }
    LaunchedEffect(state.undo) {
        if (state.undo == null) return@LaunchedEffect
        val r = snackbar.showSnackbar(archivedText, undoText, duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) actions.onUndo() else actions.onUndoShown()
    }
    LaunchedEffect(errorText) {
        val e = errorText ?: return@LaunchedEffect
        actions.onErrorShown()
        snackbar.showSnackbar(e)
    }
    DetailScaffold(
        onBackClick = actions.onBack,
        modifier = modifier,
        actions = { if (!state.archived) MoreMenu(state, actions) },
        overlay = {
            if (!state.archived) {
                BottomFloatingControls(
                    start = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            FloatingFilterChip(
                                label = stringResource(R.string.notifications_filter_unread),
                                selected = state.unreadOnly,
                                onSelectedChange = actions.onUnreadOnly,
                            )
                            if (state.unreadOnly) ClearFiltersChip(onClick = { actions.onUnreadOnly(false) })
                        }
                    },
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
        },
    ) {
        val sections = state.sections()
        Column(Modifier.fillMaxSize()) {
            LargeTitle(stringResource(if (state.archived) R.string.notifications_archived_title else R.string.notifications_title))
            Box(Modifier.weight(1f)) {
                when {
                    state.items.isEmpty() && (state.load == ListLoad.LOADING || state.load == ListLoad.NOT_LOADED) ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.items.isEmpty() && state.load == ListLoad.FAILED -> LoadFailed(actions.onRetry)
                    sections.isEmpty() -> Empty(state)
                    else -> LazyColumn(
                        Modifier.fillMaxSize().testTag("notifications_list"),
                        contentPadding = PaddingValues(bottom = LIST_BOTTOM),
                    ) {
                        sections.forEach { s ->
                            item(key = "h:${s.day}") { SectionHeader(s.day) }
                            items(s.items, key = { it.itemId }) { item ->
                                SwipeRow(item, state.names, state.archived, attention = s.day == null, actions = actions)
                            }
                        }
                    }
                }
            }
        }
    }
    state.sheet?.let { DetailSheet(it, state.names, state.sheetGone, actions.onCloseSheet) }
    state.confirmDelete?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.notifications_delete_title),
            text = stringResource(R.string.notifications_delete_body) +
                if ((item.count ?: 0) > 1 || item.kind == "grant.request") {
                    "\n\n" + stringResource(R.string.notifications_delete_batch, FeedKinds.firstName(item, state.names).text())
                } else {
                    ""
                },
            confirmLabel = stringResource(R.string.notifications_delete),
            onConfirm = actions.onDelete,
            onDismiss = { actions.onAskDelete(null) },
            destructive = true,
        )
    }
}

@Composable
private fun MoreMenu(state: NotificationsUiState, actions: NotificationsActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("notifications_more")) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.notifications_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notifications_mark_all_read)) },
                leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                enabled = state.unread > 0 && !state.markingAll,
                onClick = {
                    open = false
                    actions.onMarkAllRead()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notifications_archived_title)) },
                leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onArchived()
                },
            )
        }
    }
}

@Composable
private fun Empty(state: NotificationsUiState) {
    when {
        state.archived -> EmptyState(
            Icons.Outlined.Archive,
            stringResource(R.string.notifications_empty_archived_title),
            stringResource(R.string.notifications_empty_archived_body),
            Modifier.testTag("notifications_empty_archived"),
        )
        state.unreadOnly -> EmptyState(
            Icons.Outlined.DoneAll,
            stringResource(R.string.notifications_empty_unread_title),
            stringResource(R.string.notifications_empty_unread_body),
            Modifier.testTag("notifications_empty_unread"),
        )
        else -> EmptyState(
            Icons.Outlined.Notifications,
            stringResource(R.string.notifications_empty_title),
            stringResource(R.string.notifications_empty_body),
            Modifier.testTag("notifications_empty"),
        )
    }
}

@Composable
private fun LoadFailed(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.notifications_load_failed), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Spacing.l))
        Button(onClick = onRetry) { Text(stringResource(R.string.notifications_retry)) }
    }
}

/** Needs attention ([day] null), Today, Yesterday, then the date ("Mon 5 Oct"; with the year when not this year's). */
@Composable
private fun SectionHeader(day: LocalDate?) {
    val today = LocalDate.now(ZoneId.systemDefault())
    val text = when {
        day == null -> stringResource(R.string.notifications_needs_attention)
        day == today -> stringResource(R.string.notifications_today)
        day == today.minusDays(1) -> stringResource(R.string.notifications_yesterday)
        day == LocalDate.MIN -> stringResource(R.string.notifications_undated)
        else -> FeedSections.dayTitle(day, today)
    }
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = if (day == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.l, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/** A row in a [SwipeToDismissBox]: end-to-start archives (Archived: deletes), start-to-end reads (Archived: moves back). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(item: FeedItem, names: FeedNames, archived: Boolean, attention: Boolean, actions: NotificationsActions) {
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = { SwipeBackground(swipe.dismissDirection, item, archived) },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> if (archived) actions.onAskDelete(item) else actions.onArchive(item)
                SwipeToDismissBoxValue.StartToEnd -> if (archived) actions.onMoveBack(item) else actions.onToggleRead(item)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // The row stays where it is until the list changes (a read toggle, a refused archive, a delete to confirm).
            scope.launch { swipe.reset() }
        },
    ) {
        FeedRow(item, names, archived, attention, actions)
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, item: FeedItem, archived: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (icon, label, bg) = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> if (archived) {
            Triple(Icons.Outlined.DeleteOutline, R.string.notifications_delete, colors.errorContainer)
        } else {
            Triple(Icons.Outlined.Archive, R.string.notifications_archive, colors.secondaryContainer)
        }
        SwipeToDismissBoxValue.StartToEnd -> if (archived) {
            Triple(Icons.Outlined.Unarchive, R.string.notifications_move_back, colors.secondaryContainer)
        } else if (item.status == FeedManager.STATUS_ACTIVE) {
            Triple(Icons.Outlined.MarkEmailRead, R.string.notifications_mark_read, colors.primaryContainer)
        } else {
            Triple(Icons.Outlined.MarkEmailUnread, R.string.notifications_mark_unread, colors.primaryContainer)
        }
        SwipeToDismissBoxValue.Settled -> Triple(null, 0, Color.Transparent)
    }
    Box(
        Modifier.fillMaxSize().background(bg).padding(horizontal = Spacing.xl),
        contentAlignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (icon != null) Icon(icon, contentDescription = stringResource(label))
    }
}

/**
 * One item (ANDROID-PLAN 0.1.23, 3): the connection's tile, or the kind's icon in its History category colour; the
 * title and a second line; the time and a gold dot while unread. `urgent` unread items sit in Needs attention on the
 * error container with a red leading bar; `high` has an amber tile and says "Important"; `low` is quieter.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // one row: tile, texts, time, dot, swipe actions and menu
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedRow(item: FeedItem, names: FeedNames, archived: Boolean, attention: Boolean, actions: NotificationsActions) {
    val colors = MaterialTheme.colorScheme
    val bar = colors.error
    val unread = item.status == FeedManager.STATUS_ACTIVE
    val title = FeedKinds.title(item, names).text()
    val supporting = FeedKinds.supporting(item, names)?.text()
    val at = FeedManager.at(item)
    var menu by remember { mutableStateOf(false) }
    val archive = stringResource(R.string.notifications_archive)
    val toggle = stringResource(if (unread) R.string.notifications_mark_read else R.string.notifications_mark_unread)
    val moveBack = stringResource(R.string.notifications_move_back)
    val delete = stringResource(R.string.notifications_delete)
    val stateText = listOfNotNull(
        stringResource(R.string.notifications_state_unread).takeIf { unread },
        stringResource(R.string.notifications_state_important).takeIf { item.priority == FeedManager.PRIORITY_HIGH },
        stringResource(R.string.notifications_state_urgent).takeIf { item.priority == FeedManager.PRIORITY_URGENT },
    ).joinToString(", ")
    val lineColor = when {
        item.priority == FeedManager.PRIORITY_LOW && !unread -> colors.onSurfaceVariant
        unread -> colors.onSurface
        else -> colors.onSurfaceVariant
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (attention) colors.errorContainer else colors.background)
                .drawBehind { if (attention) drawRect(bar, size = Size(ATTENTION_BAR.toPx(), size.height)) }
                .combinedClickable(onClick = { actions.onOpen(item) }, onLongClick = { menu = true })
                .heightIn(min = 72.dp)
                .semantics {
                    stateDescription = stateText
                    customActions = if (archived) {
                        listOf(
                            CustomAccessibilityAction(moveBack) { actions.onMoveBack(item); true },
                            CustomAccessibilityAction(delete) { actions.onAskDelete(item); true },
                        )
                    } else {
                        listOf(
                            CustomAccessibilityAction(archive) { actions.onArchive(item); true },
                            CustomAccessibilityAction(toggle) { actions.onToggleRead(item); true },
                        )
                    }
                }
                .testTag("feed_row_${item.itemId}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(Spacing.gutter))
            Tile(item, names)
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f).padding(vertical = Spacing.m)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (attention) colors.onErrorContainer else lineColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (attention) colors.onErrorContainer else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(Modifier.padding(start = Spacing.s, end = Spacing.l), horizontalAlignment = Alignment.End) {
                if (at != null) {
                    Text(
                        Times.time(at),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (attention) colors.onErrorContainer else lineColor,
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (unread) colors.primary else Color.Transparent, CircleShape)
                        .testTag(if (unread) "unread_dot" else "read_dot"),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val entries = if (archived) {
                listOf(moveBack to { actions.onMoveBack(item) }, delete to { actions.onAskDelete(item) })
            } else {
                listOf(archive to { actions.onArchive(item) }, toggle to { actions.onToggleRead(item) })
            }
            entries.forEach { (label, run) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    menu = false
                    run()
                })
            }
        }
    }
}

private val ATTENTION_BAR = 4.dp
private val LIST_BOTTOM = 96.dp

/** The connection's tile where the item names one; else the kind's icon in its History category colour. */
@Composable
private fun Tile(item: FeedItem, names: FeedNames) {
    val c = item.connectionId?.let { names.connections[it] }
    if (c != null) {
        val photo = rememberProfilePhoto(c.photo)
        val style = if (c.favorite) TileStyle.Favorite else TileStyle.Connection
        InitialTile(name = c.displayName.ifEmpty { c.name }, style = style, photo = photo)
        return
    }
    val hue = when {
        item.priority == FeedManager.PRIORITY_URGENT -> CategoryHue.RED
        item.priority == FeedManager.PRIORITY_HIGH -> CategoryHue.AMBER
        else -> FeedIcons.hue(item.kind)
    }
    InitialTile(name = "", icon = FeedIcons.icon(item.kind), colors = categoryColor(hue))
}

/** The kinds' icons and colours: History's categories (0.1.22), a feed kind falling in the audit kind's category. */
object FeedIcons {
    fun category(kind: String): AuditCategory = when {
        kind == "guide" -> AuditCategory.OTHER
        kind.startsWith("credential.") -> AuditCategory.SECURITY
        else -> AuditCategory.of(kind)
    }

    fun hue(kind: String): CategoryHue = when (category(kind)) {
        AuditCategory.VAULT_ACCESS -> CategoryHue.GREEN
        AuditCategory.SECURITY -> CategoryHue.AMBER
        AuditCategory.DEVICES -> CategoryHue.PURPLE
        AuditCategory.CONNECTIONS -> CategoryHue.TEAL
        AuditCategory.MESSAGES -> CategoryHue.BLUE
        AuditCategory.ITEMS -> CategoryHue.GOLD
        AuditCategory.AGENTS -> CategoryHue.ORANGE
        AuditCategory.LOCATION -> CategoryHue.INDIGO
        AuditCategory.ACCOUNT -> CategoryHue.GREY
        AuditCategory.DROPPED -> CategoryHue.RED
        AuditCategory.OTHER -> CategoryHue.GREY
    }

    fun icon(kind: String): ImageVector = when (category(kind)) {
        AuditCategory.VAULT_ACCESS -> Icons.Outlined.LockOpen
        AuditCategory.SECURITY -> Icons.Outlined.Shield
        AuditCategory.DEVICES -> Icons.Outlined.Devices
        AuditCategory.CONNECTIONS -> Icons.Outlined.People
        AuditCategory.MESSAGES -> Icons.Outlined.ChatBubbleOutline
        AuditCategory.ITEMS -> Icons.Outlined.Inventory2
        AuditCategory.AGENTS -> Icons.Outlined.SmartToy
        AuditCategory.LOCATION -> Icons.Outlined.LocationOn
        AuditCategory.ACCOUNT -> Icons.Outlined.AccountCircle
        AuditCategory.DROPPED -> Icons.Outlined.Block
        AuditCategory.OTHER -> Icons.Outlined.EventNote
    }
}

/**
 * The detail sheet (ANDROID-PLAN 0.1.23, 4): the label, the exact time, the connection or item as History resolves
 * them; "This is no longer waiting" for an ask that is gone; "Not available in this version of the app" for kinds
 * that come later; an unknown kind shows the kind itself; a guide its body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(item: FeedItem, names: FeedNames, gone: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("feed_sheet")) {
        DetailSheetContent(item, names, gone)
    }
}

/** The sheet's content (also shown on its own in the screen catalog). */
@Suppress("CyclomaticComplexMethod") // one line per thing the item names
@Composable
fun DetailSheetContent(item: FeedItem, names: FeedNames, gone: Boolean) {
    Column(Modifier.navigationBarsPadding().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl)) {
        Text(
            FeedKinds.title(item, names).text(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.s))
        FeedManager.at(item)?.let { SheetLine(stringResource(R.string.notifications_sheet_time), Times.full(it)) }
        if (item.connectionId != null) {
            SheetLine(stringResource(R.string.notifications_sheet_connection), FeedKinds.connectionName(item, names).text())
        }
        FeedKinds.itemName(item, names)?.let { SheetLine(stringResource(R.string.notifications_sheet_item), it) }
        if (item.kind == "item.revealed" && FeedKinds.itemName(item, names) == null) {
            val deleted = FeedText(com.vettid.core.data.R.string.data_feed_deleted_item).text()
            SheetLine(stringResource(R.string.notifications_sheet_item), deleted)
        }
        if (!FeedKinds.known(item.kind)) SheetLine(stringResource(R.string.notifications_sheet_kind), item.kind)
        if (item.kind == "guide") item.body?.takeIf { it.isNotBlank() }?.let { body ->
            Spacer(Modifier.height(Spacing.s))
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
        val note = when {
            FeedKinds.notAvailable(item.kind) -> R.string.notifications_sheet_not_available
            gone && FeedKinds.isAsk(item.kind) -> R.string.notifications_sheet_no_longer_waiting
            gone -> R.string.notifications_sheet_gone
            !FeedKinds.known(item.kind) -> R.string.notifications_sheet_unknown
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.height(Spacing.m))
            Text(stringResource(note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SheetLine(label: String, value: String) {
    Column(Modifier.padding(vertical = Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
