package com.vettid.core.data.vault

import com.vettid.core.vault.AuditEntry
import com.vettid.core.vault.AuditExportAnswer
import com.vettid.core.vault.AuditExportFilters
import com.vettid.core.vault.AuditPage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** The History export's vault side in the app (VAULT-MESSAGING 0.22.0 §10.9): the requests and the paging. */
class HistoryExportManagerTest {
    private fun entry(seq: Long) = AuditEntry("e$seq", seq, "2026-10-08T12:00:00.000Z", "message.sent", "c1", null, null, "out", "", "")

    /** `audit.list` from [pages] in order; records each request. */
    private class Log(vararg pages: AuditPage) : AuditOps {
        private val queue = ArrayDeque(pages.toList())
        val calls = mutableListOf<Map<String, Any?>>()

        override suspend fun auditList(
            connectionId: String?,
            kinds: List<String>?,
            beforeSeq: Long?,
            limit: Int,
            q: String?,
            since: String?,
            until: String?,
        ): AuditPage {
            calls += mapOf(
                "connection_id" to connectionId, "kinds" to kinds, "before_seq" to beforeSeq, "limit" to limit, "q" to q,
                "since" to since, "until" to until,
            )
            return queue.removeFirstOrNull() ?: error("unexpected request ${calls.last()}")
        }
    }

    private class Export(
        val preview: () -> AuditExportAnswer = { error("no preview") },
        val exports: ArrayDeque<() -> AuditExportAnswer> = ArrayDeque(),
    ) : AuditExportOps {
        val previews = mutableListOf<AuditExportFilters>()
        val sent = mutableListOf<List<Any?>>()

        override suspend fun preview(filters: AuditExportFilters): AuditExportAnswer {
            previews += filters
            return preview()
        }

        override suspend fun export(
            filters: AuditExportFilters,
            format: String,
            uptoSeq: Long,
            pin: String,
        ): Pair<AuditExportAnswer, Instant> {
            sent += listOf(filters, format, uptoSeq, pin)
            return (exports.removeFirstOrNull() ?: error("no export"))() to Instant.parse("2026-10-08T14:03:12.511Z")
        }

        override suspend fun deviceNames(): Map<String, String> = mapOf("d1" to "Pixel 7")
    }

    private val answer = ExportAnswer(count = 5, more = false, uptoSeq = 20, uptoHash = "AA==")

    @Test
    fun pagesFromUptoSeqAcrossPartialPagesAndKeepsTheFirstCount() = runTest {
        val log = Log(
            // A search that ran out of its budget: no entries, a cursor.
            AuditPage(emptyList(), nextBeforeSeq = 15, partial = true),
            AuditPage(listOf(entry(14), entry(12)), nextBeforeSeq = 12, partial = true),
            AuditPage(listOf(entry(9), entry(8), entry(7), entry(6)), nextBeforeSeq = 6),
        )
        val since = Instant.parse("2026-10-01T04:00:00Z")
        val filter = AuditFilter(category = AuditCategory.MESSAGES, connectionId = "c1", query = " alice ", since = since)
        val progress = mutableListOf<Int>()
        val got = HistoryExportManager({ Export() }, { log }).entries(filter, answer) { progress += it }
        assertEquals(listOf(14L, 12L, 9L, 8L, 7L), got.map { it.seq })
        assertEquals(listOf(0, 2, 5), progress)
        assertEquals(listOf(21L, 15L, 12L), log.calls.map { it["before_seq"] })
        log.calls.forEach {
            assertEquals(100, it["limit"])
            assertEquals("c1", it["connection_id"])
            assertEquals(listOf("message", "call"), it["kinds"])
            assertEquals("alice", it["q"])
            assertEquals("2026-10-01T04:00:00Z", it["since"])
            assertNull(it["until"])
        }
    }

    @Test
    fun fewerThanCountWhenTheLogEnds() = runTest {
        // The retention dropped the oldest entries meanwhile: the results end early.
        val log = Log(AuditPage(listOf(entry(20), entry(19), entry(18))))
        val got = HistoryExportManager({ Export() }, { log }).entries(AuditFilter(), answer)
        assertEquals(listOf(20L, 19L, 18L), got.map { it.seq })
        assertEquals(1, log.calls.size)
    }

    @Test
    fun dropsEntriesAboveUptoSeqRepeatsAndACursorThatDoesNotMove() = runTest {
        val log = Log(
            AuditPage(listOf(entry(25), entry(20), entry(19)), nextBeforeSeq = 19),
            AuditPage(listOf(entry(19), entry(18)), nextBeforeSeq = 30),
        )
        val got = HistoryExportManager({ Export() }, { log }).entries(AuditFilter(), answer)
        assertEquals(listOf(20L, 19L, 18L), got.map { it.seq })
        assertEquals(2, log.calls.size)
    }

    @Test
    fun nothingIsReadForCountZero() = runTest {
        val log = Log()
        assertTrue(HistoryExportManager({ Export() }, { log }).entries(AuditFilter(), answer.copy(count = 0)).isEmpty())
        assertTrue(log.calls.isEmpty())
    }

    @Test
    fun theReadingIsCancellableBetweenPages() = runTest {
        val job = Job()
        val log = object : AuditOps {
            var n = 0L
            override suspend fun auditList(
                connectionId: String?,
                kinds: List<String>?,
                beforeSeq: Long?,
                limit: Int,
                q: String?,
                since: String?,
                until: String?,
            ): AuditPage {
                n++
                if (n == 2L) job.cancel()
                return AuditPage(listOf(entry(beforeSeq!! - 1)), nextBeforeSeq = beforeSeq - 1)
            }
        }
        val r = async(job) { HistoryExportManager({ Export() }, { log }).entries(AuditFilter(), answer) }
        try {
            r.await()
            fail("not cancelled")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(2L, log.n)
    }

    @Test
    fun previewAndExportSendTheListsFilters() = runTest {
        val wire = """{"count":2,"more":false,"upto_seq":20,"upto_hash":"AA==","oldest_seq":9,"newest_seq":20,
            "oldest_at":"2026-10-01T10:00:00.000Z","newest_at":"2026-10-08T10:00:00.000Z","entry_seq":21}"""
        val a = Json { ignoreUnknownKeys = true }.decodeFromString(AuditExportAnswer.serializer(), wire)
        val ops = Export(preview = { a.copy(entrySeq = null) }, exports = ArrayDeque(listOf({ a })))
        val m = HistoryExportManager({ ops }, { Log() })
        val filter = AuditFilter(category = AuditCategory.SECURITY, until = Instant.parse("2026-10-08T04:00:00Z"))
        val p = m.preview(filter)
        assertEquals(2L, p.count)
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), p.oldestAt)
        assertNull(p.entrySeq)
        val sent = AuditExportFilters(kinds = listOf("credential", "identity", "recovery", "audit"), until = "2026-10-08T04:00:00Z")
        assertEquals(sent, ops.previews.single())
        val e = m.export(filter, ExportFormat.JSON, 20, "123456")
        assertEquals(21L, e.entrySeq)
        assertEquals(Instant.parse("2026-10-08T14:03:12.511Z"), e.answeredAt)
        assertEquals(listOf(ops.previews.single(), "json", 20L, "123456"), ops.sent.single())
    }

    @Test
    fun aUtkTheVaultNoLongerKnowsIsRetriedOnceWithTheNext() = runTest {
        val a = AuditExportAnswer(count = 1, uptoSeq = 3, uptoHash = "AA==", entrySeq = 4)
        val ops = Export(exports = ArrayDeque(listOf({ throw VaultOpException("audit.export", "utk_invalid", "") }, { a })))
        assertEquals(4L, HistoryExportManager({ ops }, { Log() }).export(AuditFilter(), ExportFormat.CSV, 3, "123456").entrySeq)
        assertEquals(2, ops.sent.size)
    }

    @Test
    fun refusalsKeepTheirCodes() = runTest {
        for ((code, kind) in listOf(
            "credential_frozen" to FailureKind.CREDENTIAL_FROZEN, "rotation_required" to FailureKind.ROTATION_REQUIRED,
            "unsupported_type" to FailureKind.NOT_SUPPORTED, "not_found" to FailureKind.NOT_FOUND, "bad_pin" to FailureKind.BAD_PIN,
        )) {
            val ops = Export(preview = { throw VaultOpException("audit.export", code, "") })
            try {
                HistoryExportManager({ ops }, { Log() }).preview(AuditFilter())
                fail(code)
            } catch (e: VaultFailure) {
                assertEquals(code, kind, e.kind)
                assertEquals(code, e.code)
            }
        }
    }

    @Test
    fun deviceNamesFromDeviceList() {
        val o = Json.decodeFromString(
            JsonObject.serializer(),
            """{"devices":[{"id":"d1","kind":"app","name":"Pixel 7"},{"id":"d2","name":""},{"id":"d3"},{"name":"x"}]}""",
        )
        assertEquals(mapOf("d1" to "Pixel 7"), VaultAuditExportOps.devicesOf(o))
        assertFalse(VaultAuditExportOps.devicesOf(JsonObject(emptyMap())).isNotEmpty())
    }
}
