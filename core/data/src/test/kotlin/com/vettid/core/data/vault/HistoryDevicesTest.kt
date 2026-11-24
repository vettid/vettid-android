package com.vettid.core.data.vault

import com.vettid.core.vault.AuditPage
import kotlinx.coroutines.async
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

    /**
     * R33: a device paired after the list was read is named, not called removed: an entry's device the kept list lacks
     * reads the list again, once per such id; a device still missing after that read is gone and reads nothing more.
     */
    @Test
    fun aDeviceTheKeptListLacksIsReadAgainOnce() = runTest {
        var calls = 0
        var list = mapOf("d1" to "Pixel 10 Pro")
        val m = HistoryManager(ops = { noLog }, devices = { calls++; list })
        assertEquals(mapOf("d1" to "Pixel 10 Pro"), m.deviceNames())
        list = mapOf("d1" to "Pixel 10 Pro", "desk" to "billie") // the desktop paired since
        assertEquals("billie", m.deviceNames(wanted = listOf("d1", "desk"))?.get("desk"))
        assertEquals(2, calls)
        assertEquals("billie", m.deviceNames(wanted = listOf("desk"))?.get("desk"))
        assertEquals("known now: no read", 2, calls)
        // A device that is really gone reads once, then never again.
        assertNull(m.deviceNames(wanted = listOf("gone"))?.get("gone"))
        assertEquals(3, calls)
        m.deviceNames(wanted = listOf("gone"))
        assertEquals("known gone: no read", 3, calls)
        // A refresh starts over.
        m.deviceNames(refresh = true)
        m.deviceNames(wanted = listOf("gone"))
        assertEquals(5, calls)
    }

    /** A wanted read that fails names no one gone: the next call for that id reads again. */
    @Test
    fun aFailedReadForAWantedIdIsTriedAgain() = runTest {
        var calls = 0
        var fail = false
        val m = HistoryManager(ops = { noLog }, devices = {
            calls++
            if (fail) throw VaultFailure(FailureKind.NO_RESPONSE)
            mapOf("d1" to "A")
        })
        m.deviceNames()
        fail = true
        assertEquals(mapOf("d1" to "A"), m.deviceNames(wanted = listOf("desk")))
        assertEquals(2, calls)
        fail = false
        m.deviceNames(wanted = listOf("desk"))
        assertEquals("tried again, then known gone", 3, calls)
        m.deviceNames(wanted = listOf("desk"))
        assertEquals(3, calls)
    }

    /** Two callers wanting the same new id at once make one read; the second is answered by the first's list. */
    @Test
    fun oneReadAtATime() = runTest {
        var calls = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var list = mapOf("d1" to "A")
        val m = HistoryManager(ops = { noLog }, devices = {
            calls++
            if (calls > 1) gate.await()
            list
        })
        m.deviceNames()
        list = mapOf("d1" to "A", "desk" to "billie")
        val first = async { m.deviceNames(wanted = listOf("desk")) }
        val second = async { m.deviceNames(wanted = listOf("desk")) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        assertEquals("billie", first.await()?.get("desk"))
        assertEquals("billie", second.await()?.get("desk"))
        assertEquals(2, calls)
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
