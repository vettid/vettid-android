// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.presence

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import java.time.Duration
import java.time.Instant

private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

/** The presence policy and the questions to connections (ANDROID-PLAN §4 "Presence"; VAULT-MESSAGING §10.17). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PresenceManagerTest {
    private class SetCall(val version: Long, val state: String?, val share: String?, val except: List<String>?)

    private class Ops : PresenceOps {
        var policy = """{"version":4,"state":"available","share":"all","except":[]}"""
        val gets = mutableListOf<String>()
        val sets = mutableListOf<SetCall>()
        val queries = mutableListOf<String>()
        var refuse = mutableMapOf<String, String>()

        /** `presence.set` answers `conflict` this many times first; the policy read after it is [afterConflict]. */
        var conflicts = 0
        var afterConflict: String? = null
        var ping = 0

        override suspend fun get(): JsonObject {
            synchronized(this) { gets += "get" }
            refuse["get"]?.let { throw VaultOpException("presence.get", it) }
            return obj(policy)
        }

        override suspend fun set(version: Long, state: String?, share: String?, except: List<String>?): JsonObject {
            synchronized(this) { sets += SetCall(version, state, share, except) }
            refuse["set"]?.let { throw VaultOpException("presence.set", it) }
            if (conflicts > 0) {
                conflicts--
                afterConflict?.let { policy = it }
                throw VaultOpException("presence.set", "conflict")
            }
            return obj("""{"version":${version + 1}}""")
        }

        override suspend fun query(connectionId: String): JsonObject {
            synchronized(this) { queries += connectionId }
            refuse["query"]?.let { throw VaultOpException("presence.query", it) }
            return obj("""{"ping_id":"ping-${++ping}","exp":"2026-11-03T12:00:30Z"}""")
        }
    }

    private var clock = Instant.parse("2026-11-03T12:00:00Z")
    private var closed = false

    private fun TestScope.manager(ops: Ops) = PresenceManager(this, ops = { ops }, closed = { closed }, now = { clock })

    /** The manager's vault calls run on the IO dispatcher (vaultGuard): let them finish. */
    private fun TestScope.settle(done: () -> Boolean) {
        repeat(TRIES) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(SLEEP_MS)
        }
        fail("not settled")
    }

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = clock, body = body.toByteArray()))

    private fun result(ping: String, conn: String = "c1", state: String = "busy", active: String? = "2026-11-03T11:50:00Z") =
        event("presence.result", """{"connection_id":"$conn","ping_id":"$ping","state":"$state"${active?.let { ""","last_active":"$it"""" } ?: ""}}""")

    private suspend fun TestScope.open(ops: Ops): PresenceManager = manager(ops).also { it.refreshPolicy() }

    @Test
    fun theVaultHasPresenceWhenItAnswersPresenceGet() = runTest {
        val m = manager(Ops())
        assertEquals(PresenceAvailability.UNKNOWN, m.availability.value)
        m.refreshPolicy()
        assertEquals(PresenceAvailability.AVAILABLE, m.availability.value)
        assertEquals(PresencePolicy(4, PresenceState.AVAILABLE, PresenceShare.ALL, emptyList()), m.policy.value)
    }

    @Test
    fun anUnsupportedTypeMeansNoPresenceAndNothingIsAskedOrChanged() = runTest {
        val ops = Ops().apply { refuse["get"] = "unsupported_type" }
        val m = manager(ops)
        try {
            m.refreshPolicy()
            fail("refused")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
        }
        assertEquals(PresenceAvailability.UNSUPPORTED, m.availability.value)
        m.query("c1")
        assertTrue(ops.queries.isEmpty())
        assertTrue(m.readings.value.isEmpty())
        // Not asked again before the next unlock.
        try {
            m.update { it.copy(state = PresenceState.BUSY) }
            fail("refused")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
        }
        assertEquals(1, ops.gets.size)
        assertTrue(ops.sets.isEmpty())
    }

    @Test
    fun aChangeSendsOnlyWhatChangesWithTheVersionAndKeepsTheNewVersion() = runTest {
        val ops = Ops()
        val m = open(ops)
        m.update { it.copy(state = PresenceState.BUSY) }
        with(ops.sets.single()) {
            assertEquals(4L, version)
            assertEquals("busy", state)
            assertNull(share)
            assertNull(except)
        }
        assertEquals(PresencePolicy(5, PresenceState.BUSY, PresenceShare.ALL, emptyList()), m.policy.value)
    }

    @Test
    fun allAndNoOneWithTheirExceptionsAreSentSortedAndWithoutRepeats() = runTest {
        val ops = Ops()
        val m = open(ops)
        m.update { it.withExcept(listOf("c9", "c2", "c9", "")) }
        assertEquals(listOf("c2", "c9"), ops.sets.last().except)
        // No one: the exceptions start again (they would mean the opposite), then "Only…" is picked.
        m.update { it.withShare(PresenceShare.NONE).withExcept(listOf("c5")) }
        with(ops.sets.last()) {
            assertEquals("none", share)
            assertEquals(listOf("c5"), except)
        }
        assertEquals(PresenceShare.NONE, m.policy.value?.share)
        assertEquals(listOf("c5"), m.policy.value?.except)
        assertEquals(6L, m.policy.value?.version)
    }

    @Test
    fun theConnectionPagesSwitchAddsAndRemovesTheConnectionInExcept() = runTest {
        val ops = Ops()
        val m = open(ops)
        // All: off puts it in except, on takes it out.
        m.update { it.withSharedWith("c1", false) }
        assertEquals(listOf("c1"), ops.sets.last().except)
        assertFalse(m.policy.value!!.sharesWith("c1"))
        m.update { it.withSharedWith("c1", true) }
        assertEquals(emptyList<String>(), ops.sets.last().except)
        assertTrue(m.policy.value!!.sharesWith("c1"))
        // Nothing to change: nothing sent.
        val sent = ops.sets.size
        m.update { it.withSharedWith("c1", true) }
        assertEquals(sent, ops.sets.size)
    }

    @Test
    fun aConflictReadsThePolicyAgainAndAppliesTheMembersChangeOnceMore() = runTest {
        val ops = Ops().apply {
            conflicts = 1
            afterConflict = """{"version":9,"state":"away","share":"all","except":["c7"]}"""
        }
        val m = open(ops)
        m.update { it.withSharedWith("c1", false) }
        assertEquals(2, ops.sets.size)
        assertEquals(4L, ops.sets[0].version)
        // The member's change on the fresh policy (another device's exception stays), under the fresh version.
        assertEquals(9L, ops.sets[1].version)
        assertEquals(listOf("c1", "c7"), ops.sets[1].except)
        assertNull(ops.sets[1].state)
        assertEquals(PresencePolicy(10, PresenceState.AWAY, PresenceShare.ALL, listOf("c1", "c7")), m.policy.value)
    }

    @Test
    fun aSecondConflictIsNotRetriedAgain() = runTest {
        val ops = Ops().apply {
            conflicts = 5
            afterConflict = """{"version":9,"state":"available","share":"all","except":[]}"""
        }
        val m = open(ops)
        try {
            m.update { it.copy(state = PresenceState.BUSY) }
            fail("conflict")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.CONFLICT, e.kind)
        }
        assertEquals(2, ops.sets.size)
        assertEquals(2, ops.gets.size)
    }

    @Test
    fun theSyncEventReadsThePolicyAgain() = runTest {
        val ops = Ops()
        val m = open(ops)
        ops.policy = """{"version":7,"state":"busy","share":"none","except":["c3"]}"""
        m.onEvent(event("sync.event", """{"kind":"presence.changed","version":7}"""))
        settle { m.policy.value?.version == 7L }
        assertEquals(PresenceState.BUSY, m.policy.value?.state)
        // Other events change nothing.
        m.onEvent(event("sync.event", """{"kind":"device.paired"}"""))
        advanceUntilIdle()
        assertEquals(2, ops.gets.size)
    }

    @Test
    fun aQueryReadsCheckingUntilItsResultArrives() = runTest {
        val ops = Ops()
        val m = open(ops)
        m.query("c1")
        assertEquals(PresenceReading.Checking, m.readings.value["c1"])
        // A result for another ping, or with a state the vault would not send, is not this one's answer.
        m.onEvent(result("other"))
        m.onEvent(result("ping-1", state = "invisible"))
        assertEquals(PresenceReading.Checking, m.readings.value["c1"])
        m.onEvent(result("ping-1"))
        val known = m.readings.value["c1"] as PresenceReading.Known
        assertEquals(PresenceState.BUSY, known.state)
        assertEquals(Instant.parse("2026-11-03T11:50:00Z"), known.lastActive)
        // The timer found nothing left to do.
        advanceTimeBy(60_000)
        assertEquals(known, m.readings.value["c1"])
    }

    @Test
    fun aResultWithoutLastActiveIsKnownWithoutIt() = runTest {
        val m = open(Ops())
        m.query("c1")
        m.onEvent(result("ping-1", state = "away", active = null))
        assertEquals(PresenceReading.Known(PresenceState.AWAY, null), m.readings.value["c1"])
    }

    @Test
    fun aResultThatArrivesBeforeTheQuerysAnswerIsKept() = runTest {
        val ops = Ops()
        lateinit var m: PresenceManager
        val early = object : PresenceOps by ops {
            override suspend fun query(connectionId: String): JsonObject {
                val answer = ops.query(connectionId)
                // The relay delivered the result before the answer to the query.
                m.onEvent(result("ping-1"))
                return answer
            }
        }
        m = PresenceManager(this, ops = { early }, closed = { closed }, now = { clock })
        m.refreshPolicy()
        m.query("c1")
        assertTrue(m.readings.value["c1"] is PresenceReading.Known)
    }

    @Test
    fun noResultByExpIsUnknownAndALateOneIsNotShown() = runTest {
        val m = open(Ops())
        m.query("c1")
        advanceTimeBy(29_000)
        assertEquals(PresenceReading.Checking, m.readings.value["c1"])
        advanceTimeBy(1_500)
        assertEquals(PresenceReading.Unknown, m.readings.value["c1"])
        m.onEvent(result("ping-1"))
        assertEquals(PresenceReading.Unknown, m.readings.value["c1"])
    }

    @Test
    fun aRefusedQueryIsUnknown() = runTest {
        val ops = Ops().apply { refuse["query"] = "connection_unavailable" }
        val m = open(ops)
        m.query("c1")
        assertEquals(PresenceReading.Unknown, m.readings.value["c1"])
    }

    @Test
    fun theSameConnectionIsAskedOnceAMinute() = runTest {
        val ops = Ops()
        val m = open(ops)
        m.query("c1")
        m.onEvent(result("ping-1"))
        // Opening the page again, or the refresh icon, inside the minute: the earlier answer stands, nothing is sent.
        clock = clock.plusSeconds(59)
        m.query("c1")
        assertEquals(1, ops.queries.size)
        assertTrue(m.readings.value["c1"] is PresenceReading.Known)
        // Another connection is asked at once.
        m.query("c2")
        assertEquals(listOf("c1", "c2"), ops.queries)
        // A minute after the first: asked again, Checking meanwhile.
        clock = clock.plus(Duration.ofSeconds(2))
        m.query("c1")
        assertEquals(listOf("c1", "c2", "c1"), ops.queries)
        assertEquals(PresenceReading.Checking, m.readings.value["c1"])
    }

    @Test
    fun nothingIsSentWhileTheOwnerCheckIsDueOrHeld() = runTest {
        val ops = Ops()
        val m = open(ops)
        closed = true
        m.query("c1")
        assertEquals(PresenceReading.Unknown, m.readings.value["c1"])
        assertTrue(ops.queries.isEmpty())
        for (act in listOf<suspend () -> Unit>({ m.update { it.copy(state = PresenceState.BUSY) } }, { m.refreshPolicy() })) {
            try {
                act()
                fail("refused")
            } catch (e: VaultFailure) {
                assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
            }
        }
        assertTrue(ops.sets.isEmpty())
        assertEquals(1, ops.gets.size)
        // The hold clears everything held; after the check the policy is read again and the connection can be asked.
        m.clear()
        assertNull(m.policy.value)
        assertEquals(PresenceAvailability.UNKNOWN, m.availability.value)
        assertTrue(m.readings.value.isEmpty())
        closed = false
        m.refreshPolicy()
        m.query("c1")
        assertEquals(listOf("c1"), ops.queries)
    }

    @Test
    fun aLockForgetsTheReadingsTheTimersAndAnswersStillOnTheirWay() = runTest {
        val m = open(Ops())
        m.query("c1")
        m.clear()
        assertTrue(m.readings.value.isEmpty())
        assertNull(m.policy.value)
        // Nothing the timer or a late result does brings a reading back.
        m.onEvent(result("ping-1"))
        advanceTimeBy(60_000)
        assertTrue(m.readings.value.isEmpty())
    }

    private companion object {
        const val TRIES = 200
        const val SLEEP_MS = 10L
    }
}
