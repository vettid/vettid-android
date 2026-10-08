package com.vettid.feature.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.ExportAnswer
import com.vettid.core.data.vault.ExportFormat
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.HistoryExportRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.exportFilters
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

/** Why History cannot export at all just now. */
enum class ExportRefusal {
    /** A clone alarm is open (`credential_frozen` / `rotation_required`, §3.5.9). */
    ALARM,

    /** The vault release predates `audit.export` (VAULT-MESSAGING 0.22.0): `unsupported_type` or `bad_request`. */
    OLD_VAULT,
}

/** The steps of a History export (ANDROID-PLAN 0.1.17): preview → confirm → PIN → reading → "Save to…" → done. */
sealed interface ExportStep {
    /** No export under way. */
    data object Closed : ExportStep

    /** The preview (`audit.export{dry_run}`) is on its way. */
    data object Previewing : ExportStep

    /** The confirm sheet: [preview]'s count and range, the [format] chosen (none at first). */
    data class Confirm(val preview: ExportAnswer, val format: ExportFormat? = null) : ExportStep

    /** Nothing matches (a preview's `count` 0, or `not_found`: the entries are gone). */
    data object NothingToExport : ExportStep

    /** Export is refused for now; no Continue. */
    data class Refused(val reason: ExportRefusal) : ExportStep

    /**
     * The vault PIN, on its own step. [error] the last refusal (`bad_pin`, or another failure); [retryUntil] the end
     * of the PIN backoff (`retry_after`), during which Continue stays off. The PIN lives here only, and is cleared
     * after every answer.
     */
    data class Pin(
        val preview: ExportAnswer,
        val format: ExportFormat,
        val pin: String = "",
        val busy: Boolean = false,
        val error: FailureKind? = null,
        val retryUntil: Instant? = null,
    ) : ExportStep {
        fun canSend(now: Instant): Boolean =
            pin.length in PIN_MIN..PIN_MAX && !busy && (retryUntil == null || !now.isBefore(retryUntil))

        override fun toString(): String = "Pin($format, busy=$busy, error=$error, retryUntil=$retryUntil)"
    }

    /** The authorised entries are read (`audit.list` pages): [done] of [total]. */
    data class Reading(val done: Int, val total: Long) : ExportStep

    /** The system's "Save to…" dialog, with the suggested [fileName]; [requested] once the dialog was opened. */
    data class Save(val fileName: String, val format: ExportFormat, val requested: Boolean = false) : ExportStep

    /** The file is written to the chosen document. */
    data object Writing : ExportStep

    /** Saved: [written] entries of the [authorised] count (fewer when the retention dropped some meanwhile). */
    data class Done(val written: Int, val authorised: Long) : ExportStep

    /** A failure: [kind] of the vault's (an owner check due, the network…), or [saveFailed] writing the document. */
    data class Failed(val kind: FailureKind?, val saveFailed: Boolean = false) : ExportStep

    companion object {
        /** §11.3: the PIN is 6–32 ASCII digits. */
        const val PIN_MIN = 6
        const val PIN_MAX = 32
    }
}

/** What History knows when the member asks for an export: the list's filters and the names it shows. */
data class ExportContext(
    val filter: AuditFilter,
    val connectionNames: Map<String, String> = emptyMap(),
    val itemNames: Map<String, String> = emptyMap(),
)

/**
 * History export (VAULT-MESSAGING 0.22.0 §10.9, ANDROID-PLAN 0.1.17): exports what the list's filters show, never
 * more. The preview counts; the vault PIN, sealed to a UTK, authorises the export (`audit.export`); the entries are
 * then read with `audit.list` below the answer's `upto_seq` and held in memory only; the file is written only to the
 * document the member picks in "Save to…". Nothing is written without a successful `audit.export` answer; cancelling
 * or dismissing the dialog drops the entries (the vault's `audit.exported` entry stays).
 */
@HiltViewModel
class HistoryExportViewModel @Inject constructor(
    private val repo: HistoryExportRepository,
    private val documents: ExportDocuments,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val state = MutableStateFlow<ExportStep>(ExportStep.Closed)
    val step: StateFlow<ExportStep> = state.asStateFlow()

    /** The clock (tests set it). */
    internal var now: () -> Instant = Instant::now

    /** Where the file is written (tests run it on theirs). */
    internal var io: CoroutineDispatcher = Dispatchers.IO

    private var ctx: ExportContext? = null
    private var job: Job? = null

    /** The file's content, from a successful export until it is saved or dropped. */
    private var pending: Pending? = null

    private class Pending(val format: ExportFormat, val header: ExportHeader, val rows: List<ExportRow>)

    /** "Export…": the preview of what [context] shows. */
    fun start(context: ExportContext) {
        drop()
        ctx = context
        state.value = ExportStep.Previewing
        job = viewModelScope.launch {
            state.value = try {
                val p = repo.preview(context.filter)
                if (p.count <= 0) ExportStep.NothingToExport else ExportStep.Confirm(p)
            } catch (e: VaultFailure) {
                val refused = refusal(e)
                when {
                    refused != null -> ExportStep.Refused(refused)
                    e.kind == FailureKind.NOT_FOUND -> ExportStep.NothingToExport
                    else -> ExportStep.Failed(e.kind)
                }
            }
        }
    }

    fun chooseFormat(f: ExportFormat) = state.update { s -> if (s is ExportStep.Confirm) s.copy(format = f) else s }

    /** Continue on the confirm sheet: the PIN step. */
    fun confirm() = state.update { s ->
        val f = (s as? ExportStep.Confirm)?.format
        if (s is ExportStep.Confirm && f != null) ExportStep.Pin(s.preview, f) else s
    }

    fun setPin(pin: String) = state.update { s ->
        if (s is ExportStep.Pin && !s.busy) s.copy(pin = pin.filter { it in '0'..'9' }.take(ExportStep.PIN_MAX)) else s
    }

    /** Sends `audit.export` with the PIN, then reads the entries. */
    fun submitPin() {
        val s = state.value as? ExportStep.Pin
        val c = ctx
        if (s == null || c == null || !s.canSend(now())) return
        state.value = s.copy(busy = true, error = null)
        job = viewModelScope.launch {
            val answer = try {
                repo.export(c.filter, s.format, s.preview.uptoSeq, s.pin)
            } catch (e: VaultFailure) {
                state.value = pinRefused(s, e)
                return@launch
            }
            read(c, s.format, answer)
        }
    }

    private fun pinRefused(s: ExportStep.Pin, e: VaultFailure): ExportStep {
        refusal(e)?.let { if (it == ExportRefusal.ALARM) return ExportStep.Refused(it) }
        return when (e.kind) {
            FailureKind.NOT_FOUND -> ExportStep.NothingToExport
            FailureKind.OWNER_CHECK_REQUIRED -> ExportStep.Failed(e.kind)
            else -> s.copy(
                pin = "",
                busy = false,
                error = e.kind,
                retryUntil = if (e.kind == FailureKind.BACKOFF && e.retryAfterSeconds > 0) now().plusSeconds(e.retryAfterSeconds) else null,
            )
        }
    }

    private suspend fun read(c: ExportContext, format: ExportFormat, answer: ExportAnswer) {
        state.value = ExportStep.Reading(0, answer.count)
        try {
            // Device names are best effort: without them the file has the ids alone.
            val devices = try {
                repo.deviceNames()
            } catch (_: VaultFailure) {
                null
            }
            val records = repo.entries(c.filter, answer) { n -> state.value = ExportStep.Reading(n, answer.count) }
            val rows = records.map { row(it, c, devices) }
            val at = answer.answeredAt ?: now()
            pending = Pending(format, header(c, answer, at), rows)
            state.value = ExportStep.Save(ExportFiles.fileName(at, format), format)
        } catch (e: VaultFailure) {
            pending = null
            state.value = ExportStep.Failed(e.kind)
        }
    }

    private fun row(r: AuditRecord, c: ExportContext, devices: Map<String, String>?): ExportRow = ExportRow(
        record = r,
        label = HistoryText.title(context, r.kind),
        category = r.category.id,
        connectionName = r.connectionId?.let { c.connectionNames[it] ?: context.getString(R.string.history_connection_removed) },
        deviceName = r.deviceId?.let { id -> devices?.let { it[id] ?: context.getString(R.string.history_device_removed) } },
        itemName = AuditKinds.itemOf(r.kind, r.ref)?.let { c.itemNames[it] ?: context.getString(R.string.history_item_deleted) },
    )

    private fun header(c: ExportContext, a: ExportAnswer, at: Instant): ExportHeader {
        val f = c.filter.exportFilters()
        return ExportHeader(
            exportedAt = at,
            filters = ExportFilters(
                category = c.filter.category?.id,
                kinds = f.kinds,
                connectionId = f.connectionId,
                connectionName = f.connectionId?.let { c.connectionNames[it] ?: context.getString(R.string.history_connection_removed) },
                q = f.q,
                since = f.since,
                until = f.until,
            ),
            more = a.more,
            authorisedCount = a.count,
            oldestSeq = a.oldestSeq,
            newestSeq = a.newestSeq,
            logHeadSeq = a.uptoSeq,
            logHeadHash = a.uptoHash,
        )
    }

    /** The UI opened "Save to…" (so a recomposition does not open it again). */
    fun saveRequested() = state.update { s -> if (s is ExportStep.Save) s.copy(requested = true) else s }

    /** The dialog's answer: the chosen document, or null when it was dismissed (the entries are dropped). */
    fun saveTo(uri: String?) {
        val p = pending
        if (state.value !is ExportStep.Save || p == null) return
        if (uri == null) {
            close()
            return
        }
        state.value = ExportStep.Writing
        job = viewModelScope.launch {
            val ok = try {
                withContext(io) {
                    documents.write(uri) { out ->
                        when (p.format) {
                            ExportFormat.CSV -> HistoryCsv.write(p.rows, out)
                            ExportFormat.JSON -> HistoryJson.write(p.header, p.rows, out)
                        }
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                // An I/O failure or a provider's SecurityException: remove what was half written.
                withContext(io) { documents.discard(uri) }
                false
            }
            pending = null
            state.value = if (ok) ExportStep.Done(p.rows.size, p.header.authorisedCount) else ExportStep.Failed(null, saveFailed = true)
        }
    }

    /** Cancel, Back or Close at any step: the entries read so far are dropped. */
    fun close() {
        drop()
        state.value = ExportStep.Closed
    }

    private fun drop() {
        job?.cancel()
        job = null
        pending = null
    }

    override fun onCleared() {
        drop()
    }

    private companion object {
        const val CODE_BAD_REQUEST = "bad_request"

        fun refusal(e: VaultFailure): ExportRefusal? = when {
            e.kind == FailureKind.CREDENTIAL_FROZEN || e.kind == FailureKind.ROTATION_REQUIRED -> ExportRefusal.ALARM
            e.kind == FailureKind.NOT_SUPPORTED || e.code == "unsupported_type" -> ExportRefusal.OLD_VAULT
            e.code == CODE_BAD_REQUEST -> ExportRefusal.OLD_VAULT
            else -> null
        }
    }
}
