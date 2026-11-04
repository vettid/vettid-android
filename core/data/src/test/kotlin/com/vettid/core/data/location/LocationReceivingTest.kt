// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.location

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/**
 * Receiving a connection's location (ANDROID-PLAN §4 "Location", L1b; VAULT-MESSAGING §10.16): incoming shares from the list and
 * the events, `location.get`, live updates, expiry, stopping, asking, and the memory-only and lock-race rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("LargeClass", "TooManyFunctions")
class LocationReceivingTest {
    private val t0 = Instant.parse("2026-11-04T12:00:00Z")
    private var clock = t0
    private var unlocked = true
    private var gated = false
    private val relists = mutableListOf<Unit>()
    private var here: Fix? = null

    private class Ops : LocationOps {
        var answerGet: (String, Boolean) -> JsonObject = { _, _ -> throw VaultOpException("location.get", "not_found") }
        var getGate: CompletableDeferred<Unit>? = null
        val gets = mutableListOf<Pair<String, Boolean>>()
        var stopRefusal: String? = null
        val stops = mutableListOf<String>()
        var requestRefusal: VaultOpException? = null
        val requests = mutableListOf<Pair<String, String?>>()

        override suspend fun start(r: ShareRequest): JsonObject = obj("{}")

        override suspend fun update(sample: com.vettid.core.vault.LocationSample, ttl: Duration) = Unit

        override suspend fun stop(shareId: String): JsonObject {
            synchronized(this) { stops += shareId }
            stopRefusal?.let { throw VaultOpException("location.share.stop", it) }
            return obj("{}")
        }

        override suspend fun list(): JsonObject = obj("""{"outgoing":[],"incoming":[]}""")

        override suspend fun get(connectionId: String, history: Boolean): JsonObject {
            synchronized(this) { gets += connectionId to history }
            val answer = answerGet(connectionId, history)
            getGate?.await()
            return answer
        }

        override suspend fun request(connectionId: String, note: String?): JsonObject {
            synchronized(this) { requests += connectionId to note }
            requestRefusal?.let { throw it }
            return obj("""{"request_id":"r1"}""")
        }
    }

    private fun rig(scope: TestScope, ops: Ops = Ops(), services: Set<String> = emptySet()) = LocationReceiving(
        scope = scope.backgroundScope,
        ops = { ops },
        unlocked = { unlocked },
        gated = { gated },
        isService = { it in services },
        here = { here },
        now = { clock },
        relist = { relists += Unit },
    )

    private fun message(type: String, body: String) =
        VaultMessage(Inner(id = Ulid.new(), type = type, ts = clock, body = body.toByteArray()))

    private fun position(lat: Double = 52.52, lon: Double = 13.405, at: Instant = t0, extra: String = "") =
        """{"lat":$lat,"lon":$lon,"accuracy_m":1000,"at":"$at"$extra}"""

    private fun entry(id: String = "s1", c: String = "c1", expires: Instant = t0.plusSeconds(3600), last: String? = null, extra: String = "") =
        """{"share_id":"$id","connection_id":"$c","mode":"continuous","precision":"approximate","interval_seconds":60,"history":false,"started_at":"$t0","expires_at":"$expires"${last?.let { ""","last":$it""" } ?: ""}$extra}"""

    private fun list(vararg entries: String) = obj("""{"outgoing":[],"incoming":[${entries.joinToString(",")}]}""")

    private fun LocationReceiving.take(vararg entries: String) = applyList(list(*entries), begin())

    private fun update(share: String = "s1", conn: String = "c1", lat: Double = 52.52, at: Instant = clock, extra: String = "") =
        message("location.update", """{"connection_id":"$conn","share_id":"$share","lat":$lat,"lon":13.405,"accuracy_m":50,"at":"$at"$extra}""")

    private fun event(kind: String, direction: String = "in", id: String = "s1", conn: String = "c1", extra: String = "") =
        message("location.event", """{"event":"$kind","direction":"$direction","connection_id":"$conn","share_id":"$id"$extra}""")

    // --- the list and the events ---

    @Test
    fun theListGivesTheIncomingSharesWithTheirLatestPosition() {
        val r = rig(TestScope())
        r.take(entry("s1", "c1", last = position(extra = ""","received_at":"$t0"""")), entry("s2", "c2"))
        assertEquals(setOf("s1", "s2"), r.incoming.value.map { it.shareId }.toSet())
        assertEquals(52.52, r.incoming.value.first { it.shareId == "s1" }.last!!.lat, 0.0)
        assertNull(r.incoming.value.first { it.shareId == "s2" }.last)
    }

    @Test
    fun anEntryThatDoesNotReadMakesTheWholeListUnreadable() {
        val r = rig(TestScope())
        r.take(entry("s1"))
        // history is a string, not a boolean: nothing of the list is taken (the old state stays).
        r.take(entry("s2"), """{"share_id":"s3","connection_id":"c3","mode":"continuous","precision":"approximate","interval_seconds":60,"history":"false","expires_at":"${t0.plusSeconds(60)}"}""")
        assertEquals(listOf("s1"), r.incoming.value.map { it.shareId })
        // an interval out of the vault's bounds
        r.take(entry("s2", extra = ""","x":1""").replace(""""interval_seconds":60""", """"interval_seconds":5"""))
        assertEquals(listOf("s1"), r.incoming.value.map { it.shareId })
        // a share lasting more than 7 days
        r.take(entry("s4", expires = t0.plusSeconds(8 * 24 * 3600L)))
        assertEquals(listOf("s1"), r.incoming.value.map { it.shareId })
    }

    @Test
    fun anExpiredEntryIsLeftOutAndAPositionOutOfRangeIsUnreadable() {
        val r = rig(TestScope())
        r.take(entry("s1", expires = t0.minusSeconds(1)), entry("s2"))
        assertEquals(listOf("s2"), r.incoming.value.map { it.shareId })
        // lat 91, heading 360, a string number: each makes the entry (the list) unreadable
        listOf(position(lat = 91.0), position(extra = ""","heading_deg":360"""), position(extra = ""","speed_mps":"3"""")).forEach { bad ->
            r.take(entry("s9", last = bad))
            assertEquals(listOf("s2"), r.incoming.value.map { it.shareId })
        }
    }

    @Test
    fun startedAddsAndReplacesTheConnectionsShareAndStoppedEndsIt() {
        val r = rig(TestScope())
        r.take(entry("s1", "c1"))
        val ev = event("started", id = "s2", extra = ""","mode":"once","precision":"city","expires_at":"${t0.plusSeconds(900)}"""")
        r.onEvent(ev)
        // a new share from the connection replaces the old; the list is read for the rest
        assertEquals(listOf("s2"), r.incoming.value.map { it.shareId })
        assertTrue(r.incoming.value.single().once)
        assertEquals(1, relists.size)
        r.onEvent(event("stopped", id = "s2"))
        assertTrue(r.incoming.value.isEmpty())
        // the direction out is the sending side's: ignored here
        r.take(entry("s1", "c1"))
        r.onEvent(event("stopped", direction = "out", id = "s1"))
        assertEquals(1, r.incoming.value.size)
    }

    @Test
    fun theFeedItemOfASharedLocationReadsTheListForAShareNotKnown() {
        val r = rig(TestScope())
        val item = message("feed.event", """{"item_id":"i1","seq":1,"kind":"location.shared","at":"$t0","status":"active","connection_id":"c1","ref":"s1"}""")
        r.onEvent(item)
        assertEquals(1, relists.size)
        r.take(entry("s1"))
        r.onEvent(item)
        assertEquals(1, relists.size) // known now
    }

    @Test
    fun aShareEndsAtItsExpiryOnTheAppsSideToo() {
        val scope = TestScope()
        val r = rig(scope)
        r.take(entry("s1", expires = t0.plusSeconds(60)))
        assertEquals(1, r.incoming.value.size)
        clock = t0.plusSeconds(60)
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertTrue(r.incoming.value.isEmpty())
    }

    // --- live positions ---

    @Test
    fun aLiveUpdateIsTakenOnlyForAKnownShareOfTheSameConnection() {
        val r = rig(TestScope())
        r.take(entry("s1", "c1"))
        r.onEvent(update(share = "s1", conn = "c2")) // another connection's name on our share
        r.onEvent(update(share = "nope", conn = "c1")) // a share not known
        assertNull(r.incoming.value.single().last)
        r.onEvent(update(lat = 10.0))
        val p = r.incoming.value.single().last!!
        assertEquals(10.0, p.lat, 0.0)
        assertEquals(clock, p.receivedAt)
        // an older fix does not replace a newer one
        r.onEvent(update(lat = 20.0, at = t0.minusSeconds(30)))
        assertEquals(10.0, r.incoming.value.single().last!!.lat, 0.0)
        // a malformed one is dropped
        r.onEvent(update(lat = 95.0))
        assertEquals(10.0, r.incoming.value.single().last!!.lat, 0.0)
    }

    @Test
    fun theTrailIsKeptOnlyWhenTheSharerAllowsIt() {
        val r = rig(TestScope())
        r.take(entry("s1", "c1"), entry("s2", "c2", extra = "").replace(""""history":false""", """"history":true"""))
        r.onEvent(update("s1", "c1"))
        r.onEvent(update("s2", "c2"))
        r.onEvent(update("s2", "c2", lat = 53.0))
        assertEquals(0, r.incoming.value.first { it.shareId == "s1" }.trailPoints.size)
        assertEquals(2, r.incoming.value.first { it.shareId == "s2" }.trailPoints.size)
    }

    @Test
    fun aSnapshotIsKeptWithTheShare() {
        val r = rig(TestScope())
        r.take(entry("s1", "c1"))
        r.onEvent(event("snapshot", extra = ""","points":[${position()},${position(lat = 53.0)}]"""))
        assertEquals(2, r.incoming.value.single().snapshot.size)
        // one point that does not read: the whole event is ignored
        r.onEvent(event("snapshot", extra = ""","points":[${position()},{"lat":"x"}]"""))
        assertEquals(2, r.incoming.value.single().snapshot.size)
    }

    // --- location.get ---

    @Test
    fun openReadsTheLatestPositionTheTrailAndTheSnapshot() = runBlocking {
        val ops = Ops()
        val r = rig(TestScope(), ops)
        r.take(entry("s1", "c1", extra = "").replace(""""history":false""", """"history":true"""))
        ops.answerGet = { _, _ ->
            obj("""{"share_id":"s1","mode":"continuous","precision":"approximate","expires_at":"${t0.plusSeconds(3600)}","last":${position(extra = ""","received_at":"$t0"""")},"history":[${position()},${position(lat = 53.0)}],"snapshot":[${position(lat = 54.0)}]}""")
        }
        r.open("c1")
        val s = r.incoming.value.single()
        assertEquals("c1" to true, ops.gets.single()) // the trail is asked for because the share allows it
        assertNotNull(s.last)
        assertEquals(2, s.trailPoints.size)
        assertEquals(1, s.snapshot.size)
    }

    @Test
    fun getIsReadStrictly() = runBlocking {
        val ops = Ops()
        val r = rig(TestScope(), ops)
        r.take(entry("s1", "c1"))
        ops.answerGet = { _, _ -> obj("""{"share_id":"s1","mode":"continuous","precision":"approximate","expires_at":"${t0.plusSeconds(3600)}","last":{"lat":1,"lon":"2","at":"$t0"}}""") }
        try {
            r.open("c1")
            fail("expected a failure")
        } catch (e: VaultFailure) {
            assertEquals("unreadable_result", e.code)
        }
        assertNull(r.incoming.value.single().last)
    }

    @Test
    fun getNotFoundEndsTheShareHere() = runBlocking {
        val r = rig(TestScope())
        r.take(entry("s1", "c1"))
        try {
            r.open("c1")
            fail("expected a failure")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_FOUND, e.kind)
        }
        assertTrue(r.incoming.value.isEmpty())
    }

    @Test
    fun aGetAcrossALockWritesNothing() = runBlocking {
        val ops = Ops()
        val r = rig(TestScope(), ops)
        r.take(entry("s1", "c1"))
        ops.answerGet = { _, _ -> obj("""{"share_id":"s1","mode":"continuous","precision":"approximate","expires_at":"${t0.plusSeconds(3600)}","last":${position()}}""") }
        val gate = CompletableDeferred<Unit>()
        ops.getGate = gate
        val read = async(kotlinx.coroutines.Dispatchers.Default) { r.open("c1") }
        while (synchronized(ops) { ops.gets.isEmpty() }) kotlinx.coroutines.delay(5)
        r.clear() // a lock (or a hold) while the answer is on its way
        gate.complete(Unit)
        read.await()
        assertTrue(r.incoming.value.isEmpty())
    }

    @Test
    fun aListAcrossALockOrAHoldWritesNothing() {
        val r = rig(TestScope())
        val token = r.begin()
        r.clear()
        r.applyList(list(entry("s1")), token)
        assertTrue(r.incoming.value.isEmpty())
        // a read begun after the lock is taken
        r.take(entry("s1"))
        assertEquals(1, r.incoming.value.size)
    }

    @Test
    fun aListThatBeganBeforeAStopCannotBringTheShareBack() = runBlocking {
        val r = rig(TestScope())
        r.take(entry("s1"))
        val token = r.begin()
        r.stopReceiving("s1")
        r.applyList(list(entry("s1")), token)
        assertTrue(r.incoming.value.isEmpty())
    }

    @Test
    fun nothingIsReadOrTakenWhileLockedOrWhileTheCheckIsDueOrHeld() = runBlocking {
        val r = rig(TestScope())
        r.take(entry("s1"))
        gated = true
        r.onEvent(update(lat = 10.0))
        r.onEvent(event("stopped"))
        r.take(entry("s2", "c2"))
        assertEquals(listOf("s1"), r.incoming.value.map { it.shareId })
        assertNull(r.incoming.value.single().last)
        try {
            r.open("c1")
            fail("expected a failure")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
        gated = false
        unlocked = false
        r.onEvent(update(lat = 10.0))
        assertNull(r.incoming.value.single().last)
    }

    @Test
    fun clearDropsEverythingReceived() {
        val r = rig(TestScope())
        r.take(entry("s1", last = position()))
        r.onEvent(update())
        assertNotNull(r.incoming.value.single().last)
        r.clear()
        assertTrue(r.incoming.value.isEmpty())
    }

    // --- stop receiving ---

    @Test
    fun stopReceivingEndsTheShareAndIsAllowedWhileTheCheckIsDue() = runBlocking {
        val ops = Ops()
        val r = rig(TestScope(), ops)
        r.take(entry("s1"))
        gated = true // §3.6.3: stopping is allowed while due or held
        r.stopReceiving("s1")
        assertEquals(listOf("s1"), ops.stops)
        assertTrue(r.incoming.value.isEmpty())
    }

    @Test
    fun aStopTheVaultKnowsNothingOfStillEndsItAndAFailureKeepsTheShare() = runBlocking {
        val ops = Ops()
        val r = rig(TestScope(), ops)
        r.take(entry("s1"))
        ops.stopRefusal = "internal"
        try {
            r.stopReceiving("s1")
            fail("expected a failure")
        } catch (_: VaultFailure) {
            // kept
        }
        assertEquals(1, r.incoming.value.size)
        ops.stopRefusal = "not_found"
        r.stopReceiving("s1")
        assertTrue(r.incoming.value.isEmpty())
        unlocked = false
        try {
            r.stopReceiving("s1")
            fail("a locked vault takes no stop")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
    }

    // --- ask ---

    @Test
    fun askSendsTheNoteAndASecondAskWithinTenMinutesSaysHowLongToWait() = runBlocking {
        val ops = Ops()
        val r = rig(TestScope(), ops)
        assertEquals(AskResult.Sent, r.ask("c1", "Where are you?"))
        assertEquals("c1" to "Where are you?", ops.requests.single())
        ops.requestRefusal = VaultOpException("location.request", "limit")
        clock = clock.plus(Duration.ofMinutes(3))
        assertEquals(AskResult.Limited(7), r.ask("c1", null))
        clock = clock.plus(Duration.ofSeconds(30)) // 3.5 minutes since: 6.5 left, rounded up
        assertEquals(AskResult.Limited(7), r.ask("c1", null))
        // an ask this phone did not make: the whole allowance
        assertEquals(AskResult.Limited(10), r.ask("c2", null))
    }

    @Test
    fun askRefusesAServiceANoteOver256BytesAndAClosedVault() = runBlocking {
        val ops = Ops()
        val r = rig(TestScope(), ops, services = setOf("svc"))
        fun kind(block: suspend () -> Unit): Pair<FailureKind, String?> = try {
            runBlocking { block() }
            error("expected a failure")
        } catch (e: VaultFailure) {
            e.kind to e.code
        }
        assertEquals(FailureKind.SERVICE_FORBIDDEN, kind { r.ask("svc", null) }.first)
        assertEquals(FailureKind.OTHER to "bad_request", kind { r.ask("c1", "x".repeat(257)) })
        assertEquals(AskResult.Sent, r.ask("c1", "x".repeat(256)))
        gated = true
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, kind { r.ask("c1", null) }.first)
        assertEquals(1, ops.requests.size) // nothing was sent for the refused ones
    }

    // --- distance and direction ---

    @Test
    fun theOffsetFromHereNeedsThePhonesOwnKnownPlaceAndDoesNotAsk() = runBlocking {
        val r = rig(TestScope())
        r.take(entry("s1", "c1", last = position(lat = 52.0, lon = 13.0)))
        assertNull(r.offsetFromHere("c1")) // the phone does not know where it is (or the member has not allowed location)
        here = Fix(51.0, 13.0, 10.0, null, null, null, t0)
        val o = r.offsetFromHere("c1")!!
        assertEquals(111_000.0, o.meters, 1_000.0)
        assertEquals(0.0, o.bearingDeg, 1.0) // due north
        assertNull(r.offsetFromHere("nobody"))
    }
}
