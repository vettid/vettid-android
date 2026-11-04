// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.location

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.LocationSample
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * The sending side of Location (ANDROID-PLAN §4 "Location", L1a; VAULT-MESSAGING §10.16) through [LocationSharing] with a fake
 * vault, fake platform positions and a manual clock: cadence, the optional members, the 5-minute rule, a once share's wait, the
 * app's own timer, stopping, the lock, the daily check, and taking up again after an unlock or a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationSharingTest {
    private val t0 = Instant.parse("2026-11-03T12:00:00Z")
    private var clock = t0
    private var unlocked = true
    private var due = false
    private var device: String? = "dev-this"

    private class Sent(val body: JsonObject, val ttl: Duration)

    private inner class Ops : LocationOps {
        val starts = mutableListOf<ShareRequest>()
        val updates = mutableListOf<Sent>()
        val stops = mutableListOf<String>()
        var lists = 0
        private var n = 0

        /** The answer to `location.share.list`. */
        var outgoing: String = "[]"
        var listRefusal: String? = null
        var stopRefusal: String? = null

        override suspend fun start(r: ShareRequest): JsonObject {
            synchronized(this) { starts += r }
            val seconds = if (r.once) 900L else r.durationSeconds!!
            return obj("""{"share_id":"s${++n}","expires_at":"${t0.plusSeconds(seconds)}"}""")
        }

        override suspend fun update(sample: LocationSample, ttl: Duration) {
            synchronized(this) { updates += Sent(sample.body(), ttl) }
        }

        override suspend fun stop(shareId: String): JsonObject {
            synchronized(this) { stops += shareId }
            stopRefusal?.let { throw VaultOpException("location.share.stop", it) }
            return obj("{}")
        }

        override suspend fun list(): JsonObject {
            synchronized(this) { lists++ }
            listRefusal?.let { throw VaultOpException("location.share.list", it) }
            return obj("""{"outgoing":$outgoing,"incoming":[]}""")
        }
    }

    private class Provider : PositionProvider {
        val requests = mutableListOf<PositionRequest>()
        val fixes = MutableSharedFlow<Fix>()
        var live = 0

        override fun fixes(request: PositionRequest): Flow<Fix> = flow {
            synchronized(this@Provider) { requests += request }
            live++
            try {
                fixes.collect { emit(it) }
            } finally {
                live--
            }
        }
    }

    private class Platform(override val positions: Provider) : LocationPlatform {
        var starts = 0
        var stops = 0
        var refuse = false

        override fun startService(): Boolean {
            starts++
            return !refuse
        }

        override fun stopService() {
            stops++
        }
    }

    private class MemStore(var saved: List<StoredShare> = emptyList()) : ShareStore {
        override fun load(): List<StoredShare> = saved

        override fun save(shares: List<StoredShare>) {
            saved = shares
        }
    }

    private inner class Rig(val scope: TestScope, val ops: Ops = Ops(), val store: MemStore = MemStore(), attach: Boolean = true) {
        val provider = Provider()
        val platform = Platform(provider)
        val sharing = LocationSharing(
            scope.backgroundScope,
            ops = { ops },
            store = store,
            unlocked = { unlocked },
            due = { due },
            deviceId = { device },
            now = { clock },
            io = StandardTestDispatcher(scope.testScheduler),
            watchEvery = Duration.ofSeconds(5),
            retryDelays = listOf(Duration.ofSeconds(5)),
        ).also { if (attach) it.attach(platform) }

        /** Time passes: the clock and the test scheduler together. */
        fun elapse(d: Duration) {
            clock = clock.plus(d)
            scope.advanceTimeBy(d.toMillis())
            scope.runCurrent()
            settle()
        }

        /** The vault calls run on the IO dispatcher (vaultGuard): let them finish. */
        fun settle() {
            // The manager runs in the test's background scope, which advanceUntilIdle does not wait for: runCurrent does.
            repeat(TRIES) {
                scope.runCurrent()
                Thread.sleep(SLEEP_MS)
            }
            scope.runCurrent()
        }

        fun fix(accuracy: Double? = 20.0, age: Duration = Duration.ZERO, lat: Double = 52.52, lon: Double = 13.405) =
            Fix(lat, lon, accuracy, 34.0, 1.5, 90.0, clock.minus(age))

        suspend fun emit(f: Fix) {
            provider.fixes.emit(f)
            settle()
        }

        suspend fun begin(
            timing: ShareTiming = ShareTiming.ForAWhile(),
            precision: SharePrecision = SharePrecision.APPROXIMATE,
            id: String = "c1",
        ): OutgoingShare {
            val s = sharing.start(ShareRequest(id, timing, precision))
            settle()
            return s
        }
    }

    private fun TestScope.rig(ops: Ops = Ops(), store: MemStore = MemStore(), attach: Boolean = true) = Rig(this, ops, store, attach)

    private fun message(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = clock, body = body.toByteArray()))

    private fun listed(
        id: String,
        device: String,
        mode: String = "continuous",
        interval: Int? = 60,
        expires: Instant = t0.plusSeconds(3_600),
        sent: String? = null,
        precision: String = "approximate",
    ): String {
        val every = interval?.let { """"interval_seconds":$it,""" } ?: ""
        val last = sent?.let { ""","last_sent_at":"$it"""" } ?: ""
        return """{"share_id":"$id","connection_id":"c-$id","mode":"$mode","precision":"$precision",$every"history":false,"device_id":"$device","started_at":"$t0","expires_at":"$expires"$last}"""
    }

    // --- starting, cadence ---

    @Test
    fun startingSendsTheShareAndStartsTheServiceAndThePositions() = runTest {
        val r = rig()
        val share = r.begin(ShareTiming.ForAWhile(ShareLength.HOURS_8, ShareInterval.MINUTES_5), SharePrecision.CITY, "c9")
        assertEquals("s1", share.shareId)
        assertEquals(t0.plusSeconds(8 * 3_600), share.expiresAt)
        assertEquals(ShareRequest("c9", ShareTiming.ForAWhile(ShareLength.HOURS_8, ShareInterval.MINUTES_5), SharePrecision.CITY), r.ops.starts.single())
        assertEquals(1, r.platform.starts)
        assertEquals(listOf(PositionRequest(300_000, highAccuracy = false)), r.provider.requests)
        assertEquals(listOf(ServiceState.Target("c9", share.expiresAt)), r.sharing.serviceState.value.targets)
        assertEquals(share, r.sharing.outgoing.value.single())
        assertEquals(listOf(StoredShare("s1", "c9", share.expiresAt.toEpochMilli())), r.store.saved)
    }

    @Test
    fun theSheetsChoicesAreWithinTheVaultsBounds() = runTest {
        val r = rig()
        val once = ShareRequest("c1", ShareTiming.Once)
        assertTrue(once.withinBounds())
        assertNull(once.durationSeconds)
        assertNull(once.intervalSeconds)
        // Every choice of the sheet is within the vault's bounds (5 minutes to 7 days, 10 seconds to 1 hour).
        for (l in ShareLength.entries) for (i in ShareInterval.entries) assertTrue("$l $i", ShareRequest("c1", ShareTiming.ForAWhile(l, i)).withinBounds())
        assertEquals(listOf(900L, 3_600L, 28_800L, 86_400L, 604_800L), ShareLength.entries.map { it.seconds })
        assertEquals(listOf(30L, 60L, 300L, 900L), ShareInterval.entries.map { it.seconds })
        assertEquals(ShareLength.HOUR_1, ShareLength.DEFAULT)
        assertEquals(ShareInterval.MINUTE_1, ShareInterval.DEFAULT)
        assertEquals(SharePrecision.APPROXIMATE, ShareRequest("c1").precision)
        assertFalse(ShareRequest("c1").trail)
        assertTrue(r.ops.starts.isEmpty())
    }

    @Test
    fun theProviderIsAskedForTheShortestIntervalOfTheContinuousShares() = runTest {
        val r = rig()
        r.begin(ShareTiming.ForAWhile(ShareLength.HOUR_1, ShareInterval.MINUTES_5), id = "a")
        assertEquals(300_000L, r.provider.requests.last().intervalMs)
        val one = r.begin(ShareTiming.ForAWhile(ShareLength.HOUR_1, ShareInterval.MINUTE_1), id = "b")
        assertEquals(60_000L, r.provider.requests.last().intervalMs)
        r.begin(ShareTiming.ForAWhile(ShareLength.HOUR_1, ShareInterval.SECONDS_30), id = "c")
        assertEquals(30_000L, r.provider.requests.last().intervalMs)
        // The shortest one goes: the next shortest is asked for again.
        r.sharing.stop("s3")
        r.settle()
        assertEquals(60_000L, r.provider.requests.last().intervalMs)
        r.sharing.stop(one.shareId)
        r.settle()
        assertEquals(300_000L, r.provider.requests.last().intervalMs)
    }

    @Test
    fun aNewShareForTheSameConnectionReplacesTheOldOne() = runTest {
        val r = rig()
        r.begin(id = "c1")
        val second = r.begin(ShareTiming.ForAWhile(ShareLength.DAY_1, ShareInterval.MINUTES_15), id = "c1")
        assertEquals(listOf(second), r.sharing.outgoing.value)
        assertEquals(900_000L, r.provider.requests.last().intervalMs)
        assertEquals(listOf("s2"), r.store.saved.map { it.shareId })
    }

    // --- positions ---

    @Test
    fun aFixIsSentWithTheCadenceTtlAndTheFixTime() = runTest {
        val r = rig()
        r.begin(ShareTiming.ForAWhile(ShareLength.HOUR_1, ShareInterval.MINUTES_15))
        r.emit(r.fix(accuracy = 25.0, lat = 48.8566, lon = 2.3522))
        val sent = r.ops.updates.single()
        // exp = now + max(2 x interval, 60 s)
        assertEquals(Duration.ofSeconds(1_800), sent.ttl)
        assertEquals("2026-11-03T12:00:00.000Z", (sent.body["at"] as JsonPrimitive).content)
        assertEquals("25.0", (sent.body["accuracy_m"] as JsonPrimitive).content)
        assertEquals(clock, r.sharing.outgoing.value.single().lastSentAt)
    }

    @Test
    fun theTtlIsAtLeastSixtySeconds() = runTest {
        val r = rig()
        r.begin(ShareTiming.ForAWhile(ShareLength.HOUR_1, ShareInterval.SECONDS_30))
        r.emit(r.fix())
        assertEquals(Duration.ofSeconds(60), r.ops.updates.single().ttl)
    }

    @Test
    fun altitudeSpeedAndHeadingGoOnlyWhenSomeShareIsExact() = runTest {
        val r = rig()
        r.begin(precision = SharePrecision.APPROXIMATE, id = "a")
        r.emit(r.fix())
        assertEquals(setOf("lat", "lon", "accuracy_m", "at"), r.ops.updates.last().body.keys)
        r.begin(precision = SharePrecision.CITY, id = "b")
        r.elapse(Duration.ofSeconds(60))
        r.emit(r.fix())
        assertEquals(setOf("lat", "lon", "accuracy_m", "at"), r.ops.updates.last().body.keys)
        // One exact share among them: all of the position's members are sent (the vault reduces them per share).
        r.begin(precision = SharePrecision.EXACT, id = "c")
        assertTrue(r.provider.requests.last().highAccuracy)
        r.elapse(Duration.ofSeconds(60))
        r.emit(r.fix())
        assertEquals(setOf("lat", "lon", "accuracy_m", "altitude_m", "speed_mps", "heading_deg", "at"), r.ops.updates.last().body.keys)
    }

    @Test
    fun aFixOlderThanFiveMinutesIsNotSent() = runTest {
        val r = rig()
        r.begin()
        r.emit(r.fix(age = Duration.ofMinutes(5).plusSeconds(1)))
        assertTrue(r.ops.updates.isEmpty())
        r.emit(r.fix(age = Duration.ofMinutes(4)))
        assertEquals(1, r.ops.updates.size)
    }

    @Test
    fun positionsComeNoFasterThanTheCadenceAllows() = runTest {
        val r = rig()
        r.begin(ShareTiming.ForAWhile(ShareLength.HOUR_1, ShareInterval.MINUTE_1))
        r.emit(r.fix())
        r.elapse(Duration.ofSeconds(20))
        r.emit(r.fix())
        assertEquals(1, r.ops.updates.size)
        r.elapse(Duration.ofSeconds(35))
        r.emit(r.fix())
        assertEquals(2, r.ops.updates.size)
    }

    @Test
    fun nothingIsSentWhenNoShareHasThisPhoneAsItsSource() = runTest {
        val r = rig()
        // Another device of the member is the source of this share.
        r.ops.outgoing = "[${listed("x1", "dev-other")}]"
        r.sharing.refresh()
        r.settle()
        assertEquals(1, r.sharing.outgoing.value.size)
        assertFalse(r.sharing.outgoing.value.single().fromThisPhone)
        assertEquals(0, r.platform.starts)
        assertTrue(r.provider.requests.isEmpty())
        assertFalse(r.sharing.serviceState.value.active)
        assertTrue(r.ops.updates.isEmpty())
    }

    // --- once ---

    @Test
    fun aOnceShareSendsAtOnceWhenTheFixIsGoodAndNeedsNoMore() = runTest {
        val r = rig()
        val s = r.begin(ShareTiming.Once, id = "c1")
        assertEquals(t0.plusSeconds(900), s.expiresAt)
        assertTrue(r.provider.requests.last().highAccuracy)
        r.emit(r.fix(accuracy = 80.0))
        assertEquals(1, r.ops.updates.size)
        assertEquals(Duration.ofSeconds(60), r.ops.updates.single().ttl)
        // Delivered: the phone has nothing more to send, the service stops, the share stays listed until it ends.
        assertFalse(r.sharing.serviceState.value.active)
        assertEquals(1, r.platform.stops)
        assertEquals(1, r.sharing.outgoing.value.size)
        assertTrue(r.store.saved.isEmpty())
        r.emit(r.fix(accuracy = 5.0))
        assertEquals(1, r.ops.updates.size)
    }

    @Test
    fun aOnceShareWaitsSixtySecondsForOneHundredMetresThenSendsTheBest() = runTest {
        val r = rig()
        r.begin(ShareTiming.Once)
        r.emit(r.fix(accuracy = 900.0, lat = 1.0))
        r.elapse(Duration.ofSeconds(20))
        r.emit(r.fix(accuracy = 300.0, lat = 2.0))
        r.elapse(Duration.ofSeconds(20))
        r.emit(r.fix(accuracy = 500.0, lat = 3.0))
        assertTrue("still waiting", r.ops.updates.isEmpty())
        r.elapse(Duration.ofSeconds(21))
        val sent = r.ops.updates.single()
        assertEquals("2.0", (sent.body["lat"] as JsonPrimitive).content)
        assertEquals("300.0", (sent.body["accuracy_m"] as JsonPrimitive).content)
    }

    @Test
    fun aOnceShareWithNoFixAtSixtySecondsSendsTheFirstThatArrives() = runTest {
        val r = rig()
        r.begin(ShareTiming.Once)
        r.elapse(Duration.ofSeconds(75))
        assertTrue(r.ops.updates.isEmpty())
        r.emit(r.fix(accuracy = 4_000.0))
        assertEquals(1, r.ops.updates.size)
    }

    @Test
    fun aGoodFixDuringTheWaitIsSentAsSoonAsItArrives() = runTest {
        val r = rig()
        r.begin(ShareTiming.Once)
        r.emit(r.fix(accuracy = 400.0))
        r.elapse(Duration.ofSeconds(10))
        r.emit(r.fix(accuracy = 100.0, lat = 7.0))
        assertEquals("7.0", (r.ops.updates.single().body["lat"] as JsonPrimitive).content)
    }

    // --- the app's own timer, stopping ---

    @Test
    fun theAppsOwnTimerEndsAShareAtItsExpiryWithoutAnyMessage() = runTest {
        val r = rig()
        val s = r.begin(ShareTiming.ForAWhile(ShareLength.MINUTES_15, ShareInterval.MINUTE_1))
        r.elapse(Duration.ofSeconds(899))
        assertTrue(r.sharing.serviceState.value.active)
        r.elapse(Duration.ofSeconds(2))
        assertEquals(s.expiresAt, t0.plusSeconds(900))
        assertTrue(r.sharing.outgoing.value.isEmpty())
        assertFalse(r.sharing.serviceState.value.active)
        assertEquals(1, r.platform.stops)
        assertTrue(r.store.saved.isEmpty())
        assertTrue("the vault is told nothing", r.ops.stops.isEmpty())
        assertEquals(0, r.provider.live)
    }

    @Test
    fun noPositionIsSentPastTheExpiryEvenIfTheTimerLagged() = runTest {
        val r = rig()
        r.begin(ShareTiming.ForAWhile(ShareLength.MINUTES_15, ShareInterval.MINUTE_1))
        // The wall clock is past the end but the uptime timer has not fired (deep sleep).
        clock = t0.plusSeconds(901)
        r.emit(r.fix())
        assertTrue(r.ops.updates.isEmpty())
        assertTrue(r.sharing.outgoing.value.isEmpty())
    }

    @Test
    fun stoppingStopsSendingAtOnceAndTellsTheVault() = runTest {
        val r = rig()
        val s = r.begin()
        r.sharing.stop(s.shareId)
        r.settle()
        assertEquals(listOf(s.shareId), r.ops.stops)
        assertTrue(r.sharing.outgoing.value.isEmpty())
        assertFalse(r.sharing.serviceState.value.active)
        assertEquals(0, r.provider.live)
        assertTrue(r.store.saved.isEmpty())
        r.emit(r.fix())
        assertTrue(r.ops.updates.isEmpty())
    }

    @Test
    fun stopAllStopsEveryShareThePhoneSends() = runTest {
        val r = rig()
        r.begin(id = "a")
        r.begin(id = "b")
        r.sharing.stopAll()
        // Sending ended before the vault was told.
        assertFalse(r.sharing.serviceState.value.active)
        r.settle()
        assertEquals(listOf("s1", "s2"), r.ops.stops.sorted())
        assertTrue(r.sharing.outgoing.value.isEmpty())
    }

    @Test
    fun aStopTheVaultRefusesWhileTheCheckIsDueIsTriedAgainWhenItPasses() = runTest {
        val r = rig()
        val s = r.begin()
        due = true
        r.elapse(Duration.ofSeconds(5))
        try {
            r.sharing.stop(s.shareId)
            fail("expected a refusal")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
        // Nothing was sent to the vault, and the phone sends nothing for the share meanwhile.
        assertTrue(r.ops.stops.isEmpty())
        assertEquals(1, r.sharing.outgoing.value.size)
        assertFalse(r.sharing.serviceState.value.active)
        due = false
        r.elapse(Duration.ofSeconds(6))
        assertEquals(listOf(s.shareId), r.ops.stops)
        assertTrue(r.sharing.outgoing.value.isEmpty())
    }

    @Test
    fun aStopTheVaultDoesNotKnowIsDone() = runTest {
        val r = rig()
        val s = r.begin()
        r.ops.stopRefusal = "not_found"
        r.sharing.stop(s.shareId)
        r.settle()
        assertTrue(r.sharing.outgoing.value.isEmpty())
    }

    @Test
    fun theReceiverStoppingEndsTheShare() = runTest {
        val r = rig()
        r.begin(id = "a")
        val keep = r.begin(id = "b")
        r.sharing.onEvent(message("location.event", """{"event":"stopped","direction":"out","connection_id":"a","share_id":"s1"}"""))
        r.settle()
        assertEquals(listOf(keep), r.sharing.outgoing.value)
        assertTrue(r.sharing.serviceState.value.active)
        // An incoming share's event is not ours.
        r.sharing.onEvent(message("location.event", """{"event":"stopped","direction":"in","connection_id":"b","share_id":"s2"}"""))
        r.settle()
        assertEquals(1, r.sharing.outgoing.value.size)
    }

    @Test
    fun aSyncEventSayingEndedEndsTheShare() = runTest {
        val r = rig()
        r.begin()
        r.sharing.onEvent(message("sync.event", """{"kind":"location.share.changed","share_id":"s1","state":"ended"}"""))
        r.settle()
        assertTrue(r.sharing.outgoing.value.isEmpty())
        assertFalse(r.sharing.serviceState.value.active)
        assertEquals(0, r.provider.live)
    }

    // --- locked, due, held ---

    @Test
    fun theLockStopsTheServiceAndSendsNothingButTheStoredIdsStay() = runTest {
        val r = rig()
        val s = r.begin()
        unlocked = false
        r.sharing.clear()
        r.settle()
        assertFalse(r.sharing.serviceState.value.active)
        assertTrue(r.sharing.outgoing.value.isEmpty())
        assertEquals(1, r.platform.stops)
        assertEquals(0, r.provider.live)
        r.emit(r.fix())
        assertTrue(r.ops.updates.isEmpty())
        assertEquals(listOf(s.shareId), r.store.saved.map { it.shareId })
    }

    @Test
    fun afterTheNextUnlockAShareStillActiveHerePicksUpWithTheNotice() = runTest {
        val r = rig()
        val s = r.begin(ShareTiming.ForAWhile(ShareLength.HOUR_1, ShareInterval.MINUTES_5))
        unlocked = false
        r.sharing.clear()
        clock = clock.plusSeconds(600)
        unlocked = true
        r.ops.outgoing = "[${listed(s.shareId, "dev-this", interval = 300, expires = s.expiresAt)},${listed("x9", "dev-other")}]"
        r.sharing.resume()
        r.settle()
        val state = r.sharing.serviceState.value
        assertEquals(listOf(ServiceState.Target("c-${s.shareId}", s.expiresAt)), state.targets)
        assertTrue(state.resumed)
        assertFalse(state.paused)
        assertEquals(2, r.sharing.outgoing.value.size)
        assertEquals(PositionRequest(300_000, false), r.provider.requests.last())
        r.emit(r.fix())
        assertEquals(1, r.ops.updates.size)
    }

    @Test
    fun aShareTheVaultNoLongerListsIsNotTakenUp() = runTest {
        val store = MemStore(listOf(StoredShare("gone", "c-gone", t0.plusSeconds(3_600).toEpochMilli())))
        val r = rig(store = store)
        r.ops.outgoing = "[]"
        r.sharing.resume()
        r.settle()
        assertFalse(r.sharing.serviceState.value.active)
        assertEquals(0, r.platform.starts)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun afterTheProcessEndsTheStoredIdsTakeTheShareUpAgain() = runTest {
        // A new process: nothing in memory, the id on disk, and the device's id not known yet.
        device = null
        val store = MemStore(listOf(StoredShare("s7", "c-s7", t0.plusSeconds(3_600).toEpochMilli())))
        val r = rig(store = store)
        r.ops.outgoing = "[${listed("s7", "dev-this", interval = 30)}]"
        r.sharing.resume()
        r.settle()
        assertEquals(1, r.platform.starts)
        assertEquals(30_000L, r.provider.requests.last().intervalMs)
        assertTrue(r.sharing.serviceState.value.resumed)
        r.emit(r.fix())
        assertEquals(1, r.ops.updates.size)
        assertEquals(listOf("s7"), store.saved.map { it.shareId })
    }

    @Test
    fun theShareIsTakenUpFromTheListByThisPhonesDeviceId() = runTest {
        val r = rig()
        r.ops.outgoing = "[${listed("a1", "dev-this")},${listed("a2", "dev-other")}]"
        r.sharing.resume()
        r.settle()
        assertEquals(listOf("c-a1"), r.sharing.serviceState.value.targets.map { it.connectionId })
    }

    @Test
    fun aOnceShareAlreadyDeliveredIsNotTakenUpAgain() = runTest {
        val r = rig()
        r.ops.outgoing = "[${listed("o1", "dev-this", mode = "once", interval = null, sent = "2026-11-03T12:00:10Z")}]"
        r.sharing.resume()
        r.settle()
        assertFalse(r.sharing.serviceState.value.active)
        assertEquals(1, r.sharing.outgoing.value.size)
        assertEquals(0, r.platform.starts)
    }

    @Test
    fun aVaultWithoutLocationIsAskedNoMoreAndNothingIsShown() = runTest {
        val r = rig()
        r.ops.listRefusal = "unsupported_type"
        r.sharing.resume()
        r.settle()
        assertEquals(1, r.ops.lists)
        assertFalse(r.sharing.serviceState.value.active)
    }

    @Test
    fun theServiceIsPausedWhileTheCheckIsDueAndNothingIsSent() = runTest {
        val r = rig()
        r.begin()
        r.emit(r.fix())
        assertEquals(1, r.ops.updates.size)
        due = true
        r.elapse(Duration.ofSeconds(6))
        val paused = r.sharing.serviceState.value
        assertTrue(paused.paused)
        assertTrue("the share stays", paused.active)
        assertEquals(0, r.provider.live)
        r.elapse(Duration.ofSeconds(120))
        r.emit(r.fix())
        assertEquals("nothing sent while paused", 1, r.ops.updates.size)
        assertEquals("the service is kept", 0, r.platform.stops)
    }

    @Test
    fun whenTheCheckPassesSendingGoesOn() = runTest {
        val r = rig()
        r.begin()
        r.emit(r.fix())
        due = true
        r.elapse(Duration.ofSeconds(6))
        assertTrue(r.sharing.serviceState.value.paused)
        due = false
        r.elapse(Duration.ofSeconds(6))
        assertFalse(r.sharing.serviceState.value.paused)
        assertEquals(1, r.provider.live)
        r.elapse(Duration.ofSeconds(60))
        r.emit(r.fix())
        assertEquals(2, r.ops.updates.size)
    }

    @Test
    fun aShareCannotBeStartedWhileTheCheckIsDueOrTheVaultIsLocked() = runTest {
        val r = rig()
        due = true
        try {
            r.sharing.start(ShareRequest("c1"))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
        assertTrue(r.ops.starts.isEmpty())
        due = false
        unlocked = false
        try {
            r.sharing.start(ShareRequest("c1"))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
    }

    // --- the service the system refused ---

    @Test
    fun aServiceTheSystemRefusedInTheBackgroundIsStartedWhenTheAppComesToTheFront() = runTest {
        val r = rig()
        r.platform.refuse = true
        r.begin()
        assertEquals(1, r.platform.starts)
        r.platform.refuse = false
        r.sharing.onForeground()
        assertEquals(2, r.platform.starts)
        r.sharing.onForeground()
        assertEquals("not again once it runs", 2, r.platform.starts)
    }

    @Test
    fun sharesTakenUpBeforeThePlatformIsAttachedStartTheirServiceWhenItIs() = runTest {
        val r = rig(attach = false)
        r.ops.outgoing = "[${listed("a1", "dev-this")}]"
        r.sharing.resume()
        r.settle()
        assertTrue(r.provider.requests.isEmpty())
        r.sharing.attach(r.platform)
        r.settle()
        assertEquals(1, r.platform.starts)
        assertEquals(1, r.provider.requests.size)
    }

    private companion object {
        const val TRIES = 4
        const val SLEEP_MS = 15L
    }
}
