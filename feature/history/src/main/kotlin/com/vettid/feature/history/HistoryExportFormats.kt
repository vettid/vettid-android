package com.vettid.feature.history

import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.ExportFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * One entry of a History export as History shows it (VAULT-MESSAGING 0.22.0 §10.9): the vault's [record], the app's
 * [label] for its kind, its [category] identifier (ANDROID-PLAN 0.1.17), and the names of its connection, device and
 * item (null where the entry has none).
 */
data class ExportRow(
    val record: AuditRecord,
    val label: String,
    val category: String,
    val connectionName: String? = null,
    val deviceName: String? = null,
    val itemName: String? = null,
)

/** The filters an export used, as sent (§10.9 JSON `filters`: only those used). */
data class ExportFilters(
    val category: String? = null,
    val kinds: List<String>? = null,
    val connectionId: String? = null,
    val connectionName: String? = null,
    val q: String? = null,
    val since: String? = null,
    val until: String? = null,
)

/**
 * The JSON header (§10.9): [exportedAt] the `ts` of the vault's answer, [authorisedCount] the vault's `count`,
 * [oldestSeq] / [newestSeq] its range, the log head [logHeadSeq] / [logHeadHash] its `upto_seq` / `upto_hash`.
 * Nothing that identifies the vault, the account or the device.
 */
data class ExportHeader(
    val exportedAt: Instant,
    val filters: ExportFilters,
    val more: Boolean,
    val authorisedCount: Long,
    val oldestSeq: Long?,
    val newestSeq: Long?,
    val logHeadSeq: Long,
    val logHeadHash: String,
)

/** The History export's file name and `ts` text (§10.9: `vettid-history-<YYYYMMDD>-<HHMMSS>.<csv|json>`, UTC). */
object ExportFiles {
    private val nameTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    private val ts: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun fileName(at: Instant, format: ExportFormat): String = "vettid-history-${nameTime.format(at)}.${format.extension}"

    /** §5.3's `ts`: RFC 3339 UTC with milliseconds. */
    fun ts(at: Instant): String = ts.format(at)
}

/**
 * The CSV of §10.9: RFC 4180 (CRLF line ends; a field with a comma, a double quote, CR or LF in double quotes, inner
 * quotes doubled), UTF-8 with a byte order mark, a header row, `hash` in lowercase hex, absent values empty. A field
 * starting with `=`, `+`, `-`, `@`, a tab or CR gets an apostrophe before it, inside the quoting (formula injection).
 */
object HistoryCsv {
    val COLUMNS = listOf("seq", "time", "category", "event", "kind", "direction", "connection", "device", "item", "ref", "hash")
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val CRLF = "\r\n"
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

    fun write(rows: List<ExportRow>, out: OutputStream) {
        out.write(BOM)
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write(line(COLUMNS))
        for (r in rows) {
            val e = r.record
            w.write(
                line(
                    listOf(
                        e.seq.toString(), e.atText, r.category, r.label, e.kind, e.direction.orEmpty(), r.connectionName.orEmpty(),
                        r.deviceName.orEmpty(), r.itemName.orEmpty(), e.ref.orEmpty(), hex(e.hash),
                    ),
                ),
            )
        }
        w.flush()
    }

    fun bytes(rows: List<ExportRow>): ByteArray = java.io.ByteArrayOutputStream().also { write(rows, it) }.toByteArray()

    private fun line(fields: List<String>): String = fields.joinToString(",", postfix = CRLF) { field(it) }

    /** One field: the apostrophe against formula injection, then RFC 4180 quoting. */
    fun field(raw: String): String {
        val v = if (raw.isNotEmpty() && raw[0] in FORMULA_START) "'$raw" else raw
        val quote = v.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        return if (quote) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    /** A base64 hash as 64 lowercase hex digits; empty when absent or unreadable. */
    fun hex(b64: String): String {
        val b = b64.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        return b?.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }.orEmpty()
    }

    private const val BYTE_MASK = 0xFF
}

/**
 * The JSON of §10.9 (`application/json`, UTF-8 without a byte order mark, one object): the header, then the entries
 * with the chain fields (`prev`, `hash`, base64) so that the export can be checked against the log. Optional members
 * are absent, never `null`.
 */
object HistoryJson {
    const val FORMAT = "vettid-history"
    const val FORMAT_VERSION = 1
    private val json = Json

    fun write(header: ExportHeader, rows: List<ExportRow>, out: OutputStream) {
        val o = buildJsonObject {
            put("format", FORMAT)
            put("format_version", FORMAT_VERSION)
            put("exported_at", ExportFiles.ts(header.exportedAt))
            putJsonObject("filters") { filters(header.filters) }
            put("count", rows.size)
            put("more", header.more)
            put("authorised_count", header.authorisedCount)
            header.oldestSeq?.let { put("oldest_seq", it) }
            header.newestSeq?.let { put("newest_seq", it) }
            putJsonObject("log_head") {
                put("seq", header.logHeadSeq)
                put("hash", header.logHeadHash)
            }
            putJsonArray("entries") { rows.forEach { r -> addJsonObject { entry(r) } } }
        }
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), o))
        w.flush()
    }

    fun bytes(header: ExportHeader, rows: List<ExportRow>): ByteArray =
        java.io.ByteArrayOutputStream().also { write(header, rows, it) }.toByteArray()

    private fun JsonObjectBuilder.filters(f: ExportFilters) {
        f.category?.let { put("category", it) }
        f.kinds?.let { k -> putJsonArray("kinds") { k.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
        f.connectionId?.let { put("connection_id", it) }
        f.connectionName?.let { put("connection_name", it) }
        f.q?.let { put("q", it) }
        f.since?.let { put("since", it) }
        f.until?.let { put("until", it) }
    }

    private fun JsonObjectBuilder.entry(r: ExportRow) {
        val e = r.record
        put("seq", e.seq)
        put("entry_id", e.entryId)
        put("at", e.atText)
        put("kind", e.kind)
        put("label", r.label)
        put("category", r.category)
        e.direction?.let { put("direction", it) }
        e.connectionId?.let { put("connection_id", it) }
        r.connectionName?.let { put("connection_name", it) }
        e.deviceId?.let { put("device_id", it) }
        r.deviceName?.let { put("device_name", it) }
        e.ref?.let { put("ref", it) }
        r.itemName?.let { put("item_name", it) }
        put("prev", e.prev)
        put("hash", e.hash)
    }
}
