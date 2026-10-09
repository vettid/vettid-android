package com.vettid.core.data.vault

import com.vettid.core.vault.AuditExportAnswer
import com.vettid.core.vault.AuditExportFilters
import com.vettid.core.vault.VaultApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** A History export's file format (VAULT-MESSAGING 0.22.0 §10.9): the wire name, the MIME type and the extension. */
enum class ExportFormat(val wire: String, val mime: String, val extension: String) {
    CSV("csv", "text/csv", "csv"),
    JSON("json", "application/json", "json"),
}

/**
 * `audit.export`'s answer (§10.9), a preview's or an export's: [count] entries match (newest first, at most 10,000),
 * [more] beyond the cap; [uptoSeq] bounds the export, [uptoHash] (base64) is that entry's hash (the file's log head);
 * the range when [count] > 0; an export also has [entrySeq] (its `audit.exported` entry) and [answeredAt] (the
 * answer's `ts`, the file's `exported_at`).
 */
data class ExportAnswer(
    val count: Long,
    val more: Boolean,
    val uptoSeq: Long,
    val uptoHash: String,
    val oldestSeq: Long? = null,
    val newestSeq: Long? = null,
    val oldestAt: Instant? = null,
    val newestAt: Instant? = null,
    val entrySeq: Long? = null,
    val answeredAt: Instant? = null,
) {
    companion object {
        /** The export cap (§10.9). */
        const val MAX_ENTRIES = 10_000L

        fun of(a: AuditExportAnswer, at: Instant? = null) = ExportAnswer(
            count = a.count,
            more = a.more,
            uptoSeq = a.uptoSeq,
            uptoHash = a.uptoHash,
            oldestSeq = a.oldestSeq,
            newestSeq = a.newestSeq,
            oldestAt = a.oldestAt?.let { parseInstant(it) },
            newestAt = a.newestAt?.let { parseInstant(it) },
            entrySeq = a.entrySeq,
            answeredAt = at,
        )
    }
}

/** The filters of [AuditFilter] as `audit.export` and `audit.list` send them (§10.9); `since`/`until` as sent. */
fun AuditFilter.exportFilters(): AuditExportFilters = AuditExportFilters(
    connectionId = connectionId,
    kinds = kinds(),
    q = q,
    since = since?.toString(),
    until = until?.toString(),
)

/**
 * The member's History export (VAULT-MESSAGING 0.22.0 §10.9, ANDROID-PLAN 0.1.17). Failures are [VaultFailure]s:
 * `credential_frozen` / `rotation_required` while a clone alarm is open (the preview too), `unsupported_type` from a
 * vault before 0.22.0, `not_found` when nothing matches, `bad_pin`, `backoff` with its `retry_after`.
 */
interface HistoryExportRepository {
    /** The dry run: what [filter] matches, without the PIN. */
    suspend fun preview(filter: AuditFilter): ExportAnswer

    /** The export: the vault PIN sealed to a UTK authorises a file of [format] for the entries up to [uptoSeq]. */
    suspend fun export(filter: AuditFilter, format: ExportFormat, uptoSeq: Long, pin: String): ExportAnswer

    /**
     * The authorised entries, newest first: `audit.list` with [filter] from `before_seq` = `upto_seq` + 1, `limit`
     * 100, following `next_before_seq` across partial pages, the first [ExportAnswer.count] (fewer when the
     * retention dropped some meanwhile). [onProgress] gets the number read so far. Cancellable between pages.
     */
    suspend fun entries(filter: AuditFilter, answer: ExportAnswer, onProgress: (Int) -> Unit = {}): List<AuditRecord>

    /** id → name of the vault's devices (`device.list`, §10.3), for the file's device names. */
    suspend fun deviceNames(): Map<String, String>
}

/** The vault calls behind [HistoryExportManager]. */
interface AuditExportOps {
    suspend fun preview(filters: AuditExportFilters): AuditExportAnswer

    suspend fun export(filters: AuditExportFilters, format: String, uptoSeq: Long, pin: String): Pair<AuditExportAnswer, Instant>

    suspend fun deviceNames(): Map<String, String>
}

/** [AuditExportOps] over the vault client. */
internal class VaultAuditExportOps(private val api: VaultApi) : AuditExportOps {
    override suspend fun preview(filters: AuditExportFilters): AuditExportAnswer = api.auditExportPreview(filters)

    override suspend fun export(filters: AuditExportFilters, format: String, uptoSeq: Long, pin: String) =
        api.auditExport(filters, format, uptoSeq, pin)

    override suspend fun deviceNames(): Map<String, String> = devicesOf(api.deviceList())

    companion object {
        /** `device.list`'s `{devices: [{id, name, …}]}` as id → name (unnamed devices left out). */
        fun devicesOf(o: JsonObject): Map<String, String> = (o["devices"] as? JsonArray).orEmpty().mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val id = (d["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val name = (d["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to name
        }.toMap()
    }
}

/**
 * [HistoryExportRepository] over [AuditExportOps] and [AuditOps]. The paging reads `audit.list` directly, never
 * with [HistoryManager]'s fallback for an older vault's search: a vault that answered `audit.export` searches, and
 * an export must hold only what its filters match. Entries newer than `upto_seq`, repeated or out of order are
 * dropped: the file holds only what the member confirmed (§10.9, "What the PIN is for").
 */
class HistoryExportManager(
    private val ops: suspend () -> AuditExportOps,
    private val audit: suspend () -> AuditOps,
    private val pageSize: Int = PAGE_SIZE,
) : HistoryExportRepository {
    override suspend fun preview(filter: AuditFilter): ExportAnswer = vaultGuard { ExportAnswer.of(ops().preview(filter.exportFilters())) }

    override suspend fun export(filter: AuditFilter, format: ExportFormat, uptoSeq: Long, pin: String): ExportAnswer {
        val f = filter.exportFilters()
        val send: suspend () -> ExportAnswer = {
            vaultGuard { ops().export(f, format.wire, uptoSeq, pin).let { (a, at) -> ExportAnswer.of(a, at) } }
        }
        return try {
            send()
        } catch (e: VaultFailure) {
            // A UTK the vault no longer knows (it was spent): once more with the next one from the pool.
            if (e.code != CODE_UTK_INVALID) throw e
            send()
        }
    }

    override suspend fun entries(filter: AuditFilter, answer: ExportAnswer, onProgress: (Int) -> Unit): List<AuditRecord> {
        val want = answer.count.coerceIn(0, ExportAnswer.MAX_ENTRIES).toInt()
        val out = ArrayList<AuditRecord>(want)
        if (want == 0) return out
        val f = filter.exportFilters()
        var before: Long? = answer.uptoSeq + 1
        var last = answer.uptoSeq + 1
        var pages = 0
        while (before != null && out.size < want && pages < MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            val cursor: Long = before
            val page = vaultGuard { audit().auditList(f.connectionId, f.kinds, cursor, pageSize, f.q, f.since, f.until) }
            pages++
            for (e in page.entries) {
                if (out.size >= want) break
                if (e.seq < last && e.seq <= answer.uptoSeq) {
                    out += AuditRecord.of(e)
                    last = e.seq
                }
            }
            onProgress(out.size)
            // Only a cursor that moves down continues (a page without one is the end of the results).
            before = page.nextBeforeSeq?.takeIf { it < cursor }
        }
        return out
    }

    override suspend fun deviceNames(): Map<String, String> = vaultGuard { ops().deviceNames() }

    companion object {
        /** VAULT-MESSAGING 0.22.0 §10.9: `limit` 100 keeps each page well under the claim-check threshold. */
        const val PAGE_SIZE = 100

        /** 10,000 entries are 100 full pages; partial pages of a search (§10.9 scan budget) add a few. */
        const val MAX_PAGES = 1_000
        private const val CODE_UTK_INVALID = "utk_invalid"
    }
}
