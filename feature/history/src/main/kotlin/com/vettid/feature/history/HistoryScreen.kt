package com.vettid.feature.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.vault.AuditCategory
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.components.UrgentBanner
import com.vettid.core.ui.components.VettIdListRow
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** History (the drawer): the member's audit log. */
@Serializable
data object HistoryRoute

/** History of one connection, from its detail screen (ANDROID-PLAN 0.1.11: the connection preset). */
@Serializable
data class ConnectionHistoryRoute(val connectionId: String)

/** One audit entry, by its `seq` (§10.9). */
@Serializable
data class HistoryEntryRoute(val seq: Long)

/** What the History destinations need from the app shell. */
data class HistoryHost(
    val navigate: (Any) -> Unit,
    val onBack: () -> Unit,
    /** Opens a connection's detail (the entry detail's connection). */
    val onOpenConnection: (String) -> Unit,
)

/** Registers History, a connection's History and the entry detail. */
fun NavGraphBuilder.historyDestination(chrome: ShellChrome, host: HistoryHost) {
    composable<HistoryRoute> { HistoryRouteContent(chrome, host, onBack = null) }
    composable<ConnectionHistoryRoute> { HistoryRouteContent(chrome, host, onBack = host.onBack) }
    composable<HistoryEntryRoute> {
        val vm: HistoryEntryViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        HistoryEntryScreen(state, onBack = host.onBack, onRetry = vm::load, onOpenConnection = host.onOpenConnection)
    }
}

@Composable
private fun HistoryRouteContent(chrome: ShellChrome, host: HistoryHost, onBack: (() -> Unit)?) {
    val vm: HistoryViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        chrome = chrome,
        onBack = onBack,
        actions = HistoryActions(
            onOpen = { host.navigate(HistoryEntryRoute(it)) },
            onQuery = vm::setQuery,
            onCategory = vm::setCategory,
            onConnection = vm::setConnection,
            onDates = vm::setDates,
            onClearFilters = vm::clearFilters,
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
        ),
    )
}

/** What the History screen can ask for. */
data class HistoryActions(
    val onOpen: (Long) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onCategory: (AuditCategory?) -> Unit = {},
    val onConnection: (String?) -> Unit = {},
    val onDates: (DatePreset, Instant?, Instant?) -> Unit = { _, _, _ -> },
    val onClearFilters: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** The icon of each group, on the entry's tile. */
fun categoryIcon(c: AuditCategory): ImageVector = when (c) {
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

/**
 * History (owner request 2026-10-07): newest first, more as the list scrolls (`before_seq` cursors), each entry
 * with its title, time and connection; a search, the date range, a connection and the groups filter it. Read-only.
 */
@Suppress("LongMethod")
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    chrome: ShellChrome,
    actions: HistoryActions,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            if (state.chainBroken) {
                UrgentBanner(
                    text = stringResource(R.string.history_chain_broken),
                    actionLabel = stringResource(R.string.history_retry),
                    onClick = actions.onRetry,
                    statusBarPadding = false,
                    modifier = Modifier.testTag("history_chain_broken"),
                )
            }
            SearchField(state.filter.query, actions.onQuery)
            FilterRow(state, actions)
            if (state.localSearch && state.filter.q != null) {
                Text(
                    stringResource(R.string.history_search_local),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs).testTag("history_local_search"),
                )
            }
            val error = state.error
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading && state.entries.isEmpty() -> Centered {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    error != null && state.entries.isEmpty() -> Centered {
                        NoticeCard(
                            kind = NoticeKind.WARNING,
                            title = stringResource(R.string.history_error_title),
                            body = stringResource(error.messageRes()),
                            modifier = Modifier.padding(Spacing.xl),
                            actions = { TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.history_retry)) } },
                        )
                    }
                    state.entries.isEmpty() && state.filter.isEmpty -> EmptyState(
                        icon = Icons.Outlined.History,
                        title = stringResource(R.string.history_empty_title),
                        body = stringResource(R.string.history_empty_body),
                        modifier = Modifier.testTag("history_empty"),
                    )
                    state.entries.isEmpty() && state.end -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyState(
                            icon = Icons.Outlined.Search,
                            title = stringResource(R.string.history_no_match_title),
                            body = stringResource(R.string.history_no_match_body),
                            modifier = Modifier.weight(1f).testTag("history_no_match"),
                        )
                        TextButton(onClick = actions.onClearFilters, modifier = Modifier.padding(bottom = Spacing.xxl)) {
                            Text(stringResource(R.string.history_filters_clear))
                        }
                    }
                    else -> EntryList(state, actions)
                }
            }
        }
    }
    if (onBack != null) {
        DetailScaffold(onBackClick = onBack, modifier = modifier) {
            Column(Modifier.fillMaxSize()) {
                LargeTitle(stringResource(R.string.history_title))
                body()
            }
        }
    } else {
        TopLevelScaffold(title = stringResource(R.string.history_title), chrome = chrome, modifier = modifier) { body() }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        // `q` is at most 128 bytes (§10.9): the field stops there.
        onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= AuditFilter.MAX_Q_BYTES) onQuery(it) },
        placeholder = { Text(stringResource(R.string.history_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.history_search_clear))
                }
            }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
            .testTag("history_search"),
    )
}

@Composable
private fun FilterRow(state: HistoryUiState, actions: HistoryActions) {
    var datesOpen by rememberSaveable { mutableStateOf(false) }
    var pickRange by rememberSaveable { mutableStateOf(false) }
    var pickConnection by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilterChip(
                selected = state.datePreset != DatePreset.ANY,
                onClick = { datesOpen = true },
                label = { Text(dateLabel(state)) },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.testTag("history_dates"),
            )
            DropdownMenu(expanded = datesOpen, onDismissRequest = { datesOpen = false }) {
                DatePreset.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(stringResource(presetLabel(p))) },
                        onClick = {
                            datesOpen = false
                            if (p == DatePreset.CUSTOM) pickRange = true else actions.onDates(p, null, null)
                        },
                    )
                }
            }
        }
        val connectionName = state.filter.connectionId?.let {
            state.connectionNames[it] ?: stringResource(R.string.history_connection_removed)
        }
        FilterChip(
            selected = state.filter.connectionId != null,
            onClick = { pickConnection = true },
            label = { Text(connectionName ?: stringResource(R.string.history_connection_any)) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.testTag("history_connection"),
        )
        // One category at a time, "All" by default (ANDROID-PLAN 0.1.11).
        FilterChip(
            selected = state.filter.category == null,
            onClick = { actions.onCategory(null) },
            label = { Text(stringResource(R.string.history_category_all)) },
            modifier = Modifier.testTag("history_category_all"),
        )
        AuditCategory.filters.forEach { c ->
            FilterChip(
                selected = state.filter.category == c,
                onClick = { actions.onCategory(c) },
                label = { Text(stringResource(AuditKinds.categoryLabel(c))) },
                modifier = Modifier.testTag("history_category_${c.name.lowercase()}"),
            )
        }
        if (!state.filter.isEmpty) {
            TextButton(onClick = actions.onClearFilters) { Text(stringResource(R.string.history_filters_clear)) }
        }
    }
    if (pickRange) {
        RangeDialog(onDismiss = { pickRange = false }) { s, u ->
            pickRange = false
            actions.onDates(DatePreset.CUSTOM, s, u)
        }
    }
    if (pickConnection) {
        ConnectionDialog(state, onDismiss = { pickConnection = false }) {
            pickConnection = false
            actions.onConnection(it)
        }
    }
}

private fun presetLabel(p: DatePreset): Int = when (p) {
    DatePreset.ANY -> R.string.history_date_any
    DatePreset.TODAY -> R.string.history_date_day
    DatePreset.WEEK -> R.string.history_date_week
    DatePreset.MONTH -> R.string.history_date_month
    DatePreset.CUSTOM -> R.string.history_date_custom
}

@Composable
private fun dateLabel(state: HistoryUiState): String {
    val f = state.filter
    val since = f.since
    if (state.datePreset != DatePreset.CUSTOM || since == null) return stringResource(presetLabel(state.datePreset))
    val zone = ZoneId.systemDefault()
    val last = (f.until ?: Instant.now()).minusMillis(1)
    return stringResource(R.string.history_date_range, Times.dayLabel(Times.day(since, zone)), Times.dayLabel(Times.day(last, zone)))
}

/** The date range picker: [since] the first day's start, [until] the start of the day after the last (local time). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDialog(onDismiss: () -> Unit, onPick: (Instant?, Instant?) -> Unit) {
    val picker = rememberDateRangePickerState()
    val zone = ZoneId.systemDefault()
    // The picker's selections are UTC midnights of the chosen calendar days.
    fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = picker.selectedStartDateMillis != null,
                onClick = {
                    val start = picker.selectedStartDateMillis?.let { day(it) }
                    val end = (picker.selectedEndDateMillis ?: picker.selectedStartDateMillis)?.let { day(it) }
                    if (start != null && end != null) {
                        val (since, until) = HistoryFilters.days(start, end, zone)
                        onPick(since, until)
                    }
                },
            ) { Text(stringResource(R.string.history_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_cancel)) } },
    ) {
        DateRangePicker(state = picker, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConnectionDialog(state: HistoryUiState, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_connection_pick)) },
        text = {
            LazyColumn {
                item { ChoiceRow(stringResource(R.string.history_connection_any), state.filter.connectionId == null) { onPick(null) } }
                items(state.connections, key = { it.id }) { c ->
                    ChoiceRow(c.name, state.filter.connectionId == c.id) { onPick(c.id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(Spacing.m))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EntryList(state: HistoryUiState, actions: HistoryActions) {
    val list = rememberLazyListState()
    // Infinite scroll: the next page once the last rows come into view.
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - LOAD_AHEAD
        }
    }
    LaunchedEffect(nearEnd, state.entries.size, state.end) {
        if (nearEnd && !state.end) actions.onLoadMore()
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("history_list")) {
        items(state.entries, key = { it.seq }) { e -> EntryRow(e, state.connectionNames, actions.onOpen) }
        item(key = "footer") {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.l), contentAlignment = Alignment.Center) {
                when {
                    state.loadingMore || (state.loading && state.entries.isNotEmpty()) -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp).testTag("history_loading_more"),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (state.partial || state.filter.q != null) {
                            Text(
                                stringResource(R.string.history_searching_older),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.error != null -> TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.history_retry)) }
                    state.end -> Text(
                        stringResource(R.string.history_end),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: AuditRecord, names: Map<String, String>, onOpen: (Long) -> Unit) {
    val connection = e.connectionId?.let { names[it] ?: stringResource(R.string.history_connection_removed) }
    VettIdListRow(
        title = HistoryText.title(e.kind),
        supporting = connection ?: stringResource(AuditKinds.categoryLabel(e.category)),
        meta = e.at?.let { Times.short(it) },
        tileIcon = categoryIcon(e.category),
        onClick = { onOpen(e.seq) },
        modifier = Modifier.testTag("history_entry_${e.seq}"),
    )
}

private const val LOAD_AHEAD = 5
