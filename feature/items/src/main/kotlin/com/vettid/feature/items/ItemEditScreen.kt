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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.style.TextAlign
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
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
import com.vettid.core.ui.components.RemovableTagChip
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.TopBarTextAction
import com.vettid.core.ui.theme.Spacing

@Composable
internal fun ItemEditRouteContent(host: ItemsHost) {
    val vm: ItemEditViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedId) { state.savedId?.let { host.replace(ItemDetailRoute(it)) } }
    ItemEditScreen(
        state = state,
        actions = ItemEditActions(
            onBack = host.onBack,
            onAskDiscard = { vm.askDiscard(true) },
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
            onConfirmLeaveCritical = vm::confirmLeaveCritical,
            onDismissLeaveCritical = vm::dismissLeaveCritical,
        ),
    )
}

/** What the add/edit screen can ask for. */
data class ItemEditActions(
    /** Leaves the editor (nothing unsaved, or after "Discard"). */
    val onBack: () -> Unit = {},
    /** Back or up with unsaved changes: "Discard changes to this item?". */
    val onAskDiscard: () -> Unit = {},
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
    val onConfirmLeaveCritical: () -> Unit = {},
    val onDismissLeaveCritical: () -> Unit = {},
)

/**
 * Adding or editing an item (VAULT-ITEMS §9: name, category, sensitivity, tags, fields; §10.7's checks before
 * anything is sent). A new item picks its sensitivity here; a critical one is saved with the credential password.
 * A secret or critical item's stored values show as kept, never revealed (VAULT-MESSAGING 0.21.0 §10.7 Kept values):
 * typing into a field replaces its value. Fields are value-first (owner feedback 2026-10-08): one input captioned with
 * the field's label, its type as a hint, and a menu to rename, retype (unsaved fields), move or remove it.
 *
 * Saving is "Save item" in the top bar (owner request 2026-10-09): no Save bar above the keyboard, which read as
 * "save this field". The keyboard's Next moves to the next input and Done on the last one closes the keyboard; neither
 * saves. Back or up with unsaved changes asks "Discard changes to this item?".
 */
@Composable
@Suppress("CyclomaticComplexMethod")
fun ItemEditScreen(state: ItemEditUiState, actions: ItemEditActions, modifier: Modifier = Modifier) {
    val prompt = state.prompt
    if (prompt != null) {
        return CredentialPasswordContent(
            prompt,
            state.draft.name.ifBlank { state.nameHint ?: stringResource(R.string.items_unnamed) },
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
    val leave = { if (state.dirty) actions.onAskDiscard() else actions.onBack() }
    BackHandler(enabled = state.dirty && !state.confirmDiscard) { actions.onAskDiscard() }
    val ime = rememberImeChain(d.fields)
    FormScaffold(
        title = stringResource(if (state.isNew) R.string.items_new_title else R.string.items_edit_title),
        primaryLabel = null,
        onPrimary = {},
        onBack = leave,
        modifier = modifier.testTag("item_edit"),
        header = { HeaderGlyph(if (state.isNew) ItemTemplates.icon(d.category) else Icons.Outlined.Edit) },
        topActions = {
            TopBarTextAction(
                label = stringResource(R.string.items_save_item),
                onClick = actions.onSave,
                enabled = !state.loading,
                busy = state.busy,
                modifier = Modifier.testTag("item_edit_save"),
            )
        },
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
            if (state.savedButProtection) {
                NoticeCard(
                    NoticeKind.INFO,
                    stringResource(R.string.items_saved_protection_not_title),
                    stringResource(R.string.items_saved_protection_not_body),
                    modifier = Modifier.testTag("item_edit_saved_protection_not"),
                )
            }
            val nameProblem = listOf(DraftProblem.NAME_EMPTY, DraftProblem.NAME_TOO_LONG).firstOrNull { it in problems }
            // A template's name is the placeholder, never pre-typed (owner request 2026-10-08): left empty, it is used.
            val hint = state.nameHint
            OutlinedTextField(
                value = d.name,
                onValueChange = { actions.onName(it.replace("\n", "")) },
                label = { Text(stringResource(R.string.items_name)) },
                placeholder = hint?.let { h -> { Text(h) } },
                singleLine = true,
                isError = show && nameProblem != null,
                supportingText = when {
                    show && nameProblem != null -> {
                        { Text(ItemsText.problem(nameProblem)) }
                    }
                    hint != null && d.name.isBlank() -> {
                        { Text(stringResource(R.string.items_name_default, hint)) }
                    }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ime.name.action),
                keyboardActions = ime.name.keyboardActions,
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
                val slot = FieldSlot(i, d.fields.size, focus = state.focusField == i, ime = ime.fields[i], requester = ime.focus[i])
                FieldEditor(slot, f, problems, actions)
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
    if (state.confirmLeaveCritical) {
        ConfirmDialog(
            title = stringResource(R.string.items_leave_critical_title),
            text = stringResource(R.string.items_leave_critical_body),
            confirmLabel = stringResource(R.string.items_leave_critical_confirm),
            onConfirm = actions.onConfirmLeaveCritical,
            onDismiss = actions.onDismissLeaveCritical,
            destructive = true,
            modifier = Modifier.testTag("item_edit_leave_critical"),
        )
    }
    if (state.confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.items_discard_title),
            text = stringResource(R.string.items_discard_body),
            confirmLabel = stringResource(R.string.items_discard),
            onConfirm = actions.onDiscard,
            onDismiss = actions.onKeepEditing,
            destructive = true,
            dismissLabel = stringResource(R.string.items_keep_editing),
            modifier = Modifier.testTag("item_edit_discard"),
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

/**
 * The protection, for a new item and an existing one alike (owner request 2026-10-08). An existing item's change is
 * applied after its content when saved (`item.sensitivity`, §10.7): critical either way with the credential password,
 * leaving critical after the warning. `@profile` items stay standard (§10.8): the other choices are off, with the reason.
 */
@Composable
private fun SensitivitySection(state: ItemEditUiState, actions: ItemEditActions) {
    val s = state.protection
    Text(stringResource(R.string.items_protection), style = MaterialTheme.typography.titleSmall)
    Column(Modifier.testTag("item_edit_sensitivity")) {
        Sensitivity.entries.forEach { x ->
            SensitivityChoice(x, x == s, enabled = !state.inProfile || x == Sensitivity.DATA) { actions.onSensitivity(x) }
        }
    }
    val note = when {
        state.inProfile && s == Sensitivity.DATA -> stringResource(R.string.items_protection_profile)
        state.protectionTo != null -> stringResource(
            R.string.items_protection_change,
            stringResource(ItemsText.sensitivity(state.draft.sensitivity)),
            stringResource(ItemsText.sensitivity(s)),
        )
        else -> null
    }
    note?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("item_edit_protection_note"),
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
                RemovableTagChip(
                    label = tagLabel(t),
                    tag = t,
                    removeDescription = stringResource(R.string.items_cd_remove_tag, tagLabel(t)),
                    onRemove = { actions.onRemoveTag(t) },
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

/** Where a field is in the list, whether its value input takes the focus (it was just added), and its keyboard step. */
private data class FieldSlot(val index: Int, val count: Int, val focus: Boolean, val ime: ImeStep, val requester: FocusRequester)

/**
 * A single-line input's keyboard action: Next moves the focus to [next]'s input; Done (the last input) closes the
 * keyboard. Neither ever saves the item (owner request 2026-10-09).
 */
internal class ImeStep(val action: ImeAction, val run: () -> Unit) {
    val keyboardActions = KeyboardActions(onNext = { run() }, onDone = { run() })
}

/** The name's step and each field's: name → field 1 → … → the last field (Done). */
private class ImeChain(val name: ImeStep, val fields: List<ImeStep>, val focus: List<FocusRequester>)

@Composable
private fun rememberImeChain(fields: List<DraftField>): ImeChain {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember(fields.size) { List(fields.size) { FocusRequester() } }
    val close = {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    fun to(i: Int): ImeStep = if (i < focus.size) {
        ImeStep(ImeAction.Next) { runCatching { focus[i].requestFocus() } }
    } else {
        ImeStep(ImeAction.Done, close)
    }
    return ImeChain(name = to(0), fields = List(fields.size) { to(it + 1) }, focus = focus)
}

/**
 * One field, value-first: a single input captioned with the field's label, with the keyboard of its kind, and under
 * it the kind as a hint (a problem replaces it). The label and kind change from the field's menu, never inline.
 */
@Composable
@Suppress("CyclomaticComplexMethod")
private fun FieldEditor(slot: FieldSlot, f: DraftField, problems: Set<DraftProblem>, actions: ItemEditActions) {
    val i = slot.index
    val kindName = if (f.kind == FieldKinds.DATE && f.monthYear) {
        stringResource(R.string.items_kind_month_year)
    } else {
        stringResource(ItemsText.kind(f.kind))
    }
    val caption = f.label.trim().ifEmpty { kindName }
    val labelProblem = listOf(DraftProblem.LABEL_EMPTY, DraftProblem.LABEL_TOO_LONG).firstOrNull { it in problems }
        ?: DraftProblem.BAD_CHARACTER.takeIf { it in problems && ItemChecks.labelProblems(f.label.trim()).isNotEmpty() }
    val valueProblem = (problems - setOfNotNull(DraftProblem.LABEL_EMPTY, DraftProblem.LABEL_TOO_LONG, labelProblem)).firstOrNull()
    // A value that is not (yet) one of its kind says its rule while the member types (owner request 2026-10-08).
    val live = DraftProblem.VALUE_INVALID.takeIf { !f.kept && FieldInput.incomplete(f.kind, f.text) }
    val error = labelProblem?.let { ItemsText.problem(it) } ?: (valueProblem ?: live)?.let { ItemsText.problem(it, f.kind) }
    // A kept value is not at hand (§10.7 Kept values): the field stays empty until the member types a new one.
    val region = phoneRegion()
    val hint = when {
        f.kept -> stringResource(R.string.items_field_kind_kept, kindName)
        // Never a block: a number libphonenumber does not know is saved as typed (owner request 2026-10-09).
        f.kind == FieldKinds.PHONE && PhoneInput.doubtful(f.text, region) -> stringResource(R.string.items_phone_check)
        else -> kindName
    }
    val focus = slot.requester
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
                AddressEditor(f.address, input, slot.ime) { actions.onFieldAddress(i, it) }
                Text(
                    error ?: hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = Spacing.xs)
                        .testTag(if (f.kept) "item_edit_field_kept_$i" else "item_edit_field_hint_$i"),
                )
            } else {
                ValueEditor(i, f, caption, error ?: hint, error != null, input, slot.ime) { actions.onFieldText(i, it) }
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

/**
 * One value's input, with the keyboard of its kind and its format enforced while typing and on paste (§10.7,
 * [FieldInput]): characters the kind cannot hold never get in, and a `date` is typed as digits with the dashes drawn
 * in, or picked from a calendar (a month-and-year field from a month picker).
 */
@Composable
@Suppress("CyclomaticComplexMethod", "LongParameterList")
private fun ValueEditor(
    i: Int,
    f: DraftField,
    caption: String,
    supporting: String,
    isError: Boolean,
    input: Modifier,
    ime: ImeStep,
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
            imeAction = ime.action,
            onImeAction = ime.run,
            modifier = tag,
        )
    }
    if (f.kind == FieldKinds.DATE) return DateEditor(i, f, caption, supporting, isError, tag, ime, onValue)
    val keyboard = when (f.kind) {
        FieldKinds.NUMBER -> KeyboardType.Decimal
        FieldKinds.EMAIL -> KeyboardType.Email
        FieldKinds.PHONE -> KeyboardType.Phone
        FieldKinds.URL -> KeyboardType.Uri
        FieldKinds.OTP -> KeyboardType.Ascii
        else -> KeyboardType.Text
    }
    val placeholder = when (f.kind) {
        FieldKinds.URL -> stringResource(R.string.items_placeholder_url)
        FieldKinds.OTP -> stringResource(R.string.items_placeholder_otp)
        FieldKinds.EMAIL -> stringResource(R.string.items_placeholder_email)
        FieldKinds.PHONE -> stringResource(R.string.items_placeholder_phone)
        FieldKinds.NUMBER -> stringResource(R.string.items_placeholder_number)
        else -> null
    }
    val multi = f.kind == FieldKinds.MULTILINE
    val free = f.kind == FieldKinds.TEXT || multi
    // A phone number holds its dialable characters and is drawn as typed for the region (owner request 2026-10-09).
    val phone = f.kind == FieldKinds.PHONE
    val region = phoneRegion()
    OutlinedTextField(
        value = if (phone) PhoneInput.raw(f.text) else f.text,
        onValueChange = { v -> onValue(if (phone) PhoneInput.raw(v) else FieldInput.accept(f.kind, v)) },
        visualTransformation = if (phone) PhoneInput.Mask(region) else VisualTransformation.None,
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
            autoCorrectEnabled = free,
            capitalization = when {
                free -> KeyboardCapitalization.Sentences
                f.kind == FieldKinds.OTP -> KeyboardCapitalization.Characters
                else -> KeyboardCapitalization.None
            },
            // A multi-line value's Enter is a new line; every other input moves on or closes the keyboard.
            imeAction = if (multi) ImeAction.Default else ime.action,
        ),
        keyboardActions = if (multi) KeyboardActions.Default else ime.keyboardActions,
        modifier = tag,
    )
}

/**
 * A `date` (§10.7): digits on a number keyboard with the dashes drawn in (`YYYY-MM-DD`, or `YYYY-MM` for a
 * month-and-year field), a digit no date can continue with refused, and a calendar button (Material3 date picker; a
 * month picker for month and year). What is stored is always the spec's format.
 */
@Composable
@Suppress("LongParameterList")
private fun DateEditor(
    i: Int,
    f: DraftField,
    caption: String,
    supporting: String,
    isError: Boolean,
    modifier: Modifier,
    ime: ImeStep,
    onValue: (String) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val month = f.monthYear
    OutlinedTextField(
        value = DateInput.digits(f.text),
        onValueChange = { v -> DateInput.accept(f.text, v, month)?.let(onValue) },
        label = { Text(caption) },
        placeholder = {
            Text(
                if (f.kept) {
                    stringResource(R.string.items_field_kept_placeholder)
                } else {
                    stringResource(if (month) R.string.items_placeholder_month else R.string.items_placeholder_date)
                },
            )
        },
        singleLine = true,
        isError = isError,
        supportingText = { Text(supporting) },
        visualTransformation = DateInput.Mask,
        trailingIcon = {
            IconButton(onClick = { picking = true }, modifier = Modifier.testTag("item_edit_field_pick_$i")) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = stringResource(
                        if (month) R.string.items_cd_pick_month else R.string.items_cd_pick_date,
                        caption,
                    ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ime.action),
        keyboardActions = ime.keyboardActions,
        modifier = modifier,
    )
    if (picking) {
        val done: (String?) -> Unit = { v ->
            picking = false
            if (v != null) onValue(v)
        }
        if (month) MonthPickerDialog(f.text, done) else DatePickerDialogFor(f.text, done)
    }
}

/** The Material3 calendar; the day picked is stored as `YYYY-MM-DD` (§10.7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogFor(stored: String, onDone: (String?) -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = DateInput.toPicker(stored))
    DatePickerDialog(
        onDismissRequest = { onDone(null) },
        confirmButton = {
            TextButton(
                onClick = { onDone(picker.selectedDateMillis?.let { DateInput.fromPicker(it) }) },
                enabled = picker.selectedDateMillis != null,
                modifier = Modifier.testTag("item_date_pick_ok"),
            ) { Text(stringResource(R.string.items_ok)) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.items_cancel)) } },
    ) {
        DatePicker(state = picker, modifier = Modifier.testTag("item_date_picker"))
    }
}

/** A month and year (a card's expiry): the year with arrows, the twelve months; stored as `YYYY-MM` (§10.7). */
@Composable
private fun MonthPickerDialog(stored: String, onDone: (String?) -> Unit) {
    val initial = DateInput.yearMonth(stored) ?: DateInput.day(stored)?.let { YearMonth.from(it) } ?: YearMonth.now()
    var year by rememberSaveable { mutableIntStateOf(initial.year) }
    var month by rememberSaveable { mutableIntStateOf(initial.monthValue) }
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.items_month_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year = (year - 1).coerceAtLeast(1) }, modifier = Modifier.testTag("item_month_prev_year")) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.items_cd_previous_year),
                        )
                    }
                    Text(
                        year.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).testTag("item_month_year"),
                    )
                    IconButton(
                        onClick = { year = (year + 1).coerceAtMost(MAX_YEAR) },
                        modifier = Modifier.testTag("item_month_next_year"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.items_cd_next_year),
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    for (m in 1..MONTHS) {
                        FilterChip(
                            selected = m == month,
                            onClick = { month = m },
                            label = { Text(Month.of(m).getDisplayName(TextStyle.SHORT, locale)) },
                            modifier = Modifier.testTag("item_month_$m"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(DateInput.month(YearMonth.of(year, month))) }, modifier = Modifier.testTag("item_month_ok")) {
                Text(stringResource(R.string.items_ok))
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.items_cancel)) } },
        modifier = Modifier.testTag("item_month_picker"),
    )
}

private const val MONTHS = 12
private const val MAX_YEAR = 9999

@Composable
private fun AddressEditor(a: AddressValue, first: Modifier, ime: ImeStep, onValue: (AddressValue) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AddressPart(a.street, R.string.items_address_street, modifier = first) { onValue(a.copy(street = it)) }
        AddressPart(a.street2, R.string.items_address_street2) { onValue(a.copy(street2 = it)) }
        AddressPart(a.postalCode, R.string.items_address_postal_code) { onValue(a.copy(postalCode = it)) }
        AddressPart(a.city, R.string.items_address_city) { onValue(a.copy(city = it)) }
        AddressPart(a.region, R.string.items_address_region) { onValue(a.copy(region = it)) }
        // ISO 3166-1 alpha-2, upper case (§10.7).
        AddressPart(a.country, R.string.items_address_country, caps = true, ime = ime) { onValue(a.copy(country = FieldInput.country(it))) }
    }
}

@Composable
@Suppress("LongParameterList")
private fun AddressPart(
    value: String,
    label: Int,
    caps: Boolean = false,
    modifier: Modifier = Modifier,
    ime: ImeStep? = null,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.replace("\n", "")) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        // Next moves to the address's next part (the default action); the country takes the field's step.
        keyboardOptions = KeyboardOptions(
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Words,
            imeAction = ime?.action ?: ImeAction.Next,
        ),
        keyboardActions = ime?.keyboardActions ?: KeyboardActions.Default,
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
