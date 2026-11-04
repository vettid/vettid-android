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
import kotlinx.coroutines.async
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
@Suppress("LargeClass")
class LocationSharingTest {
    private val t0 = Instant.parse("2026-11-03T12:00:00Z")
    private var clock = t0
    private var unlocked = true
    private var due = false
    private var device: String? = "dev-this"
    private var elapsed = 1_000_000_000_000L

    private class Sent(val body: JsonObject, val ttl: Duration)

    private inner class Ops : LocationOps {
        val starts = mutableListOf<ShareRequest>()
        val updates = mutableListOf<Sent>()
        val stops = mutableListOf<String>()

        /** The stops the vault took: it lists those shares no more. */
        val taken = mutableSetOf<String>()
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
            synchronized(this) { taken += shareId }
            return obj("{}")
        }

        /** When set, `location.share.list` answers only after it is completed (a read in flight), with the list as it was asked. */
        var listGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        /** Completed when a `location.share.list` reaches the vault: a test sets a fresh one, then waits for its read to be in flight. */
        @Volatile var listAsked = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun list(): JsonObject {
            synchronized(this) { lists++ }
            listAsked.complete(Unit)
            listRefusal?.let { throw VaultOpException("location.share.list", it) }
            // The vault no longer lists a share it has stopped.
            val kept = (Json.parseToJsonElement(outgoing) as kotlinx.serialization.json.JsonArray).filter {
                val id = ((it as JsonObject)["share_id"] as JsonPrimitive).content
                synchronized(this) { id !in taken }
            }
            val answer = obj("""{"outgoing":${kotlinx.serialization.json.JsonArray(kept)},"incoming":[]}""")
            listGate?.await()
            return answer
        }
    }

    private class Provider : PositionProvider {
        val requests = mutableListOf<PositionRequest>()
        val fixes = MutableSharedFlow<Fix>()
        var live = 0

        /** The platform refuses the positions (the permission was taken away). */
        var failWith: Throwable? = null

        override fun fixes(request: PositionRequest): Flow<Fix> = flow {
            synchronized(this@Provider) { requests += request }
            failWith?.let { throw it }
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
        var precise = true
        var issue = SendIssue.NONE
        var notRunning = mutableListOf<Boolean>()

        override fun issue() = issue

        override fun showNotRunning(show: Boolean) {
            notRunning += show
        }

        override fun precise() = precise

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

    private inner class Rig(
        val scope: TestScope,
        val ops: Ops = Ops(),
        val store: MemStore = MemStore(),
        attach: Boolean = true,
        retry: List<Duration> = listOf(Duration.ofSeconds(5)),
        services: Set<String> = emptySet(),
    ) {
        val provider = Provider()
        val platform = Platform(provider)
        val sharing = LocationSharing(
            scope.backgroundScope,
            ops = { ops },
            store = store,
            unlocked = { unlocked },
            due = { due },
            deviceId = { device },
            isService = { it in services },
            now = { clock },
            io = StandardTestDispatcher(scope.testScheduler),
            watchEvery = Duration.ofSeconds(5),
            retryDelays = retry,
            elapsedNanos = { elapsed },
        ).also {
            if (attach) {
                it.attach(platform)
                it.setInFront(true) // a share is started by the member's tap, with the app in front
            }
        }

        /** Time passes: the clock and the test scheduler together. */
        fun elapse(d: Duration) {
            clock = clock.plus(d)
            elapsed += d.toNanos()
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

        /** A fix that carries the platform's uptime reading, [ago] before now by that clock, whatever its date says. */
        fun upFix(ago: Duration, dated: Duration = Duration.ZERO) =
            Fix(52.52, 13.405, 20.0, null, null, null, clock.minus(dated), elapsedNanos = elapsed - ago.toNanos())

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

    private fun TestScope.rig(
        ops: Ops = Ops(),
        store: MemStore = MemStore(),
        attach: Boolean = true,
        retry: List<Duration> = listOf(Duration.ofSeconds(5)),
        services: Set<String> = emptySet(),
    ) = Rig(this, ops, store, attach, retry, services)

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
    fun aStopIsSentAtOnceWhileTheCheckIsDueOrHeld() = runTest {
        // VAULT-MESSAGING 0.37.0 §3.6.3: stopping is allowed while the vault is due or held, as locking is.
        val r = rig()
        val s = r.begin()
        due = true
        r.elapse(Duration.ofSeconds(5))
        r.sharing.stop(s.shareId)
        r.settle()
        assertEquals(listOf(s.shareId), r.ops.stops)
        assertTrue(r.sharing.outgoing.value.isEmpty())
        assertTrue(r.store.saved.isEmpty())
    }

    @Test
    fun aStopTheVaultFailsIsKeptOnDiskAndTriedAgain() = runTest {
        val r = rig()
        val s = r.begin()
        r.ops.stopRefusal = "internal"
        try {
            r.sharing.stop(s.shareId)
            fail("expected a failure")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OTHER, e.kind)
        }
        // Sending stopped at once; the stop is on disk before and after the failure.
        assertFalse(r.sharing.serviceState.value.active)
        assertEquals(listOf(StoredShare(s.shareId, "c1", s.expiresAt.toEpochMilli(), stopping = true)), r.store.saved)
        r.ops.stopRefusal = null
        r.elapse(Duration.ofSeconds(6))
        assertEquals(2, r.ops.stops.size)
        assertTrue(r.store.saved.isEmpty())
    }

    @Test
    fun aStoppedShareNeverComesBackAfterALockEvenIfTheVaultStillListsIt() = runTest {
        val r = rig()
        val s = r.begin()
        r.ops.stopRefusal = "internal"
        runCatching { r.sharing.stop(s.shareId) }
        r.settle()
        assertEquals(1, r.platform.starts)
        unlocked = false
        r.sharing.clear()
        r.settle()
        assertEquals("the mark survives the lock", listOf(true), r.store.saved.map { it.stopping })
        // The unlock: the vault still lists the share with this phone as its source.
        unlocked = true
        r.ops.stopRefusal = null
        r.ops.outgoing = "[${listed(s.shareId, "dev-this", expires = s.expiresAt)}]"
        r.sharing.resume()
        r.settle()
        assertEquals("no service", 1, r.platform.starts)
        assertEquals("no positions", 0, r.provider.live)
        assertFalse(r.sharing.serviceState.value.active)
        assertFalse(r.sharing.serviceState.value.resumed)
        r.emit(r.fix())
        assertTrue("no update", r.ops.updates.isEmpty())
        assertEquals("a stop was sent again", 2, r.ops.stops.count { it == s.shareId })
        assertTrue(r.store.saved.isEmpty())
    }

    @Test
    fun aStoppedShareNeverComesBackAfterTheProcessEnds() = runTest {
        val store = MemStore(listOf(StoredShare("s7", "c-s7", t0.plusSeconds(3_600).toEpochMilli(), stopping = true)))
        val r = rig(store = store)
        r.ops.outgoing = "[${listed("s7", "dev-this", interval = 30)}]"
        r.sharing.resume()
        r.settle()
        assertEquals(0, r.platform.starts)
        assertTrue(r.provider.requests.isEmpty())
        assertFalse(r.sharing.serviceState.value.active)
        assertEquals(listOf("s7"), r.ops.stops)
        assertTrue(store.saved.isEmpty())
        r.emit(r.fix())
        assertTrue(r.ops.updates.isEmpty())
    }

    @Test
    fun aStopMarkForAShareTheVaultNoLongerListsIsDropped() = runTest {
        val store = MemStore(listOf(StoredShare("gone", "c1", t0.plusSeconds(3_600).toEpochMilli(), stopping = true)))
        val r = rig(store = store)
        r.sharing.resume()
        r.settle()
        // The stop is sent anyway (the vault answers {}), without waiting for the list; the mark goes with it.
        assertEquals(listOf("gone"), r.ops.stops)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun stopAllIsOnDiskBeforeAnythingIsSent() = runTest {
        val r = rig()
        r.begin(id = "a")
        r.ops.stopRefusal = "internal"
        r.sharing.stopAll()
        r.settle()
        assertEquals(listOf(true), r.store.saved.map { it.stopping })
    }

    @Test
    fun theResumeNeverLoopsTightlyWhileTheVaultRefuses() = runTest {
        val r = rig()
        r.ops.listRefusal = "owner_check_required"
        r.sharing.resume()
        r.settle()
        val first = r.ops.lists
        r.elapse(Duration.ofSeconds(4))
        assertEquals("not again before the watch interval", first, r.ops.lists)
        r.elapse(Duration.ofSeconds(2))
        assertTrue(r.ops.lists > first)
        assertTrue("a few tries, not hundreds", r.ops.lists < 5)
    }

    @Test
    fun aListReadDoesNotForgetAShareStartedAfterItWasAsked() = runTest {
        val r = rig()
        // The list read starts and is held at the vault; a share is started meanwhile and is not in the answer. The read is
        // known to be in flight before the start (it used to race: when the read began after the start, the list rightly
        // dropped the share).
        r.ops.outgoing = "[]"
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        r.ops.listGate = gate
        r.ops.listAsked = kotlinx.coroutines.CompletableDeferred()
        val read = backgroundScope.async { r.sharing.refresh() }
        r.ops.listAsked.await()
        val share = r.sharing.start(ShareRequest("c1"))
        gate.complete(Unit)
        read.await()
        r.settle()
        assertEquals(listOf(share.shareId), r.sharing.outgoing.value.map { it.shareId })
    }

    @Test
    fun theVaultsDeviceIdDecidesAndTheStoredIdsAreOnlyAFallback() = runTest {
        // This phone's id is known and the vault names another device: not ours, though the id is on disk.
        val store = MemStore(listOf(StoredShare("x1", "c-x1", t0.plusSeconds(3_600).toEpochMilli())))
        val r = rig(store = store)
        r.ops.outgoing = "[${listed("x1", "dev-other")}]"
        r.sharing.resume()
        r.settle()
        assertFalse(r.sharing.serviceState.value.active)
        assertFalse(r.sharing.outgoing.value.single().fromThisPhone)
    }

    // --- the service and the app in front ---

    @Test
    fun positionsAreNotCollectedWithoutTheServiceUnlessTheAppIsInFront() = runTest {
        val r = rig()
        r.platform.refuse = true
        r.begin()
        assertEquals("the app is in front: collected without the service", 1, r.provider.requests.size)
        r.sharing.setInFront(false)
        r.settle()
        assertEquals("service refused, app not in front", 0, r.provider.live)
        r.sharing.setInFront(true)
        r.settle()
        assertEquals(1, r.provider.live)
    }

    @Test
    fun aServiceThatEndedItselfIsStartedAgainWhenTheAppComesToTheFront() = runTest {
        val r = rig()
        r.begin()
        assertEquals(1, r.provider.live)
        r.platform.refuse = true
        r.sharing.onServiceEnded()
        r.sharing.setInFront(false)
        r.settle()
        assertEquals("no service, not in front: no positions", 0, r.provider.live)
        r.platform.refuse = false
        r.sharing.setInFront(true)
        r.settle()
        assertEquals(2, r.platform.starts)
        assertEquals(1, r.provider.live)
        // Told again after the app left: nothing happens while no share is wanted.
        r.sharing.stopAll()
        r.settle()
        r.sharing.onServiceEnded()
    }

    @Test
    fun anEndedServiceNobodyWantedIsNotRestarted() = runTest {
        val r = rig()
        r.sharing.onServiceEnded()
        r.sharing.setInFront(true)
        assertEquals(0, r.platform.starts)
    }

    // --- availability ---

    @Test
    fun theVaultHasLocationWhenTheListAnswersAndNotWhenItIsUnsupported() = runTest {
        val r = rig()
        assertEquals(LocationAvailability.UNKNOWN, r.sharing.availability.value)
        r.sharing.resume()
        r.settle()
        assertEquals(LocationAvailability.AVAILABLE, r.sharing.availability.value)
        unlocked = false
        r.sharing.clear()
        assertEquals(LocationAvailability.UNKNOWN, r.sharing.availability.value)
        unlocked = true
        r.ops.listRefusal = "unsupported_type"
        r.sharing.resume()
        r.settle()
        assertEquals(LocationAvailability.UNSUPPORTED, r.sharing.availability.value)
    }

    @Test
    fun aStartLearnsAvailabilityToo() = runTest {
        val r = rig()
        r.begin()
        assertEquals(LocationAvailability.AVAILABLE, r.sharing.availability.value)
    }

    // --- a fix's age, by the uptime clock ---

    @Test
    fun aFixIsJudgedByTheUptimeClockNotTheDate() = runTest {
        val r = rig()
        r.begin()
        // Dated now, but six minutes old by the uptime clock (the date was set forward): dropped.
        r.emit(r.upFix(ago = Duration.ofMinutes(6)))
        assertTrue(r.ops.updates.isEmpty())
        // Dated an hour ago (the date was set back), one second old by the uptime clock: sent.
        r.emit(r.upFix(ago = Duration.ofSeconds(1), dated = Duration.ofMinutes(60)))
        assertEquals(1, r.ops.updates.size)
    }

    // --- once with approximate access only ---

    @Test
    fun withApproximateAccessOnlyAOnceShareSendsTheFirstFix() = runTest {
        val r = rig()
        r.platform.precise = false
        r.begin(ShareTiming.Once)
        r.emit(r.fix(accuracy = 2_000.0))
        assertEquals(1, r.ops.updates.size)
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
    fun aServiceConnectionIsNeverSentALocation() = runTest {
        // VAULT-MESSAGING §6.4: a service can't receive data; the vault refuses `location.share.start` toward one.
        val r = rig(services = setOf("svc"))
        try {
            r.sharing.start(ShareRequest("svc"))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.SERVICE_FORBIDDEN, e.kind)
        }
        assertTrue(r.ops.starts.isEmpty())
        // A person is unaffected.
        r.sharing.start(ShareRequest("c1"))
        assertEquals(listOf("c1"), r.ops.starts.map { it.connectionId })
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

    // --- review fixes: H1, M1, M2, M3 and the low ones ---

    @Test
    fun whileAStopIsPendingNoPositionIsSentAtAll() = runTest {
        // The vault forwards each position to every share this phone sources: a pending stop for A would leave A fed by B's.
        val r = rig()
        val a = r.begin(id = "a")
        r.begin(id = "b")
        r.ops.stopRefusal = "internal"
        runCatching { r.sharing.stop(a.shareId) }
        r.settle()
        r.emit(r.fix())
        assertTrue("no update at all", r.ops.updates.isEmpty())
        assertEquals(0, r.provider.live)
        val state = r.sharing.serviceState.value
        assertEquals(SendIssue.STOP_PENDING, state.issue)
        assertEquals("B is still shared with", listOf("b"), state.targets.map { it.connectionId })
        // The stop lands: sending goes on.
        r.ops.stopRefusal = null
        r.elapse(Duration.ofSeconds(6))
        assertEquals(SendIssue.NONE, r.sharing.serviceState.value.issue)
        r.elapse(Duration.ofSeconds(60))
        r.emit(r.fix())
        assertEquals(1, r.ops.updates.size)
    }

    @Test
    fun aListReadInFlightWhenAStopLandsDoesNotBringTheShareBack() = runTest {
        val r = rig()
        val s = r.begin()
        r.ops.outgoing = "[${listed(s.shareId, "dev-this", expires = s.expiresAt)}]"
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        r.ops.listGate = gate
        r.ops.listAsked = kotlinx.coroutines.CompletableDeferred()
        val read = backgroundScope.async { r.sharing.refresh() }
        // The read is at the vault before the stop (with settle() alone it could arrive after, and then no longer test the guard).
        r.ops.listAsked.await()
        r.sharing.stop(s.shareId)
        r.settle()
        // The read answers with the list as it was when asked, which still has the share.
        gate.complete(Unit)
        read.await()
        r.settle()
        assertTrue(r.sharing.outgoing.value.isEmpty())
        assertFalse(r.sharing.serviceState.value.active)
        r.emit(r.fix())
        assertTrue(r.ops.updates.isEmpty())
    }

    @Test
    fun theStopMarksAreLoadedAndSentWithoutWaitingForTheOwnerCheck() = runTest {
        due = true
        val store = MemStore(listOf(StoredShare("s7", "c-s7", t0.plusSeconds(3_600).toEpochMilli(), stopping = true)))
        val r = rig(store = store)
        r.sharing.resume()
        r.settle()
        assertEquals(listOf("s7"), r.ops.stops)
        assertEquals("the list waits for the check", 0, r.ops.lists)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun aStopThatKeepsFailingIsTriedWithAGrowingWait() = runTest {
        val r = rig(retry = listOf(5L, 15L, 60L).map { Duration.ofSeconds(it) })
        val s = r.begin()
        r.ops.stopRefusal = "internal"
        runCatching { r.sharing.stop(s.shareId) }
        repeat(6) { r.elapse(Duration.ofSeconds(5)) }
        // Now, and after 5, 10 and 25 seconds; a fixed 5 s would have made seven by 30 s.
        assertEquals(4, r.ops.stops.size)
    }

    @Test
    fun aWipeOrSignOutForgetsTheStopMarksAndTheStoredSettings() = runTest {
        val r = rig()
        val s = r.begin()
        r.ops.stopRefusal = "internal"
        runCatching { r.sharing.stop(s.shareId) }
        r.settle()
        assertEquals(1, r.store.saved.size)
        r.sharing.forget()
        r.settle()
        assertTrue(r.store.saved.isEmpty())
        r.ops.stopRefusal = null
        r.ops.outgoing = "[]"
        r.sharing.resume()
        r.settle()
        assertEquals("nothing is stopped for a vault this phone left", 1, r.ops.stops.size)
    }

    @Test
    fun aSecondStartForTheSameConnectionIsRefused() = runTest {
        val r = rig()
        val a = backgroundScope.async { runCatching { r.sharing.start(ShareRequest("c1")) } }
        val b = backgroundScope.async { runCatching { r.sharing.start(ShareRequest("c1")) } }
        r.settle()
        val results = listOf(a.await(), b.await())
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, r.ops.starts.size)
        assertEquals("busy", (results.single { it.isFailure }.exceptionOrNull() as VaultFailure).code)
    }

    @Test
    fun aOnceShareThatEndsWithoutAPositionIsRemembered() = runTest {
        val r = rig()
        r.begin(ShareTiming.Once)
        r.elapse(Duration.ofSeconds(901))
        assertEquals(setOf("c1"), r.sharing.missedOnce.value)
        // A new share for the connection clears it.
        r.begin(ShareTiming.ForAWhile(), id = "c1")
        assertTrue(r.sharing.missedOnce.value.isEmpty())
    }

    @Test
    fun aDeliveredOnceShareIsNotMissed() = runTest {
        val r = rig()
        r.begin(ShareTiming.Once)
        r.emit(r.fix(accuracy = 10.0))
        r.elapse(Duration.ofSeconds(901))
        assertTrue(r.sharing.missedOnce.value.isEmpty())
    }

    @Test
    fun whenNothingCanBeSentTheStateSaysWhy() = runTest {
        val r = rig()
        r.begin()
        assertEquals(SendIssue.NONE, r.sharing.serviceState.value.issue)
        r.platform.issue = SendIssue.NO_PERMISSION
        r.elapse(Duration.ofSeconds(6))
        assertEquals(SendIssue.NO_PERMISSION, r.sharing.serviceState.value.issue)
        r.platform.issue = SendIssue.LOCATION_OFF
        r.elapse(Duration.ofSeconds(6))
        assertEquals(SendIssue.LOCATION_OFF, r.sharing.serviceState.value.issue)
        assertTrue("the share stays", r.sharing.serviceState.value.active)
        r.platform.issue = SendIssue.NONE
        r.elapse(Duration.ofSeconds(6))
        assertEquals(SendIssue.NONE, r.sharing.serviceState.value.issue)
    }

    @Test
    fun aPermissionTakenAwayShowsInTheStateNotAsASilentFailure() = runTest {
        val r = rig()
        r.provider.failWith = SecurityException()
        r.begin()
        r.settle()
        assertEquals(SendIssue.NO_PERMISSION, r.sharing.serviceState.value.issue)
    }

    @Test
    fun aRefusedServiceIsSaidUnlessTheAppIsInFront() = runTest {
        val r = rig()
        r.platform.refuse = true
        r.begin()
        assertEquals(SendIssue.NONE, r.sharing.serviceState.value.issue)
        r.sharing.setInFront(false)
        assertEquals(SendIssue.SERVICE, r.sharing.serviceState.value.issue)
        assertEquals("a plain notice is shown", listOf(true), r.platform.notRunning)
        r.sharing.setInFront(true)
        r.settle()
        assertEquals(SendIssue.NONE, r.sharing.serviceState.value.issue)
        assertEquals("and taken away", listOf(true, false), r.platform.notRunning)
    }

    @Test
    fun theDueStateIsPausedNotAnIssue() = runTest {
        val r = rig()
        r.begin()
        due = true
        r.elapse(Duration.ofSeconds(6))
        assertTrue(r.sharing.serviceState.value.paused)
        assertEquals(SendIssue.NONE, r.sharing.serviceState.value.issue)
    }

    // --- round 3 ---

    @Test
    fun theStoppedSharesConnectionIsSaidToBeStoppingWhileItsStopIsPending() = runTest {
        val r = rig()
        val s = r.begin()
        r.ops.stopRefusal = "internal"
        runCatching { r.sharing.stop(s.shareId) }
        r.settle()
        assertEquals(setOf("c1"), r.sharing.serviceState.value.stopping)
        r.ops.stopRefusal = null
        r.elapse(Duration.ofSeconds(6))
        assertTrue(r.sharing.serviceState.value.stopping.isEmpty())
    }

    @Test
    fun positionsResumeOnceThePermissionIsGivenAgain() = runTest {
        val r = rig()
        r.provider.failWith = SecurityException()
        r.begin()
        r.settle()
        assertEquals(SendIssue.NO_PERMISSION, r.sharing.serviceState.value.issue)
        r.emit(r.fix())
        assertTrue(r.ops.updates.isEmpty())
        // The member grants it again in Android's settings.
        r.provider.failWith = null
        r.elapse(Duration.ofSeconds(6))
        assertEquals(SendIssue.NONE, r.sharing.serviceState.value.issue)
        r.elapse(Duration.ofSeconds(60))
        r.emit(r.fix())
        assertEquals(1, r.ops.updates.size)
    }

    @Test
    fun positionsResumeWhenTheAppComesBackAfterThePermissionWasGiven() = runTest {
        val r = rig()
        r.provider.failWith = SecurityException()
        r.begin()
        r.settle()
        r.provider.failWith = null
        r.sharing.onForeground()
        r.settle()
        assertEquals(1, r.provider.live)
    }

    @Test
    fun aShareIsNotStartedThatCannotSend() = runTest {
        val r = rig()
        r.sharing.setInFront(false)
        try {
            r.sharing.start(ShareRequest("c1"))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(SendIssue.SERVICE, SendIssue.of(e.code))
        }
        r.sharing.setInFront(true)
        r.platform.issue = SendIssue.LOCATION_OFF
        try {
            r.sharing.start(ShareRequest("c1"))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(SendIssue.LOCATION_OFF, SendIssue.of(e.code))
        }
        r.platform.issue = SendIssue.NO_PERMISSION
        try {
            r.sharing.start(ShareRequest("c1"))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(SendIssue.NO_PERMISSION, SendIssue.of(e.code))
        }
        assertTrue("nothing reached the vault", r.ops.starts.isEmpty())
        r.platform.issue = SendIssue.NONE
        r.sharing.start(ShareRequest("c1"))
        assertEquals(1, r.ops.starts.size)
    }

    @Test
    fun aShareIsNotStartedWithoutAPlatform() = runTest {
        val r = rig(attach = false)
        try {
            r.sharing.start(ShareRequest("c1"))
            fail()
        } catch (e: VaultFailure) {
            assertEquals(SendIssue.SERVICE, SendIssue.of(e.code))
        }
    }

    @Test
    fun aStopAskedAgainKeepsTheMarkAlreadyMade() = runTest {
        val expires = t0.plusSeconds(3_600).toEpochMilli()
        val store = MemStore(listOf(StoredShare("s7", "c-s7", expires, stopping = true)))
        val r = rig(store = store)
        r.ops.stopRefusal = "internal"
        r.ops.outgoing = "[${listed("s7", "dev-this")}]"
        r.sharing.resume()
        r.settle()
        runCatching { r.sharing.stop("s7") }
        r.settle()
        val kept = store.saved.single()
        assertEquals("s7", kept.shareId)
        assertEquals("c-s7", kept.connectionId)
        assertEquals(expires, kept.expiresAtMs)
        assertTrue(kept.stopping)
    }

    @Test
    fun aHeldPositionIsNotSentWhenAStopWasAskedForMeanwhile() = runTest {
        val r = rig()
        val a = r.begin(id = "a")
        r.begin(ShareTiming.Once, id = "b")
        r.emit(r.fix(accuracy = 900.0))
        assertTrue(r.ops.updates.isEmpty())
        r.ops.stopRefusal = "internal"
        runCatching { r.sharing.stop(a.shareId) }
        r.elapse(Duration.ofSeconds(61))
        assertTrue("the held position stays unsent", r.ops.updates.isEmpty())
    }

    // --- strict reading of location.share.list (M3) ---

    private fun read(vararg entries: String): List<OutgoingShare>? =
        ShareWire.outgoing(obj("""{"outgoing":[${entries.joinToString(",")}]}"""), "dev-this", emptySet(), t0)

    private fun entry(
        mode: String = "continuous",
        interval: String? = """"interval_seconds":60,""",
        history: String = "false",
        expires: Instant = t0.plusSeconds(3_600),
    ) = """{"share_id":"s","connection_id":"c","mode":"$mode","precision":"approximate",${interval ?: ""}"history":$history,"device_id":"d","started_at":"$t0","expires_at":"$expires"}"""

    @Test
    fun aWellFormedListIsRead() {
        assertEquals(1, read(entry())!!.size)
        assertEquals(1, read(entry(mode = "once", interval = null, expires = t0.plusSeconds(900)))!!.size)
        assertEquals(1, read(entry(expires = t0.plusSeconds(604_800)))!!.size)
        assertEquals(1, read(entry(interval = """"interval_seconds":10,"""))!!.size)
        assertEquals(1, read(entry(interval = """"interval_seconds":3600,"""))!!.size)
    }

    @Test
    fun oneBadEntryMakesTheWholeListUnreadable() {
        assertNull("continuous without an interval", read(entry(), entry(interval = null)))
        assertNull(read(entry(interval = """"interval_seconds":9,""")))
        assertNull(read(entry(interval = """"interval_seconds":3601,""")))
        assertNull(read(entry(interval = """"interval_seconds":"60",""")))
        assertNull("a once share with an interval", read(entry(mode = "once")))
        assertNull("history is not a boolean", read(entry(history = "\"false\"")))
        assertNull(read(entry(history = "1")))
        assertNull("more than 7 days ahead", read(entry(expires = t0.plusSeconds(604_800 + 3_600))))
    }

    @Test
    fun anUnreadableListIsNeverApplied() = runTest {
        val r = rig()
        r.ops.outgoing = "[${entry(interval = null)}]"
        r.sharing.resume()
        r.settle()
        assertTrue(r.sharing.outgoing.value.isEmpty())
        assertFalse(r.sharing.serviceState.value.active)
    }

    private companion object {
        const val TRIES = 4
        const val SLEEP_MS = 15L
    }
}
