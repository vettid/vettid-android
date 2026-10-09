package com.vettid.feature.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.ExportAnswer
import com.vettid.core.data.vault.ExportFormat
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.time.Instant

/** What the History export steps can ask for. */
data class HistoryExportActions(
    val onFormat: (ExportFormat) -> Unit = {},
    val onContinue: () -> Unit = {},
    val onPin: (String) -> Unit = {},
    val onSubmitPin: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** The credential alarm's screen (an open alarm refuses the export). */
    val onOpenAlarm: () -> Unit = {},
    /** The owner-check screen (`owner_check_required`). */
    val onOwnerCheck: () -> Unit = {},
)

/** The filters of the list in words, for the confirm sheet ("Messages and calls · Alice Moreau · Last 7 days"). */
data class ExportFilterWords(val filter: AuditFilter = AuditFilter(), val connectionName: String? = null, val dates: String? = null)

/** `ACTION_CREATE_DOCUMENT` with the MIME type and the suggested name (the Storage Access Framework's "Save to…"). */
internal object CreateExportDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.first)
            .putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
}

/**
 * The History export over the History screen (ANDROID-PLAN 0.1.17), from its ⋯ menu: the steps of [vm], the "Save
 * to…" dialog launched once the entries are read, and Back closing (and dropping) the export.
 */
@Composable
internal fun HistoryExportHost(vm: HistoryExportViewModel, words: ExportFilterWords, onOpenAlarm: () -> Unit, onOwnerCheck: () -> Unit) {
    val step by vm.step.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(CreateExportDocument) { uri -> vm.saveTo(uri?.toString()) }
    val s = step
    LaunchedEffect(s) {
        if (s is ExportStep.Save && !s.requested) {
            vm.saveRequested()
            launcher.launch(s.format.mime to s.fileName)
        }
    }
    if (s == ExportStep.Closed) return
    BackHandler { vm.close() }
    HistoryExportContent(
        s,
        words,
        HistoryExportActions(
            onFormat = vm::chooseFormat,
            onContinue = vm::confirm,
            onPin = vm::setPin,
            onSubmitPin = vm::submitPin,
            onSave = { if (s is ExportStep.Save) launcher.launch(s.format.mime to s.fileName) },
            onClose = vm::close,
            onOpenAlarm = {
                vm.close()
                onOpenAlarm()
            },
            onOwnerCheck = {
                vm.close()
                onOwnerCheck()
            },
        ),
    )
}

/** One step of the export, full screen. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun HistoryExportContent(step: ExportStep, words: ExportFilterWords, actions: HistoryExportActions, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.history_export_title)
    val m = modifier.testTag("history_export")
    when (step) {
        ExportStep.Closed -> Unit
        ExportStep.Previewing -> Progress(title, stringResource(R.string.history_export_preparing), null, actions.onClose, m)
        is ExportStep.Confirm -> ConfirmStep(step, words, actions, m)
        ExportStep.NothingToExport -> Message(
            stringResource(R.string.history_export_nothing_title), stringResource(R.string.history_export_nothing),
            NoticeKind.INFO, actions.onClose, m,
        )
        is ExportStep.Refused -> when (step.reason) {
            ExportRefusal.ALARM -> Message(
                stringResource(R.string.history_export_alarm_title), stringResource(R.string.history_export_alarm),
                NoticeKind.URGENT, actions.onClose, m, stringResource(R.string.history_export_alarm_open) to actions.onOpenAlarm,
            )
            ExportRefusal.OLD_VAULT -> Message(
                stringResource(R.string.history_export_old_vault_title), stringResource(R.string.history_export_old_vault),
                NoticeKind.INFO, actions.onClose, m,
            )
        }
        is ExportStep.Pin -> PinStep(step, actions, m)
        is ExportStep.Reading -> Progress(
            title,
            pluralStringResource(R.plurals.history_export_reading, step.total.toInt(), step.total.toInt()),
            step.done.toFloat() / step.total.coerceAtLeast(1),
            actions.onClose,
            m,
            stringResource(R.string.history_export_reading_progress, step.done, step.total.toInt()),
        )
        is ExportStep.Save -> FormScaffold(
            title = stringResource(R.string.history_export_save_title),
            body = stringResource(R.string.history_export_save_body),
            primaryLabel = stringResource(R.string.history_export_save_again),
            onPrimary = actions.onSave,
            onBack = actions.onClose,
            secondaryLabel = stringResource(R.string.history_cancel),
            onSecondary = actions.onClose,
            modifier = m,
            header = { Glyph(Icons.Outlined.FileDownload) },
        ) {
            Text(step.fileName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("history_export_file_name"))
        }
        ExportStep.Writing -> Progress(title, stringResource(R.string.history_export_writing), null, null, m)
        is ExportStep.Done -> DoneStep(step, actions, m)
        is ExportStep.Failed -> {
            val body = if (step.saveFailed) {
                stringResource(R.string.history_export_save_failed)
            } else {
                stringResource((step.kind ?: FailureKind.OTHER).messageRes())
            }
            val extra = if (step.kind == FailureKind.OWNER_CHECK_REQUIRED) {
                stringResource(R.string.history_export_owner_check) to actions.onOwnerCheck
            } else {
                null
            }
            Message(stringResource(R.string.history_export_failed_title), body, NoticeKind.WARNING, actions.onClose, m, extra)
        }
    }
}

@Composable
private fun Glyph(icon: ImageVector) {
    Spacer(Modifier.height(Spacing.l))
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(40.dp))
}

@Composable
private fun countLabel(n: Long): String = pluralStringResource(R.plurals.history_export_count, n.toInt(), n.toInt())

/** "1 Oct 2026 – 8 Oct 2026" from the preview's `oldest_at` and `newest_at` in local time (`at` need not grow with `seq`). */
@Composable
private fun rangeLabel(p: ExportAnswer): String? {
    val a = p.oldestAt
    val b = p.newestAt
    if (a == null || b == null) return null
    val (first, last) = if (b.isBefore(a)) b to a else a to b
    return stringResource(R.string.history_date_range, Times.dayLabel(Times.day(first)), Times.dayLabel(Times.day(last)))
}

@Composable
private fun filterWords(w: ExportFilterWords): String {
    val f = w.filter
    val parts = listOfNotNull(
        f.category?.let { stringResource(AuditKinds.categoryLabel(it)) },
        f.connectionId?.let { w.connectionName ?: stringResource(R.string.history_connection_removed) },
        w.dates.takeIf { f.since != null || f.until != null },
        f.q?.let { stringResource(R.string.history_export_search, it) },
    )
    return if (parts.isEmpty()) stringResource(R.string.history_export_filters_none) else parts.joinToString(" · ")
}

@Composable
private fun ConfirmStep(step: ExportStep.Confirm, words: ExportFilterWords, actions: HistoryExportActions, modifier: Modifier) {
    val p = step.preview
    val count = countLabel(p.count)
    val range = rangeLabel(p)
    FormScaffold(
        title = stringResource(R.string.history_export_title),
        body = if (range != null) stringResource(R.string.history_export_count_range, count, range) else count,
        primaryLabel = stringResource(R.string.history_export_continue),
        onPrimary = actions.onContinue,
        primaryEnabled = step.format != null,
        onBack = actions.onClose,
        secondaryLabel = stringResource(R.string.history_cancel),
        onSecondary = actions.onClose,
        modifier = modifier,
        header = { Glyph(Icons.Outlined.FileDownload) },
    ) {
        Text(
            stringResource(R.string.history_export_filters, filterWords(words)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("history_export_filters"),
        )
        if (p.more) {
            Spacer(Modifier.height(Spacing.m))
            NoticeCard(
                kind = NoticeKind.INFO,
                title = countLabel(ExportAnswer.MAX_ENTRIES),
                body = stringResource(R.string.history_export_more),
                modifier = Modifier.testTag("history_export_more"),
            )
        }
        Spacer(Modifier.height(Spacing.l))
        Text(stringResource(R.string.history_export_format), style = MaterialTheme.typography.titleSmall)
        FormatRow(ExportFormat.CSV, R.string.history_export_csv, R.string.history_export_csv_note, step.format, actions.onFormat)
        FormatRow(ExportFormat.JSON, R.string.history_export_json, R.string.history_export_json_note, step.format, actions.onFormat)
        Spacer(Modifier.height(Spacing.l))
        NoticeCard(
            kind = NoticeKind.WARNING,
            title = stringResource(R.string.history_export_unencrypted_title),
            body = stringResource(R.string.history_export_unencrypted),
            modifier = Modifier.testTag("history_export_unencrypted"),
        )
    }
}

@Composable
private fun FormatRow(f: ExportFormat, label: Int, note: Int, selected: ExportFormat?, onPick: (ExportFormat) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .selectable(selected = selected == f, role = Role.RadioButton, onClick = { onPick(f) })
            .padding(vertical = Spacing.xs)
            .testTag("history_export_format_${f.wire}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == f, onClick = null)
        Spacer(Modifier.size(Spacing.m))
        Column {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PinStep(step: ExportStep.Pin, actions: HistoryExportActions, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val until = step.retryUntil
    LaunchedEffect(until) {
        while (until != null && now < until.toEpochMilli()) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val waiting = until?.let { maxOf(0L, (it.toEpochMilli() - now + MS_ROUND) / MS_PER_S) } ?: 0L
    FormScaffold(
        title = stringResource(R.string.history_export_pin_title),
        body = stringResource(R.string.history_export_pin_body),
        primaryLabel = stringResource(R.string.history_export_export),
        onPrimary = actions.onSubmitPin,
        primaryEnabled = step.canSend(Instant.ofEpochMilli(now)),
        busy = step.busy,
        onBack = actions.onClose,
        secondaryLabel = stringResource(R.string.history_cancel),
        onSecondary = actions.onClose,
        modifier = modifier,
        header = { Glyph(Icons.Outlined.Lock) },
    ) {
        val error = when {
            waiting > 0 -> stringResource(R.string.history_export_pin_backoff, waiting)
            step.error == FailureKind.BAD_PIN -> stringResource(R.string.history_export_pin_wrong)
            step.error == FailureKind.BACKOFF -> stringResource(R.string.history_export_pin_backoff_undated)
            step.error != null -> stringResource(step.error.messageRes())
            else -> null
        }
        SecretField(
            value = step.pin,
            onValueChange = actions.onPin,
            label = stringResource(R.string.history_export_pin_label),
            isPin = true,
            error = error,
            enabled = !step.busy,
            imeAction = ImeAction.Done,
            onImeAction = { if (step.canSend(Instant.now())) actions.onSubmitPin() },
            modifier = Modifier.testTag("history_export_pin"),
        )
    }
}

@Composable
private fun DoneStep(step: ExportStep.Done, actions: HistoryExportActions, modifier: Modifier) {
    val body = if (step.written.toLong() < step.authorised) {
        stringResource(
            R.string.history_export_done_partial,
            step.written,
            step.authorised.toInt(),
            (step.authorised - step.written).toInt(),
        )
    } else {
        pluralStringResource(R.plurals.history_export_done, step.written, step.written)
    }
    FormScaffold(
        title = stringResource(R.string.history_export_done_title),
        body = body,
        primaryLabel = stringResource(R.string.history_export_close),
        onPrimary = actions.onClose,
        modifier = modifier.testTag("history_export_done"),
        header = { Glyph(Icons.Outlined.CheckCircle) },
    ) {
        Text(
            stringResource(R.string.history_export_done_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Progress(title: String, label: String, fraction: Float?, onCancel: (() -> Unit)?, modifier: Modifier, detail: String? = null) {
    FormScaffold(
        title = title,
        body = label,
        primaryLabel = null,
        onPrimary = {},
        onBack = onCancel,
        secondaryLabel = onCancel?.let { stringResource(R.string.history_cancel) },
        onSecondary = onCancel ?: {},
        modifier = modifier,
        header = { Glyph(Icons.Outlined.FileDownload) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().testTag("history_export_progress"),
                )
            } else {
                LinearProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
            }
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Message(
    title: String,
    body: String,
    kind: NoticeKind,
    onClose: () -> Unit,
    modifier: Modifier,
    extra: Pair<String, () -> Unit>? = null,
) {
    FormScaffold(
        title = stringResource(R.string.history_export_title),
        primaryLabel = stringResource(R.string.history_export_close),
        onPrimary = onClose,
        onBack = onClose,
        modifier = modifier,
    ) {
        NoticeCard(
            kind = kind,
            title = title,
            body = body,
            modifier = Modifier.testTag("history_export_message"),
            actions = extra?.let { (label, action) -> { TextButton(onClick = action) { Text(label) } } },
        )
    }
}

private const val TICK_MS = 1_000L
private const val MS_PER_S = 1_000L
private const val MS_ROUND = 999L
