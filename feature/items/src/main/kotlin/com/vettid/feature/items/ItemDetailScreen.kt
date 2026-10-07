package com.vettid.feature.items

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.ItemDetail
import com.vettid.core.data.items.ItemFieldView
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.items.Totp
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.FloatingPillActionBar
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.LocalUserPresence
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PillAction
import com.vettid.core.ui.components.TagLabel
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ItemDetailRouteContent(host: ItemsHost) {
    val vm: ItemDetailViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val presence = LocalUserPresence.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.items_reveal_prompt_title)
    val subtitle = state.item?.name
    // Revealed values do not outlive the screen being in front (§10.7: revealed on purpose).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.hide() }
    LaunchedEffect(state.deleted) { if (state.deleted) host.onBack() }
    ItemDetailScreen(
        state = state,
        actions = ItemDetailActions(
            onBack = host.onBack,
            onRetry = vm::load,
            onReveal = { scope.launch { if (presence.confirm(title, subtitle)) vm.reveal() } },
            onOpen = vm::open,
            onHide = vm::hide,
            onToggleShown = vm::toggleShown,
            onEdit = {
                val item = state.item
                if (item?.sensitivity == Sensitivity.SECRET && !item.revealed) {
                    scope.launch { if (presence.confirm(title, subtitle)) host.navigate(ItemEditRoute(itemId = item.itemId)) }
                } else if (item != null) {
                    vm.handOff()
                    host.navigate(ItemEditRoute(itemId = item.itemId))
                }
            },
            onDelete = vm::askDelete,
            onConfirmDelete = vm::confirmDelete,
            onProtection = vm::askProtection,
            onPickProtection = vm::pickProtection,
            onConfirmLeaveCritical = vm::confirmLeaveCritical,
            onDismissDialog = vm::dismissDialog,
            onDismissError = vm::dismissError,
            onPassword = vm::setPassword,
            onSubmitPassword = vm::submitPassword,
            onCancelPassword = vm::cancelPassword,
        ),
    )
}

/** What an item's detail can ask for. */
data class ItemDetailActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onReveal: () -> Unit = {},
    val onOpen: () -> Unit = {},
    val onHide: () -> Unit = {},
    val onToggleShown: (String) -> Unit = {},
    val onEdit: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onProtection: () -> Unit = {},
    val onPickProtection: (Sensitivity) -> Unit = {},
    val onConfirmLeaveCritical: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onSubmitPassword: () -> Unit = {},
    val onCancelPassword: () -> Unit = {},
)

/**
 * An item (ANDROID-PLAN §4, the "Vault" detail): its name, category, sensitivity and tags, then its fields in a card.
 * A `secret` item's values show after the phone's lock confirms the member ("Reveal"); a `critical` item's after the
 * credential password ("Open"). Passwords and one-time code seeds stay masked until shown on purpose; a one-time
 * code field shows the current code. The pill: edit, protection (sensitivity) and delete.
 */
@Composable
fun ItemDetailScreen(state: ItemDetailUiState, actions: ItemDetailActions, modifier: Modifier = Modifier) {
    val item = state.item
    val prompt = state.prompt
    if (prompt != null && item != null) {
        return CredentialPasswordContent(prompt, item.name, actions.onPassword, actions.onSubmitPassword, actions.onCancelPassword)
    }
    DetailScaffold(
        onBackClick = actions.onBack,
        modifier = modifier.testTag("item_detail"),
        overlay = {
            if (item != null && !state.missing) {
                FloatingPillActionBar(
                    listOf(
                        PillAction(Icons.Outlined.Edit, stringResource(R.string.items_edit), actions.onEdit),
                        PillAction(Icons.Outlined.Shield, stringResource(R.string.items_protection), actions.onProtection),
                        PillAction(
                            Icons.Outlined.DeleteOutline,
                            stringResource(R.string.items_delete),
                            actions.onDelete,
                            destructive = true,
                        ),
                    ),
                )
            }
        },
    ) {
        when {
            state.missing -> EmptyState(
                icon = Icons.Outlined.Delete,
                title = stringResource(R.string.items_missing_title),
                body = stringResource(R.string.items_missing_body),
                modifier = Modifier.testTag("item_missing"),
            )
            item == null && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            item == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.items_error_item),
                    ItemsText.failure(state.error ?: com.vettid.core.data.vault.FailureKind.OTHER),
                    modifier = Modifier.padding(Spacing.xl),
                    actions = { TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.items_retry)) } },
                )
            }
            else -> DetailBody(item, state, actions)
        }
    }
    Dialogs(state, actions)
}

@Composable
private fun DetailBody(item: ItemDetail, state: ItemDetailUiState, actions: ItemDetailActions) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xxl * 2),
    ) {
        LargeTitle(item.name, Modifier.testTag("item_name"))
        Row(
            Modifier.padding(horizontal = Spacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Icon(ItemsText.sensitivityIcon(item.sensitivity), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(
                    R.string.items_detail_kind,
                    ItemsText.category(item.category),
                    stringResource(ItemsText.sensitivity(item.sensitivity)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("item_kind"),
            )
        }
        if (item.tags.isNotEmpty()) {
            FlowRow(
                Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                item.tags.forEach { TagLabel(tagLabel(it)) }
            }
        }
        Spacer(Modifier.height(Spacing.m))
        state.error?.let { e ->
            NoticeCard(
                NoticeKind.WARNING,
                stringResource(R.string.items_error_item),
                ItemsText.failure(e),
                modifier = Modifier.padding(horizontal = Spacing.s).testTag("item_error"),
                actions = { TextButton(onClick = actions.onDismissError) { Text(stringResource(R.string.items_ok)) } },
            )
            Spacer(Modifier.height(Spacing.m))
        }
        Gate(item, state, actions)
        DetailCard(Modifier.testTag("item_fields")) {
            if (item.fields.isEmpty()) {
                Text(stringResource(R.string.items_no_fields), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.fields.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = Spacing.s), color = MaterialTheme.colorScheme.outlineVariant)
                FieldRow(f, item.revealed, f.fieldId != null && f.fieldId in state.shown, actions)
            }
        }
        if (item.hasNotes || !item.notes.isNullOrEmpty()) {
            Spacer(Modifier.height(Spacing.m))
            DetailCard(Modifier.testTag("item_notes")) {
                Text(stringResource(R.string.items_notes), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    item.notes ?: stringResource(R.string.items_hidden_value),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (item.notes == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(Spacing.m))
        val times = listOfNotNull(
            item.createdAt?.let { stringResource(R.string.items_created, Times.full(it)) },
            item.updatedAt?.takeIf { it != item.createdAt }?.let { stringResource(R.string.items_updated, Times.full(it)) },
        )
        times.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
        }
    }
}

/** The notice above a hidden item's fields: reveal (secret) or open with the password (critical); hide again. */
@Composable
private fun Gate(item: ItemDetail, state: ItemDetailUiState, actions: ItemDetailActions) {
    when {
        item.sensitivity == Sensitivity.DATA -> return
        !item.revealed && item.sensitivity == Sensitivity.SECRET -> NoticeCard(
            NoticeKind.INFO,
            stringResource(R.string.items_secret_hidden_title),
            stringResource(R.string.items_secret_hidden_body),
            modifier = Modifier.padding(horizontal = Spacing.s).testTag("item_gate"),
            actions = {
                TextButton(onClick = actions.onReveal, enabled = !state.busy, modifier = Modifier.testTag("item_reveal")) {
                    Text(stringResource(R.string.items_reveal))
                }
            },
        )
        !item.revealed -> NoticeCard(
            NoticeKind.INFO,
            stringResource(R.string.items_critical_hidden_title),
            stringResource(R.string.items_critical_hidden_body),
            modifier = Modifier.padding(horizontal = Spacing.s).testTag("item_gate"),
            actions = {
                TextButton(onClick = actions.onOpen, enabled = !state.busy, modifier = Modifier.testTag("item_open")) {
                    Text(stringResource(R.string.items_open))
                }
            },
        )
        else -> Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = actions.onHide, modifier = Modifier.testTag("item_hide")) { Text(stringResource(R.string.items_hide)) }
        }
    }
    Spacer(Modifier.height(Spacing.m))
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun FieldRow(f: ItemFieldView, revealed: Boolean, shown: Boolean, actions: ItemDetailActions) {
    val context = LocalContext.current
    val v = f.value
    val masked = FieldKinds.masked(f.kind)
    val text = when (v) {
        null -> null
        is FieldValue.Text -> v.text
        is FieldValue.Address -> v.address.lines().joinToString("\n")
    }
    Column(Modifier.fillMaxWidth().testTag("item_field_${f.fieldId}")) {
        Text(f.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val display = when {
                text == null || !revealed -> stringResource(R.string.items_hidden_value)
                text.isEmpty() -> stringResource(R.string.items_empty_value)
                masked && !shown -> stringResource(R.string.items_masked_value)
                else -> text
            }
            Text(
                display,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (masked && shown) FontFamily.Monospace else null,
                color = if (text.isNullOrEmpty() || (masked && !shown)) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f).testTag("item_value_${f.fieldId}"),
            )
            if (!text.isNullOrEmpty() && revealed) {
                val fid = f.fieldId
                if (masked && fid != null) {
                    IconButton(onClick = { actions.onToggleShown(fid) }) {
                        Icon(
                            if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (shown) R.string.items_cd_hide_value else R.string.items_cd_show_value,
                                f.label,
                            ),
                        )
                    }
                }
                IconButton(onClick = { copy(context, f.label, text, sensitive = masked) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.items_cd_copy, f.label))
                }
            }
        }
        if (f.kind == FieldKinds.OTP && revealed && !text.isNullOrEmpty()) OtpCode(text)
    }
}

/** The current one-time code of an `otp` seed (§10.7: apps show codes), with the seconds it has left. */
@Composable
private fun OtpCode(seed: String) {
    val totp = remember(seed) { Totp.parse(seed) }
    if (totp == null) {
        Text(
            stringResource(R.string.items_otp_unreadable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(totp) {
        while (true) {
            delay(OTP_TICK_MS)
            now = System.currentTimeMillis() / 1000
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("item_otp")) {
        Text(
            totp.code(now).chunked(3).joinToString(" "),
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(Spacing.m))
        Text(
            stringResource(R.string.items_otp_left, totp.secondsLeft(now)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Dialogs(state: ItemDetailUiState, actions: ItemDetailActions) {
    val item = state.item ?: return
    when (state.dialog) {
        DetailDialog.DELETE -> ConfirmDialog(
            title = stringResource(R.string.items_delete_title, item.name),
            text = stringResource(
                if (item.sensitivity == Sensitivity.CRITICAL) R.string.items_delete_body_critical else R.string.items_delete_body,
            ),
            confirmLabel = stringResource(R.string.items_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onDismissDialog,
            destructive = true,
        )
        DetailDialog.LEAVE_CRITICAL -> ConfirmDialog(
            title = stringResource(R.string.items_leave_critical_title),
            text = stringResource(R.string.items_leave_critical_body),
            confirmLabel = stringResource(R.string.items_leave_critical_confirm),
            onConfirm = actions.onConfirmLeaveCritical,
            onDismiss = actions.onDismissDialog,
            destructive = true,
        )
        DetailDialog.PROTECTION_PICK -> AlertDialog(
            onDismissRequest = actions.onDismissDialog,
            title = { Text(stringResource(R.string.items_protection_title)) },
            text = {
                Column {
                    Sensitivity.entries.forEach { s ->
                        SensitivityChoice(s, s == item.sensitivity) { actions.onPickProtection(s) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = actions.onDismissDialog) { Text(stringResource(R.string.items_cancel)) } },
            modifier = Modifier.testTag("item_protection_dialog"),
        )
        null -> Unit
    }
}

/** One sensitivity to choose, with what it means. */
@Composable
internal fun SensitivityChoice(s: Sensitivity, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = Spacing.xs)
            .testTag("sensitivity_${s.wire}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.size(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(stringResource(ItemsText.sensitivity(s)), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(ItemsText.sensitivityNote(s)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Copies a value; a masked one is flagged sensitive so that the system does not show it in the clipboard preview. */
private fun copy(context: Context, label: String, text: String, sensitive: Boolean) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
}

/** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33), by name so that API 31–32 phones get it too. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
private const val OTP_TICK_MS = 1_000L
