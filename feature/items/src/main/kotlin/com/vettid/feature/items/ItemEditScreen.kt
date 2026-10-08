package com.vettid.feature.items

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.items.AddressValue
import com.vettid.core.data.items.DraftField
import com.vettid.core.data.items.DraftProblem
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.ItemCategories
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.theme.Spacing

@Composable
internal fun ItemEditRouteContent(host: ItemsHost) {
    val vm: ItemEditViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedId) { state.savedId?.let { host.replace(ItemDetailRoute(it)) } }
    BackHandler(enabled = state.dirty && state.prompt == null) { vm.askDiscard(true) }
    ItemEditScreen(
        state = state,
        actions = ItemEditActions(
            onBack = { if (state.dirty) vm.askDiscard(true) else host.onBack() },
            onDiscard = {
                vm.askDiscard(false)
                host.onBack()
            },
            onKeepEditing = { vm.askDiscard(false) },
            onName = vm::setName,
            onCategory = vm::setCategory,
            onSensitivity = vm::setSensitivity,
            onNotes = vm::setNotes,
            onRemoveNotes = vm::removeNotes,
            onTagInput = vm::setTagInput,
            onAddTag = vm::addTag,
            onRemoveTag = vm::removeTag,
            onInProfile = vm::setInProfile,
            onFieldText = vm::setFieldText,
            onFieldAddress = vm::setFieldAddress,
            onAskAddField = vm::askAddField,
            onAskRenameField = vm::askRenameField,
            onAskFieldKind = vm::askFieldKind,
            onAskNewCategory = vm::askNewCategory,
            onDialogText = vm::setDialogText,
            onDialogKind = vm::setDialogKind,
            onConfirmDialog = vm::confirmDialog,
            onDismissDialog = vm::dismissDialog,
            onFieldFocused = vm::fieldFocused,
            onRemoveField = vm::removeField,
            onMoveField = vm::moveField,
            onSave = vm::save,
            onDismissError = vm::dismissError,
            onPassword = vm::setPassword,
            onSubmitPassword = vm::submitPassword,
            onCancelPassword = vm::cancelPassword,
        ),
    )
}

/** What the add/edit screen can ask for. */
data class ItemEditActions(
    val onBack: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onKeepEditing: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onCategory: (String) -> Unit = {},
    val onSensitivity: (Sensitivity) -> Unit = {},
    val onNotes: (String) -> Unit = {},
    val onRemoveNotes: () -> Unit = {},
    val onTagInput: (String) -> Unit = {},
    val onAddTag: () -> Unit = {},
    val onRemoveTag: (String) -> Unit = {},
    val onInProfile: (Boolean) -> Unit = {},
    val onFieldText: (Int, String) -> Unit = { _, _ -> },
    val onFieldAddress: (Int, AddressValue) -> Unit = { _, _ -> },
    val onAskAddField: () -> Unit = {},
    val onAskRenameField: (Int) -> Unit = {},
    val onAskFieldKind: (Int) -> Unit = {},
    val onAskNewCategory: () -> Unit = {},
    val onDialogText: (String) -> Unit = {},
    val onDialogKind: (String) -> Unit = {},
    val onConfirmDialog: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onFieldFocused: () -> Unit = {},
    val onRemoveField: (Int) -> Unit = {},
    val onMoveField: (Int, Int) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onSubmitPassword: () -> Unit = {},
    val onCancelPassword: () -> Unit = {},
)

/**
 * Adding or editing an item (VAULT-ITEMS §9: name, category, sensitivity, tags, fields; §10.7's checks before
 * anything is sent). A new item picks its sensitivity here; a critical one is saved with the credential password.
 * A secret or critical item's stored values show as kept, never revealed (VAULT-MESSAGING 0.21.0 §10.7 Kept values):
 * typing into a field replaces its value. Fields are value-first (owner feedback 2026-10-08): one input captioned with
 * the field's label, its type as a hint, and a menu to rename, retype (unsaved fields), move or remove it.
 */
@Composable
@Suppress("CyclomaticComplexMethod")
fun ItemEditScreen(state: ItemEditUiState, actions: ItemEditActions, modifier: Modifier = Modifier) {
    val prompt = state.prompt
    if (prompt != null) {
        return CredentialPasswordContent(
            prompt,
            state.draft.name.ifBlank { stringResource(R.string.items_unnamed) },
            actions.onPassword,
            actions.onSubmitPassword,
            actions.onCancelPassword,
        )
    }
    if (state.loading) {
        return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
    val d = state.draft
    FormScaffold(
        title = stringResource(if (state.isNew) R.string.items_new_title else R.string.items_edit_title),
        primaryLabel = stringResource(R.string.items_save),
        onPrimary = actions.onSave,
        primaryEnabled = !state.loading,
        busy = state.busy,
        onBack = actions.onBack,
        modifier = modifier.testTag("item_edit"),
        header = { HeaderGlyph(if (state.isNew) ItemTemplates.icon(d.category) else Icons.Outlined.Edit) },
    ) {
        val show = state.showErrors
        val problems = state.check.problems
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            state.error?.let { e ->
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.items_error_save),
                    ItemsText.failure(
                        e,
                        creating = state.isNew,
                        critical = d.sensitivity == Sensitivity.CRITICAL,
                        profile = state.inProfile,
                        limit = state.limit,
                    ),
                    modifier = Modifier.testTag("item_edit_error"),
                    actions = { TextButton(onClick = actions.onDismissError) { Text(stringResource(R.string.items_ok)) } },
                )
            }
            val nameProblem = listOf(DraftProblem.NAME_EMPTY, DraftProblem.NAME_TOO_LONG).firstOrNull { it in problems }
            OutlinedTextField(
                value = d.name,
                onValueChange = actions.onName,
                label = { Text(stringResource(R.string.items_name)) },
                singleLine = true,
                isError = show && nameProblem != null,
                supportingText = if (show && nameProblem != null) {
                    { Text(ItemsText.problem(nameProblem)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("item_edit_name"),
            )
            CategoryPicker(d.category, state.pickerCustoms, actions)
            SensitivitySection(state, actions)
            TagsSection(state, actions)
            Text(stringResource(R.string.items_fields), style = MaterialTheme.typography.titleSmall)
            if (d.fields.isEmpty()) {
                Text(
                    stringResource(R.string.items_no_fields_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("item_edit_no_fields"),
                )
            }
            d.fields.forEachIndexed { i, f ->
                val problems = if (show) state.check.fieldProblems[i].orEmpty() else emptySet()
                FieldEditor(FieldSlot(i, d.fields.size, focus = state.focusField == i), f, problems, actions)
            }
            AddFieldButton(enabled = d.fields.size < ItemChecks.MAX_FIELDS, onAdd = actions.onAskAddField)
            OutlinedTextField(
                value = d.notes,
                onValueChange = actions.onNotes,
                label = { Text(stringResource(R.string.items_notes)) },
                placeholder = if (d.keepNotes) {
                    { Text(stringResource(R.string.items_notes_kept_placeholder)) }
                } else {
                    null
                },
                minLines = 3,
                isError = show && DraftProblem.NOTES_TOO_LONG in problems,
                supportingText = when {
                    show && DraftProblem.NOTES_TOO_LONG in problems -> {
                        { Text(ItemsText.problem(DraftProblem.NOTES_TOO_LONG)) }
                    }
                    d.keepNotes -> {
                        { Text(stringResource(R.string.items_notes_kept)) }
                    }
                    else -> null
                },
                modifier = Modifier.fillMaxWidth().testTag("item_edit_notes"),
            )
            if (d.keepNotes) {
                TextButton(
                    onClick = actions.onRemoveNotes,
                    modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("item_edit_notes_remove"),
                ) { Text(stringResource(R.string.items_notes_remove)) }
            }
            val other = problems - setOf(DraftProblem.NAME_EMPTY, DraftProblem.NAME_TOO_LONG, DraftProblem.NOTES_TOO_LONG)
            if (show && other.isNotEmpty()) {
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.items_problems_title),
                    other.map { ItemsText.problem(it) }.joinToString("\n"),
                    modifier = Modifier.testTag("item_edit_problems"),
                )
            }
            SizeNote(state)
        }
    }
    EditDialogs(state, actions)
    if (state.confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.items_discard_title),
            text = stringResource(R.string.items_discard_body),
            confirmLabel = stringResource(R.string.items_discard),
            onConfirm = actions.onDiscard,
            onDismiss = actions.onKeepEditing,
            destructive = true,
            dismissLabel = stringResource(R.string.items_keep_editing),
        )
    }
}

/** The recommended categories, then the member's own (§10.7 allows any), then "New category…". */
@Composable
private fun CategoryPicker(category: String, customs: List<String>, actions: ItemEditActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = ItemsText.category(category),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.items_category)) },
            trailingIcon = {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag("item_edit_category")) {
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = stringResource(R.string.items_cd_choose_category))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ItemTemplates.categories.forEach { c ->
                DropdownMenuItem(
                    text = { Text(stringResource(c.label)) },
                    leadingIcon = { Icon(c.icon, contentDescription = null) },
                    onClick = {
                        open = false
                        actions.onCategory(c.id)
                    },
                )
            }
            if (customs.isNotEmpty()) HorizontalDivider()
            customs.forEach { c ->
                DropdownMenuItem(
                    text = { Text(ItemsText.category(c)) },
                    leadingIcon = { Icon(ItemTemplates.icon(c), contentDescription = null) },
                    onClick = {
                        open = false
                        actions.onCategory(c)
                    },
                    modifier = Modifier.testTag("item_edit_category_$c"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.items_category_new)) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onAskNewCategory()
                },
                modifier = Modifier.testTag("item_edit_category_new"),
            )
        }
    }
}

@Composable
private fun SensitivitySection(state: ItemEditUiState, actions: ItemEditActions) {
    val s = state.draft.sensitivity
    Text(stringResource(R.string.items_protection), style = MaterialTheme.typography.titleSmall)
    if (state.isNew) {
        Column(Modifier.testTag("item_edit_sensitivity")) {
            Sensitivity.entries.forEach { x ->
                // @profile items stay data (§10.8): the other choices are off while the item carries it.
                SensitivityChoice(x, x == s, enabled = !state.inProfile || x == Sensitivity.DATA) { actions.onSensitivity(x) }
            }
        }
    } else {
        Text(
            stringResource(R.string.items_protection_existing, stringResource(ItemsText.sensitivity(s))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagsSection(state: ItemEditUiState, actions: ItemEditActions) {
    Text(stringResource(R.string.items_tags), style = MaterialTheme.typography.titleSmall)
    // What tags do (§10.12: a share rule names tags; the items that match follow it, asked first or automatically).
    Text(
        stringResource(R.string.items_tags_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("item_edit_tags_note"),
    )
    // The reserved @profile is the built-in choice below, not a chip.
    val own = state.draft.tags.filter { it != ItemChecks.PROFILE_TAG }
    if (own.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("item_edit_tags")) {
            own.forEach { t ->
                InputChip(
                    selected = false,
                    onClick = { actions.onRemoveTag(t) },
                    label = { Text(tagLabel(t)) },
                    trailingIcon = {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.items_cd_remove_tag, tagLabel(t)))
                    },
                )
            }
        }
    }
    val typed = state.tagInput
    val invalid = typed.isNotBlank() && ItemChecks.normalizeTag(typed) == null
    OutlinedTextField(
        value = typed,
        onValueChange = actions.onTagInput,
        label = { Text(stringResource(R.string.items_tag_add)) },
        singleLine = true,
        isError = invalid,
        supportingText = { Text(stringResource(if (invalid) R.string.items_problem_tag else R.string.items_tag_hint)) },
        trailingIcon = {
            IconButton(onClick = actions.onAddTag, enabled = typed.isNotBlank() && !invalid) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.items_tag_add))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { actions.onAddTag() }),
        modifier = Modifier.fillMaxWidth().testTag("item_edit_tag_input"),
    )
    if (state.shareImpact.isNotEmpty()) {
        NoticeCard(
            NoticeKind.INFO,
            stringResource(R.string.items_impact_title),
            state.shareImpact.map { impactText(it) }.joinToString("\n"),
            modifier = Modifier.testTag("item_edit_share_impact"),
        )
    }
    ProfileChoice(state, actions.onInProfile)
}

/**
 * "Shared profile" (`@profile`, §10.8) as a built-in tag choice: only a standard item can carry it, so for a secret or
 * critical one it is shown off, with the reason (an item that carries it anyway can still take it off).
 */
@Composable
private fun ProfileChoice(state: ItemEditUiState, onInProfile: (Boolean) -> Unit) {
    val on = state.inProfile
    val available = on || state.draft.sensitivity == Sensitivity.DATA
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .toggleable(value = on, enabled = available, role = Role.Checkbox, onValueChange = onInProfile)
            .padding(vertical = Spacing.xs)
            .testTag("item_edit_profile_choice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = on, onCheckedChange = null, enabled = available)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.items_tag_profile),
                style = MaterialTheme.typography.bodyLarge,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(if (available) R.string.items_profile_choice_note else R.string.items_problem_profile),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun impactText(i: ShareImpact): String {
    val who = i.connectionName.ifBlank { stringResource(R.string.items_sharing_this_connection) }
        .let { com.vettid.core.data.account.AccountNames.isolate(it) }
    return when {
        i.withdrawn -> stringResource(R.string.items_impact_withdrawn, who)
        i.usableOnly -> stringResource(R.string.items_impact_usable, who)
        i.mode == com.vettid.core.data.items.ShareMode.AUTO -> stringResource(R.string.items_impact_auto, who)
        else -> stringResource(R.string.items_impact_ask, who)
    }
}

/** Where a field is in the list, and whether its value input takes the focus (it was just added). */
private data class FieldSlot(val index: Int, val count: Int, val focus: Boolean)

/**
 * One field, value-first: a single input captioned with the field's label, with the keyboard of its kind, and under
 * it the kind as a hint (a problem replaces it). The label and kind change from the field's menu, never inline.
 */
@Composable
private fun FieldEditor(slot: FieldSlot, f: DraftField, problems: Set<DraftProblem>, actions: ItemEditActions) {
    val i = slot.index
    val kindName = stringResource(ItemsText.kind(f.kind))
    val caption = f.label.trim().ifEmpty { kindName }
    val labelProblem = listOf(DraftProblem.LABEL_EMPTY, DraftProblem.LABEL_TOO_LONG).firstOrNull { it in problems }
        ?: DraftProblem.BAD_CHARACTER.takeIf { it in problems && ItemChecks.labelProblems(f.label.trim()).isNotEmpty() }
    val valueProblem = (problems - setOfNotNull(DraftProblem.LABEL_EMPTY, DraftProblem.LABEL_TOO_LONG, labelProblem)).firstOrNull()
    val error = labelProblem?.let { ItemsText.problem(it) } ?: valueProblem?.let { ItemsText.problem(it, f.kind) }
    // A kept value is not at hand (§10.7 Kept values): the field stays empty until the member types a new one.
    val hint = if (f.kept) stringResource(R.string.items_field_kind_kept, kindName) else kindName
    val focus = remember { FocusRequester() }
    LaunchedEffect(slot.focus) {
        if (slot.focus) {
            runCatching { focus.requestFocus() }
            actions.onFieldFocused()
        }
    }
    // No card around it: the outlined input is the field (a bordered card around it read as a second box).
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().testTag("item_edit_field_$i")) {
        Column(Modifier.weight(1f)) {
            val input = Modifier.focusRequester(focus)
            if (f.kind == FieldKinds.ADDRESS) {
                Text(caption, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.m))
                AddressEditor(f.address, input) { actions.onFieldAddress(i, it) }
                Text(
                    error ?: hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = Spacing.xs)
                        .testTag(if (f.kept) "item_edit_field_kept_$i" else "item_edit_field_hint_$i"),
                )
            } else {
                ValueEditor(i, f, caption, error ?: hint, error != null, input) { actions.onFieldText(i, it) }
            }
        }
        FieldMenu(slot, f, caption, actions)
    }
}

/** A field's ⋯ menu: Rename…, Change type… (unsaved fields only, §10.7), Move up, Move down, Remove. */
@Composable
private fun FieldMenu(slot: FieldSlot, f: DraftField, caption: String, actions: ItemEditActions) {
    val i = slot.index
    var open by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.padding(top = Spacing.xs)) {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("item_edit_field_menu_$i")) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.items_cd_field_options, caption))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val pick = { a: () -> Unit ->
                open = false
                a()
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.items_field_rename)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { pick { actions.onAskRenameField(i) } },
                modifier = Modifier.testTag("item_edit_field_rename"),
            )
            // A saved field keeps its kind (its id names the same field, §10.7): offered, off, with the reason.
            val saved = f.fieldId != null
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.items_field_change_type))
                        if (saved) {
                            Text(stringResource(R.string.items_field_type_fixed), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                enabled = !saved,
                onClick = { pick { actions.onAskFieldKind(i) } },
                modifier = Modifier.testTag("item_edit_field_kind"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.items_field_move_up)) },
                leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                enabled = i > 0,
                onClick = { pick { actions.onMoveField(i, -1) } },
                modifier = Modifier.testTag("item_edit_field_up"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.items_field_move_down)) },
                leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                enabled = i < slot.count - 1,
                onClick = { pick { actions.onMoveField(i, 1) } },
                modifier = Modifier.testTag("item_edit_field_down"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.items_field_remove)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                onClick = { pick { actions.onRemoveField(i) } },
                modifier = Modifier.testTag("item_edit_field_remove"),
            )
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "LongParameterList")
private fun ValueEditor(
    i: Int,
    f: DraftField,
    caption: String,
    supporting: String,
    isError: Boolean,
    input: Modifier,
    onValue: (String) -> Unit,
) {
    val tag = input.fillMaxWidth().testTag("item_edit_field_value_$i")
    if (f.kind == FieldKinds.PASSWORD) {
        return SecretField(
            value = f.text,
            onValueChange = onValue,
            label = caption,
            error = supporting.takeIf { isError },
            supporting = supporting.takeUnless { isError },
            imeAction = ImeAction.Next,
            modifier = tag,
        )
    }
    val keyboard = when (f.kind) {
        FieldKinds.NUMBER -> KeyboardType.Decimal
        FieldKinds.EMAIL -> KeyboardType.Email
        FieldKinds.PHONE -> KeyboardType.Phone
        FieldKinds.URL -> KeyboardType.Uri
        else -> KeyboardType.Text
    }
    val placeholder = when (f.kind) {
        FieldKinds.DATE -> stringResource(R.string.items_placeholder_date)
        FieldKinds.URL -> stringResource(R.string.items_placeholder_url)
        FieldKinds.OTP -> stringResource(R.string.items_placeholder_otp)
        else -> null
    }
    val multi = f.kind == FieldKinds.MULTILINE
    OutlinedTextField(
        value = f.text,
        onValueChange = { v -> onValue(if (multi) v else v.replace("\n", "")) },
        label = { Text(caption) },
        placeholder = (if (f.kept) stringResource(R.string.items_field_kept_placeholder) else placeholder)?.let { p -> { Text(p) } },
        singleLine = !multi,
        minLines = if (multi) 3 else 1,
        isError = isError,
        supportingText = { Text(supporting) },
        textStyle = MaterialTheme.typography.bodyLarge.let {
            if (f.kind == FieldKinds.OTP) it.copy(fontFamily = FontFamily.Monospace) else it
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            autoCorrectEnabled = f.kind == FieldKinds.TEXT || multi,
            capitalization = if (f.kind == FieldKinds.TEXT || multi) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            imeAction = if (multi) ImeAction.Default else ImeAction.Next,
        ),
        modifier = tag,
    )
}

@Composable
private fun AddressEditor(a: AddressValue, first: Modifier, onValue: (AddressValue) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AddressPart(a.street, R.string.items_address_street, modifier = first) { onValue(a.copy(street = it)) }
        AddressPart(a.street2, R.string.items_address_street2) { onValue(a.copy(street2 = it)) }
        AddressPart(a.postalCode, R.string.items_address_postal_code) { onValue(a.copy(postalCode = it)) }
        AddressPart(a.city, R.string.items_address_city) { onValue(a.copy(city = it)) }
        AddressPart(a.region, R.string.items_address_region) { onValue(a.copy(region = it)) }
        // ISO 3166-1 alpha-2, upper case (§10.7).
        AddressPart(a.country, R.string.items_address_country, caps = true) { onValue(a.copy(country = it.uppercase().take(2))) }
    }
}

@Composable
private fun AddressPart(value: String, label: Int, caps: Boolean = false, modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.replace("\n", "")) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Words),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun AddFieldButton(enabled: Boolean, onAdd: () -> Unit) {
    TextButton(
        onClick = onAdd,
        enabled = enabled,
        modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("item_edit_add_field"),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null)
        Spacer(Modifier.width(Spacing.xs))
        Text(stringResource(if (enabled) R.string.items_add_field else R.string.items_problem_fields))
    }
}

/** The editor's small dialogs: add a field, rename one, change an unsaved one's type, a new category. */
@Composable
private fun EditDialogs(state: ItemEditUiState, actions: ItemEditActions) {
    when (val d = state.dialog) {
        is EditDialog.AddField -> FieldDialog(
            title = stringResource(R.string.items_add_field),
            confirm = stringResource(R.string.items_field_add_confirm),
            label = d.label,
            kind = d.kind,
            actions = actions,
        )
        is EditDialog.RenameField -> FieldDialog(
            title = stringResource(R.string.items_field_rename_title),
            confirm = stringResource(R.string.items_field_rename_confirm),
            label = d.label,
            kind = null,
            actions = actions,
        )
        is EditDialog.FieldKind -> {
            val label = state.draft.fields.getOrNull(d.index)?.label.orEmpty()
            AlertDialog(
                onDismissRequest = actions.onDismissDialog,
                title = { Text(stringResource(R.string.items_field_type_title, label)) },
                text = { KindPicker(d.kind, actions.onDialogKind) },
                confirmButton = {
                    TextButton(onClick = actions.onConfirmDialog, modifier = Modifier.testTag("item_field_dialog_confirm")) {
                        Text(stringResource(R.string.items_field_type_confirm))
                    }
                },
                dismissButton = { TextButton(onClick = actions.onDismissDialog) { Text(stringResource(R.string.items_cancel)) } },
                modifier = Modifier.testTag("item_field_dialog"),
            )
        }
        is EditDialog.NewCategory -> NewCategoryDialog(d.name, actions)
        null -> Unit
    }
}

/** What the field is called (the label checks of §10.7) and, when adding, its type. */
@Composable
private fun FieldDialog(title: String, confirm: String, label: String, kind: String?, actions: ItemEditActions) {
    val trimmed = label.trim()
    val problem = ItemChecks.labelProblems(trimmed).firstOrNull()
    // An empty label only turns the button off; a label that is too long or has control characters says why.
    val shown = problem?.takeIf { label.isNotEmpty() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = actions.onDismissDialog,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { actions.onDialogText(it.replace("\n", "")) },
                    label = { Text(stringResource(R.string.items_field_name_prompt)) },
                    singleLine = true,
                    isError = shown != null,
                    supportingText = shown?.let { p -> { Text(ItemsText.problem(p)) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (problem == null) actions.onConfirmDialog() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("item_field_dialog_label"),
                )
                if (kind != null) KindPicker(kind, actions.onDialogKind)
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmDialog,
                enabled = problem == null,
                modifier = Modifier.testTag("item_field_dialog_confirm"),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = actions.onDismissDialog) { Text(stringResource(R.string.items_cancel)) } },
        modifier = Modifier.testTag("item_field_dialog"),
    )
}

/** The type of a new field, from the kinds the app offers (never `file`, §10.7). */
@Composable
private fun KindPicker(kind: String, onKind: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = stringResource(ItemsText.kind(kind)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.items_field_type)) },
            trailingIcon = {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag("item_field_dialog_kind")) {
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = stringResource(R.string.items_cd_choose_kind))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FieldKinds.CHOOSABLE.forEach { k ->
                DropdownMenuItem(
                    text = { Text(stringResource(ItemsText.kind(k))) },
                    onClick = {
                        open = false
                        onKind(k)
                    },
                    modifier = Modifier.testTag("item_field_kind_$k"),
                )
            }
        }
    }
}

/**
 * A category of the member's own (§10.7: `[a-z][a-z0-9_]{0,31}`): the typed name, the identifier it gives, or why it
 * gives none. A name that gives a recommended category selects that one.
 */
@Composable
private fun NewCategoryDialog(name: String, actions: ItemEditActions) {
    val derived = ItemCategories.derive(name)
    val id = derived.id
    val note = when {
        name.isBlank() -> stringResource(R.string.items_category_new_hint)
        id == null && derived.problem == ItemCategories.Problem.STARTS_WITH_DIGIT -> stringResource(R.string.items_category_problem_digit)
        id == null -> stringResource(R.string.items_category_problem_empty)
        derived.recommended -> stringResource(R.string.items_category_existing, ItemsText.category(id))
        else -> stringResource(R.string.items_category_saved_as, id)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = actions.onDismissDialog,
        title = { Text(stringResource(R.string.items_category_new_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { actions.onDialogText(it.replace("\n", "")) },
                label = { Text(stringResource(R.string.items_category_new_name)) },
                singleLine = true,
                isError = name.isNotBlank() && id == null,
                supportingText = { Text(note) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (id != null) actions.onConfirmDialog() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("item_category_dialog_name"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmDialog,
                enabled = id != null,
                modifier = Modifier.testTag("item_category_dialog_confirm"),
            ) { Text(stringResource(R.string.items_category_new_confirm)) }
        },
        dismissButton = { TextButton(onClick = actions.onDismissDialog) { Text(stringResource(R.string.items_cancel)) } },
        modifier = Modifier.testTag("item_category_dialog"),
    )
}

/**
 * The room left before the item's size limit (§10.7: 64 KiB, a critical item 12 KiB), once more than three quarters
 * are used. Kept values count from the vault's `size` (0.21.0); when some of them leave, the room is "at least" that.
 */
@Composable
private fun SizeNote(state: ItemEditUiState) {
    val c = state.check
    val left = c.roomLeft ?: return
    if (c.size * 4 < c.maxSize * 3) return
    val kb = { b: Int -> (b + KB - 1) / KB }
    val text = when {
        left < 0 -> stringResource(R.string.items_size_over, kb(-left), c.maxSize / KB)
        c.exact -> stringResource(R.string.items_size_left, left / KB, c.maxSize / KB)
        else -> stringResource(R.string.items_size_left_at_least, left / KB, c.maxSize / KB)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (left < 0 && c.exact) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("item_edit_size"),
    )
}

private const val KB = 1024
