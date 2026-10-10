package com.vettid.feature.items

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.SharingRepository
import com.vettid.core.data.items.TagChange
import com.vettid.core.data.items.TagRegistry
import com.vettid.core.data.items.TagView
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.ui.components.BottomFloatingControls
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.RowAction
import com.vettid.core.ui.components.TagDot
import com.vettid.core.ui.components.VettIdFab
import com.vettid.core.ui.components.VettIdListRow
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.TagColor
import com.vettid.core.ui.theme.TagColors
import com.vettid.core.ui.theme.VettIdTheme
import com.vettid.core.ui.theme.tagColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** The tags (§10.8): create, rename (a merge of one), describe, colour, delete. */
@Serializable
data object TagsRoute

/** What the tags screen is asking. */
sealed interface TagDialog {
    data class Create(val name: String = "", val description: String = "") : TagDialog

    data class Edit(val tag: TagView, val name: String, val description: String) : TagDialog

    /** The rename's dry run, to confirm: what it changes and shares. */
    data class ConfirmRename(val tag: String, val into: String, val change: TagChange) : TagDialog

    data class ConfirmDelete(val tag: String, val change: TagChange) : TagDialog

    /** The tag's colour, one of the palette's ten (owner decision 2026-10-09). */
    data class Colour(val tag: TagView) : TagDialog

    /** Why `@profile` has no colour to pick: it always uses the member's own. */
    data object ProfileColour : TagDialog
}

/** Immutable UI state of the tags screen. */
data class TagsUiState(
    val registry: TagRegistry? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val dialog: TagDialog? = null,
    val error: FailureKind? = null,
    /** The limit a `limit` error named (VAULT-MESSAGING 0.21.0 §10.1). */
    val limit: com.vettid.core.data.vault.VaultLimit? = null,
) {
    /** `@profile` first, then the member's tags by name. */
    val tags: List<TagView> get() = registry?.tags.orEmpty().sortedWith(compareBy({ !it.reserved }, { it.tag }))
}

/**
 * The tags (§10.8): every tag with its items and the share rules that name it (the apps show both on every tag,
 * VAULT-ITEMS §5). A rename or delete is previewed with the vault's dry run first; a tag a rule names cannot be
 * deleted (`in_use`); `@profile` is reserved.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class TagsViewModel @Inject constructor(private val sharing: SharingRepository) : ViewModel() {
    private val state = MutableStateFlow(TagsUiState(registry = sharing.tags.value))
    val uiState: StateFlow<TagsUiState> = state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = run {
        val r = sharing.refreshTags()
        state.update { it.copy(registry = r, loading = false) }
    }

    fun askCreate() = state.update { it.copy(dialog = TagDialog.Create(), error = null) }

    fun edit(t: TagView) {
        if (t.reserved) return
        state.update { it.copy(dialog = TagDialog.Edit(t, t.tag, t.description.orEmpty()), error = null) }
    }

    /** The colour picker of [t]; for `@profile`, why it has none. */
    fun askColour(t: TagView) = state.update {
        it.copy(dialog = if (t.reserved) TagDialog.ProfileColour else TagDialog.Colour(t), error = null)
    }

    /** Stores the palette colour of [slot] for the picker's tag (`tag.set`, its description and icon kept). */
    fun pickColour(slot: Int) {
        val d = state.value.dialog as? TagDialog.Colour ?: return
        val color = TagColors.stored.getOrNull(slot)
        when {
            color == null -> Unit
            TagColors.slotOf(d.tag.color) == slot -> dismiss()
            else -> run {
                sharing.setTagColor(d.tag.tag, color)
                done()
            }
        }
    }

    fun setName(v: String) = state.update { s ->
        s.copy(
            dialog = when (val d = s.dialog) {
                is TagDialog.Create -> d.copy(name = v)
                is TagDialog.Edit -> d.copy(name = v)
                else -> d
            },
        )
    }

    fun setDescription(v: String) = state.update { s ->
        val t = v.take(MAX_DESCRIPTION)
        s.copy(
            dialog = when (val d = s.dialog) {
                is TagDialog.Create -> d.copy(description = t)
                is TagDialog.Edit -> d.copy(description = t)
                else -> d
            },
        )
    }

    fun dismiss() = state.update { it.copy(dialog = null) }

    fun dismissError() = state.update { it.copy(error = null) }

    /** Create, or for an edit: a new name goes through the dry run first; a new description is set at once. */
    fun save() {
        when (val d = state.value.dialog) {
            is TagDialog.Create -> {
                val n = ItemChecks.normalizeTag(d.name, reserved = false) ?: return
                run {
                    sharing.setTag(n, d.description)
                    done()
                }
            }
            is TagDialog.Edit -> {
                val n = ItemChecks.normalizeTag(d.name, reserved = false) ?: return
                if (n != d.tag.tag) {
                    run {
                        val change = sharing.renameTag(d.tag.tag, n, dryRun = true)
                        state.update { it.copy(dialog = TagDialog.ConfirmRename(d.tag.tag, n, change)) }
                    }
                } else if (d.description != d.tag.description.orEmpty()) {
                    run {
                        sharing.setTag(n, d.description, d.tag.color)
                        done()
                    }
                } else {
                    dismiss()
                }
            }
            else -> Unit
        }
    }

    fun confirmRename() {
        val d = state.value.dialog as? TagDialog.ConfirmRename ?: return
        run {
            sharing.renameTag(d.tag, d.into, dryRun = false)
            done()
        }
    }

    fun askDelete() {
        val d = state.value.dialog as? TagDialog.Edit ?: return
        run {
            val change = sharing.deleteTag(d.tag.tag, dryRun = true)
            state.update { it.copy(dialog = TagDialog.ConfirmDelete(d.tag.tag, change)) }
        }
    }

    fun confirmDelete() {
        val d = state.value.dialog as? TagDialog.ConfirmDelete ?: return
        run {
            sharing.deleteTag(d.tag, dryRun = false)
            done()
        }
    }

    private fun done() = state.update { it.copy(dialog = null, registry = sharing.tags.value ?: it.registry) }

    private fun run(block: suspend () -> Unit) {
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                state.update { it.copy(busy = false, loading = false) }
            } catch (e: VaultFailure) {
                val keep = e.kind != FailureKind.IN_USE
                state.update { it.copy(busy = false, loading = false, error = e.kind, limit = e.limit, dialog = it.dialog.takeIf { keep }) }
            }
        }
    }

    private companion object {
        /** A tag's description is at most 256 bytes (§10.8); the field stops at 256 characters and the vault checks bytes. */
        const val MAX_DESCRIPTION = 256
    }
}

/** What the tags screen can ask for. */
data class TagsActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onCreate: () -> Unit = {},
    val onEdit: (TagView) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onDescription: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onConfirmRename: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onColour: (TagView) -> Unit = {},
    val onPickColour: (Int) -> Unit = {},
)

@Composable
internal fun TagsRouteContent(host: ItemsHost) {
    val vm: TagsViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    TagsScreen(
        state,
        TagsActions(
            onBack = host.onBack, onRetry = vm::refresh, onCreate = vm::askCreate, onEdit = vm::edit, onName = vm::setName,
            onDescription = vm::setDescription, onSave = vm::save, onDelete = vm::askDelete, onConfirmRename = vm::confirmRename,
            onConfirmDelete = vm::confirmDelete, onDismiss = vm::dismiss, onDismissError = vm::dismissError,
            onColour = vm::askColour, onPickColour = vm::pickColour,
        ),
    )
}

/** The tags: each with how many items carry it and how many sharing rules use it; "New tag" is the button. */
@Composable
fun TagsScreen(state: TagsUiState, actions: TagsActions, modifier: Modifier = Modifier) {
    DetailScaffold(
        onBackClick = actions.onBack,
        modifier = modifier.testTag("tags"),
        overlay = {
            BottomFloatingControls(
                end = {
                    VettIdFab(Icons.Outlined.Add, stringResource(R.string.items_tags_new), actions.onCreate, Modifier.testTag("tags_new"))
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            LargeTitle(stringResource(R.string.items_tags_title))
            state.error?.let { e ->
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.items_error_item),
                    ItemsText.failure(e, limit = state.limit),
                    modifier = Modifier.padding(horizontal = Spacing.s).testTag("tags_error"),
                    actions = { TextButton(onClick = actions.onDismissError) { Text(stringResource(R.string.items_ok)) } },
                )
            }
            when {
                state.loading && state.registry == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                state.tags.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Label,
                    title = stringResource(R.string.items_tags_empty_title),
                    body = stringResource(R.string.items_tags_empty_body),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.tags, key = { it.tag }) { t -> TagRow(t, actions.onEdit, actions.onColour) }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }
    TagDialogs(state, actions)
}

@Composable
private fun TagRow(t: TagView, onEdit: (TagView) -> Unit, onColour: (TagView) -> Unit) {
    val counts = pluralStringResource(R.plurals.items_tag_items, t.items, t.items) +
        if (t.rules.isEmpty()) "" else " · " + pluralStringResource(R.plurals.items_tag_rules, t.rules.size, t.rules.size)
    VettIdListRow(
        title = tagLabel(t.tag),
        supporting = if (t.reserved) {
            stringResource(R.string.items_tag_profile_note)
        } else {
            listOfNotNull(counts, t.description).joinToString(" · ")
        },
        meta = if (t.reserved) counts else null,
        tileIcon = if (t.reserved) Icons.Outlined.Person else Icons.AutoMirrored.Outlined.Label,
        tileColors = tagColor(t.tag, t.color),
        // The colour button (owner decision 2026-10-09); `@profile`'s says why it is always gold.
        action = RowAction(Icons.Outlined.Palette, stringResource(R.string.items_tags_cd_colour, tagLabel(t.tag)), { onColour(t) }),
        onClick = if (t.reserved) null else ({ onEdit(t) }),
        modifier = Modifier.testTag("tag_${t.tag}"),
    )
}

@Composable
private fun TagDialogs(state: TagsUiState, actions: TagsActions) {
    when (val d = state.dialog) {
        is TagDialog.Create -> TagForm(stringResource(R.string.items_tags_new), d.name, d.description, null, state.busy, actions)
        is TagDialog.Edit -> TagForm(stringResource(R.string.items_tags_edit), d.name, d.description, d.tag, state.busy, actions)
        is TagDialog.ConfirmRename -> ConfirmDialog(
            title = stringResource(R.string.items_tags_rename_title, d.tag, d.into),
            text = changeText(d.change, renaming = true),
            confirmLabel = stringResource(R.string.items_tags_rename),
            onConfirm = actions.onConfirmRename,
            onDismiss = actions.onDismiss,
            modifier = Modifier.testTag("tags_confirm_rename"),
        )
        is TagDialog.ConfirmDelete -> ConfirmDialog(
            title = stringResource(R.string.items_tags_delete_title, d.tag),
            text = changeText(d.change, renaming = false),
            confirmLabel = stringResource(R.string.items_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onDismiss,
            destructive = true,
            modifier = Modifier.testTag("tags_confirm_delete"),
        )
        is TagDialog.Colour -> TagColourDialog(d.tag, state.busy, actions)
        TagDialog.ProfileColour -> AlertDialog(
            onDismissRequest = actions.onDismiss,
            title = { Text(stringResource(R.string.items_tags_colour_profile_title)) },
            text = { Text(stringResource(R.string.items_tags_colour_profile)) },
            confirmButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.items_ok)) } },
            modifier = Modifier.testTag("tag_colour_profile"),
        )
        null -> Unit
    }
}

/** The palette's names, by slot ([TagColors.names]). */
private val colourNames = listOf(
    R.string.items_tag_colour_red, R.string.items_tag_colour_orange, R.string.items_tag_colour_green, R.string.items_tag_colour_teal,
    R.string.items_tag_colour_blue, R.string.items_tag_colour_indigo, R.string.items_tag_colour_violet, R.string.items_tag_colour_pink,
    R.string.items_tag_colour_brown, R.string.items_tag_colour_slate,
)

/** The ten palette colours in two rows, the one the tag shows checked; a tap stores the colour. */
@Composable
private fun TagColourDialog(tag: TagView, busy: Boolean, actions: TagsActions) {
    val current = TagColors.shownSlot(tag.tag, tag.color)
    val palette = if (VettIdTheme.colors.isDark) TagColors.dark else TagColors.light
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.items_tags_colour_title, tagLabel(tag.tag))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.selectableGroup()) {
                palette.indices.chunked(SWATCHES_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        row.forEach { i ->
                            ColourSwatch(palette[i], i, stringResource(colourNames[i]), i == current, !busy) { actions.onPickColour(i) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.items_cancel)) } },
        modifier = Modifier.testTag("tag_colour"),
    )
}

@Composable
@Suppress("LongParameterList")
private fun ColourSwatch(c: TagColor, slot: Int, name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cd = if (selected) stringResource(R.string.items_tags_cd_colour_selected, name) else name
    Box(
        modifier = Modifier
            .size(Spacing.touchTarget)
            .clip(CircleShape)
            .background(c.container)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = cd }
            .testTag("tag_colour_${TagColors.names[slot]}"),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = c.onContainer)
    }
}

private const val SWATCHES_PER_ROW = 5

/** The dry run in words (§10.8): the items it changes and, for a rename, what newly gets shared. */
@Composable
private fun changeText(c: TagChange, renaming: Boolean): String {
    val items = pluralStringResource(R.plurals.items_tags_change_items, c.items, c.items)
    if (!renaming || c.sharesTotal == 0) return items
    val auto = c.shares.count { it.mode == ShareMode.AUTO }
    val asked = c.sharesTotal - auto
    return listOfNotNull(
        items,
        auto.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.items_tags_change_shared, it, it) },
        asked.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.items_tags_change_asked, it, it) },
    ).joinToString(" ")
}

@Composable
@Suppress("LongParameterList")
private fun TagForm(title: String, name: String, description: String, tag: TagView?, busy: Boolean, actions: TagsActions) {
    val invalid = name.isNotBlank() && ItemChecks.normalizeTag(name, reserved = false) == null
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = actions.onName,
                    label = { Text(stringResource(R.string.items_tags_name)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = { Text(stringResource(if (invalid) R.string.items_problem_tag else R.string.items_tag_hint)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("tag_form_name"),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = actions.onDescription,
                    label = { Text(stringResource(R.string.items_tags_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tag != null && tag.rules.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.items_tags_used_by_rules, tag.rules.size, tag.rules.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (tag != null) {
                    TextButton(onClick = { actions.onColour(tag) }, enabled = !busy, modifier = Modifier.testTag("tag_form_colour")) {
                        TagDot(tag.tag, stored = tag.color)
                        Spacer(Modifier.width(Spacing.s))
                        Text(stringResource(R.string.items_tags_edit_colour))
                    }
                    TextButton(onClick = actions.onDelete, enabled = !busy, modifier = Modifier.testTag("tag_form_delete")) {
                        Text(stringResource(R.string.items_tags_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onSave,
                enabled = !busy && name.isNotBlank() && !invalid,
                modifier = Modifier.testTag("tag_form_save"),
            ) {
                Text(stringResource(R.string.items_save))
            }
        },
        dismissButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.items_cancel)) } },
    )
}
