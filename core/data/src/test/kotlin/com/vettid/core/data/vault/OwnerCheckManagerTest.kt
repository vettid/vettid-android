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
import com.vettid.core.vault.VaultStateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
@Suppress("LargeClass")
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerCheckManagerTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private var stored: ByteArray? = null
    private var passedCalls = 0

    private class Ops : OwnerCheckOps {
        var status: OwnerCheckStatus? = null
        var checkError: String? = null

        /** The check goes unanswered (a relay failure rather than a vault error). */
        var checkThrows: Throwable? = null

        /** Once a check was sent, the status reads fail (the count is then the estimate, deterministically). */
        var statusDownAfterCheck = false
        var statusDown = false

        /** `vault.status` does not answer (the probe's relay read). */
        var failStatus = false
        var checkErrorBody: JsonObject? = null
        /** (Release R) The answer's `recovery.present_recorded`, and the status's `recovery.present_recorded_at`. */
        var presentRecorded: Boolean? = null
        var presentRecordedAt: String? = null
        var counter: String = "ok"
        var answerDeadline: String? = "2026-10-07T12:00:00Z"
        val checks = mutableListOf<List<Any?>>()
        val sets = mutableListOf<Map<String, JsonElement>>()

        override suspend fun status() = status

        /** When set, the first check sent waits for it (an unlock's check still out when something else happens). */
        @Volatile var firstCheckGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun check(pin: String, password: String, hold: Boolean?, holdOffUntil: String?): OwnerCheckPassed {
            synchronized(checks) { checks += listOf(pin, password, hold, holdOffUntil) }
            if (synchronized(checks) { checks.size } == 1) firstCheckGate?.await()
            if (statusDownAfterCheck) statusDown = true
            checkThrows?.let { throw it }
            checkError?.let { throw VaultOpException("vault.owner-check", it, body = checkErrorBody) }
            return OwnerCheckPassed(
                answerDeadline, 86_400, hold = hold ?: true, holdOffUntil = holdOffUntil,
                recovery = presentRecorded?.let { com.vettid.core.vault.OwnerCheckRecovery("armed", it) },
            )
        }

        override suspend fun statusAndRecovery(): Pair<OwnerCheckStatus?, com.vettid.core.vault.RecoveryStatus> {
            if (statusDown || failStatus) throw VaultOpException("vault.status", "unreachable")
            return status to com.vettid.core.vault.RecoveryStatus("armed", presentRecordedAt = presentRecordedAt, counter = counter)
        }

        /** (0.43.0) The status's `time_scale`; null: absent. */
        var timeScale: Long? = null

        /** (0.84.0) The status's `relay` is `"refused"`. */
        var relayRefused = false

        private suspend fun statusGated() {
            reads++
            statusGate?.await()
        }

        /** When set, `statusRead` waits for it (a read that is still running when something else happens). */
        @Volatile var statusGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        /** How many `statusRead`s were made. */
        @Volatile var reads = 0

        override suspend fun statusRead(): StatusRead {
            statusGated()
            val (o, r) = statusAndRecovery()
            return StatusRead(o, r, timeScale, relayRefused)
        }

        /** What `settings.get` lists (a vault before L lists no `idle_lock.after_seconds`). */
        var settings: Map<String, JsonElement> = emptyMap()

        override suspend fun settingsGet() = Settings(7, JsonObject(settings))

        override suspend fun settingsSet(version: Long, set: Map<String, JsonElement>) {
            sets += set
            settings = settings + set
        }
    }

    private val read = mutableListOf<String>()

    /** Runs once, at the next look at the clock (something that happens between two steps of the manager). */
    private var nowHook: (() -> Unit)? = null

    /** The member API's `state` as the probe reads it (null: no answer), and how many times it found the vault locked. */
    private var memberState: String? = null

    /** When set, the member API's answer waits for it (a read that is still running when something else happens). */
    private var memberGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var foundLocked = 0

    /** Whether the build accepts a `time_scale` (a staging build: up to TimeScale.MAX). */
    private var accepts: (Long) -> Boolean = { true }

    /** The vault's scale as the phone stored it (null: none stored). */
    private var storedScale: Long? = null

    private fun TestScope.manager(ops: Ops) = OwnerCheckManager(
        this,
        ops = { ops },
        load = { stored },
        persist = { stored = it },
        onPassed = { passedCalls++ },
        readNotices = { read += it },
        now = { nowHook?.also { nowHook = null }?.invoke(); now },
        memberState = { memberGate?.await(); memberState },
        onFoundLocked = { foundLocked++ },
        acceptsTimeScale = { accepts(it) },
        storedTimeScale = { storedScale },
    )

    /** The manager's vault calls run on the IO dispatcher (vaultGuard): let them finish. */
    private fun TestScope.settle(done: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(SETTLE_MS)
        }
    }

    /** Waits (bounded) until [ops] has started [n] `statusRead`s: the async read is then held at its gate. */
    private fun awaitReads(ops: Ops, n: Int) {
        val end = System.nanoTime() + AWAIT_NS
        while (ops.reads < n && System.nanoTime() < end) Thread.sleep(SETTLE_MS)
        assertTrue("the read did not start", ops.reads >= n)
    }

    private companion object {
        const val AWAIT_NS = 30_000_000_000L
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

    /**
     * ANDROID-PLAN 0.1.57: `credential_required` raises "This phone can't use your vault" (never the PIN and password
     * kept for Try again); `forbidden` is "not the holder"; another code is named. Nothing on the phone changes.
     */
    @Test
    fun aCheckRefusedForTheHolderChangesNothingOnThePhone() = runTest {
        CredentialRequired.clear()
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 0) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        val before = stored?.copyOf()
        val view = m.ownerCheck.value
        ops.checkError = "credential_required"
        ops.checkErrorBody = JsonObject(mapOf("reason" to JsonPrimitive("no_holder")))
        assertEquals(OwnerCheckOutcome.Failed(FailureKind.CREDENTIAL_REQUIRED, "credential_required"), m.check("975310", "pw"))
        assertTrue(CredentialRequired.state.value != null)
        CredentialRequired.tryAgain()
        assertEquals(1, ops.checks.size) // the daily check asks for the PIN and the password again
        ops.checkErrorBody = null
        ops.checkError = "forbidden"
        assertEquals(OwnerCheckOutcome.Failed(FailureKind.NOT_HOLDER, "forbidden"), m.check("975310", "pw"))
        ops.checkError = "internal"
        assertEquals(OwnerCheckOutcome.Failed(FailureKind.OTHER, "internal", "internal"), m.check("975310", "pw"))
        assertTrue(before.contentEquals(stored))
        assertEquals(view, m.ownerCheck.value)
        assertFalse(m.lockedByOwnerCheck.value)
        CredentialRequired.clear()
    }

    /**
     * (ANDROID-PLAN 0.1.132) Only a check passed with the vault open counts in [OwnerCheckView.passes] (the waiting asks are
     * replayed for a rise since the last gated value): here, or another device's pass; never a status read.
     */
    @Test
    fun onlyAChecksPassedWithTheVaultOpenCount() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        val gatedAt = m.ownerCheck.value!!.passes
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
        assertEquals("a check passed here counts", gatedAt + 1, m.ownerCheck.value!!.passes)
        ops.status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400)
        m.refreshOwnerCheck()
        assertEquals("a status read does not", gatedAt + 1, m.ownerCheck.value!!.passes)
        ops.status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true)
        m.refreshOwnerCheck()
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-08T12:00:00Z"}"""))
        assertEquals("another device's pass counts", gatedAt + 2, m.ownerCheck.value!!.passes)
    }

    /**
     * The check an unlock makes never counts, also after an earlier pass (the review's probe: a pass, a lock, then an unlock
     * whose clock says due runs its own check; that must not read as a new pass). A later, separate check does count.
     */
    @Test
    fun theCheckAnUnlockMakesNeverCounts() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null)) // an earlier pass, then the vault locks
        val before = m.ownerCheck.value!!.passes
        // The probe's path: the status says OK, but the phone's clock is past its deadline, so the unlock checks.
        ops.status = OwnerCheckStatus("ok", now.minusSeconds(60).toString(), 86_400)
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertEquals("the unlock checked", 2, ops.checks.size)
        assertEquals(OwnerCheckState.OK, m.ownerCheck.value!!.state)
        assertEquals("the unlock's own check", before, m.ownerCheck.value!!.passes)
        ops.status = OwnerCheckStatus("due", "2026-10-06T11:00:00Z", 86_400, hold = false)
        m.refreshOwnerCheck()
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
        assertEquals("a later, separate check", before + 1, m.ownerCheck.value!!.passes)
    }

    /**
     * Whether a check is the unlock's own is said per call, not by a flag the whole manager shares: a separate check that
     * passes while an unlock's check is still out counts, and the unlock's own pass, which ends after it, still does not.
     */
    @Test
    fun aSeparateCheckWhileAnUnlocksCheckIsOutCounts() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", now.minusSeconds(60).toString(), 86_400)
            firstCheckGate = kotlinx.coroutines.CompletableDeferred()
        }
        val m = manager(ops)
        m.refreshOwnerCheck()
        val before = m.ownerCheck.value!!.passes
        val unlock = this.async(Dispatchers.Default) { m.afterUnlock("975310", "pw", m.foregroundGeneration) }
        val end = System.nanoTime() + AWAIT_NS
        while (synchronized(ops.checks) { ops.checks.size } < 1 && System.nanoTime() < end) Thread.sleep(SETTLE_MS)
        assertEquals("the unlock's check is out", 1, synchronized(ops.checks) { ops.checks.size })
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
        assertEquals("the separate check counts", before + 1, m.ownerCheck.value!!.passes)
        ops.firstCheckGate!!.complete(Unit)
        unlock.await()
        assertEquals("the unlock's own pass does not", before + 1, m.ownerCheck.value!!.passes)
    }

    /** (Notifications 11) An unlock's check that fails, then a separate check that passes: that pass makes the replay due. */
    @Test
    fun aFailedUnlockCheckThenASeparateCheckMakesTheReplayDue() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true)
            checkError = "bad_password"
        }
        val m = manager(ops)
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertTrue(m.unlockCheckOutcome.value is OwnerCheckOutcome.BadPassword)
        val gated = m.ownerCheck.value!!
        assertTrue(gated.gated(now))
        ops.checkError = null
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
        val after = m.ownerCheck.value!!
        assertEquals(gated.passes + 1, after.passes)
        assertTrue(WaitingAsksReplay.due(gated, after, now, unlocked = true))
    }

    /**
     * Another device's pass whose event carries no deadline (iOS #278's parity): the passed deadline is not kept, so a due
     * state known only from the clock ends, the replay is due, and the status is read for the next deadline.
     */
    @Test
    fun aCheckEventWithoutADeadlineEndsTheGate() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", now.minusSeconds(60).toString(), 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        val due = m.ownerCheck.value!!
        assertEquals("due only by the clock", OwnerCheckState.OK, due.state)
        assertTrue(due.gated(now))
        val readsBefore = ops.reads
        ops.status = OwnerCheckStatus("ok", "2026-10-07T11:00:00Z", 86_400, hold = true)
        m.onEvent(event("sync.event", """{"kind":"owner_check"}"""))
        val passed = m.ownerCheck.value!!
        assertFalse("the pass ends the gate", passed.gated(now))
        assertTrue(WaitingAsksReplay.due(due, passed, now, unlocked = true))
        settle { m.ownerCheck.value?.deadline == Instant.parse("2026-10-07T11:00:00Z") }
        assertTrue("the status is read again", ops.reads > readsBefore)
        assertEquals(Instant.parse("2026-10-07T11:00:00Z"), m.ownerCheck.value!!.deadline)
        assertEquals("the read is not a pass", passed.passes, m.ownerCheck.value!!.passes)
    }

    /** A status read sent before a check that passed, answered after it, does not bring the held state back (Fable's L4). */
    @Test
    fun aHeldStatusSentBeforeAPassIsDropped() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true, failures = 2) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        ops.statusGate = gate
        val stale = this.async(Dispatchers.Default) { m.refreshOwnerCheck() }
        awaitReads(ops, 2)
        ops.statusGate = null
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
        val passed = m.ownerCheck.value!!
        gate.complete(Unit) // the held read answers now, after the pass
        stale.await()
        assertEquals("the pass stands", passed, m.ownerCheck.value)
        assertFalse(m.ownerCheck.value!!.gated(now))
        // A read sent after the pass is taken as it is.
        m.refreshOwnerCheck()
        assertEquals(OwnerCheckState.HELD, m.ownerCheck.value!!.state)
    }

    /** The check right after a cancelled recovery is an unlock's own too: its pass never counts (the replay is not due). */
    @Test
    fun theCheckAfterACancelledRecoveryNeverCounts() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        val gated = m.ownerCheck.value!!
        m.afterCancelledRecovery("975310", "pw")
        assertEquals("it checked", 1, ops.checks.size)
        assertEquals(OwnerCheckState.OK, m.ownerCheck.value!!.state)
        assertEquals("the cancel-recovery unlock's own pass", gated.passes, m.ownerCheck.value!!.passes)
        assertFalse(WaitingAsksReplay.due(gated, m.ownerCheck.value, now, unlocked = true))
    }

    /** Another device's pass counts as a pass for a status read sent before it: its held answer is dropped. */
    @Test
    fun aHeldStatusSentBeforeAnotherDevicesPassIsDropped() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        ops.statusGate = gate
        val stale = this.async(Dispatchers.Default) { m.refreshOwnerCheck() }
        awaitReads(ops, 2)
        ops.statusGate = null
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T12:00:00Z"}"""))
        val passed = m.ownerCheck.value!!
        gate.complete(Unit)
        stale.await()
        assertEquals("the pass stands", passed, m.ownerCheck.value)
    }

    /**
     * (ANDROID-PLAN 0.1.134) The lift is told before its value is published (the value seen then is still the gated one),
     * once, at the pass's own time on the vault's clock: the end of a hold, and of a due state the clock alone began. A
     * value that was not gated tells nothing.
     */
    @Test
    fun theLiftIsToldBeforeItsValueAtThePassTime() = runTest {
        val lifts = mutableListOf<Pair<Instant?, Boolean>>()
        lateinit var m: OwnerCheckManager
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        m = OwnerCheckManager(
            this, ops = { ops }, load = { null }, persist = {}, onPassed = {}, now = { now },
            onLift = { at -> lifts += at to (m.ownerCheck.value?.gated(now) == true) },
        )
        m.refreshOwnerCheck()
        assertTrue(lifts.isEmpty())
        val passAt = now.minusSeconds(30)
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T12:00:00Z"}""", ts = passAt))
        assertEquals(listOf<Pair<Instant?, Boolean>>(passAt to true), lifts)
        // Not gated before: nothing.
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-08T12:00:00Z"}""", ts = now))
        assertEquals(1, lifts.size)
        // A due state the clock alone began (the deadline passed, the value still OK), ended by a pass.
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-06T11:30:00Z"}""", ts = now.minusSeconds(3600)))
        assertEquals(1, lifts.size)
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T13:00:00Z"}""", ts = now.minusSeconds(5)))
        assertEquals(now.minusSeconds(5) to true, lifts.last())
        assertEquals(2, lifts.size)
    }

    /**
     * A due state ended by a status read (a pass on another device whose event this phone did not see): the lift is the
     * value's deadline less its interval (the last pass the vault counts), not an earlier pass this process knew.
     */
    @Test
    fun aLiftLearntFromAStatusReadIsAtTheDeadlineLessTheInterval() = runTest {
        val lifts = mutableListOf<Instant?>()
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T00:00:00Z", 86_400) }
        val m = OwnerCheckManager(
            this, ops = { ops }, load = { null }, persist = {}, onPassed = {}, now = { now },
            onLift = { at -> lifts += at },
        )
        m.refreshOwnerCheck()
        // A pass known in this process, two days before.
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T00:00:00Z"}""", ts = now.minusSeconds(172_800)))
        ops.status = OwnerCheckStatus("due", "2026-10-06T11:00:00Z", 86_400)
        m.refreshOwnerCheck()
        assertTrue(lifts.isEmpty())
        ops.status = OwnerCheckStatus("ok", "2026-10-07T11:59:00Z", 86_400)
        m.refreshOwnerCheck()
        assertEquals(listOf<Instant?>(Instant.parse("2026-10-06T11:59:00Z")), lifts)
        // Never earlier than a pass known: a deadline less its interval that falls before it gives that pass.
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T11:59:00Z"}""", ts = now.minusSeconds(30)))
        ops.status = OwnerCheckStatus("due", "2026-10-06T11:00:00Z", 86_400)
        m.refreshOwnerCheck()
        ops.status = OwnerCheckStatus("ok", "2026-10-07T11:00:00Z", 86_400)
        m.refreshOwnerCheck()
        assertEquals(now.minusSeconds(30), lifts.last())
    }

    /** This phone's own check ends a held state: no time of its own is told (the vault's clock, set by its answer, is). */
    @Test
    fun aPassHereIsToldWithoutATimeOfItsOwn() = runTest {
        val lifts = mutableListOf<Instant?>()
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = OwnerCheckManager(
            this, ops = { ops }, load = { null }, persist = {}, onPassed = {}, now = { now },
            onLift = { at -> lifts += at },
        )
        m.refreshOwnerCheck()
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
        assertEquals(listOf<Instant?>(null), lifts)
    }

    /** Only a held or due answer is dropped: an OK status sent before a pass and answered after it is taken. */
    @Test
    fun anOkStatusSentBeforeAPassIsTaken() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        ops.statusGate = gate
        val read = this.async(Dispatchers.Default) { m.refreshOwnerCheck() }
        awaitReads(ops, 2)
        ops.statusGate = null
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
        ops.status = OwnerCheckStatus("ok", "2026-10-07T13:00:00Z", 86_400, hold = true)
        gate.complete(Unit)
        read.await()
        assertEquals(Instant.parse("2026-10-07T13:00:00Z"), m.ownerCheck.value!!.deadline)
    }

    /**
     * An `owner_check_required` answer and a `vault.held` from before the latest pass (the vault's clock: the answer's `ts`,
     * the pass's deadline less its interval, or another device's event `ts`) change no state; later ones do.
     */
    @Test
    fun aRefusalOrAHeldFromBeforeThePassChangesNothing() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null)) // passed at 12:00, the vault's clock
        ops.status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true)
        m.onRequired(now.minusSeconds(60))
        assertEquals("a refusal from before the pass", OwnerCheckState.OK, m.ownerCheck.value!!.state)
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = now.minusSeconds(60)))
        assertEquals("a vault.held from before the pass", OwnerCheckState.OK, m.ownerCheck.value!!.state)
        // Another device's later pass moves the time on: a held sent between the two is over too.
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T13:00:00Z"}""", ts = now.plusSeconds(3600)))
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = now.plusSeconds(1800)))
        assertEquals("a vault.held from before the later pass", OwnerCheckState.OK, m.ownerCheck.value!!.state)
        m.onRequired(now.plusSeconds(7200))
        assertTrue("a refusal from after it gates", m.ownerCheck.value!!.state != OwnerCheckState.OK)
    }

    /** With no view yet, another device's pass is still recorded: a held status sent before it is dropped. */
    @Test
    fun anotherDevicesPassWithNoViewStillDropsAStaleRead() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        assertEquals(null, m.ownerCheck.value)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        ops.statusGate = gate
        val stale = this.async(Dispatchers.Default) { m.refreshOwnerCheck() }
        awaitReads(ops, 1)
        // The pass with no view starts a fresh read of its own: held at its gate until the stale one has ended.
        val fresh = kotlinx.coroutines.CompletableDeferred<Unit>()
        ops.statusGate = fresh
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T12:00:00Z"}"""))
        runCurrent() // the launched read starts (and goes to its gate)
        awaitReads(ops, 2)
        gate.complete(Unit)
        stale.await()
        assertTrue("not held: ${m.ownerCheck.value}", m.ownerCheck.value?.gated(now) != true)
        // The fresh read says the state after the pass.
        ops.status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true)
        fresh.complete(Unit)
        settle { m.ownerCheck.value?.state == OwnerCheckState.OK }
        assertEquals(OwnerCheckState.OK, m.ownerCheck.value!!.state)
    }

    /** A `vault.held` or a refusal whose ts is the pass's own time is kept (only one strictly before it is over). */
    @Test
    fun aHeldAtThePassTimeIsKept() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null)) // passed at 12:00 = now
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = now))
        assertEquals(OwnerCheckState.HELD, m.ownerCheck.value!!.state)
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
        m.onRequired(now)
        assertTrue(m.ownerCheck.value!!.state != OwnerCheckState.OK)
    }

    /** A pass that lands between the held's first look and its publish: the look under the lock drops it. */
    @Test
    fun aPassDuringTheHeldsHandlingWins() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = false) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        // The held looks at the clock (its hold-off) after its first test: another device's later pass arrives then.
        nowHook = {
            m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T13:00:00Z"}""", ts = now.plusSeconds(60)))
        }
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = now))
        assertEquals(null, nowHook)
        assertEquals(OwnerCheckState.OK, m.ownerCheck.value!!.state)
        assertEquals(Instant.parse("2026-10-07T13:00:00Z"), m.ownerCheck.value!!.deadline)
    }

    /** The latest pass only moves forward (an older one arriving late does not move it back), and a wipe forgets it. */
    @Test
    fun thePassTimeOnlyMovesForwardAndAWipeForgetsIt() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T13:00:00Z"}""", ts = now.plusSeconds(3600)))
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T13:00:00Z"}""", ts = now.plusSeconds(60)))
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = now.plusSeconds(1800)))
        assertEquals("before the newer pass", OwnerCheckState.OK, m.ownerCheck.value!!.state)
        m.clear()
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = now.minusSeconds(60)))
        assertEquals("after a wipe it is taken", OwnerCheckState.HELD, m.ownerCheck.value!!.state)
    }

    /**
     * On a vault with a time scale (staging, 24), the pass time is the deadline less the interval as it runs there
     * (ceil(86,400 / 24) = 3,600 s): a held just before it is over, one at it is kept.
     */
    @Test
    fun thePassTimeUsesTheScaledInterval() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true)
            timeScale = 24
        }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null)) // deadline 2026-10-07T12:00Z: passed at 11:00Z
        val passAt = Instant.parse("2026-10-07T11:00:00Z")
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = passAt.minusSeconds(1)))
        assertEquals("just before the pass", OwnerCheckState.OK, m.ownerCheck.value!!.state)
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = passAt))
        assertEquals("at the pass", OwnerCheckState.HELD, m.ownerCheck.value!!.state)
    }

    /** With no view, another device's pass records its time too, not only the count: a held from before it is over. */
    @Test
    fun anotherDevicesPassWithNoViewRecordsItsTime() = runTest {
        val m = manager(Ops())
        assertEquals(null, m.ownerCheck.value)
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T13:00:00Z"}""", ts = now.plusSeconds(3600)))
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = now.plusSeconds(1800)))
        assertEquals("dropped: no view made from it", null, m.ownerCheck.value)
    }

    /** Another device's pass with nothing to show yet: the status is read, so the view does not stay empty. */
    @Test
    fun anotherDevicesPassWithNoViewReadsTheStatus() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T13:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T13:00:00Z"}""", ts = now.plusSeconds(60)))
        settle { m.ownerCheck.value != null }
        assertEquals(1, ops.reads)
        assertEquals(OwnerCheckState.OK, m.ownerCheck.value!!.state)
        // With a view and a deadline, nothing more is read: the event's deadline is taken, and no read starts after it.
        m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T14:00:00Z"}""", ts = now.plusSeconds(120)))
        assertEquals(Instant.parse("2026-10-07T14:00:00Z"), m.ownerCheck.value!!.deadline)
        repeat(20) {
            runCurrent()
            Thread.sleep(SETTLE_MS)
        }
        assertEquals("no read launched", 1, ops.reads)
    }

    /**
     * A `time_scale` this build does not accept is real time: the pass time is the deadline less the whole interval, so a
     * held the reported scale would have put before the pass is kept (a scale larger than the vault's is the only input
     * that moves the pass later, the fail-open way).
     */
    @Test
    fun aScaleTheBuildDoesNotAcceptIsRealTime() = runTest {
        accepts = { it == 1L }
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true)
            timeScale = 24
        }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null)) // deadline 2026-10-07T12:00Z
        // At 24 the pass would be 11:00Z; at real time it is 2026-10-06T12:00Z, so this held is after it.
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = Instant.parse("2026-10-07T10:59:59Z")))
        assertEquals(OwnerCheckState.HELD, m.ownerCheck.value!!.state)
    }

    /** Out of range is real time too, whatever the build accepts. */
    @Test
    fun aScaleOutOfRangeIsRealTime() {
        assertEquals(1L, TimeScales.inForce(com.vettid.core.crypto.altchan.TimeScale.MAX + 1) { true })
        assertEquals(1L, TimeScales.inForce(0) { true })
        assertEquals(1L, TimeScales.inForce(null) { true })
        assertEquals(24L, TimeScales.inForce(24) { true })
        assertEquals(1L, TimeScales.inForce(24) { it == 1L })
    }

    /** The upper bound itself is in range. */
    @Test
    fun theLargestScaleIsInRange() {
        val max = com.vettid.core.crypto.altchan.TimeScale.MAX
        assertEquals(max, TimeScales.inForce(max) { true })
    }

    /** The build's rule decides; with no rule to ask (no channel yet) nothing is accepted. */
    @Test
    fun withNoRuleNothingIsAccepted() {
        assertFalse(TimeScales.accepted(1, null))
        assertFalse(TimeScales.accepted(24, null))
        assertTrue(TimeScales.accepted(24) { it == 24L })
        assertFalse(TimeScales.accepted(24) { it == 1L })
        assertEquals(1L, TimeScales.inForce(24) { TimeScales.accepted(it, null) })
    }

    /** A status read begun before a clear does not set the scale after it: a pass after the clear is at real time. */
    @Test
    fun aReadBegunBeforeAClearDoesNotSetTheScale() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true)
            timeScale = 24
        }
        val m = manager(ops)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        ops.statusGate = gate
        val read = this.async(Dispatchers.Default) { runCatching { m.refreshOwnerCheck() } }
        awaitReads(ops, 1)
        ops.statusGate = null
        m.clear()
        gate.complete(Unit)
        read.await()
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null)) // deadline 2026-10-07T12:00Z
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = Instant.parse("2026-10-07T10:59:59Z")))
        assertEquals("after the real-time pass: taken", OwnerCheckState.HELD, m.ownerCheck.value?.state)
    }

    /**
     * A check sent before this process read a status (after a cancelled recovery): the presence check time takes the
     * stored scale, never the deadline less the unscaled 24 hours.
     */
    @Test
    fun thePresenceCheckTimeBeforeAnyStatusReadTakesTheStoredScale() = runTest {
        storedScale = 24
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true)
            presentRecorded = false
        }
        val m = manager(ops)
        m.afterCancelledRecovery("975310", "pw") // no status read first: deadline 2026-10-07T12:00Z, the check at 11:00Z
        assertTrue(m.presenceUnrecorded.value)
        ops.presentRecordedAt = "2026-10-07T10:59:59Z"
        m.refreshOwnerCheck() // this status has no time_scale: the stored one still counts
        assertTrue(m.presenceUnrecorded.value)
        ops.presentRecordedAt = "2026-10-07T11:00:00Z"
        m.refreshOwnerCheck()
        assertFalse(m.presenceUnrecorded.value)
    }

    /**
     * The stored scale is for the presence time only: a pass before any status read is at real time, so a held dated
     * between the deadline less 24 hours and the deadline less one hour (the pass at 24) is still taken.
     */
    @Test
    fun theStoredScaleStaysOutOfThePassTime() = runTest {
        storedScale = 24
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.afterCancelledRecovery("975310", "pw") // no status read: deadline 2026-10-07T12:00Z, the pass at real time 2026-10-06T12:00Z
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = Instant.parse("2026-10-07T10:00:00Z")))
        assertEquals(OwnerCheckState.HELD, m.ownerCheck.value?.state)
    }

    /** The tenth failed check locks the vault (seen from a fresh status): that lock forgets the scale too. */
    @Test
    fun theTenthFailureForgetsTheScale() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true, failures = 9)
            timeScale = 24
        }
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkError = "bad_password"
        ops.status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true, failures = 10)
        // Real time: the status read after the failure runs on the IO dispatcher and wins against its 5 s timeout. It says
        // 24, then the lock it shows resets the scale.
        assertEquals(OwnerCheckOutcome.BadPassword(0), withContext(Dispatchers.Default) { m.check("975310", "wrong", null) })
        assertTrue(m.lockedByOwnerCheck.value)
        ops.checkError = null
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null)) // deadline 2026-10-07T12:00Z, no status read
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = Instant.parse("2026-10-07T10:59:59Z")))
        assertEquals(OwnerCheckState.HELD, m.ownerCheck.value?.state)
    }

    /** A lock found by a probe (no status answer, the member API says locked) forgets the scale too. */
    @Test
    fun aLockFoundByAProbeForgetsTheScale() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true)
            timeScale = 24
        }
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.failStatus = true
        memberState = "locked"
        assertEquals(VaultProbe.LOCKED, m.probeVault(lockCauseKnown = false))
        ops.failStatus = false
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null)) // deadline 2026-10-07T12:00Z, no status read
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = Instant.parse("2026-10-07T10:59:59Z")))
        assertEquals(OwnerCheckState.HELD, m.ownerCheck.value?.state)
    }

    /**
     * (#19721 b′) A probe's lock is applied only while [OwnerCheckRepository.probeVault]'s `mayLock` says so, asked when the
     * member API's `locked` is in: over an unlock under way it is no lock (its result is newer), and nothing is marked.
     */
    @Test
    fun aProbesLockIsAppliedOnlyWhileItMayBe() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-08T11:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.failStatus = true
        memberState = "locked"
        var asked = 0
        assertEquals(VaultProbe.NO_ANSWER, m.probeVault(lockCauseKnown = false) { asked++; false })
        assertEquals(1, asked)
        assertEquals(false, m.lockedByOwnerCheck.value)
        assertEquals(0, foundLocked)
        assertEquals(VaultProbe.LOCKED, m.probeVault(lockCauseKnown = false) { true })
        assertEquals(1, foundLocked)
    }

    /** A lock forgets the scale (a `vault.locking` or the member's own): a check sent before the next read is at real time. */
    @Test
    fun aLockForgetsTheScale() = runTest {
        for (lockHere in listOf(false, true)) {
            val ops = Ops().apply {
                status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true)
                timeScale = 24
            }
            val m = manager(ops)
            m.refreshOwnerCheck()
            if (lockHere) m.onLockedHere() else m.onEvent(event("vault.locking", """{"reason":"member"}"""))
            assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
            m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = Instant.parse("2026-10-07T10:59:59Z")))
            assertEquals("lockHere=$lockHere", OwnerCheckState.HELD, m.ownerCheck.value?.state)
        }
    }

    /** A clear (a wipe, a deleted vault) forgets the scale: a pass after it, before any status read, is at real time. */
    @Test
    fun aClearForgetsTheScale() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true)
            timeScale = 24
        }
        val m = manager(ops)
        m.refreshOwnerCheck()
        m.clear()
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null)) // no status read: deadline 2026-10-07T12:00Z
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = Instant.parse("2026-10-07T10:59:59Z")))
        assertEquals("after the real-time pass: taken", OwnerCheckState.HELD, m.ownerCheck.value?.state)
    }

    /**
     * The presence notice's check time is the deadline less the interval as it runs (staging, 24: an hour), as for the
     * pass: a presence recorded before that check, inside the unscaled 24 hours, does not end the notice.
     */
    @Test
    fun thePresenceCheckTimeUsesTheScaledInterval() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true)
            presentRecorded = false
            timeScale = 24
        }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw")) // deadline 2026-10-07T12:00Z: the check at 11:00Z
        assertTrue(m.presenceUnrecorded.value)
        ops.presentRecordedAt = "2026-10-07T10:59:59Z" // after the unscaled time, before the check
        m.refreshOwnerCheck()
        assertTrue(m.presenceUnrecorded.value)
        ops.presentRecordedAt = "2026-10-07T11:00:00Z"
        m.refreshOwnerCheck()
        assertFalse(m.presenceUnrecorded.value)
    }

    /**
     * A held dropped under the lock (a pass landed while it was handled) does not move the newest held's `ts`: after a
     * restart (the pass time is not kept, the `ts` is), an earlier held that is not over is still taken.
     */
    @Test
    fun aDroppedHeldLeavesTheHeldTs() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = false) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        nowHook = {
            m.onEvent(event("sync.event", """{"kind":"owner_check","deadline":"2026-10-07T13:00:00Z"}""", ts = now.plusSeconds(60)))
        }
        m.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = now))
        assertEquals(null, nowHook)
        assertEquals(OwnerCheckState.OK, m.ownerCheck.value!!.state)
        m.refreshOwnerCheck() // saved again, with the held's ts as it stands
        val again = manager(ops)
        again.onEvent(event("vault.held", """{"waiting":{"messages":1}}""", ts = now.minusSeconds(30)))
        assertTrue("taken (due: no hold)", again.ownerCheck.value!!.state != OwnerCheckState.OK)
    }

    /** A dropped stale read leaves the count alone: the pass's full count stands, not the failures read before it. */
    @Test
    fun aDroppedReadLeavesTheEstimate() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, hold = true, failures = 8) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        ops.statusGate = gate
        val stale = this.async(Dispatchers.Default) { m.refreshOwnerCheck() }
        awaitReads(ops, 2)
        ops.statusGate = null
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw", null))
        gate.complete(Unit)
        stale.await()
        ops.statusDown = true
        ops.checkThrows = com.vettid.core.vault.VaultStateException("no response")
        assertEquals("10 from the pass, less this one", 9, (m.check("975310", "pw") as OwnerCheckOutcome.Failed).estimatedLeft)
    }

    /** The check an unlock sends before the app opens (§3.6.5): its `credential_required` raises the full screen too. */
    @Test
    fun theUnlocksCheckRaisesCredentialRequired() = runTest {
        CredentialRequired.clear()
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 0)
            checkError = "credential_required"
            checkErrorBody = JsonObject(mapOf("reason" to JsonPrimitive("no_holder")))
        }
        val m = manager(ops)
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertEquals(false, CredentialRequired.state.value?.resends)
        CredentialRequired.clear()
    }

    @Test
    fun failedChecksSayWhichEntryAndHowManyAreLeft() = runTest {
        // The status read fails after each check, so the counts are the app's own (failures + 1), not a race with the read.
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 0)
            statusDownAfterCheck = true
        }
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkError = "bad_pin"
        assertEquals(OwnerCheckOutcome.BadPin(9, estimated = true), m.check("111111", "pw"))
        ops.checkError = "bad_password"
        assertEquals(OwnerCheckOutcome.BadPassword(8, estimated = true), m.check("975310", "nope"))
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
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertEquals(OwnerCheckOutcome.Passed, m.unlockCheckOutcome.value)
        assertEquals(listOf<Any?>("975310", "pw", null, null), ops.checks.single())
        m.consumeUnlockCheckOutcome()
        assertNull(m.unlockCheckOutcome.value)

        // Not past the deadline: no check.
        ops.status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400)
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertEquals(1, ops.checks.size)
    }

    /** (ANDROID-PLAN 0.1.93) A wrong password or a backoff after the unlock keeps the PIN for the held screen, once. */
    @Test
    fun theUnlockKeepsThePinForAPasswordOnlyRetry() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 1) }
        val m = manager(ops)
        ops.checkError = "bad_password"
        m.afterUnlock("975310", "nope", m.foregroundGeneration)
        assertTrue(m.unlockCheckOutcome.value is OwnerCheckOutcome.BadPassword)
        assertEquals("975310", m.takeUnlockPin())
        assertNull(m.takeUnlockPin())
        ops.checkError = "backoff"
        ops.checkErrorBody = JsonObject(mapOf("retry_after" to JsonPrimitive(300)))
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertEquals("975310", m.takeUnlockPin())
        // Not in front: never kept. A pass: nothing to keep.
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        m.setForeground(false)
        assertNull(m.takeUnlockPin())
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertNull(m.takeUnlockPin())
        m.setForeground(true)
        ops.checkError = null
        ops.checkErrorBody = null
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertNull(m.takeUnlockPin())
        // Nothing due: no check, nothing kept.
        ops.status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400)
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertNull(m.takeUnlockPin())
    }

    /** The kept PIN does not outlive a lock of any reason, a wipe, another unlock or a check. */
    @Test
    fun theKeptPinEndsWithALockAWipeOrAnotherCheck() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 1) }
        val m = manager(ops)
        ops.checkError = "bad_password"
        m.afterUnlock("975310", "nope", m.foregroundGeneration)
        m.onEvent(event("vault.locking", """{"reason":"idle"}"""))
        assertNull(m.takeUnlockPin())
        m.afterUnlock("975310", "nope", m.foregroundGeneration)
        m.onLockedHere()
        assertNull(m.takeUnlockPin())
        m.afterUnlock("975310", "nope", m.foregroundGeneration)
        m.clear()
        assertNull(m.takeUnlockPin())
        m.afterUnlock("975310", "nope", m.foregroundGeneration)
        m.check("975310", "pw")
        assertNull(m.takeUnlockPin())
        // The process in the background: not kept, and the front is told.
        m.setForeground(false)
        assertEquals(false, m.inFront.value)
        m.afterUnlock("975310", "nope", m.foregroundGeneration)
        assertNull(m.takeUnlockPin())
    }

    /** A departure since the generation read before the unlock request keeps no PIN, though the app is in front again. */
    @Test
    fun anOutAndBackSinceTheGenerationKeepsNoPin() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 1) }
        val m = manager(ops)
        ops.checkError = "bad_password"
        val gen = m.foregroundGeneration
        m.setForeground(false)
        m.setForeground(true)
        m.afterUnlock("975310", "nope", gen)
        assertNull(m.takeUnlockPin())
        m.afterUnlock("975310", "nope", m.foregroundGeneration)
        assertEquals("975310", m.takeUnlockPin())
    }

    /** (ANDROID-PLAN 0.1.95) With no count read since the app started the estimate is 0, whatever the saved state says. */
    @Test
    fun withNoCountReadSinceStartTheEstimateIsZero() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 0)
            statusDownAfterCheck = true
        }
        manager(ops).refreshOwnerCheck() // saves the state
        val restarted = manager(ops) // a new process: the saved view says 10 left, but no count was read
        ops.checkError = "bad_password"
        assertEquals(OwnerCheckOutcome.BadPassword(0, estimated = true), restarted.check("975310", "nope"))
        assertFalse(restarted.lockedByOwnerCheck.value) // an estimate never marks the lock
    }

    /** A check sent that got no answer may have counted: the estimate falls by one and the outcome carries it. */
    @Test
    fun anUnansweredCheckSpendsOneOfTheEstimate() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 7) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkThrows = com.vettid.core.vault.VaultStateException("no response")
        val r = m.check("975310", "pw") as OwnerCheckOutcome.Failed
        assertEquals(FailureKind.NO_RESPONSE, r.kind)
        assertEquals(2, r.estimatedLeft) // 3 left as read, less the check that may have counted
        ops.checkThrows = null
        ops.checkError = "bad_password"
        ops.statusDownAfterCheck = true
        // Answered failures spend one each from the same estimate: 2, then 1.
        assertEquals(OwnerCheckOutcome.BadPassword(1, estimated = true), m.check("975310", "nope"))
    }

    /** The sink is process-wide: each test starts without a manager listening (the one it makes registers itself). */
    @org.junit.After
    fun detachSink() {
        CheckSink.counter = null
    }

    private fun tagged(e: java.io.IOException, type: String) = e.also { it.addSuppressed(com.vettid.core.vault.OpTypeTag(type)) }

    /** A counted operation whose send failed (a relay refusal, a timeout, an I/O error) may have counted: one try each. */
    @Test
    fun aCountedOperationWithATransportFailureSpendsOne() = runTest {
        val ops = opsAt(5)
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkError = "bad_password"
        val relay = runCatching { vaultGuard { throw tagged(com.vettid.core.relay.RelayException(503, "unavailable", ""), "pin.change") } }
        // The failure keeps its class and kind.
        assertEquals(FailureKind.NETWORK, (relay.exceptionOrNull() as VaultFailure).kind)
        runCatching { vaultGuard { throw tagged(java.io.IOException("socket timeout"), "vault.delete") } }
        assertEquals(OwnerCheckOutcome.BadPassword(2, estimated = true), m.check("975310", "nope")) // 5 - 2 - 1
        // Not counted: a message send, or an untagged failure.
        runCatching { vaultGuard { throw tagged(java.io.IOException("x"), "message.send") } }
        runCatching { vaultGuard { throw java.io.IOException("x") } }
        assertEquals(OwnerCheckOutcome.BadPassword(1, estimated = true), m.check("975310", "nope"))
    }

    private fun opsAt(failures: Int) = Ops().apply {
        status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = failures)
        statusDownAfterCheck = true
    }

    /** A return to the foreground re-reads the check (onOpened) but keeps the estimate; an unlock or a start resets it. */
    @Test
    fun aForegroundReturnKeepsTheEstimate() = runTest {
        val ops = opsAt(7)
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkError = "bad_password"
        assertEquals(OwnerCheckOutcome.BadPassword(2, estimated = true), m.check("975310", "nope"))
        m.onOpened(afterUnlock = false)
        advanceUntilIdle()
        assertEquals(OwnerCheckOutcome.BadPassword(1, estimated = true), m.check("975310", "nope"))
        m.onOpened() // an unlock: no count read since
        advanceUntilIdle()
        assertEquals(OwnerCheckOutcome.BadPassword(0, estimated = true), m.check("975310", "nope"))
    }

    /** The other operations that count as failed checks (§3.6.4) spend the same estimate, answered or not; others do not. */
    @Test
    fun otherOperationsThatCountSpendTheEstimate() = runTest {
        val ops = opsAt(5)
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkError = "bad_password"
        runCatching { vaultGuard { throw VaultOpException("pin.change", "bad_pin") } }
        runCatching { vaultGuard { throw VaultOpException("credential.password.change", "bad_password") } }
        // A wrong password that is not a failed check (an item operation) counts nothing here.
        runCatching { vaultGuard { throw VaultOpException("item.get", "bad_password") } }
        // 5 left as read, less two: this failed check leaves 2.
        assertEquals(OwnerCheckOutcome.BadPassword(2, estimated = true), m.check("975310", "nope"))
        // A message send (not a check) with no answer leaves the estimate alone, whatever its timing.
        runCatching { vaultGuard { throw VaultStateException("message.send: no response", "message.send") } }
        assertEquals(OwnerCheckOutcome.BadPassword(1, estimated = true), m.check("975310", "nope"))
        // A counted operation with no answer may have counted: exactly one try.
        runCatching { vaultGuard { throw VaultStateException("pin.change: no response", "pin.change") } }
        assertEquals(OwnerCheckOutcome.BadPassword(0, estimated = true), m.check("975310", "nope"))
    }

    /**
     * (ANDROID-PLAN 0.1.96, VAULT-MESSAGING 0.73.2) The count the unlock's own read gave survives the open that follows: the
     * unlock's check gets no answer with 6 left, and the next unanswered check says 4, not 0 ("probably locked").
     */
    @Test
    fun theUnlocksOwnCountSurvivesTheOpen() = runTest {
        val ops = opsAt(4)
        val m = manager(ops)
        ops.checkThrows = com.vettid.core.vault.VaultStateException("no response")
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertEquals(5, (m.unlockCheckOutcome.value as OwnerCheckOutcome.Failed).estimatedLeft)
        m.onOpened() // the open that ends this unlock
        advanceUntilIdle()
        assertEquals(4, (m.check("975310", "pw") as OwnerCheckOutcome.Failed).estimatedLeft)
    }

    /** A late `vault.locking` does not clear the kept count; the next unlock attempt does; an open with no read before it resets. */
    @Test
    fun theKeptCountIsClearedByTheNextUnlockAttemptOnly() = runTest {
        val ops = opsAt(4)
        val m = manager(ops)
        ops.checkThrows = com.vettid.core.vault.VaultStateException("no response")
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        m.onEvent(event("vault.locking", """{"reason":"idle"}"""))
        m.onOpened()
        advanceUntilIdle()
        assertEquals(4, (m.check("975310", "pw") as OwnerCheckOutcome.Failed).estimatedLeft)
        ops.statusDown = false // the read works this time: the flag is set, and only beginUnlock() clears it
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        m.beginUnlock() // the attempt that follows: its open has no count read
        m.onOpened()
        advanceUntilIdle()
        assertEquals(0, (m.check("975310", "pw") as OwnerCheckOutcome.Failed).estimatedLeft)
    }

    /** An unlock whose status read fails read no count: the open that follows keeps nothing (fail secure). */
    @Test
    fun anUnlockWhoseReadFailedKeepsNoCount() = runTest {
        val ops = opsAt(4).apply { statusDown = true }
        val m = manager(ops)
        ops.checkThrows = com.vettid.core.vault.VaultStateException("no response")
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        m.onOpened()
        advanceUntilIdle()
        assertEquals(0, (m.check("975310", "pw") as OwnerCheckOutcome.Failed).estimatedLeft)
    }

    /** (ANDROID-PLAN 0.1.97) After a `vault.locking` that is not the owner check's, a read never names the check as the cause. */
    @Test
    fun aProbeAfterAnotherKindOfLockNeverNamesTheOwnerCheck() = runTest {
        val ops = opsAt(1).apply { failStatus = true }
        val m = manager(ops)
        m.onEvent(event("vault.locking", """{"reason":"idle"}"""))
        memberState = "locked"
        assertEquals(VaultProbe.LOCKED, m.probeVault(lockCauseKnown = true)) // the watch's scheduled read assumes ten checks
        assertTrue(m.lockCauseUnknown.value)
    }

    /** The member's own lock, then an open: a later read that finds the vault locked by an estimate of 0 names the check. */
    @Test
    fun anOpenAfterAnotherKindOfLockLetsALaterProbeNameTheOwnerCheck() = runTest {
        val ops = opsAt(1).apply { failStatus = true }
        val m = manager(ops)
        m.onLockedHere()
        m.onOpened()
        memberState = "locked"
        assertEquals(VaultProbe.LOCKED, m.probeVault(lockCauseKnown = true))
        assertFalse(m.lockCauseUnknown.value)
    }

    /** (ANDROID-PLAN 0.1.96) The check after a cancelled recovery does not go out with the previous session's estimate. */
    @Test
    fun theCheckAfterACancelledRecoveryForgetsTheEstimate() = runTest {
        val ops = opsAt(2)
        val m = manager(ops)
        m.refreshOwnerCheck() // 8 left, as read in an earlier session
        ops.checkThrows = com.vettid.core.vault.VaultStateException("no response")
        m.afterCancelledRecovery("975310", "pw")
        assertEquals(0, (m.unlockCheckOutcome.value as OwnerCheckOutcome.Failed).estimatedLeft)
    }

    /** (ANDROID-PLAN 0.1.96) A `vault.locking` of any reason drops a probe that finishes after it, not only the member's own. */
    @Test
    fun aProbeThatFinishesAfterAnyLockingEventIsDropped() = runTest {
        val ops = opsAt(1).apply { failStatus = true }
        val m = manager(ops)
        memberState = "locked"
        memberGate = kotlinx.coroutines.CompletableDeferred()
        val late = this.async(Dispatchers.Default) { m.probeVault(lockCauseKnown = false) }
        Thread.sleep(200)
        m.onEvent(event("vault.locking", """{"reason":"update"}"""))
        memberGate!!.complete(Unit)
        assertEquals(VaultProbe.NO_ANSWER, late.await())
        assertFalse(m.lockedByOwnerCheck.value)
    }

    /**
     * (#19721 b′, Fable's race) The vault-gone probe's API read says `locked` (true when read: an unlock locks first) while an
     * unlock begins, is answered and ends; the owner check's epoch has not moved yet (it moves later, at the opening). The
     * gate's mark (the unlock results taken) has: the lock is not applied, and nothing is marked.
     */
    @Test
    fun aVaultGoneProbeDropsALockOverAnUnlockAnsweredMeanwhile() = runTest {
        val ops = opsAt(1).apply { failStatus = true }
        val m = manager(ops)
        val notices = LockNotices()
        val hold = UnlockNoticeHold()
        val gate = VaultGoneGate({ AppPhase.Unlocked }, { true }, { hold.inFlight() }, { notices.openings }, { m.screenProbing })
        memberState = "locked"
        memberGate = kotlinx.coroutines.CompletableDeferred()
        val mark = gate.begin()
        val late = this.async(Dispatchers.Default) { m.probeVault(lockCauseKnown = false) { gate.mayLock(mark) } }
        Thread.sleep(200)
        val ticket = hold.begin()
        notices.opened(java.time.Instant.parse("2026-10-07T12:00:00Z"))
        ticket.end()
        memberGate!!.complete(Unit)
        assertEquals(VaultProbe.NO_ANSWER, late.await())
        assertFalse(m.lockedByOwnerCheck.value)
        assertEquals(0, foundLocked)
    }

    /** A lock by the member during a read drops the read's result (the epoch moved). */
    @Test
    fun aProbeThatFinishesAfterTheMembersLockIsDropped() = runTest {
        val ops = opsAt(1).apply { failStatus = true }
        val m = manager(ops)
        memberState = "locked"
        memberGate = kotlinx.coroutines.CompletableDeferred()
        val late = this.async(Dispatchers.Default) { m.probeVault(lockCauseKnown = false) }
        Thread.sleep(200)
        m.onLockedHere()
        memberGate!!.complete(Unit)
        assertEquals(VaultProbe.NO_ANSWER, late.await())
        assertFalse(m.lockedByOwnerCheck.value)
    }

    /** The estimate is never kept in the view or on disk: only a count the vault gave. */
    @Test
    fun noEstimateIsSaved() = runTest {
        val ops = opsAt(2)
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkError = "bad_password"
        m.check("975310", "nope")
        assertEquals(2, m.ownerCheck.value!!.failures)
        assertEquals(2, manager(Ops()).ownerCheck.value!!.failures)
    }

    /** A read that finishes after `clear()` (or a lock, or an unlock) is dropped: it marks nothing. */
    @Test
    fun aLateProbeResultIsDropped() = runTest {
        val ops = opsAt(1).apply { failStatus = true }
        val m = manager(ops)
        memberState = "locked"
        memberGate = kotlinx.coroutines.CompletableDeferred()
        val late = this.async(Dispatchers.Default) { m.probeVault(lockCauseKnown = true) }
        Thread.sleep(200) // the relay read has failed and the API read waits
        m.clear()
        memberGate!!.complete(Unit)
        assertEquals(VaultProbe.NO_ANSWER, late.await())
        assertFalse(m.lockedByOwnerCheck.value)
        assertEquals(0, foundLocked)
    }

    /** A cause already known is not downgraded by a probe that finishes later. */
    @Test
    fun aKnownCauseIsNeverDowngraded() = runTest {
        val ops = Ops().apply { failStatus = true }
        val m = manager(ops)
        m.onEvent(event("vault.locking", """{"reason":"owner_check"}"""))
        memberState = "locked"
        assertEquals(VaultProbe.LOCKED, m.probeVault(lockCauseKnown = false))
        assertFalse(m.lockCauseUnknown.value)
    }

    /** `vault.status` answering means open; the API's `locked` marks the lock (cause per the caller); `unlocked` is no answer. */
    @Test
    fun theProbeTakesOnlyTheVaultsAnswerOrTheApisLocked() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 4) }
        val m = manager(ops)
        // Real time: the status read runs on the IO dispatcher and wins against the 5 s timeout.
        assertEquals(VaultProbe.OPEN, withContext(Dispatchers.Default) { m.probeVault(lockCauseKnown = true) })
        assertEquals(6, m.ownerCheck.value!!.checksLeft)
        assertFalse(m.lockedByOwnerCheck.value)
        ops.failStatus = true
        memberState = "unlocked"
        assertEquals(VaultProbe.NO_ANSWER, m.probeVault(lockCauseKnown = true))
        assertFalse(m.lockedByOwnerCheck.value)
        memberState = "locked"
        assertEquals(VaultProbe.LOCKED, m.probeVault(lockCauseKnown = false))
        assertTrue(m.lockedByOwnerCheck.value)
        assertTrue(m.lockCauseUnknown.value)
        assertEquals(1, foundLocked)
        // A `vault.locking` for ten wrong checks names the cause.
        m.onEvent(event("vault.locking", """{"reason":"owner_check"}"""))
        assertFalse(m.lockCauseUnknown.value)
    }

    /** The lock is marked from the estimate never: the status read fails, the count is failures + 1. */
    @Test
    fun anEstimatedCountNeverMarksTheLock() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 9)
            statusDownAfterCheck = true
        }
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkError = "bad_password"
        assertEquals(OwnerCheckOutcome.BadPassword(0, estimated = true), m.check("975310", "nope"))
        assertEquals(false, m.lockedByOwnerCheck.value)
    }

    /** The tenth failure marks the lock from a fresh `vault.status`, without waiting for `vault.locking`. */
    @Test
    fun aFreshCountOfZeroMarksTheLock() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 9) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkError = "bad_password"
        ops.status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 10)
        // Real time: the status read runs on the IO dispatcher and wins against the 5 s timeout.
        val r = withContext(Dispatchers.Default) { m.check("975310", "nope") }
        assertEquals(OwnerCheckOutcome.BadPassword(0), r)
        assertTrue(m.lockedByOwnerCheck.value)
    }

    /** The notices are the feed's unread `owner_check.*` items (one source: the feed, ANDROID-PLAN 0.1.23, 6). */
    @Test
    fun noticesAreTheOwnerCheckFeedItems() = runTest {
        val m = manager(Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T00:00:00Z", 86_400) })
        val feed = listOf(
            FeedItem("i1", 1, "owner_check.failed", "2026-10-06T10:00:00Z", "active", "high", ref = "pin"),
            FeedItem("i2", 2, "message.received", "2026-10-06T10:01:00Z", "active"),
            FeedItem("i3", 3, "owner_check.locked", "2026-10-06T10:02:00Z", "active", "urgent", ref = "10"),
            FeedItem("i4", 4, "owner_check.hold_changed", "2026-10-06T09:00:00Z", "read", "high", ref = "off"),
        )
        m.onFeed(feed)
        assertEquals(listOf("i3", "i1"), m.notices.value.map { it.itemId })
        assertTrue(m.notices.value.first().urgent)
        val i5 = FeedItem("i5", 5, "owner_check.hold_changed", "2026-10-06T11:00:00Z", "active", "high", ref = "on:expired")
        m.onFeed(feed + i5)
        assertEquals("i5", m.notices.value.first().itemId)
        // Read in the Notifications list: the banner closes.
        m.onFeed(feed.map { if (it.itemId == "i3") it.copy(status = "read") else it } + i5)
        assertEquals(listOf("i5", "i1"), m.notices.value.map { it.itemId })
        m.dismissNotices()
        assertTrue(m.notices.value.isEmpty())
        assertEquals(listOf("i5", "i1"), read)
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

    /** Release R (§11.14.5): `present_recorded: false` starts the presence notice; a later recorded presence ends it. */
    @Test
    fun thePresenceNoticeFollowsTheVault() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true)
            presentRecorded = false
        }
        val m = manager(ops)
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw"))
        assertTrue(m.presenceUnrecorded.value)
        // Kept across a restart.
        assertTrue(manager(Ops()).presenceUnrecorded.value)
        // A presence recorded before that check (its time: the deadline less the interval) does not end it.
        ops.presentRecordedAt = "2026-10-06T11:00:00Z"
        m.refreshOwnerCheck()
        assertTrue(m.presenceUnrecorded.value)
        // One recorded since does.
        ops.presentRecordedAt = "2026-10-06T12:01:00Z"
        m.refreshOwnerCheck()
        assertFalse(m.presenceUnrecorded.value)
        // So does a later answer that says it was recorded.
        ops.presentRecorded = false
        m.check("975310", "pw")
        assertTrue(m.presenceUnrecorded.value)
        ops.presentRecorded = true
        m.check("975310", "pw")
        assertFalse(m.presenceUnrecorded.value)
    }

    /** Before release R the answer has no `recovery`: nothing changes. */
    @Test
    fun noPresenceNoticeBeforeReleaseR() = runTest {
        val m = manager(Ops())
        m.check("975310", "pw")
        assertFalse(m.presenceUnrecorded.value)
    }

    /** ANDROID-PLAN "Other screens": after a cancel from the app the check goes at once; one that fails asks for the screen. */
    @Test
    fun theCheckAfterACancelledRecovery() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.afterCancelledRecovery("975310", "pw")
        assertEquals(1, ops.checks.size)
        assertFalse(m.checkAfterCancel.value)
        ops.checkError = "bad_password"
        m.afterCancelledRecovery("975310", "wrong")
        assertEquals(2, ops.checks.size)
        assertTrue(m.checkAfterCancel.value)
        assertTrue(m.unlockCheckOutcome.value is OwnerCheckOutcome.BadPassword)
        m.consumeCheckAfterCancel()
        assertFalse(m.checkAfterCancel.value)
    }

    /** Without the check's time no status can end the notice: an old present_recorded_at must not. */
    @Test
    fun thePresenceNoticeStaysWithoutTheChecksTime() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true)
            presentRecorded = false
            answerDeadline = null
            presentRecordedAt = "2026-10-01T12:00:00Z"
        }
        val m = manager(ops)
        m.check("975310", "pw")
        assertTrue(m.presenceUnrecorded.value)
        m.refreshOwnerCheck()
        assertTrue(m.presenceUnrecorded.value)
    }

    /** Every status read hands its recovery on (the clock of a counter that stays unavailable). */
    @Test
    fun everyStatusReadReportsTheRecovery() = runTest {
        val seen = mutableListOf<String?>()
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true)
            counter = "unavailable"
        }
        val m = OwnerCheckManager(
            this, ops = { ops }, load = { null }, persist = {}, onPassed = {}, now = { now },
            onRecoveryStatus = { r, _ -> seen += r?.counter },
        )
        m.refreshOwnerCheck()
        ops.counter = "ok"
        m.refreshOwnerCheck()
        assertEquals(listOf<String?>("unavailable", "ok"), seen)
    }

    /**
     * STAGING-TIME-SCALE S2 (VAULT-MESSAGING 0.43.0 §11.10.8): each status read hands its `time_scale` on before the
     * deadline it came with is taken in, so the early warning is reckoned with the scale of the vault that set it.
     */
    @Test
    fun everyStatusReadReportsItsTimeScaleBeforeItsDeadline() = runTest {
        val seen = mutableListOf<Pair<Long?, Instant?>>()
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-06T12:30:00Z", 86_400, hold = true)
            timeScale = 24
        }
        lateinit var m: OwnerCheckManager
        m = OwnerCheckManager(
            this, ops = { ops }, load = { null }, persist = {}, onPassed = {}, now = { now },
            onTimeScale = { t, _ -> seen += t to m.ownerCheck.value?.deadline },
        )
        m.refreshOwnerCheck()
        ops.timeScale = null
        m.refreshOwnerCheck()
        assertEquals(listOf<Pair<Long?, Instant?>>(24L to null, null to Instant.parse("2026-10-06T12:30:00Z")), seen)
    }

    /**
     * STAGING-TIME-SCALE S2: a staging phone updated while its vault was open does not know the scale; its status read
     * says 24, the scale is stored (a staging build accepts it), and the deadline an hour away (the interval at 24) is no
     * longer inside the warning, nor due on a return to the app. A production build never takes it: real time.
     */
    @Test
    fun theFirstStatusReadFillsTheScaleForTheEarlyWarning() = runTest {
        var stored: Long? = null
        val staging: (Long) -> Boolean = { it == 1L || it == 24L }
        val production: (Long) -> Boolean = { it == 1L }
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-06T13:00:00Z", 86_400, hold = true)
            timeScale = 24
        }
        val m = OwnerCheckManager(
            this, ops = { ops }, load = { null }, persist = {}, onPassed = {}, now = { now },
            onTimeScale = { t, _ -> TimeScales.fromStatus(stored, t, staging)?.let { stored = it } },
        )
        // Before the read: the scale counts as 1, and a deadline an hour away would warn at once.
        val before = OwnerCheckView(OwnerCheckState.OK, Instant.parse("2026-10-06T13:00:00Z"), 86_400, 0, true, null)
        assertTrue(before.warning(now, stored ?: 1))
        m.refreshOwnerCheck()
        assertEquals(24L, stored)
        val v = m.ownerCheck.value!!
        assertFalse(v.warning(now, stored ?: 1))
        assertFalse(v.gated(now) || v.warning(now, stored ?: 1))
        assertTrue(v.warning(now.plusSeconds(3_600 - 150), stored ?: 1))

        // The rule the descriptor is checked against: a production build stores nothing but 1.
        assertNull(TimeScales.fromStatus(null, 24, production))
        assertEquals(1L, TimeScales.fromStatus(null, 1, production))
    }

    /** `vault.status`'s `time_scale` (0.43.0): read when present, null from a release before the scale. */
    @Test
    fun theStatusCarriesItsTimeScale() {
        fun read(body: String) =
            VaultJson.decode(com.vettid.core.vault.VaultStatusInfo.serializer(), VaultJson.parseObject(body.toByteArray()))
        assertEquals(24L, read("""{"vault_id":"x","time_scale":24}""").timeScale)
        val without = read("""{"vault_id":"x"}""")
        assertNull(without.timeScale)
    }

    /** (0.84.0 §3.4) The lasting notice follows the latest `vault.status`: shown with `relay: "refused"`, gone without it. */
    @Test
    fun theRelayNoticeFollowsTheLatestStatus() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        assertFalse(m.relayRefused.value)
        ops.relayRefused = true
        m.refreshOwnerCheck()
        assertTrue(m.relayRefused.value)
        m.refreshOwnerCheck()
        assertTrue("it lasts while the field is present", m.relayRefused.value)
        ops.relayRefused = false
        m.refreshOwnerCheck()
        assertFalse(m.relayRefused.value)
    }

    /** A status without an owner check (an older vault) still carries `relay`; it is not kept across a restart. */
    @Test
    fun theRelayNoticeIsNotKeptOnThePhone() = runTest {
        val ops = Ops().apply { status = null }
        ops.relayRefused = true
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertTrue(m.relayRefused.value)
        assertFalse(manager(Ops()).relayRefused.value)
    }

    /** A locked app shows no relay notice: any lock (the member's, the vault's own, ten failures) and a wipe clear it. */
    @Test
    fun theRelayNoticeIsClearedAtLockAndWipe() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        ops.relayRefused = true
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertTrue(m.relayRefused.value)
        m.onLockedHere()
        assertFalse("the member's lock", m.relayRefused.value)

        m.refreshOwnerCheck()
        assertTrue(m.relayRefused.value)
        m.onEvent(event("vault.locking", """{"reason":"idle"}"""))
        assertFalse("the vault locked itself", m.relayRefused.value)

        m.refreshOwnerCheck()
        m.onEvent(event("vault.locking", """{"reason":"owner_check"}"""))
        assertFalse("ten failed checks", m.relayRefused.value)

        m.refreshOwnerCheck()
        assertTrue(m.relayRefused.value)
        m.clear()
        assertFalse("a wipe or sign-out", m.relayRefused.value)
    }

    /** A read that began before a lock, a vault.locking or a wipe and finishes after it never brings the notice back. */
    @Test
    fun aRelayReadThatFinishesAfterALockIsDropped() = runTest {
        val locks = listOf<Pair<String, (OwnerCheckManager) -> Unit>>(
            "the member's lock" to { it.onLockedHere() },
            "vault.locking" to { it.onEvent(event("vault.locking", """{"reason":"update"}""")) },
            "a wipe" to { it.clear() },
        )
        for ((name, lock) in locks) {
            val ops = Ops().apply {
                status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true)
                relayRefused = true
                statusGate = kotlinx.coroutines.CompletableDeferred()
            }
            val m = manager(ops)
            val late = this.async(Dispatchers.Default) { runCatching { m.refreshOwnerCheck() } }
            awaitReads(ops, 1)
            lock(m)
            ops.statusGate!!.complete(Unit)
            late.await()
            assertFalse("$name: the late read must not show the notice", m.relayRefused.value)
        }
    }

    /** An older read that finishes after a newer one does not overwrite it. */
    @Test
    fun anOlderRelayReadDoesNotOverwriteANewerOne() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true)
            relayRefused = true
            statusGate = kotlinx.coroutines.CompletableDeferred()
        }
        val m = manager(ops)
        val firstGate = ops.statusGate!!
        val old = this.async(Dispatchers.Default) { runCatching { m.refreshOwnerCheck() } }
        awaitReads(ops, 1)
        ops.statusGate = null
        ops.relayRefused = false
        m.refreshOwnerCheck()
        assertFalse(m.relayRefused.value)
        // The first read (which began first, and was answered "refused") finishes last.
        ops.relayRefused = true
        firstGate.complete(Unit)
        old.await()
        assertFalse(m.relayRefused.value)
    }

    /** A lock found by the probe (the member API says `locked`) clears the notice too. */
    @Test
    fun aLockFoundByTheProbeClearsTheRelayNotice() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        ops.relayRefused = true
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertTrue(m.relayRefused.value)
        ops.failStatus = true
        memberState = "locked"
        assertEquals(VaultProbe.LOCKED, m.probeVault(lockCauseKnown = false))
        assertFalse(m.relayRefused.value)
    }

    /** M2: the vault's one relay attempt comes after the unlock's read; a new `identity.rotation_failed` says it happened. */
    @Test
    fun aNewRotationFailedItemReadsTheStatusOnce() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertFalse(m.relayRefused.value)
        ops.relayRefused = true
        val before = ops.reads
        val item = FeedItem("r1", 1, "identity.rotation_failed", "2026-11-05T10:00:00Z", "active", "high", ref = "recovery")
        m.onFeed(listOf(item))
        settle { m.relayRefused.value }
        assertTrue(m.relayRefused.value)
        // The status that found it refused also schedules the one re-read of this unlock (60 s): let it run first.
        settle { ops.reads >= before + 2 }
        assertEquals(before + 2, ops.reads)
        val reads = ops.reads
        // The same item again (the feed list changed for another reason) does not read again; a read item is not new.
        // Deterministic: a read would be a coroutine launched in this scope by onFeed itself, before anything runs.
        val launched = coroutineContext.job.children.count()
        m.onFeed(listOf(item, FeedItem("m1", 2, "message.received", "2026-11-05T10:01:00Z", "active")))
        assertEquals("no read is launched for an item already seen", launched, coroutineContext.job.children.count())
        advanceUntilIdle()
        assertEquals(reads, ops.reads)
        // A wipe forgets the ids.
        m.clear()
        m.onFeed(listOf(item))
        settle { ops.reads > reads }
        assertTrue(ops.reads > reads)
    }

    /** M2: after an unlock's read found the relay refused, the status is read once more a minute later. */
    @Test
    fun anUnlockThatFoundTheRelayRefusedReadsAgainAMinuteLater() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        ops.relayRefused = true
        val m = manager(ops)
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertTrue(m.relayRefused.value)
        val reads = ops.reads
        ops.relayRefused = false
        advanceTimeBy(59_000)
        assertEquals(reads, ops.reads)
        advanceTimeBy(2_000)
        settle { !m.relayRefused.value }
        assertFalse(m.relayRefused.value)
        assertEquals(reads + 1, ops.reads)
        // Once only: it was not refused any more, so nothing is scheduled.
        advanceTimeBy(300_000)
        assertEquals(reads + 1, ops.reads)
    }

    /**
     * (As vettid-ios #221) Any status that finds the relay refused schedules the one re-read, not only the unlock's: here a
     * later read (a return to the foreground) finds it. Once until the next lock: a second refused status schedules nothing
     * more, and after a lock the next refused status schedules it again.
     */
    @Test
    fun aLaterStatusThatFindsTheRelayRefusedReadsAgainOnceUntilTheNextLock() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertFalse(m.relayRefused.value)
        val quiet = ops.reads
        advanceTimeBy(300_000)
        advanceUntilIdle()
        assertEquals("nothing is scheduled while the relay is not refused", quiet, ops.reads)

        ops.relayRefused = true
        m.refreshOwnerCheck()
        assertTrue(m.relayRefused.value)
        val reads = ops.reads
        advanceTimeBy(30_000)
        // A second refused status (applied without suspending: under runTest a read that waits on IO lets the virtual clock
        // run to the scheduled re-read) schedules no second re-read.
        m.noteRelay(m.beginStatusRead(), refused = true)
        assertEquals(reads, ops.reads)
        advanceTimeBy(31_000)
        settle { ops.reads >= reads + 1 }
        assertEquals("the one re-read, 60 s after the first refused status", reads + 1, ops.reads)
        advanceTimeBy(600_000)
        advanceUntilIdle()
        assertEquals("once only", reads + 1, ops.reads)

        m.onLockedHere()
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertTrue(m.relayRefused.value)
        val after = ops.reads
        advanceTimeBy(59_000)
        assertEquals(after, ops.reads)
        advanceTimeBy(2_000)
        settle { ops.reads > after }
        assertEquals("a lock lets the next unlock re-read again", after + 1, ops.reads)
    }

    /**
     * (As vettid-ios #221) Every status read of the open vault decides, not only the owner check's: the Settings overview's
     * read (`VaultManager.overview`) goes through [OwnerCheckManager.beginStatusRead] and [OwnerCheckManager.noteRelay]
     * under the same ordering: a lock since it began, or a newer read applied, drops it.
     */
    @Test
    fun aStatusReadElsewhereDecidesUnderTheSameOrdering() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        m.noteRelay(m.beginStatusRead(), refused = true)
        assertTrue("an overview read shows the notice", m.relayRefused.value)
        m.noteRelay(m.beginStatusRead(), refused = false)
        assertFalse("and takes it away", m.relayRefused.value)

        val older = m.beginStatusRead()
        ops.relayRefused = false
        m.refreshOwnerCheck() // a newer owner-check read, applied first
        m.noteRelay(older, refused = true)
        assertFalse("an older read does not overwrite a newer one", m.relayRefused.value)

        val beforeLock = m.beginStatusRead()
        m.onLockedHere()
        m.noteRelay(beforeLock, refused = true)
        assertFalse("a read that began before a lock is dropped", m.relayRefused.value)
    }

    /**
     * Review of #301, F1: a lock the manager never hears of (`VaultManager.refresh` finds the vault locked through the member
     * API after the `vault.locking` expired) leaves the re-read spent; the next unlock attempt ([OwnerCheckManager.unlockStarting])
     * begins the relay state again, so that unlock's refused read schedules its own re-read.
     */
    @Test
    fun aNewUnlockAttemptRereadsAgainAfterALockTheManagerMissed() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        ops.relayRefused = true
        val m = manager(ops)
        m.unlockStarting()
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        val first = ops.reads
        advanceTimeBy(61_000)
        settle { ops.reads > first }
        assertEquals("the first unlock's re-read", first + 1, ops.reads)
        // The vault locks itself; this manager is told nothing. The next unlock attempt starts.
        m.unlockStarting()
        assertFalse("nothing of the earlier unlock is shown", m.relayRefused.value)
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        assertTrue(m.relayRefused.value)
        val second = ops.reads
        advanceTimeBy(59_000)
        assertEquals(second, ops.reads)
        advanceTimeBy(2_000)
        settle { ops.reads > second }
        assertEquals("the second unlock's own re-read", second + 1, ops.reads)
    }

    /**
     * Review of #301, F3: the Settings overview's status read ([RelayReads.read]) decides the notice, stamped before it is
     * sent (a lock during the read drops its answer), and a read that got no answer applies nothing.
     */
    @Test
    fun theOverviewsReadDecidesStampedBeforeItIsSent() = runTest {
        val m = manager(Ops())
        val refused = com.vettid.core.vault.VaultStatusInfo("v", relay = JsonPrimitive("refused"))
        val absent = com.vettid.core.vault.VaultStatusInfo("v")
        assertEquals(refused, RelayReads.read(m, open = { true }) { refused })
        assertTrue("an overview read shows the notice", m.relayRefused.value)
        assertNull(RelayReads.read(m, open = { true }) { null })
        assertTrue("no answer changes nothing", m.relayRefused.value)
        RelayReads.read(m, open = { true }) { absent }
        assertFalse("and one without `relay` takes it away", m.relayRefused.value)
        RelayReads.read(m, open = { true }) {
            m.onLockedHere() // the member locks while the overview's read is out
            refused
        }
        assertFalse("an answer to a read sent before the lock is dropped", m.relayRefused.value)
    }

    /**
     * Review of #301, F2: the overview's answer is applied only while the vault is still open, even when its stamp was
     * taken after the member's lock cleared the notice and before the lock set the phase.
     */
    @Test
    fun theOverviewsAnswerIsNotAppliedOnceTheVaultIsNoLongerOpen() = runTest {
        val m = manager(Ops())
        var open = true
        RelayReads.read(m, open = { open }) {
            open = false // the phase became Locked while the read was out; no epoch moved since the stamp
            com.vettid.core.vault.VaultStatusInfo("v", relay = JsonPrimitive("refused"))
        }
        assertFalse(m.relayRefused.value)
    }

    /**
     * Review of #301, F4: a successful rotate ([RelayReads.keysReplaced], called by `VaultManager.rotate`) reads the status
     * again, so the notice goes; a read that fails there is not thrown.
     */
    @Test
    fun replacedKeysReadTheStatusAgain() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        ops.relayRefused = true
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertTrue(m.relayRefused.value)
        // Let the one re-read that refused status scheduled run first: under runTest, a read that waits on IO lets the
        // virtual clock run to it, which would count one read more here.
        val first = ops.reads
        advanceTimeBy(61_000)
        settle { ops.reads > first }
        ops.relayRefused = false
        val reads = ops.reads
        RelayReads.keysReplaced(m)
        assertEquals(reads + 1, ops.reads)
        assertFalse(m.relayRefused.value)
        ops.failStatus = true
        RelayReads.keysReplaced(m) // no exception
    }

    /**
     * Lead's review of #301: clearing the relay notice moves only the relay's own epoch. A probe in flight when an unlock
     * attempt starts ([OwnerCheckManager.unlockStarting]) still returns its answer, not NO_ANSWER (as on master); its relay
     * value is dropped.
     */
    @Test
    fun aProbeAcrossAnUnlockAttemptIsUnaffectedByTheRelayClear() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true)
            relayRefused = true
            statusGate = kotlinx.coroutines.CompletableDeferred()
        }
        val m = manager(ops)
        val probe = this.async(Dispatchers.Default) { m.probeVault(lockCauseKnown = false) }
        awaitReads(ops, 1)
        m.unlockStarting()
        ops.statusGate!!.complete(Unit)
        assertEquals(VaultProbe.OPEN, probe.await())
        assertFalse("the relay value of a read that spans the clear is dropped", m.relayRefused.value)
    }

    /**
     * Lead's review of #301: the `identity.rotation_failed` ids seen are forgotten at a lock, as on iOS, so the vault's
     * update of the same item at a later unlock reads the status again.
     */
    @Test
    fun theSameRotationFailedItemAfterALockReadsTheStatusAgain() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        val m = manager(ops)
        val item = FeedItem("r1", 1, "identity.rotation_failed", "2026-11-05T10:00:00Z", "active", "high", ref = "recovery")
        val r0 = ops.reads
        m.onFeed(listOf(item))
        settle { ops.reads > r0 }
        assertEquals(r0 + 1, ops.reads)
        m.onLockedHere()
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        val r1 = ops.reads
        val launched = coroutineContext.job.children.count()
        m.onFeed(listOf(item.copy(seq = 2)))
        assertEquals("the same item after a lock is new again", launched + 1, coroutineContext.job.children.count())
        settle { ops.reads > r1 }
        assertEquals(r1 + 1, ops.reads)
    }

    /** The re-read is cancelled by a lock. */
    @Test
    fun theRelayRereadIsCancelledAtLock() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400, hold = true) }
        ops.relayRefused = true
        val m = manager(ops)
        m.afterUnlock("975310", "pw", m.foregroundGeneration)
        val reads = ops.reads
        m.onLockedHere()
        advanceTimeBy(120_000)
        advanceUntilIdle()
        assertEquals(reads, ops.reads)
        assertFalse(m.relayRefused.value)
    }

    // --- Release L (VAULT-MESSAGING 0.29.0 §3.6.8): the idle lock ---

    private val deadline = "2026-10-06T11:00:00Z"
    private val idleAt = "2026-10-13T11:00:00Z"

    /** `idle_lock_at` from the status and from vault.held, kept on the phone; a passed check drops it (the deadline moved). */
    @Test
    fun theIdleLocksTimeIsReadKeptAndDroppedWhenACheckMovesTheDeadline() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", deadline, 86_400, hold = true, idleLockAt = idleAt) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        assertEquals(Instant.parse(idleAt), m.ownerCheck.value!!.idleLockAt)
        assertEquals("7 days past the deadline", 604_800L, m.ownerCheck.value!!.idleAfterSeconds)
        assertEquals("kept across a restart", Instant.parse(idleAt), manager(Ops()).ownerCheck.value!!.idleLockAt)
        val held = """{"deadline":"$deadline","waiting":{"messages":1},"idle_lock_at":"2026-10-12T11:00:00Z"}"""
        m.onEvent(event("vault.held", held, now.plusSeconds(1)))
        assertEquals(Instant.parse("2026-10-12T11:00:00Z"), m.ownerCheck.value!!.idleLockAt)
        m.onEvent(event("vault.held", """{"waiting":{"messages":2}}""", now.plusSeconds(2)))
        assertEquals("a vault.held without it keeps what was read", Instant.parse("2026-10-12T11:00:00Z"), m.ownerCheck.value!!.idleLockAt)
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw"))
        assertNull(m.ownerCheck.value!!.idleLockAt)
        // A vault before L: none.
        ops.status = OwnerCheckStatus("ok", deadline, 86_400)
        m.refreshOwnerCheck()
        assertNull(m.ownerCheck.value!!.idleLockAt)
    }

    /** Before the deadline: `idle_lock_at` − `deadline` is the value in force (later on it can move, §3.6.8). */
    private suspend fun readBeforeTheDeadline(ops: Ops, m: OwnerCheckManager) {
        ops.status = OwnerCheckStatus("ok", "2026-10-06T13:00:00Z", 86_400, idleLockAt = "2026-10-13T13:00:00Z")
        m.refreshOwnerCheck()
        ops.status = OwnerCheckStatus("held", deadline, 86_400, hold = true, idleLockAt = idleAt)
        m.refreshOwnerCheck()
    }

    /** `vault.locking{reason: "idle"}`: said until the vault opens again, with how long past the deadline it was. */
    @Test
    fun anIdleLockIsSaidUntilTheVaultOpensAgain() = runTest {
        val ops = Ops()
        val m = manager(ops)
        readBeforeTheDeadline(ops, m)
        assertNull(m.idleLocked.value)
        m.onEvent(event("vault.locking", """{"reason":"owner_check"}"""))
        assertNull("another reason", m.idleLocked.value)
        m.onEvent(event("vault.locking", """{"reason":"idle","unlock_id":"${"a".repeat(32)}"}"""))
        assertEquals(IdleLocked(604_800), m.idleLocked.value)
        assertEquals("kept across a restart", IdleLocked(604_800), manager(Ops()).idleLocked.value)
        m.onOpened()
        assertNull(m.idleLocked.value)
        assertNull(manager(Ops()).idleLocked.value)
    }

    /** Without the notice (the phone was off): a time past the `idle_lock_at` last read says why; before it, nothing. */
    @Test
    fun withoutTheNoticeTheTimeLastReadSaysWhy() = runTest {
        val ops = Ops()
        val m = manager(ops)
        readBeforeTheDeadline(ops, m)
        assertNull(m.idleLockedNow(Instant.parse(idleAt).minusSeconds(1)))
        assertEquals(IdleLocked(604_800), m.idleLockedNow(Instant.parse(idleAt)))
        ops.status = OwnerCheckStatus("held", deadline, 86_400, hold = true)
        m.refreshOwnerCheck()
        assertNull("a vault before L says nothing of it", m.idleLockedNow(Instant.parse(idleAt).plusSeconds(60)))
    }

    /**
     * Review of #191, D1: an unlock past the idle lock's time moves `idle_lock_at` to one hour after it; the notice
     * still says how long past the deadline the vault was set to wait, not that time plus the gap and the hour.
     */
    @Test
    fun theTimeSaidIsTheValueInForceNotAMovedIdleLockTime() = runTest {
        val ops = Ops()
        val m = manager(ops)
        readBeforeTheDeadline(ops, m)
        ops.status = OwnerCheckStatus("held", deadline, 86_400, hold = true, idleLockAt = "2026-10-14T15:17:00Z")
        m.refreshOwnerCheck()
        m.onEvent(event("vault.locking", """{"reason":"idle"}"""))
        assertEquals(IdleLocked(604_800), m.idleLocked.value)
        // Never read before the deadline: not known (the plain text), never a made-up figure.
        stored = null
        val fresh = manager(Ops().apply { status = OwnerCheckStatus("held", deadline, 86_400, idleLockAt = "2026-10-14T15:17:00Z") })
        fresh.refreshOwnerCheck()
        fresh.onEvent(event("vault.locking", """{"reason":"idle"}"""))
        assertEquals(IdleLocked(null), fresh.idleLocked.value)
    }

    /** Review of #191, D2: after the member's own lock on this phone, the unlock screen does not say it locked itself. */
    @Test
    fun aLockMadeHereIsNotSaidToBeAnIdleLock() = runTest {
        val ops = Ops()
        val m = manager(ops)
        readBeforeTheDeadline(ops, m)
        m.onLockedHere()
        assertNull(m.idleLockedNow(Instant.parse(idleAt).plusSeconds(60)))
        assertNull("kept across a restart", manager(Ops()).idleLockedNow(Instant.parse(idleAt).plusSeconds(60)))
        m.onOpened()
        assertEquals(IdleLocked(604_800), m.idleLockedNow(Instant.parse(idleAt).plusSeconds(60)))
        // The vault's own notice is said whoever locked before.
        m.onLockedHere()
        m.onEvent(event("vault.locking", """{"reason":"idle"}"""))
        assertEquals(IdleLocked(604_800), m.idleLockedNow(Instant.parse(idleAt).plusSeconds(60)))
    }

    /**
     * The setting: none for a vault before L; a shorter value in force at once, a longer one after the next check, and
     * the lowest set since the last check stays in force (owner decision 2026-10-23).
     */
    @Test
    fun theIdleLockSettingShorterAtOnceLongerAfterTheNextCheck() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("ok", "2026-10-07T11:00:00Z", 86_400) }
        val m = manager(ops)
        m.refreshIdleLockSetting()
        assertNull("a vault before L: no setting", m.idleLockSetting.value)
        ops.settings = mapOf(OwnerCheckManager.KEY_IDLE to JsonPrimitive(604_800))
        m.refreshIdleLockSetting()
        assertEquals(IdleLockSetting(604_800), m.idleLockSetting.value)
        m.setIdleLockAfter(2_592_000)
        assertEquals(mapOf(OwnerCheckManager.KEY_IDLE to JsonPrimitive(2_592_000L)), ops.sets.last())
        assertTrue("longer: after the next check", m.idleLockSetting.value!!.afterNextCheck)
        assertEquals(604_800L, m.idleLockSetting.value!!.inForceSeconds)
        m.setIdleLockAfter(3_600)
        assertEquals("shorter: at once", IdleLockSetting(3_600), m.idleLockSetting.value)
        m.setIdleLockAfter(1_209_600)
        assertEquals("the lowest since the last check stays", IdleLockSetting(1_209_600, 3_600), m.idleLockSetting.value)
        // Kept across a restart.
        val again = manager(ops)
        again.refreshIdleLockSetting()
        assertEquals(IdleLockSetting(1_209_600, 3_600), again.idleLockSetting.value)
        assertEquals(OwnerCheckOutcome.Passed, again.check("975310", "pw"))
        assertEquals("a passed check puts it in force", IdleLockSetting(1_209_600), again.idleLockSetting.value)
        // Only the choices; no "never".
        assertTrue(runCatching { again.setIdleLockAfter(0) }.isFailure)
        assertTrue(runCatching { again.setIdleLockAfter(3_599) }.isFailure)
        assertEquals(listOf(3_600L, 86_400L, 604_800L, 1_209_600L, 2_592_000L), IdleLockSetting.CHOICES_S)
    }

    /** Second review of #191, G1: once a check puts a longer value in force, the idle notice says that value. */
    @Test
    fun aCheckPuttingALongerValueInForceChangesTheTimeSaid() = runTest {
        val ops = Ops().apply { settings = mapOf(OwnerCheckManager.KEY_IDLE to JsonPrimitive(604_800)) }
        val m = manager(ops)
        readBeforeTheDeadline(ops, m)
        m.refreshIdleLockSetting()
        m.setIdleLockAfter(2_592_000)
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw"))
        m.onEvent(event("vault.locking", """{"reason":"idle"}"""))
        assertEquals(IdleLocked(2_592_000), m.idleLocked.value)
        // On a scaled vault (staging, 24): as it runs there.
        ops.timeScale = 24
        ops.status = OwnerCheckStatus("held", deadline, 86_400, hold = true)
        m.refreshOwnerCheck()
        m.setIdleLockAfter(1_209_600)
        assertEquals(OwnerCheckOutcome.Passed, m.check("975310", "pw"))
        m.onEvent(event("vault.locking", """{"reason":"idle"}"""))
        assertEquals(IdleLocked(50_400), m.idleLocked.value)
    }

    /** Keep the lowest since the last check, not the lowest of the values set: 7 d, then 30 d, then 14 d keeps 7 d. */
    @Test
    fun theLowestSinceTheLastCheckIsTheValueInForceNotTheLastSet() = runTest {
        val ops = Ops().apply {
            status = OwnerCheckStatus("ok", "2026-10-07T11:00:00Z", 86_400)
            settings = mapOf(OwnerCheckManager.KEY_IDLE to JsonPrimitive(604_800))
        }
        val m = manager(ops)
        m.refreshIdleLockSetting()
        m.setIdleLockAfter(2_592_000)
        m.setIdleLockAfter(1_209_600)
        assertEquals(IdleLockSetting(1_209_600, 604_800), m.idleLockSetting.value)
        assertTrue(m.idleLockSetting.value!!.afterNextCheck)
    }

    /**
     * Review of cc184f74, G1 after a restart: a longer value set, the app killed, a check passed before the setting was
     * read again; the vault then locks with the longer value, so the notice must not say the shorter one.
     */
    @Test
    fun aCheckAfterARestartWithALongerValueWaitingSaysTheTimeIsNotKnown() = runTest {
        val ops = Ops().apply { settings = mapOf(OwnerCheckManager.KEY_IDLE to JsonPrimitive(604_800)) }
        val m = manager(ops)
        readBeforeTheDeadline(ops, m)
        m.refreshIdleLockSetting()
        m.setIdleLockAfter(2_592_000)
        // The process ends; the next one does not open Settings before the check.
        val next = manager(ops)
        assertNull("the setting is not read in this process", next.idleLockSetting.value)
        assertEquals(OwnerCheckOutcome.Passed, next.check("975310", "pw"))
        next.onEvent(event("vault.locking", """{"reason":"idle"}"""))
        assertEquals("the approved time-not-known text", IdleLocked(null), next.idleLocked.value)
    }
}
