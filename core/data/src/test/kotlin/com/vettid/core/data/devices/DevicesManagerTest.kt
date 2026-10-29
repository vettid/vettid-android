// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.devices

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

/** The devices list and the two actions of AA1 (ANDROID-PLAN 0.1.53 "Devices & agents"; VAULT-MESSAGING §10.3, §10.11). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DevicesManagerTest {
    private val now = Instant.parse("2026-10-21T12:00:00Z")

    private fun dev(id: String, kind: String, name: String, created: String? = null, session: String? = null, active: String? = null) = buildString {
        append("""{"id":"$id","kind":"$kind","state":"active","name":"$name","ik":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="""")
        created?.let { append(""","created_at":"$it"""") }
        active?.let { append(""","last_active_at":"$it"""") }
        session?.let { append(""","session_expires_at":"$it"""") }
        append("}")
    }

    private fun list(vararg d: String) = obj("""{"devices":[${d.joinToString(",")}]}""")

    private class Ops : DevicesOps {
        var devices: JsonObject = JsonObject(emptyMap())
        var grantsAll: JsonObject = JsonObject(emptyMap())
        var grantsByAgent = mutableMapOf<String, JsonObject>()
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var refuse = mutableMapOf<String, String>()

        override suspend fun list(): JsonObject {
            synchronized(this) { calls += "list" }
            gate?.await()
            refuse["list"]?.let { throw VaultOpException("device.list", it) }
            return devices
        }

        override suspend fun grants(agentId: String?): JsonObject {
            synchronized(this) { calls += "grants:${agentId ?: "*"}" }
            refuse["grants"]?.let { throw VaultOpException("leash.grant.list", it) }
            return if (agentId == null) grantsAll else grantsByAgent[agentId] ?: obj("""{"grants":[],"suspended":[]}""")
        }

        override suspend fun endAccess(deviceId: String) {
            synchronized(this) { calls += "end:$deviceId" }
            refuse["end"]?.let { throw VaultOpException("device.session.end", it) }
        }

        override suspend fun unlink(deviceId: String) {
            synchronized(this) { calls += "unlink:$deviceId" }
            refuse["unlink"]?.let { throw VaultOpException("device.unlink", it) }
        }
    }

    private fun TestScope.manager(ops: Ops, self: String? = "me", gone: MutableList<String> = mutableListOf()) =
        DevicesManager(this, ops = { ops }, self = { self }, onDeviceGone = { gone += it })

    /** The manager's vault calls run on the IO dispatcher (vaultGuard): let them finish. */
    private fun TestScope.settle(done: () -> Boolean) {
        repeat(TRIES) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(SLEEP_MS)
        }
        fail("not settled")
    }

    private fun sync(kind: String) = VaultMessage(Inner(id = Ulid.new(), type = "sync.event", ts = now, body = """{"kind":"$kind"}""".toByteArray()))

    private val fleet = list(
        dev("d-old", "desktop", "Old desktop", created = "2026-09-01T10:00:00Z"),
        dev("a-new", "agent", "New agent", created = "2026-10-20T10:00:00Z", session = "2026-10-21T15:40:00Z", active = "2026-10-21T11:50:00Z"),
        dev("me", "app", "Pixel"),
        dev("d-new", "desktop", "New desktop", created = "2026-10-10T10:00:00Z"),
        dev("a-old", "agent", "Old agent", created = "2026-10-01T10:00:00Z"),
        dev("x", "toaster", "Odd", created = "2026-10-05T10:00:00Z"),
        dev("a-none", "agent", "No date"),
    )

    @Test
    fun thisPhoneFirstThenAgentsThenDesktopsEachNewestFirst() = runTest {
        val ops = Ops().apply { devices = fleet }
        val m = manager(ops)
        m.refresh()
        val d = m.devices.value
        // The agents newest first (an entry without a date last), then desktops, an unknown kind among them.
        assertEquals(listOf("me", "a-new", "a-old", "a-none", "d-new", "x", "d-old"), d.map { it.id })
        assertTrue(d[0].thisPhone)
        assertTrue(d[1].isAgent)
        assertTrue(d.last().isDesktop)
        // An unknown kind keeps its own value as its role.
        assertEquals("toaster", d.first { it.id == "x" }.kind)
        assertEquals(ListLoad.LOADED, m.load.value)
    }

    @Test
    fun anEntryWithoutAnIdIsNotListedAndANameIsCleaned() {
        val body = obj("""{"devices":[{"kind":"agent","name":"No id"},{"id":"a1","kind":"agent","name":"  Re\u0000d‮   Team  "}]}""")
        val d = DeviceWire.devices(body, self = null)
        assertEquals(listOf("a1"), d.map { it.id })
        // Control and format characters dropped; inner white space stays (§10.8).
        assertEquals("Red   Team", d[0].name)
        // A tag character (U+E0041) is dropped whole, not by UTF-16 half.
        assertEquals("ab", DeviceWire.cleanName("a\uDB40\uDC41b"))
        assertEquals("a\u2003\u00a0b", DeviceWire.cleanName("a\u2003\u00a0b"))
        assertEquals(DeviceWire.MAX_NAME, DeviceWire.cleanName("x".repeat(200)).length)
        // The cap and the cleaning together: no trailing space after the cut, an invisible name is empty.
        assertEquals(DeviceWire.MAX_NAME - 1, DeviceWire.cleanName("\u202E" + "x".repeat(DeviceWire.MAX_NAME - 1) + " yy").length)
        assertEquals("", DeviceWire.cleanName("\u3164\u200D"))
    }

    @Test
    fun theSessionShownIsDroppedOncePast() {
        val d = DeviceWire.devices(list(dev("a", "agent", "A", session = "2026-10-21T15:40:00Z")), self = null).single()
        assertTrue(d.hasAccess(now))
        assertFalse(d.hasAccess(Instant.parse("2026-10-21T15:40:00Z")))
        assertFalse(d.hasAccess(Instant.parse("2026-10-22T00:00:00Z")))
        val none = DeviceWire.devices(list(dev("b", "agent", "B")), self = null).single()
        assertFalse(none.hasAccess(now))
    }

    @Test
    fun theFingerprintIsEightGroupsOfFourHexDigits() {
        val d = DeviceWire.devices(list(dev("a", "agent", "A")), self = null).single()
        assertTrue(Regex("([0-9a-f]{4} ){7}[0-9a-f]{4}").matches(d.fingerprint!!))
        val bad = DeviceWire.devices(obj("""{"devices":[{"id":"z","kind":"agent","ik":"not base64"}]}"""), self = null).single()
        assertNull(bad.fingerprint)
    }

    @Test
    fun aSuspendedAgentIsMarkedFromTheGrantList() = runTest {
        val ops = Ops().apply {
            devices = fleet
            grantsAll = obj("""{"grants":[],"suspended":["a-old","d-new"]}""")
        }
        val m = manager(ops)
        m.refresh()
        assertEquals(setOf("a-old"), m.devices.value.filter { it.paused }.map { it.id }.toSet())
    }

    @Test
    fun theGrantListIsNotAskedWithoutAnAgentAndItsFailureLeavesTheList() = runTest {
        val ops = Ops().apply { devices = list(dev("d", "desktop", "D")) }
        val m = manager(ops)
        m.refresh()
        assertFalse(ops.calls.any { it.startsWith("grants") })
        ops.devices = fleet
        ops.refuse["grants"] = "unsupported_type"
        m.refresh()
        assertEquals(7, m.devices.value.size)
        assertTrue(m.devices.value.none { it.paused })
    }

    @Test
    fun aFailedReadSaysSoAndKeepsWhatWasRead() = runTest {
        val ops = Ops().apply { devices = fleet }
        val m = manager(ops)
        m.refresh()
        ops.refuse["list"] = "owner_check_required"
        try {
            m.refresh()
            fail("expected a failure")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
        assertEquals(ListLoad.FAILED, m.load.value)
        assertEquals(7, m.devices.value.size)
    }

    @Test
    fun theListIsClearedAtLockAndALateReadIsNotApplied() = runTest {
        val ops = Ops().apply { devices = fleet; gate = CompletableDeferred() }
        val m = manager(ops)
        val job = async { m.refresh() }
        settle { ops.calls.contains("list") }
        // The vault locks while the read is on its way.
        m.clear()
        ops.gate!!.complete(Unit)
        job.await()
        assertTrue(m.devices.value.isEmpty())
        assertEquals(ListLoad.NOT_LOADED, m.load.value)
        // And what was read is dropped at a clear, with the "removed" notice.
        ops.gate = null
        m.refresh()
        assertEquals(7, m.devices.value.size)
        m.clear()
        assertTrue(m.devices.value.isEmpty())
        assertNull(m.removed.value)
    }

    @Test
    fun theKindsOfSyncEventThatChangeTheListReadItOnceInAFlurry() = runTest {
        val ops = Ops().apply { devices = fleet; gate = CompletableDeferred() }
        val m = manager(ops)
        m.onEvent(sync("device.paired"))
        settle { ops.calls.contains("list") }
        // One in flight; the others queue behind it as one.
        m.onEvent(sync("device.unlinked"))
        m.onEvent(sync("device.session"))
        m.onEvent(sync("leash.agent.suspended"))
        m.onEvent(sync("message.read"))
        ops.gate!!.complete(Unit)
        settle { ops.calls.count { it == "list" } >= 2 }
        // A deterministic end: a read through the same lock runs after every queued one, and counts as the third.
        m.refresh()
        assertEquals(3, ops.calls.count { it == "list" })
    }

    @Test
    fun endingAccessDropsTheSessionAndTheDevicesRequestsAndReadsAgain() = runTest {
        val ops = Ops().apply { devices = fleet }
        val gone = mutableListOf<String>()
        val m = manager(ops, gone = gone)
        m.refresh()
        assertTrue(m.devices.value.first { it.id == "a-new" }.sessionExpiresAt != null)
        m.endAccess("a-new")
        assertNull(m.devices.value.first { it.id == "a-new" }.sessionExpiresAt)
        assertEquals(listOf("a-new"), gone)
        assertTrue(ops.calls.contains("end:a-new"))
    }

    @Test
    fun aRefusedEndOfAccessLeavesTheSessionAndTheRequests() = runTest {
        val ops = Ops().apply { devices = fleet }
        val gone = mutableListOf<String>()
        val m = manager(ops, gone = gone)
        m.refresh()
        ops.refuse["end"] = "not_found"
        try {
            m.endAccess("a-new")
            fail("expected a failure")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_FOUND, e.kind)
        }
        assertTrue(m.devices.value.first { it.id == "a-new" }.sessionExpiresAt != null)
        assertTrue(gone.isEmpty())
    }

    @Test
    fun removingADeviceListsItNoMoreNamesItForTheSnackbarAndDropsItsRequests() = runTest {
        val ops = Ops().apply { devices = fleet }
        val gone = mutableListOf<String>()
        val m = manager(ops, gone = gone)
        m.refresh()
        m.remove("a-old")
        assertTrue(m.devices.value.none { it.id == "a-old" })
        assertEquals("Old agent", m.removed.value?.name)
        assertEquals(listOf("a-old"), gone)
        m.dismissRemoved()
        assertNull(m.removed.value)
    }

    @Test
    fun aLockDuringARemovalLeavesTheClearedListAloneButStillCleansApprovals() = runTest {
        val ops = Ops().apply { devices = fleet }
        val gone = mutableListOf<String>()
        lateinit var m: DevicesManager
        val locking = object : DevicesOps by ops {
            override suspend fun unlink(deviceId: String) {
                ops.unlink(deviceId)
                m.clear() // the vault locks while the request is on its way
            }
        }
        m = DevicesManager(this, ops = { locking }, self = { "me" }, onDeviceGone = { gone += it })
        m.refresh()
        m.remove("a-old")
        assertTrue(m.devices.value.isEmpty())
        assertNull(m.removed.value)
        assertEquals(listOf("a-old"), gone)
    }

    @Test
    fun anotherAppEntryIsNeverEndedOrRemoved() = runTest {
        val ops = Ops().apply { devices = list(dev("me", "app", "Pixel"), dev("app2", "app", "Old phone")) }
        val m = manager(ops)
        m.refresh()
        for (act in listOf<suspend () -> Unit>({ m.endAccess("app2") }, { m.remove("app2") })) {
            try {
                act()
                fail("expected a failure")
            } catch (_: VaultFailure) {
            }
        }
        assertTrue(ops.calls.none { it.startsWith("end:") || it.startsWith("unlink:") })
    }

    @Test
    fun aFailedRemovalChangesNothing() = runTest {
        val ops = Ops().apply { devices = fleet }
        val gone = mutableListOf<String>()
        val m = manager(ops, gone = gone)
        m.refresh()
        ops.refuse["unlink"] = "forbidden"
        try {
            m.remove("a-old")
            fail("expected a failure")
        } catch (_: VaultFailure) {
        }
        assertTrue(m.devices.value.any { it.id == "a-old" })
        assertNull(m.removed.value)
        assertTrue(gone.isEmpty())
    }

    @Test
    fun thisPhoneIsNeverEndedOrRemovedAndNothingIsSent() = runTest {
        val ops = Ops().apply { devices = fleet }
        val m = manager(ops)
        m.refresh()
        listOf<suspend () -> Unit>({ m.endAccess("me") }, { m.remove("me") }).forEach { act ->
            try {
                act()
                fail("expected a failure")
            } catch (_: VaultFailure) {
            }
        }
        assertTrue(ops.calls.none { it.startsWith("end:") || it.startsWith("unlink:") })
    }

    @Test
    fun aFailingApprovalsCleanUpDoesNotTurnADoneRemovalIntoAFailure() = runTest {
        val ops = Ops().apply { devices = fleet }
        val m = DevicesManager(this, ops = { ops }, self = { "me" }, onDeviceGone = { error("store") })
        m.refresh()
        m.remove("a-old")
        assertTrue(m.devices.value.none { it.id == "a-old" })
    }

    @Test
    fun theRemovalBoundIsTheLongestStatusLifetimePlusAMinute() = runTest {
        val ops = Ops().apply {
            grantsByAgent["a"] = obj("""{"grants":[{"grant_id":"g1","status_ttl":300},{"grant_id":"g2"},{"grant_id":"g3","status_ttl":3600}],"suspended":[]}""")
            grantsByAgent["b"] = obj("""{"grants":[{"grant_id":"g1","status_ttl":120},{"grant_id":"g2"}],"suspended":[]}""")
            grantsByAgent["none"] = obj("""{"grants":[],"suspended":[]}""")
        }
        val m = manager(ops)
        assertEquals(3_660L, m.removalBoundSeconds("a"))
        // A grant without a `status_ttl` is the default 15 minutes.
        assertEquals(960L, m.removalBoundSeconds("b"))
        // No permission left: nothing is said.
        assertEquals(0L, m.removalBoundSeconds("none"))
    }

    @Test
    fun whenTheVaultCannotBeAskedTheBoundIsTheWorstCaseNeverShorter() = runTest {
        val ops = Ops().apply { refuse["grants"] = "internal" }
        val m = manager(ops)
        assertEquals(3_660L, m.removalBoundSeconds("a"))
    }

    @Test
    fun aGrantThatDoesNotReadCountsAsTheLongest() {
        assertEquals(listOf(3_600L), DeviceWire.statusTtls(obj("""{"grants":"nope"}""")))
        assertEquals(listOf(3_600L), DeviceWire.statusTtls(obj("""{}""")))
        val ttls = DeviceWire.statusTtls(obj("""{"grants":[{"status_ttl":"x"},{"status_ttl":99999},"junk",{"status_ttl":10}]}"""))
        assertEquals(listOf(3_600L, 3_600L, 3_600L, 60L), ttls)
    }

    private companion object {
        const val TRIES = 200
        const val SLEEP_MS = 10L
    }
}
