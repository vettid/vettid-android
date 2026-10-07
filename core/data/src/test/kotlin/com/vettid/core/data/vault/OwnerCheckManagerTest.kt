package com.vettid.core.data.vault

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.vault.FeedItem
import com.vettid.core.vault.HeldCounts
import com.vettid.core.vault.OwnerCheckPassed
import com.vettid.core.vault.OwnerCheckStatus
import com.vettid.core.vault.Settings
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The app's side of the daily owner check (VAULT-MESSAGING 0.13.0 §3.6). */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerCheckManagerTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private var stored: ByteArray? = null
    private var passedCalls = 0

    private class Ops : OwnerCheckOps {
        var status: OwnerCheckStatus? = null
        var checkError: String? = null
        var checkErrorBody: JsonObject? = null
        val checks = mutableListOf<List<Any?>>()
        val sets = mutableListOf<Map<String, JsonElement>>()
        var feed = listOf<FeedItem>()
        val read = mutableListOf<String>()

        override suspend fun status() = status

        override suspend fun check(pin: String, password: String, hold: Boolean?, holdOffUntil: String?): OwnerCheckPassed {
            checks += listOf(pin, password, hold, holdOffUntil)
            checkError?.let { throw VaultOpException("vault.owner_check", it, body = checkErrorBody) }
            return OwnerCheckPassed("2026-10-07T12:00:00Z", 86_400, hold = hold ?: true, holdOffUntil = holdOffUntil)
        }

        override suspend fun settingsGet() = Settings(7, JsonObject(emptyMap()))

        override suspend fun settingsSet(version: Long, set: Map<String, JsonElement>) {
            sets += set
        }

        override suspend fun feedActive() = feed

        override suspend fun feedRead(itemId: String) {
            read += itemId
        }
    }

    private fun TestScope.manager(ops: Ops) = OwnerCheckManager(
        this,
        ops = { ops },
        load = { stored },
        persist = { stored = it },
        onPassed = { passedCalls++ },
        now = { now },
    )

    /** The manager's vault calls run on the IO dispatcher (vaultGuard): let them finish. */
    private fun TestScope.settle(done: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(SETTLE_MS)
        }
    }

    private companion object {
        const val SETTLE_TRIES = 500
        const val SETTLE_MS = 10L
    }

    private fun event(type: String, body: String, ts: Instant = now) =
        VaultMessage(Inner(id = Ulid.new(), type = type, ts = ts, body = body.toByteArray()))

    @Test
    fun statusGatesPastTheDeadlineAndSurvivesARestart() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-06T11:00:00Z", 86_400, failures = 3, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        val v = m.ownerCheck.value!!
        // The vault said ok, but the deadline has passed: the app is gated by the clock too (§3.6.3).
        assertTrue(v.gated(now))
        assertEquals(7, v.checksLeft)
        // Kept on the phone: a locked vault past its deadline asks for the password with the PIN (§3.6.5).
        val again = manager(Ops())
        assertTrue(again.ownerCheck.value!!.gated(now))
        assertEquals(3, again.ownerCheck.value!!.failures)
    }

    /**
     * What is waiting survives a restart, and so does the `ts` of the `vault.held` it came from (an older one
     * redelivered after the restart does not overwrite it); unknown stays unknown, never zero.
     */
    @Test
    fun waitingCountsAndTheirTimeSurviveARestart() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertNull(m.ownerCheck.value!!.waiting) // the vault has not said yet
        assertNull(manager(Ops()).ownerCheck.value!!.waiting)
        m.onEvent(event("vault.held", """{"deadline":"2026-10-06T11:00:00Z","waiting":{"messages":2,"requests":0,"calls":1,"other":0}}"""))
        val again = manager(Ops())
        assertEquals(WaitingCounts(2, 0, 1, 0), again.ownerCheck.value!!.waiting)
        again.onEvent(event("vault.held", """{"waiting":{"messages":0}}""", ts = now.minusSeconds(60)))
        assertEquals(WaitingCounts(2, 0, 1, 0), again.ownerCheck.value!!.waiting)
        again.onEvent(event("vault.held", """{"waiting":{"messages":5}}""", ts = now.plusSeconds(60)))
        assertEquals(WaitingCounts(5, 0, 0, 0), manager(Ops()).ownerCheck.value!!.waiting)
    }

    /** VAULT-MESSAGING 0.19.0: `vault.status`'s `owner_check.waiting`, while due or held; optional (S4 sends none). */
    @Test
    fun theStatusSaysWhatIsWaitingWhenTheVaultSendsIt() = runTest {
        val counts = HeldCounts(messages = 4, requests = 1, calls = 0, other = 0)
        val ops = Ops().apply { status = OwnerCheckStatus("due", "2026-10-06T11:00:00Z", 86_400, hold = false, waiting = counts) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertEquals(WaitingCounts(4, 1, 0, 0), m.ownerCheck.value!!.waiting)
        // An older vault: what vault.held said stays.
        ops.status = OwnerCheckStatus("due", "2026-10-06T11:00:00Z", 86_400, hold = false)
        m.refreshOwnerCheck()
        assertEquals(WaitingCounts(4, 1, 0, 0), m.ownerCheck.value!!.waiting)
        // Zero is zero when the vault says so.
        ops.status = OwnerCheckStatus("due", "2026-10-06T11:00:00Z", 86_400, hold = false, waiting = HeldCounts())
        m.refreshOwnerCheck()
        assertEquals(WaitingCounts(), m.ownerCheck.value!!.waiting)
        // Parsed from the wire as optional.
        val withField = VaultJson.decode(
            OwnerCheckStatus.serializer(),
            VaultJson.parseObject("""{"state":"held","waiting":{"messages":1,"requests":2,"calls":3,"other":4}}""".toByteArray()),
        )
        assertEquals(HeldCounts(1, 2, 3, 4), withField.waiting)
        val without = VaultJson.decode(
            OwnerCheckStatus.serializer(),
            VaultJson.parseObject("""{"state":"held"}""".toByteArray()),
        )
        assertNull(without.waiting)
    }

    @Test
    fun earlyWarningFromOneHourBefore() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", now.plus(Duration.ofMinutes(59)).toString(), 86_400) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertTrue(m.ownerCheck.value!!.warning(now))
        ops.status = OwnerCheckStatus("ok", now.plus(Duration.ofMinutes(61)).toString(), 86_400)
        m.refreshOwnerCheck()
        assertFalse(m.ownerCheck.value!!.warning(now))
    }

    @Test
    fun heldCountsAndAPassedCheck() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        m.onEvent(event("vault.held", """{"deadline":"2026-10-06T11:00:00Z","waiting":{"messages":3,"requests":1,"calls":0,"other":2}}"""))
        assertEquals(WaitingCounts(3, 1, 0, 2), m.ownerCheck.value!!.waiting)
        assertEquals(OwnerCheckState.HELD, m.ownerCheck.value!!.state)
        // An older vault.held does not replace a newer one (§3.6.3: the newest by ts).
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = now.minusSeconds(60)))
        assertEquals(3, m.ownerCheck.value!!.waiting!!.messages)

        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
        advanceUntilIdle()
        assertEquals(listOf<Any?>("975310", "pw", null, null), ops.checks.single())
        val v = m.ownerCheck.value!!
        assertEquals(OwnerCheckState.OK, v.state)
        assertEquals(Instant.parse("2026-10-07T12:00:00Z"), v.deadline)
        assertNull(v.waiting)
        assertEquals(1, passedCalls) // the catch-up
    }

    @Test
    fun holdOffRidesOnTheCheckAndIsClampedToThirtyDays() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T00:00:00Z", 86_400) }
        val m = manager(ops)
        m.check("975310", "pw", HoldOff(now.plus(Duration.ofDays(31))))
        val hold = ops.checks.single()[2]
        val until = ops.checks.single()[3]
        assertEquals(false, hold)
        val u = Instant.parse(until as String)
        assertTrue(!u.isAfter(now.plus(Duration.ofDays(30))) && u.isAfter(now.plus(Duration.ofDays(29))))
        assertTrue(m.ownerCheck.value!!.holdOff(now))

        // Off until the member turns it on: no end date.
        m.check("975310", "pw", HoldOff(null))
        assertEquals(listOf<Any?>("975310", "pw", false, null), ops.checks.last())

        // On: a plain settings.set.
        m.turnHoldOn()
        assertEquals(mapOf<String, JsonElement>("owner_check.hold" to JsonPrimitive(true)), ops.sets.single())
        assertFalse(m.ownerCheck.value!!.holdOff(now))
    }

    @Test
    fun failedChecksSayWhichEntryAndHowManyAreLeft() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 0) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkError = "bad_pin"
        ops.status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 1)
        assertEquals(OwnerCheckOutcome.BadPin(9), m.check("111111", "pw"))
        ops.checkError = "bad_password"
        ops.status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 2)
        assertEquals(OwnerCheckOutcome.BadPassword(8), m.check("975310", "nope"))
        ops.checkError = "backoff"
        ops.checkErrorBody = JsonObject(mapOf("retry_after" to JsonPrimitive(300)))
        assertEquals(OwnerCheckOutcome.Backoff(300), m.check("975310", "pw"))
        ops.checkError = "utk_invalid"
        ops.checkErrorBody = null
        assertTrue(m.check("975310", "pw") is OwnerCheckOutcome.Failed)
        // Ten failures lock the vault (§3.6.4).
        m.onEvent(event("vault.locking", """{"reason":"owner_check"}"""))
        assertTrue(m.lockedByOwnerCheck.value)
    }

    @Test
    fun aRefusedRequestGatesAndTheUnlockSendsTheCheck() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T00:00:00Z", 86_400) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.status = OwnerCheckStatus("due", "2026-10-06T11:00:00Z", 86_400, hold = false)
        m.onRequired()
        assertTrue(m.ownerCheck.value!!.gated(now))
        settle { m.ownerCheck.value?.state == OwnerCheckState.DUE }
        assertEquals(OwnerCheckState.DUE, m.ownerCheck.value!!.state)

        // Locked past the deadline: one screen for both, the check right after the unlock (§3.6.5).
        m.afterUnlock("975310", "pw")
        assertEquals(OwnerCheckOutcome.Passed, m.unlockCheckOutcome.value)
        assertEquals(listOf<Any?>("975310", "pw", null, null), ops.checks.single())
        m.consumeUnlockCheckOutcome()
        assertNull(m.unlockCheckOutcome.value)

        // Not past the deadline: no check.
        ops.status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400)
        m.afterUnlock("975310", "pw")
        assertEquals(1, ops.checks.size)
    }

    @Test
    fun noticesAreTheOwnerCheckFeedItems() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-07T00:00:00Z", 86_400)
            feed = listOf(
                FeedItem("i1", 1, "owner_check.failed", "2026-10-06T10:00:00Z", "active", "high", ref = "pin"),
                FeedItem("i2", 2, "message.received", "2026-10-06T10:01:00Z", "active"),
                FeedItem("i3", 3, "owner_check.locked", "2026-10-06T10:02:00Z", "active", "urgent", ref = "10"),
                FeedItem("i4", 4, "owner_check.hold_changed", "2026-10-06T09:00:00Z", "read", "high", ref = "off"),
            )
        }
        val m = manager(ops)
        m.onOpened()
        settle { m.notices.value.isNotEmpty() }
        assertEquals(listOf("i3", "i1"), m.notices.value.map { it.itemId })
        assertTrue(m.notices.value.first().urgent)
        val i5 = """{"item_id":"i5","seq":5,"kind":"owner_check.hold_changed","at":"2026-10-06T11:00:00Z",""" +
            """"status":"active","priority":"high","ref":"on:expired"}"""
        m.onEvent(event("feed.event", i5))
        assertEquals("i5", m.notices.value.first().itemId)
        m.dismissNotices()
        assertTrue(m.notices.value.isEmpty())
        assertEquals(listOf("i5", "i3", "i1"), ops.read)
    }

    @Test
    fun intervalIsOneToTwentyFourHours() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T00:00:00Z", 86_400) }
        val m = manager(ops)
        m.setCheckInterval(3_600)
        assertEquals(mapOf<String, JsonElement>("owner_check.interval_seconds" to JsonPrimitive(3_600L)), ops.sets.single())
        assertTrue(runCatching { m.setCheckInterval(90_000) }.isFailure)
        assertTrue(runCatching { m.setCheckInterval(600) }.isFailure)
    }
}
