package com.vettid.core.data.vault

import com.vettid.core.vault.AuditPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** History's device names (ANDROID-PLAN 0.1.27): `device.list` once per session, best effort, read again on refresh. */
class HistoryDevicesTest {
    private val noLog = AuditOps { _, _, _, _, _, _, _ -> AuditPage(emptyList(), seq = 0) }

    @Test
    fun theListIsReadOnceAndAgainOnRefresh() = runTest {
        var calls = 0
        var list = mapOf("d1" to "Pixel 10 Pro")
        val m = HistoryManager(ops = { noLog }, devices = { calls++; list }, self = { "d1" })
        assertEquals(mapOf("d1" to "Pixel 10 Pro"), m.deviceNames())
        assertEquals(mapOf("d1" to "Pixel 10 Pro"), m.deviceNames())
        assertEquals(1, calls)
        list = emptyMap()
        assertEquals(emptyMap<String, String>(), m.deviceNames(refresh = true))
        assertEquals(2, calls)
        assertEquals("d1", m.selfDeviceId())
    }

    @Test
    fun aFailureKeepsWhatWasReadOrIsUnknown() = runTest {
        var fail = true
        val m = HistoryManager(ops = { noLog }, devices = { if (fail) throw VaultFailure(FailureKind.NO_RESPONSE) else mapOf("d1" to "A") })
        assertNull(m.deviceNames())
        fail = false
        assertEquals(mapOf("d1" to "A"), m.deviceNames())
        fail = true
        assertEquals(mapOf("d1" to "A"), m.deviceNames(refresh = true))
    }

    @Test
    fun withoutADeviceSourceNothingIsKnown() = runTest {
        val m = HistoryManager(ops = { noLog })
        assertNull(m.deviceNames())
        assertNull(m.selfDeviceId())
    }
}
