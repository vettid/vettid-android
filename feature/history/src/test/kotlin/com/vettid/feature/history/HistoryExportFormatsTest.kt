package com.vettid.feature.history

import com.vettid.core.data.vault.AuditChain
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.ExportFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** The export file formats of VAULT-MESSAGING 0.22.0 §10.9, byte for byte. */
class HistoryExportFormatsTest {
    private val hash32: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun csvBytesExactly() {
        val rows = listOf(
            ExportRow(
                AuditRecord("e790", 790, null, "credential.rotated", hash = hash32, atText = "2026-10-08T14:00:00.000Z"),
                label = "Credential rotated",
                category = "security",
            ),
            ExportRow(
                AuditRecord(
                    "e789", 789, null, "message.received", connectionId = "c1", deviceId = "d1", ref = "=HYPERLINK(\"x\")",
                    direction = "in", atText = "2026-10-08T13:59:00.000Z",
                ),
                label = "Message received",
                category = "messages",
                connectionName = "Smith, \"Jo\"",
                deviceName = "-rm",
                itemName = "@x\nline",
            ),
            ExportRow(
                AuditRecord("e788", 788, null, "item.added", ref = "it1", atText = "2026-10-08T13:58:00.000Z"),
                label = "+Plus",
                category = "vault",
                itemName = "\tTab\rCR",
            ),
        )
        val expected = "seq,time,category,event,kind,direction,connection,device,item,ref,hash\r\n" +
            "790,2026-10-08T14:00:00.000Z,security,Credential rotated,credential.rotated,,,,,," +
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\r\n" +
            "789,2026-10-08T13:59:00.000Z,messages,Message received,message.received,in,\"Smith, \"\"Jo\"\"\",'-rm,\"'@x\nline\"," +
            "\"'=HYPERLINK(\"\"x\"\")\",\r\n" +
            "788,2026-10-08T13:58:00.000Z,vault,'+Plus,item.added,,,,\"'\tTab\rCR\",it1,\r\n"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertArrayEquals(bom + expected.toByteArray(Charsets.UTF_8), HistoryCsv.bytes(rows))
    }

    @Test
    fun csvInjectionGuardAndQuoting() {
        assertEquals("'=1+2", HistoryCsv.field("=1+2"))
        assertEquals("'+1", HistoryCsv.field("+1"))
        assertEquals("'-1", HistoryCsv.field("-1"))
        assertEquals("'@SUM", HistoryCsv.field("@SUM"))
        assertEquals("'\tx", HistoryCsv.field("\tx"))
        assertEquals("\"'\rx\"", HistoryCsv.field("\rx"))
        assertEquals("a=b", HistoryCsv.field("a=b"))
        assertEquals("", HistoryCsv.field(""))
        assertEquals("\"a,b\"", HistoryCsv.field("a,b"))
        assertEquals("\"'=a,\"\"b\"\"\"", HistoryCsv.field("=a,\"b\""))
        assertEquals("Zoë Ünal", HistoryCsv.field("Zoë Ünal"))
    }

    @Test
    fun csvNonAsciiIsUtf8AfterTheBom() {
        val r = AuditRecord("e1", 1, null, "connection.added", connectionId = "c", atText = "t")
        val rows = listOf(ExportRow(r, "Connection added", "connections", "Zoë"))
        val b = HistoryCsv.bytes(rows)
        assertTrue(String(b, 3, b.size - 3, Charsets.UTF_8).contains(",Zoë,"))
    }

    @Test
    fun fileNameAndTs() {
        val at = Instant.parse("2026-10-08T14:03:12.511Z")
        assertEquals("vettid-history-20261008-140312.csv", ExportFiles.fileName(at, ExportFormat.CSV))
        assertEquals("vettid-history-20261008-140312.json", ExportFiles.fileName(at, ExportFormat.JSON))
        assertEquals("2026-10-08T14:03:12.511Z", ExportFiles.ts(at))
        assertEquals("2026-10-08T14:03:12.000Z", ExportFiles.ts(Instant.parse("2026-10-08T14:03:12Z")))
    }

    /** A hash-chained log as §10.9 computes it (oldest first). */
    private fun chain(n: Int): List<AuditRecord> {
        var prev = ByteArray(32)
        return (1..n).map { i ->
            val seq = i.toLong()
            val at = Instant.parse("2026-10-08T10:00:00Z").plusSeconds(60L * i)
            val kind = if (i % 2 == 0) "message.sent" else "vault.unlocked"
            val conn = if (i % 2 == 0) "c1" else null
            val dir = if (i % 2 == 0) "out" else null
            val md = MessageDigest.getInstance("SHA-256")
            md.update("vettid/vms/2/audit".toByteArray())
            md.update(prev)
            md.update(ByteBuffer.allocate(8).putLong(seq).array())
            md.update(ByteBuffer.allocate(8).putLong(at.toEpochMilli()).array())
            for (s in listOf(kind, conn, null, null, dir)) {
                val b = (s ?: "").toByteArray()
                md.update(byteArrayOf((b.size shr 8).toByte(), b.size.toByte()))
                md.update(b)
            }
            val h = md.digest()
            val r = AuditRecord(
                "e$i", seq, at, kind, connectionId = conn, direction = dir,
                prev = Base64.getEncoder().encodeToString(prev), hash = Base64.getEncoder().encodeToString(h),
                atText = ExportFiles.ts(at),
            )
            prev = h
            r
        }
    }

    @Test
    fun jsonShapeAndTheChainRecomputes() {
        val log = chain(5).asReversed()
        val rows = log.map { r ->
            ExportRow(r, HistoryKindsLabel.of(r.kind), r.category.id, connectionName = r.connectionId?.let { "Alice Moreau" })
        }
        val header = ExportHeader(
            exportedAt = Instant.parse("2026-10-08T14:03:12.511Z"),
            filters = ExportFilters(),
            more = false,
            authorisedCount = 5,
            oldestSeq = 1,
            newestSeq = 5,
            logHeadSeq = 5,
            logHeadHash = log.first().hash,
        )
        val bytes = HistoryJson.bytes(header, rows)
        // UTF-8 without a byte order mark.
        assertEquals('{'.code.toByte(), bytes[0])
        val o = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        assertEquals(
            listOf(
                "format", "format_version", "exported_at", "filters", "count", "more", "authorised_count", "oldest_seq", "newest_seq",
                "log_head", "entries",
            ),
            o.keys.toList(),
        )
        assertEquals("vettid-history", o["format"]!!.jsonPrimitive.content)
        assertEquals(1L, o["format_version"]!!.jsonPrimitive.long)
        assertEquals("2026-10-08T14:03:12.511Z", o["exported_at"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), o["filters"])
        assertEquals(5L, o["count"]!!.jsonPrimitive.long)
        assertEquals(JsonObject(mapOf("seq" to JsonPrimitive(5L), "hash" to JsonPrimitive(log.first().hash))), o["log_head"])
        // Nothing identifying the vault, the account or the device.
        val text = String(bytes, Charsets.UTF_8)
        for (k in listOf("vault_id", "user_guid", "email", "app_key")) assertFalse(k, text.contains(k))
        val entries = o["entries"]!!.jsonArray
        val even = entries[1].jsonObject
        assertEquals(
            listOf("seq", "entry_id", "at", "kind", "label", "category", "direction", "connection_id", "connection_name", "prev", "hash"),
            even.keys.toList(),
        )
        val odd = entries[0].jsonObject
        assertEquals(listOf("seq", "entry_id", "at", "kind", "label", "category", "prev", "hash"), odd.keys.toList())
        // The chain recomputes from the file alone and ends at log_head.
        val back = entries.map { e ->
            val j = e.jsonObject
            fun s(k: String) = (j[k] as? JsonPrimitive)?.content
            AuditRecord(
                s("entry_id")!!, j["seq"]!!.jsonPrimitive.long, Instant.parse(s("at")), s("kind")!!, s("connection_id"), s("device_id"),
                s("ref"),
                s("direction"), s("prev")!!, s("hash")!!, s("at")!!,
            )
        }
        assertTrue(AuditChain.consistent(back))
        assertEquals(o["log_head"]!!.jsonObject["hash"]!!.jsonPrimitive.content, back.first().hash)
        // A changed entry breaks it.
        assertFalse(AuditChain.consistent(back.mapIndexed { i, r -> if (i == 2) r.copy(kind = "vault.locked") else r }))
    }

    @Test
    fun jsonFiltersOnlyThoseUsed() {
        val header = ExportHeader(
            Instant.EPOCH,
            ExportFilters(
                category = "security", kinds = listOf("credential", "identity", "recovery", "audit"), q = "pixel",
                since = "2026-10-01T04:00:00Z",
            ),
            more = true, authorisedCount = 0, oldestSeq = null, newestSeq = null, logHeadSeq = 9, logHeadHash = hash32,
        )
        val o = Json.parseToJsonElement(String(HistoryJson.bytes(header, emptyList()), Charsets.UTF_8)).jsonObject
        val f = o["filters"]!!.jsonObject
        assertEquals(listOf("category", "kinds", "q", "since"), f.keys.toList())
        assertEquals(4, (f["kinds"] as JsonArray).size)
        assertFalse(o.containsKey("oldest_seq"))
        assertEquals("true", o["more"]!!.jsonPrimitive.content)
        assertEquals(0, o["entries"]!!.jsonArray.size)
    }
}

/** Labels without resources (the writers take them as given). */
private object HistoryKindsLabel {
    fun of(kind: String): String = kind.replace('.', ' ')
}
