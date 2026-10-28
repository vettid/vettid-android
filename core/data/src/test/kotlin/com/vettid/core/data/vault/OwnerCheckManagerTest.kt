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
        /** (Release R) The answer's `recovery.present_recorded`, and the status's `recovery.present_recorded_at`. */
        var presentRecorded: Boolean? = null
        var presentRecordedAt: String? = null
        var counter: String = "ok"
        var answerDeadline: String? = "2026-10-07T12:00:00Z"
        val checks = mutableListOf<List<Any?>>()
        val sets = mutableListOf<Map<String, JsonElement>>()

        override suspend fun status() = status

        override suspend fun check(pin: String, password: String, hold: Boolean?, holdOffUntil: String?): OwnerCheckPassed {
            checks += listOf(pin, password, hold, holdOffUntil)
            checkError?.let { throw VaultOpException("vault.owner-check", it, body = checkErrorBody) }
            return OwnerCheckPassed(
                answerDeadline, 86_400, hold = hold ?: true, holdOffUntil = holdOffUntil,
                recovery = presentRecorded?.let { com.vettid.core.vault.OwnerCheckRecovery("armed", it) },
            )
        }

        override suspend fun statusAndRecovery() =
            status to com.vettid.core.vault.RecoveryStatus("armed", presentRecordedAt = presentRecordedAt, counter = counter)

        /** (0.43.0) The status's `time_scale`; null: absent. */
        var timeScale: Long? = null

        override suspend fun statusRead() = statusAndRecovery().let { (o, r) -> StatusRead(o, r, timeScale) }

        /** What `settings.get` lists (a vault before L lists no `idle_lock.after_seconds`). */
        var settings: Map<String, JsonElement> = emptyMap()

        override suspend fun settingsGet() = Settings(7, JsonObject(settings))

        override suspend fun settingsSet(version: Long, set: Map<String, JsonElement>) {
            sets += set
            settings = settings + set
        }
    }

    private val read = mutableListOf<String>()

    private fun TestScope.manager(ops: Ops) = OwnerCheckManager(
        this,
        ops = { ops },
        load = { stored },
        persist = { stored = it },
        onPassed = { passedCalls++ },
        readNotices = { read += it },
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
        m.afterUnlock("975310", "pw")
        assertEquals(false, CredentialRequired.state.value?.resends)
        CredentialRequired.clear()
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

    /** (ANDROID-PLAN 0.1.93) The app opens after a pass or when nothing is due; not after a wrong password or a backoff. */
    @Test
    fun theUnlockHoldsTheOpenOnAWrongPasswordOrABackoff() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 1) }
        val m = manager(ops)
        ops.checkError = "bad_password"
        assertEquals(false, m.afterUnlock("975310", "nope"))
        assertTrue(m.unlockCheckOutcome.value is OwnerCheckOutcome.BadPassword)
        ops.checkError = "backoff"
        ops.checkErrorBody = JsonObject(mapOf("retry_after" to JsonPrimitive(300)))
        assertEquals(false, m.afterUnlock("975310", "pw"))
        assertEquals(OwnerCheckOutcome.Backoff(300), m.unlockCheckOutcome.value)
        ops.checkError = null
        ops.checkErrorBody = null
        assertEquals(true, m.afterUnlock("975310", "pw"))
        // Nothing due: no check, and the app opens.
        ops.status = OwnerCheckStatus("ok", "2026-10-07T12:00:00Z", 86_400)
        assertEquals(true, m.afterUnlock("975310", "pw"))
    }

    /** The tenth failed check locks the vault without waiting for `vault.locking` (ANDROID-PLAN 0.1.93). */
    @Test
    fun theTenthFailureMarksTheLockWithoutTheNotice() = runTest {
        val ops = Ops().apply { status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 9) }
        val m = manager(ops)
        m.refreshOwnerCheck()
        ops.checkError = "bad_password"
        ops.status = OwnerCheckStatus("held", "2026-10-06T11:00:00Z", 86_400, failures = 10)
        assertEquals(OwnerCheckOutcome.BadPassword(0), m.check("975310", "nope"))
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
