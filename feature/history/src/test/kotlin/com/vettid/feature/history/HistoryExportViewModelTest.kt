package com.vettid.feature.history

import com.vettid.core.data.vault.AuditCategory
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.ExportAnswer
import com.vettid.core.data.vault.ExportFormat
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.HistoryExportRepository
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant

/** The History export's steps (ANDROID-PLAN 0.1.17): preview → confirm → PIN → reading → "Save to…" → done. */
@RunWith(RobolectricTestRunner::class)
class HistoryExportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val t0: Instant = Instant.parse("2026-10-08T14:03:12.511Z")
    private val preview = ExportAnswer(
        count = 3, more = false, uptoSeq = 30, uptoHash = "AA==", oldestSeq = 10, newestSeq = 30,
        oldestAt = Instant.parse("2026-10-01T10:00:00Z"), newestAt = Instant.parse("2026-10-08T10:00:00Z"),
    )

    private class Repo : HistoryExportRepository {
        var previewResult: () -> ExportAnswer = { error("no preview") }
        var exportResults = ArrayDeque<() -> ExportAnswer>()
        var entriesResult: suspend (Long) -> List<AuditRecord> = { emptyList() }
        var devices: () -> Map<String, String> = { mapOf("d1" to "Pixel 7") }
        val previews = mutableListOf<AuditFilter>()
        val exports = mutableListOf<List<Any>>()
        val reads = mutableListOf<ExportAnswer>()

        override suspend fun preview(filter: AuditFilter): ExportAnswer {
            previews += filter
            return previewResult()
        }

        override suspend fun export(filter: AuditFilter, format: ExportFormat, uptoSeq: Long, pin: String): ExportAnswer {
            exports += listOf(filter, format, uptoSeq, pin)
            return (exportResults.removeFirstOrNull() ?: error("unexpected export"))()
        }

        override suspend fun entries(filter: AuditFilter, answer: ExportAnswer, onProgress: (Int) -> Unit): List<AuditRecord> {
            reads += answer
            val list = entriesResult(answer.count)
            onProgress(list.size)
            return list
        }

        override suspend fun deviceNames(): Map<String, String> = devices()
    }

    /** The documents "Save to…" returned: what was written to each. */
    private class Docs(var failWith: Exception? = null) : ExportDocuments {
        val written = mutableMapOf<String, ByteArray>()
        val discarded = mutableListOf<String>()

        override fun write(uri: String, block: (OutputStream) -> Unit) {
            val out = ByteArrayOutputStream()
            block(out)
            failWith?.let { throw it }
            written[uri] = out.toByteArray()
        }

        override fun discard(uri: String) {
            discarded += uri
        }
    }

    private val repo = Repo()
    private val docs = Docs()

    private fun vm() = HistoryExportViewModel(repo, docs, RuntimeEnvironment.getApplication()).also {
        it.now = { t0 }
        it.io = main.dispatcher
    }

    private fun records(n: Long) = (0 until n).map { i ->
        val seq = 30 - i * 10
        AuditRecord(
            "e$seq", seq, null, if (i == 0L) "message.sent" else "item.added", connectionId = if (i == 0L) "c1" else null,
            deviceId = if (i == 0L) "d1" else "d9", ref = if (i == 0L) null else "it$i", direction = if (i == 0L) "out" else null,
            prev = "AA==", hash = "AQ==", atText = "2026-10-0${i + 1}T10:00:00.000Z",
        )
    }

    private val ctx = ExportContext(
        AuditFilter(category = AuditCategory.MESSAGES, connectionId = "c1"),
        connectionNames = mapOf("c1" to "Alice Moreau"),
        itemNames = mapOf("it1" to "Passport"),
    )

    private fun success() = preview.copy(entrySeq = 31, answeredAt = t0)

    @Test
    fun theWholeFlowWritesTheChosenDocument() = runTest(main.dispatcher) {
        repo.previewResult = { preview }
        repo.exportResults += { success() }
        repo.entriesResult = { records(it) }
        val vm = vm()
        vm.start(ctx)
        assertEquals(ExportStep.Previewing, vm.step.value)
        advanceUntilIdle()
        assertEquals(ExportStep.Confirm(preview), vm.step.value)
        assertEquals(ctx.filter, repo.previews.single())
        // No format is preselected: Continue does nothing yet.
        vm.confirm()
        assertTrue(vm.step.value is ExportStep.Confirm)
        vm.chooseFormat(ExportFormat.JSON)
        vm.confirm()
        assertEquals(ExportStep.Pin(preview, ExportFormat.JSON), vm.step.value)
        vm.setPin("12a34")
        vm.submitPin() // too short
        assertTrue(repo.exports.isEmpty())
        vm.setPin("123456")
        vm.submitPin()
        assertTrue((vm.step.value as ExportStep.Pin).busy)
        advanceUntilIdle()
        assertEquals(listOf(ctx.filter, ExportFormat.JSON, 30L, "123456"), repo.exports.single())
        assertEquals(ExportStep.Save("vettid-history-20261008-140312.json", ExportFormat.JSON), vm.step.value)
        assertTrue(docs.written.isEmpty())
        vm.saveRequested()
        assertTrue((vm.step.value as ExportStep.Save).requested)
        vm.saveTo("content://docs/1")
        advanceUntilIdle()
        assertEquals(ExportStep.Done(3, 3), vm.step.value)
        val o = Json.parseToJsonElement(String(docs.written.getValue("content://docs/1"), Charsets.UTF_8)).jsonObject
        assertEquals("2026-10-08T14:03:12.511Z", o["exported_at"]!!.jsonPrimitive.content)
        val f = o["filters"]!!.jsonObject
        assertEquals("messages", f["category"]!!.jsonPrimitive.content)
        assertEquals("Alice Moreau", f["connection_name"]!!.jsonPrimitive.content)
        val e = o["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Alice Moreau", e[0]["connection_name"]!!.jsonPrimitive.content)
        assertEquals("Pixel 7", e[0]["device_name"]!!.jsonPrimitive.content)
        assertEquals("messages", e[0]["category"]!!.jsonPrimitive.content)
        assertEquals("Message sent", e[0]["label"]!!.jsonPrimitive.content)
        assertEquals("Passport", e[1]["item_name"]!!.jsonPrimitive.content)
        assertEquals("Removed device", e[1]["device_name"]!!.jsonPrimitive.content)
        assertEquals("Deleted item", e[2]["item_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun csvAndFewerEntriesThanAuthorised() = runTest(main.dispatcher) {
        repo.previewResult = { preview }
        repo.exportResults += { success() }
        repo.entriesResult = { records(it - 1) }
        val vm = vm()
        vm.start(ctx)
        advanceUntilIdle()
        vm.chooseFormat(ExportFormat.CSV)
        vm.confirm()
        vm.setPin("123456")
        vm.submitPin()
        advanceUntilIdle()
        assertEquals("vettid-history-20261008-140312.csv", (vm.step.value as ExportStep.Save).fileName)
        vm.saveTo("content://docs/2")
        advanceUntilIdle()
        assertEquals(ExportStep.Done(2, 3), vm.step.value)
        val b = docs.written.getValue("content://docs/2")
        assertEquals(0xEF.toByte(), b[0])
        assertEquals(3, String(b, Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }.size)
    }

    @Test
    fun nothingToExport() = runTest(main.dispatcher) {
        repo.previewResult = { preview.copy(count = 0, oldestAt = null, newestAt = null) }
        val vm = vm()
        vm.start(ctx)
        advanceUntilIdle()
        assertEquals(ExportStep.NothingToExport, vm.step.value)
    }

    @Test
    fun anOpenAlarmRefusesThePreviewAndTheExport() = runTest(main.dispatcher) {
        for (code in listOf("credential_frozen", "rotation_required")) {
            val kind = if (code == "credential_frozen") FailureKind.CREDENTIAL_FROZEN else FailureKind.ROTATION_REQUIRED
            repo.previewResult = { throw VaultFailure(kind, code) }
            val vm = vm()
            vm.start(ctx)
            advanceUntilIdle()
            assertEquals(ExportStep.Refused(ExportRefusal.ALARM), vm.step.value)
        }
        repo.previewResult = { preview }
        repo.exportResults += { throw VaultFailure(FailureKind.CREDENTIAL_FROZEN, "credential_frozen") }
        val vm = toPin(ExportFormat.CSV)
        vm.setPin("123456")
        vm.submitPin()
        advanceUntilIdle()
        assertEquals(ExportStep.Refused(ExportRefusal.ALARM), vm.step.value)
        assertTrue(docs.written.isEmpty())
    }

    @Test
    fun anOlderVaultSaysSo() = runTest(main.dispatcher) {
        for (f in listOf(VaultFailure(FailureKind.NOT_SUPPORTED, "unsupported_type"), VaultFailure(FailureKind.OTHER, "bad_request"))) {
            repo.previewResult = { throw f }
            val vm = vm()
            vm.start(ctx)
            advanceUntilIdle()
            assertEquals(ExportStep.Refused(ExportRefusal.OLD_VAULT), vm.step.value)
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.toPin(format: ExportFormat): HistoryExportViewModel {
        val vm = vm()
        vm.start(ctx)
        advanceUntilIdle()
        vm.chooseFormat(format)
        vm.confirm()
        return vm
    }

    @Test
    fun aWrongPinThenTheBackoff() = runTest(main.dispatcher) {
        repo.previewResult = { preview }
        repo.exportResults += { throw VaultFailure(FailureKind.BAD_PIN, "bad_pin") }
        repo.exportResults += { throw VaultFailure(FailureKind.BACKOFF, "backoff", retryAfterSeconds = 60) }
        val vm = toPin(ExportFormat.JSON)
        vm.setPin("111111")
        vm.submitPin()
        advanceUntilIdle()
        val wrong = vm.step.value as ExportStep.Pin
        assertEquals(FailureKind.BAD_PIN, wrong.error)
        assertEquals("", wrong.pin)
        assertNull(wrong.retryUntil)
        vm.setPin("222222")
        vm.submitPin()
        advanceUntilIdle()
        val held = vm.step.value as ExportStep.Pin
        assertEquals(FailureKind.BACKOFF, held.error)
        assertEquals(t0.plusSeconds(60), held.retryUntil)
        vm.setPin("333333")
        assertFalse(held.copy(pin = "333333").canSend(t0))
        vm.submitPin() // still in the backoff: not sent
        assertEquals(2, repo.exports.size)
        assertTrue(held.copy(pin = "333333").canSend(t0.plusSeconds(60)))
        assertTrue(docs.written.isEmpty())
        assertTrue(repo.reads.isEmpty())
    }

    @Test
    fun notFoundAtTheExport() = runTest(main.dispatcher) {
        repo.previewResult = { preview }
        repo.exportResults += { throw VaultFailure(FailureKind.NOT_FOUND, "not_found") }
        val vm = toPin(ExportFormat.CSV)
        vm.setPin("123456")
        vm.submitPin()
        advanceUntilIdle()
        assertEquals(ExportStep.NothingToExport, vm.step.value)
    }

    @Test
    fun ownerCheckRequired() = runTest(main.dispatcher) {
        repo.previewResult = { throw VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required") }
        val vm = vm()
        vm.start(ctx)
        advanceUntilIdle()
        assertEquals(ExportStep.Failed(FailureKind.OWNER_CHECK_REQUIRED), vm.step.value)
    }

    @Test
    fun cancelWhileReadingDropsEverything() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repo.previewResult = { preview }
        repo.exportResults += { success() }
        repo.entriesResult = {
            gate.await()
            records(it)
        }
        val vm = toPin(ExportFormat.JSON)
        vm.setPin("123456")
        vm.submitPin()
        advanceUntilIdle()
        assertEquals(ExportStep.Reading(0, 3), vm.step.value)
        vm.close()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ExportStep.Closed, vm.step.value)
        vm.saveTo("content://docs/3")
        advanceUntilIdle()
        assertTrue(docs.written.isEmpty())
    }

    @Test
    fun aDismissedDialogDiscardsTheData() = runTest(main.dispatcher) {
        repo.previewResult = { preview }
        repo.exportResults += { success() }
        repo.entriesResult = { records(it) }
        val vm = toPin(ExportFormat.JSON)
        vm.setPin("123456")
        vm.submitPin()
        advanceUntilIdle()
        vm.saveTo(null)
        assertEquals(ExportStep.Closed, vm.step.value)
        vm.saveTo("content://docs/4")
        advanceUntilIdle()
        assertTrue(docs.written.isEmpty())
    }

    @Test
    fun aFailedReadWritesNothing() = runTest(main.dispatcher) {
        repo.previewResult = { preview }
        repo.exportResults += { success() }
        repo.entriesResult = { throw VaultFailure(FailureKind.NETWORK) }
        val vm = toPin(ExportFormat.CSV)
        vm.setPin("123456")
        vm.submitPin()
        advanceUntilIdle()
        assertEquals(ExportStep.Failed(FailureKind.NETWORK), vm.step.value)
        vm.saveTo("content://docs/5")
        advanceUntilIdle()
        assertTrue(docs.written.isEmpty())
    }

    @Test
    fun aFailedWriteRemovesTheDocument() = runTest(main.dispatcher) {
        docs.failWith = IOException("disk full")
        repo.previewResult = { preview }
        repo.exportResults += { success() }
        repo.entriesResult = { records(it) }
        val vm = toPin(ExportFormat.CSV)
        vm.setPin("123456")
        vm.submitPin()
        advanceUntilIdle()
        vm.saveTo("content://docs/6")
        advanceUntilIdle()
        assertEquals(ExportStep.Failed(null, saveFailed = true), vm.step.value)
        assertEquals(listOf("content://docs/6"), docs.discarded)
        assertTrue(docs.written.isEmpty())
    }

    @Test
    fun withoutDeviceNamesTheFileKeepsTheIds() = runTest(main.dispatcher) {
        repo.previewResult = { preview }
        repo.exportResults += { success() }
        repo.entriesResult = { records(it) }
        repo.devices = { throw VaultFailure(FailureKind.NETWORK) }
        val vm = toPin(ExportFormat.JSON)
        vm.setPin("123456")
        vm.submitPin()
        advanceUntilIdle()
        vm.saveTo("content://docs/7")
        advanceUntilIdle()
        val e = Json.parseToJsonElement(String(docs.written.getValue("content://docs/7"), Charsets.UTF_8)).jsonObject["entries"]!!.jsonArray
        assertEquals("d1", e[0].jsonObject["device_id"]!!.jsonPrimitive.content)
        assertFalse(e[0].jsonObject.containsKey("device_name"))
    }

    @Test
    fun thePinNeverShowsInTheState() {
        assertFalse(ExportStep.Pin(preview, ExportFormat.CSV, pin = "987654").toString().contains("987654"))
    }
}
