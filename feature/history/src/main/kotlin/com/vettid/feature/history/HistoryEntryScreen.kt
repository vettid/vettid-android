package com.vettid.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.CenteredTitle
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.InitialTile
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing

/** An audit entry (§10.9), read-only: what, when, the connection, device and references the vault recorded. */
@Composable
fun HistoryEntryScreen(
    state: HistoryEntryUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
    onOpenConnection: (String) -> Unit = {},
    onOpenItem: (String) -> Unit = {},
) {
    DetailScaffold(onBackClick = onBack, modifier = Modifier.testTag("history_entry")) {
        val e = state.entry
        val error = state.error
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null -> NoticeCard(
                NoticeKind.WARNING,
                stringResource(R.string.history_error_title),
                stringResource(error.messageRes()),
                modifier = Modifier.padding(Spacing.xl),
                actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.history_retry)) } },
            )
            e == null -> NoticeCard(
                NoticeKind.INFO,
                stringResource(R.string.history_title),
                stringResource(R.string.history_detail_missing),
                modifier = Modifier.padding(Spacing.xl),
            )
            else -> EntryDetail(e, state, onOpenConnection, onOpenItem)
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun EntryDetail(e: AuditRecord, state: HistoryEntryUiState, onOpenConnection: (String) -> Unit, onOpenItem: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.l))
        InitialTile(
            name = e.kind,
            size = 72,
            icon = categoryIcon(e.category),
            colors = com.vettid.core.ui.theme.categoryColor(categoryHue(e.category)),
        )
        CenteredTitle(HistoryText.title(e.kind), Modifier.testTag("history_entry_title"))
        DetailCard(Modifier.padding(horizontal = Spacing.s)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Field(R.string.history_detail_when, e.at?.let { Times.full(it) } ?: stringResource(R.string.history_detail_unknown_time))
                Field(R.string.history_detail_category, stringResource(AuditKinds.categoryLabel(e.category)))
                e.connectionId?.let { id ->
                    Field(R.string.history_detail_connection, state.connectionName ?: stringResource(R.string.history_connection_removed))
                    if (state.connectionExists) {
                        TextButton(
                            onClick = { onOpenConnection(id) },
                            modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("history_open_connection"),
                        ) { Text(stringResource(R.string.history_detail_open_connection)) }
                    }
                }
                AuditKinds.itemOf(e.kind, e.ref)?.let { id ->
                    Field(R.string.history_detail_item, state.itemName ?: stringResource(R.string.history_item_deleted))
                    // A critical item opens to its metadata; its values still need the credential password there.
                    if (state.itemExists) {
                        TextButton(
                            onClick = { onOpenItem(id) },
                            modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("history_open_item"),
                        ) { Text(stringResource(R.string.history_detail_open_item)) }
                    }
                }
                e.direction?.let {
                    Field(
                        R.string.history_detail_direction,
                        when (it) {
                            "in" -> stringResource(R.string.history_detail_direction_in)
                            "out" -> stringResource(R.string.history_detail_direction_out)
                            else -> it
                        },
                    )
                }
                Field(R.string.history_detail_kind, e.kind, mono = true)
                e.deviceId?.let { Field(R.string.history_detail_device, it, mono = true) }
                e.ref?.let { ref ->
                    Field(R.string.history_detail_ref, ref, mono = true)
                    val label = stringResource(R.string.history_detail_ref)
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(ref)) },
                        modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("history_copy_ref"),
                    ) { Text(stringResource(R.string.history_detail_copy, label.lowercase())) }
                }
                Field(R.string.history_detail_seq, e.seq.toString(), mono = true)
                Field(R.string.history_detail_id, e.entryId, mono = true)
                if (e.hash.isNotEmpty()) Field(R.string.history_detail_hash, e.hash, mono = true)
            }
        }
        Text(
            stringResource(R.string.history_detail_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.l),
        )
    }
}

@Composable
private fun Field(label: Int, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}
