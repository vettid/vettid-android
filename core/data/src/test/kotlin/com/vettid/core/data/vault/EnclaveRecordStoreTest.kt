package com.vettid.core.data.vault

import com.vettid.core.attestation.CheckResult
import com.vettid.core.attestation.EnclaveCheck
import com.vettid.core.attestation.EnclaveRecord
import com.vettid.core.attestation.EnclaveRefusal
import com.vettid.core.attestation.manifest.ReleaseStatus
import com.vettid.core.vault.InMemoryDeviceStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * ANDROID-PLAN 0.1.124 §5 "What the phone keeps": the last verification's record survives a restart, is replaced by the next
 * one, goes with a wipe, and a stored record that does not read as a run of the checks in order is no record.
 */
class EnclaveRecordStoreTest {
    private val at = Instant.parse("2026-11-11T12:00:00Z")
    private val full = EnclaveRecord(
        at, EnclaveRecord.results(9, failed = false), "0123456789abcdef", 7, "ab".repeat(48), "11".repeat(48), "22".repeat(48),
        at.minusSeconds(60), at.plusSeconds(86_000), 4, ReleaseStatus.DEPRECATED, Instant.parse("2026-12-01T00:00:00Z"),
        EnclaveRefusal.OLDER_RELEASE,
    )

    @Test
    fun aRecordIsKeptAcrossARestartReplacedAndForgotten() {
        val file = InMemoryDeviceStateStore()
        val s = EnclaveRecordStore(file)
        assertNull(s.record.value)
        s.record(full)
        assertEquals(full, EnclaveRecordStore(file).record.value)
        val failed = EnclaveRecord(at, EnclaveRecord.results(3, failed = true), "0123456789abcdef", 7, pcr0 = "ab".repeat(48))
        s.record(failed)
        assertEquals(failed, EnclaveRecordStore(file).record.value)
        s.forget()
        assertNull(s.record.value)
        assertNull(EnclaveRecordStore(file).record.value)
    }

    @Test
    fun aStoredRecordThatIsNotARunInOrderIsNoRecord() {
        val file = InMemoryDeviceStateStore()
        EnclaveRecordStore(file).record(full)
        val good = String(file.load()!!)
        fun with(json: String): EnclaveRecord? {
            file.save(json.toByteArray())
            return EnclaveRecordStore(file).record.value
        }
        // A "Passed" after a check that did not pass.
        assertNull(with(good.replaceFirst("\"CHAIN\":\"PASSED\"", "\"CHAIN\":\"NOT_RUN\"")))
        assertNull(with(good.replaceFirst("\"CHAIN\":\"PASSED\"", "\"CHAIN\":\"FAILED\"")))
        // An unknown result, a missing check, an unknown refusal.
        assertNull(with(good.replaceFirst("\"MATCH\":\"PASSED\"", "\"MATCH\":\"OK\"")))
        assertNull(with(good.replaceFirst(",\"MATCH\":\"PASSED\"", "")))
        assertNull(with(good.replaceFirst("OLDER_RELEASE", "SOMETHING")))
        // Not JSON at all.
        assertNull(with("not json"))
        assertEquals(CheckResult.PASSED, with(good)!!.result(EnclaveCheck.MATCH))
    }
}
