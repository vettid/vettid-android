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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailCard
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
            onTagInput = vm::setTagInput,
            onAddTag = vm::addTag,
            onRemoveTag = vm::removeTag,
            onFieldLabel = vm::setFieldLabel,
            onFieldText = vm::setFieldText,
            onFieldAddress = vm::setFieldAddress,
            onFieldKind = vm::setFieldKind,
            onAddField = vm::addField,
            onRemoveField = vm::removeField,
            onMoveField = vm::moveField,
            onSave = vm::save,
            onDismissError = vm::dismissError,
            onPassword = vm::setPassword,
            onSubmitPassword = vm::submitPassword,
            onCancelPassword = { if (state.needsOpen) host.onBack() else vm.cancelPassword() },
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
    val onTagInput: (String) -> Unit = {},
    val onAddTag: () -> Unit = {},
    val onRemoveTag: (String) -> Unit = {},
    val onFieldLabel: (Int, String) -> Unit = { _, _ -> },
    val onFieldText: (Int, String) -> Unit = { _, _ -> },
    val onFieldAddress: (Int, AddressValue) -> Unit = { _, _ -> },
    val onFieldKind: (Int, String) -> Unit = { _, _ -> },
    val onAddField: (String, String) -> Unit = { _, _ -> },
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
        primaryEnabled = !state.needsOpen,
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
            CategoryPicker(d.category, actions.onCategory)
            SensitivitySection(state, actions)
            TagsSection(state, actions)
            Text(stringResource(R.string.items_fields), style = MaterialTheme.typography.titleSmall)
            d.fields.forEachIndexed { i, f ->
                FieldEditor(i, f, d.fields.size, if (show) state.check.fieldProblems[i].orEmpty() else emptySet(), actions)
            }
            AddFieldButton(enabled = d.fields.size < ItemChecks.MAX_FIELDS, onAdd = actions.onAddField)
            OutlinedTextField(
                value = d.notes,
                onValueChange = actions.onNotes,
                label = { Text(stringResource(R.string.items_notes)) },
                minLines = 3,
                isError = show && DraftProblem.NOTES_TOO_LONG in problems,
                supportingText = if (show && DraftProblem.NOTES_TOO_LONG in problems) {
                    { Text(ItemsText.problem(DraftProblem.NOTES_TOO_LONG)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().testTag("item_edit_notes"),
            )
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

@Composable
private fun CategoryPicker(category: String, onPick: (String) -> Unit) {
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
                        onPick(c.id)
                    },
                )
            }
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
    if (state.draft.tags.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("item_edit_tags")) {
            state.draft.tags.forEach { t ->
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
    if (state.inProfile) {
        NoticeCard(
            NoticeKind.INFO,
            stringResource(R.string.items_profile_title),
            stringResource(R.string.items_profile_body),
            modifier = Modifier.testTag("item_edit_profile_note"),
        )
    }
}

@Composable
private fun impactText(i: ShareImpact): String {
    val who = i.connectionName.ifBlank { stringResource(R.string.items_sharing_this_connection) }
        .let { com.vettid.core.data.account.AccountNames.isolate(it) }
    return when {
        i.usableOnly -> stringResource(R.string.items_impact_usable, who)
        i.mode == com.vettid.core.data.items.ShareMode.AUTO -> stringResource(R.string.items_impact_auto, who)
        else -> stringResource(R.string.items_impact_ask, who)
    }
}

@Composable
@Suppress("LongParameterList")
private fun FieldEditor(i: Int, f: DraftField, count: Int, problems: Set<DraftProblem>, actions: ItemEditActions) {
    DetailCard(Modifier.testTag("item_edit_field_$i")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindChoice(f, onKind = { actions.onFieldKind(i, it) }, modifier = Modifier.weight(1f))
            IconButton(onClick = { actions.onMoveField(i, -1) }, enabled = i > 0) {
                Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.items_cd_move_up, f.label))
            }
            IconButton(onClick = { actions.onMoveField(i, 1) }, enabled = i < count - 1) {
                Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.items_cd_move_down, f.label))
            }
            IconButton(onClick = { actions.onRemoveField(i) }, modifier = Modifier.testTag("item_edit_field_remove_$i")) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.items_cd_remove_field, f.label))
            }
        }
        val labelProblem = listOf(DraftProblem.LABEL_EMPTY, DraftProblem.LABEL_TOO_LONG).firstOrNull { it in problems }
        OutlinedTextField(
            value = f.label,
            onValueChange = { actions.onFieldLabel(i, it) },
            label = { Text(stringResource(R.string.items_field_label)) },
            singleLine = true,
            isError = labelProblem != null,
            supportingText = labelProblem?.let { p -> { Text(ItemsText.problem(p)) } },
            modifier = Modifier.fillMaxWidth().testTag("item_edit_field_label_$i"),
        )
        Spacer(Modifier.height(Spacing.s))
        val valueProblem = (problems - setOf(DraftProblem.LABEL_EMPTY, DraftProblem.LABEL_TOO_LONG)).firstOrNull()
        val error = valueProblem?.let { ItemsText.problem(it, f.kind) }
        if (f.kind == FieldKinds.ADDRESS) {
            AddressEditor(f.address, error) { actions.onFieldAddress(i, it) }
        } else {
            ValueEditor(i, f, error) { actions.onFieldText(i, it) }
        }
    }
}

@Composable
private fun KindChoice(f: DraftField, onKind: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(ItemsText.kind(f.kind))
    Box(modifier) {
        // A saved field keeps its kind (its id names the same field, §10.7).
        TextButton(onClick = { open = true }, enabled = f.fieldId == null, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (f.fieldId == null) Icon(Icons.Outlined.ArrowDropDown, contentDescription = stringResource(R.string.items_cd_choose_kind))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FieldKinds.CHOOSABLE.forEach { k ->
                DropdownMenuItem(text = { Text(stringResource(ItemsText.kind(k))) }, onClick = {
                    open = false
                    onKind(k)
                })
            }
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun ValueEditor(i: Int, f: DraftField, error: String?, onValue: (String) -> Unit) {
    val tag = Modifier.fillMaxWidth().testTag("item_edit_field_value_$i")
    if (f.kind == FieldKinds.PASSWORD) {
        return SecretField(
            value = f.text,
            onValueChange = onValue,
            label = stringResource(R.string.items_field_value),
            error = error,
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
        label = { Text(stringResource(R.string.items_field_value)) },
        placeholder = placeholder?.let { p -> { Text(p) } },
        singleLine = !multi,
        minLines = if (multi) 3 else 1,
        isError = error != null,
        supportingText = error?.let { e -> { Text(e) } },
        textStyle = MaterialTheme.typography.bodyLarge.let {
            if (f.kind == FieldKinds.OTP) it.copy(fontFamily = FontFamily.Monospace) else it
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            autoCorrectEnabled = f.kind == FieldKinds.TEXT || multi,
            capitalization = if (f.kind == FieldKinds.TEXT || multi) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        ),
        modifier = tag,
    )
}

@Composable
private fun AddressEditor(a: AddressValue, error: String?, onValue: (AddressValue) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AddressPart(a.street, R.string.items_address_street) { onValue(a.copy(street = it)) }
        AddressPart(a.street2, R.string.items_address_street2) { onValue(a.copy(street2 = it)) }
        AddressPart(a.postalCode, R.string.items_address_postal_code) { onValue(a.copy(postalCode = it)) }
        AddressPart(a.city, R.string.items_address_city) { onValue(a.copy(city = it)) }
        AddressPart(a.region, R.string.items_address_region) { onValue(a.copy(region = it)) }
        // ISO 3166-1 alpha-2, upper case (§10.7).
        AddressPart(a.country, R.string.items_address_country, caps = true) { onValue(a.copy(country = it.uppercase().take(2))) }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AddressPart(value: String, label: Int, caps: Boolean = false, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.replace("\n", "")) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AddFieldButton(enabled: Boolean, onAdd: (String, String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("item_edit_add_field"),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.height(Spacing.xs))
            Text(stringResource(if (enabled) R.string.items_add_field else R.string.items_problem_fields))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FieldKinds.CHOOSABLE.forEach { k ->
                val label = stringResource(ItemsText.kind(k))
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onAdd(label, k)
                })
            }
        }
    }
}

/** How much of the item's size limit is used, once it is more than three quarters (§10.7: 64 KiB, a critical item 12 KiB). */
@Composable
private fun SizeNote(state: ItemEditUiState) {
    val c = state.check
    if (c.size * 4 < c.maxSize * 3) return
    Text(
        stringResource(R.string.items_size, (c.size + 1023) / 1024, c.maxSize / 1024),
        style = MaterialTheme.typography.bodySmall,
        color = if (c.size > c.maxSize) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("item_edit_size"),
    )
}
