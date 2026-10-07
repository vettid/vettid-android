package com.vettid.feature.items

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.ui.components.BottomFloatingControls
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.components.VettIdFab
import com.vettid.core.ui.components.VettIdListRow
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import kotlinx.serialization.Serializable

/** Type-safe navigation route of the Vault screen (the items, ANDROID-PLAN 0.1.11). */
@Serializable
data object ItemsRoute

/** "Add to vault": the templates and a blank item. */
@Serializable
data object NewItemRoute

/** Adding ([itemId] null, from [template] or blank) or editing an item. */
@Serializable
data class ItemEditRoute(val itemId: String? = null, val template: String? = null) {
    companion object {
        const val ARG_ITEM = "itemId"
        const val ARG_TEMPLATE = "template"
    }
}

/** One item. */
@Serializable
data class ItemDetailRoute(val itemId: String) {
    companion object {
        const val ARG = "itemId"
    }
}

/** What the Vault destinations need from the app shell. */
data class ItemsHost(
    val navigate: (Any) -> Unit,
    val onBack: () -> Unit,
    /** Leaves the current screen for [route] (the edit screen, once saved, for the item). */
    val replace: (Any) -> Unit,
)

/** Registers the Vault list, the template picker, an item's detail and the add/edit screen. */
fun NavGraphBuilder.itemsDestination(chrome: ShellChrome, host: ItemsHost) {
    composable<ItemsRoute> {
        val vm: ItemsViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ItemsScreen(
            state = state,
            chrome = chrome,
            actions = ItemsActions(
                onOpen = { host.navigate(ItemDetailRoute(it)) },
                onAdd = { host.navigate(NewItemRoute) },
                onQuery = vm::setQuery,
                onTag = vm::setTag,
                onCategory = vm::setCategory,
                onSensitivity = vm::setSensitivity,
                onClearFilters = vm::clearFilters,
                onRetry = vm::refresh,
                onTags = { host.navigate(TagsRoute) },
            ),
        )
    }
    composable<NewItemRoute> {
        TemplatePickerScreen(
            onBack = host.onBack,
            onPick = { host.replace(ItemEditRoute(template = it)) },
        )
    }
    composable<ItemDetailRoute> { ItemDetailRouteContent(host) }
    composable<ItemEditRoute> { ItemEditRouteContent(host) }
    composable<TagsRoute> { TagsRouteContent(host) }
    composable<ConnectionSharingRoute> { ConnectionSharingRouteContent(host) }
    composable<RuleEditRoute> { RuleEditRouteContent(host) }
    composable<SharedWithYouRoute> { SharedWithYouRouteContent(host) }
}

/** What the Vault list can ask for. */
data class ItemsActions(
    val onOpen: (String) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onTag: (String?) -> Unit = {},
    val onCategory: (String?) -> Unit = {},
    val onSensitivity: (Sensitivity?) -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** The tags screen (create, rename, delete). */
    val onTags: () -> Unit = {},
)

/**
 * The Vault (ANDROID-PLAN §4, 0.1.11): the member's items by name, with a search and filters by tag, category and
 * sensitivity; "Add to vault" is the floating button. Values are never in the list (`item.list` carries none).
 */
@Composable
fun ItemsScreen(state: ItemsUiState, chrome: ShellChrome, actions: ItemsActions, modifier: Modifier = Modifier) {
    TopLevelScaffold(
        title = stringResource(R.string.items_title),
        chrome = chrome,
        modifier = modifier,
        overlay = {
            BottomFloatingControls(
                end = {
                    VettIdFab(
                        icon = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.items_add),
                        onClick = actions.onAdd,
                        modifier = Modifier.testTag("items_add"),
                    )
                },
            )
        },
    ) {
        val error = state.error
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.items_error_list),
                    body = ItemsText.failure(error),
                    modifier = Modifier.padding(Spacing.xl),
                    actions = { TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.items_retry)) } },
                )
            }
            state.items.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Inventory2,
                title = stringResource(R.string.items_empty_title),
                body = stringResource(R.string.items_empty_body),
                modifier = Modifier.testTag("items_empty"),
            )
            else -> Column(Modifier.fillMaxSize()) {
                SearchField(state.filter.query, actions.onQuery)
                FilterRow(state, actions)
                val visible = state.visible
                if (visible.isEmpty()) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            icon = Icons.Outlined.Search,
                            title = stringResource(R.string.items_no_match_title),
                            body = stringResource(R.string.items_no_match_body),
                            modifier = Modifier.weight(1f).testTag("items_no_match"),
                        )
                        TextButton(onClick = actions.onClearFilters, modifier = Modifier.padding(bottom = Spacing.xxl)) {
                            Text(stringResource(R.string.items_filters_clear))
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("items_list")) {
                        items(visible, key = { it.itemId }) { ItemRow(it, actions.onOpen) }
                        // Room for the floating button over the last row.
                        item(key = "footer") { Spacer(Modifier.height(96.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(i: ItemSummary, onOpen: (String) -> Unit) {
    val category = ItemsText.category(i.category)
    val kind = stringResource(ItemsText.sensitivity(i.sensitivity))
    VettIdListRow(
        title = i.name,
        supporting = if (i.sensitivity == Sensitivity.DATA) category else stringResource(R.string.items_row_supporting, category, kind),
        meta = i.updatedAt?.let { Times.short(it) },
        tileIcon = ItemTemplates.icon(i.category),
        onClick = { onOpen(i.itemId) },
        modifier = Modifier.testTag("items_row_${i.itemId}"),
    )
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.items_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.items_search_clear))
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
            .testTag("items_search"),
    )
}

@Composable
private fun FilterRow(state: ItemsUiState, actions: ItemsActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sensitivity.entries.forEach { s ->
            FilterChip(
                selected = state.filter.sensitivity == s,
                onClick = { actions.onSensitivity(s) },
                label = { Text(stringResource(ItemsText.sensitivity(s))) },
                leadingIcon = { Icon(ItemsText.sensitivityIcon(s), contentDescription = null) },
                modifier = Modifier.testTag("items_filter_${s.wire}"),
            )
        }
        Choice(
            label = state.filter.category?.let { ItemsText.category(it) } ?: stringResource(R.string.items_filter_category),
            selected = state.filter.category != null,
            options = state.categories.map { it to ItemsText.category(it) },
            anyLabel = stringResource(R.string.items_filter_category_any),
            onPick = actions.onCategory,
            tag = "items_filter_category",
        )
        if (state.tags.isNotEmpty()) {
            Choice(
                label = state.filter.tag?.let { tagLabel(it) } ?: stringResource(R.string.items_filter_tag),
                selected = state.filter.tag != null,
                options = state.tags.map { it to tagLabel(it) },
                anyLabel = stringResource(R.string.items_filter_tag_any),
                onPick = actions.onTag,
                tag = "items_filter_tag",
            )
        }
        if (!state.filter.isEmpty) {
            TextButton(onClick = actions.onClearFilters) { Text(stringResource(R.string.items_filters_clear)) }
        }
        TextButton(onClick = actions.onTags, modifier = Modifier.testTag("items_manage_tags")) {
            Text(stringResource(R.string.items_tags_manage))
        }
    }
}

/** A tag as shown: the reserved `@profile` as "Shared profile". */
@Composable
fun tagLabel(tag: String): String = if (tag == com.vettid.core.data.items.ItemChecks.PROFILE_TAG) {
    stringResource(R.string.items_tag_profile)
} else {
    tag
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    options: List<Pair<String, String>>,
    anyLabel: String,
    onPick: (String?) -> Unit,
    tag: String,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.testTag(tag),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(anyLabel) }, onClick = {
                open = false
                onPick(null)
            })
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    onPick(id)
                })
            }
        }
    }
}
