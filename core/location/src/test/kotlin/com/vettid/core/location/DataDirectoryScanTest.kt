// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.location

import android.app.Application
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.location.Fix
import com.vettid.core.data.location.LocationOps
import com.vettid.core.data.location.KeystoreShareStore
import com.vettid.core.data.location.LocationSharing
import com.vettid.core.data.location.ServiceState
import com.vettid.core.data.location.ShareInterval
import com.vettid.core.data.location.ShareLength
import com.vettid.core.data.location.ShareRequest
import com.vettid.core.data.location.ShareTiming
import com.vettid.core.data.location.SharePrecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.vettid.core.vault.VaultMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * Privacy, data directory scan (ANDROID-PLAN §4 "Tests"): after sharing for some minutes (a once share and two continuous ones, one
 * exact, with the trail) and a lock, nothing of the app's data directory (files, `no_backup`, caches, databases, DataStore, shared
 * preferences) holds a coordinate of the test route, as text or in binary form. The share store is the real Keystore-encrypted file
 * (with a fixed test key, as the Keystore is not there under Robolectric), the service is the real service with its notification;
 * only the vault and the platform's positions are fakes. This runs under Robolectric: no instrumented test is needed for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataDirectoryScanTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val t0 = Instant.parse("2026-11-03T12:00:00Z")

    @Test
    fun theScannerFindsACoordinateInEveryFormItLooksFor() {
        val dir = File(app.cacheDir, "control").apply { mkdirs() }
        val (lat, lon) = Route.points.first()
        val forms = mapOf(
            "text" to lat.toString().encodeToByteArray(),
            "six decimals" to "%.6f".format(java.util.Locale.ROOT, lon).encodeToByteArray(),
            "double" to java.nio.ByteBuffer.allocate(8).putDouble(lat).array(),
            "double (little endian)" to java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(lon).array(),
            "float" to java.nio.ByteBuffer.allocate(4).putFloat(lat.toFloat()).array(),
            "fixed point" to java.nio.ByteBuffer.allocate(4).putInt((lat * 1e7).toInt()).array(),
        )
        forms.forEach { (name, bytes) -> File(dir, name).writeBytes(byteArrayOf(1, 2, 3) + bytes + byteArrayOf(9)) }
        File(dir, "clean").writeText("nothing here")
        val found = RouteScan.find(dir, listOf(lat to lon)).map { File(it).name }.toSet()
        assertEquals(forms.keys, found)
        dir.deleteRecursively()
    }

    @Test
    fun noCoordinateOfTheRouteIsLeftOnDisk() = runTest {
        // Something in each place a position could be put, so that the scan walks real directories.
        File(app.filesDir, "datastore").apply { mkdirs() }.resolve("settings.preferences_pb").writeText("theme=dark")
        File(app.cacheDir, "x.tmp").writeText("cache")
        File(app.noBackupFilesDir, "other.bin").writeText("other")
        // The scan covers the whole data directory, databases included, and finds a coordinate put in no_backup (a positive control).
        assertTrue(app.getDatabasePath("x").parentFile!!.path.startsWith(app.dataDir.path))
        val control = File(app.noBackupFilesDir, "control.bin").apply { writeBytes(byteArrayOf(7) + Route.points[1].first.toString().encodeToByteArray()) }
        assertEquals(listOf(control.path), RouteScan.find(app.dataDir, Route.points))
        control.delete()

        val sink = SinkOps(t0)
        val provider = FlowProvider()
        val platform = RecordingPlatform(provider)
        var clock = t0
        val sealed = KeystoreFileStore(File(app.noBackupFilesDir, "location-shares.bin"), "location-shares", key = { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") })
        val store = KeystoreShareStore(sealed)
        var unlocked = true
        val sharing = LocationSharing(
            backgroundScope,
            ops = { sink },
            store = store,
            unlocked = { unlocked },
            due = { false },
            deviceId = { "dev-this" },
            now = { clock },
            io = StandardTestDispatcher(testScheduler),
        )
        sharing.attach(platform)
        sharing.setInFront(true)
        // The real service shows the notification of the share.
        LocationService.source = object : LocationServiceSource {
            override val state = MutableStateFlow(ServiceState())
            override val names = MutableStateFlow(mapOf("c-once" to "Sam"))
            override fun stopAll() = sharing.stopAll()

            override fun serviceEnded() = sharing.onServiceEnded()
        }.also { src -> backgroundScope.launch { sharing.serviceState.collect { src.state.value = it } } }

        fun settle() {
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }

        suspend fun move(seconds: Long, point: Pair<Double, Double>) {
            clock = clock.plusSeconds(seconds)
            advanceTimeBy(seconds * 1_000)
            runCurrent()
            provider.fixes.emit(Fix(point.first, point.second, 8.0, 34.0, 1.5, 90.0, clock))
            settle()
        }

        sharing.start(ShareRequest("c-once", ShareTiming.Once, SharePrecision.APPROXIMATE))
        sharing.start(ShareRequest("c-exact", ShareTiming.ForAWhile(ShareLength.HOURS_8, ShareInterval.SECONDS_30), SharePrecision.EXACT, trail = true))
        sharing.start(ShareRequest("c-city", ShareTiming.ForAWhile(ShareLength.DAY_1, ShareInterval.MINUTE_1), SharePrecision.CITY))
        settle()
        val service = Robolectric.buildService(LocationService::class.java).create().startCommand(0, 1).get()
        settle()
        // Some minutes along the route.
        Route.points.forEachIndexed { i, p -> move(if (i == 0) 5 else 90, p) }
        move(90, Route.points.last())
        assertTrue("positions did go to the vault", sink.updates.size >= 3)
        assertTrue(File(app.noBackupFilesDir, "location-shares.bin").exists())
        assertTrue(shadowOf(service).lastForegroundNotification != null)

        // While sharing, and after a stop and a lock: the member's route is nowhere.
        assertEquals(emptyList<String>(), RouteScan.find(app.dataDir, Route.points))
        sharing.stop("s2")
        settle()
        unlocked = false
        sharing.clear()
        settle()
        assertEquals(emptyList<String>(), RouteScan.find(app.dataDir, Route.points))
        // The scan covers every place a position could be put.
        listOf(app.filesDir, app.noBackupFilesDir, app.cacheDir, app.codeCacheDir).forEach { assertTrue(it.path, it.path.startsWith(app.dataDir.path)) }
        // And the encrypted settings, opened with the test's key: the share ids, and no coordinate in the plain text.
        val stored = File(app.noBackupFilesDir, "location-shares.bin")
        assertTrue(stored.exists())
        assertTrue(String(stored.readBytes(), Charsets.ISO_8859_1).let { !it.contains("share_id") && !it.contains("c-exact") })
        val plain = sealed.open(stored.readBytes())
        assertTrue(String(plain).contains("share_id"))
        assertTrue(!RouteScan.contains(plain, Route.points))
        LocationService.source = null
    }

    /** A vault that has a connection sharing with the member: the list names the share, `location.get` gives the route three ways. */
    private class IncomingOps(inner: SinkOps, private val t0: Instant) : LocationOps by inner {
        private fun point(p: Pair<Double, Double>) = """{"lat":${p.first},"lon":${p.second},"accuracy_m":20,"at":"$t0"}"""
        private val route = Route.points.joinToString(",") { point(it) }

        override suspend fun list(): JsonObject = Json.parseToJsonElement(
            """{"outgoing":[],"incoming":[{"share_id":"in1","connection_id":"c-in","mode":"continuous","precision":"exact","interval_seconds":60,"history":true,"started_at":"$t0","expires_at":"${t0.plusSeconds(3600)}","last":${point(Route.points[0])}}]}""",
        ) as JsonObject

        override suspend fun get(connectionId: String, history: Boolean): JsonObject = Json.parseToJsonElement(
            """{"share_id":"in1","mode":"continuous","precision":"exact","expires_at":"${t0.plusSeconds(3600)}","last":${point(Route.points[1])},"history":[$route],"snapshot":[$route]}""",
        ) as JsonObject
    }

    private fun message(type: String, body: String, at: Instant) =
        VaultMessage(Inner(id = Ulid.new(), type = type, ts = at, body = body.toByteArray()))

    /**
     * Received positions (L1b): the list's `last`, `location.get`'s latest, trail and snapshot, and live `location.update`s all
     * stay in memory. While they are held, after a hold, and after a lock, the data directory has no coordinate of the route.
     */
    @Test
    fun noReceivedPositionIsLeftOnDisk() = runTest {
        val sink = SinkOps(t0)
        val sealed = KeystoreFileStore(File(app.noBackupFilesDir, "location-shares.bin"), "location-shares", key = { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") })
        val sharing = LocationSharing(
            backgroundScope,
            ops = { IncomingOps(sink, t0) },
            store = KeystoreShareStore(sealed),
            unlocked = { true },
            due = { false },
            deviceId = { "dev-this" },
            now = { t0 },
            io = StandardTestDispatcher(testScheduler),
        )
        sharing.refresh()
        sharing.open("c-in")
        Route.points.forEach { (lat, lon) ->
            sharing.onEvent(message("location.update", """{"connection_id":"c-in","share_id":"in1","lat":$lat,"lon":$lon,"accuracy_m":5,"at":"$t0"}""", t0))
        }
        val held = sharing.incoming.value.single()
        assertTrue("the positions are held in memory", held.last != null && held.trailPoints.size >= Route.points.size && held.snapshot.isNotEmpty())
        // While held: not on disk.
        assertEquals(emptyList<String>(), RouteScan.find(app.dataDir, Route.points))
        // A hold drops them; so does a lock; neither leaves a trace.
        sharing.clearReceived()
        assertTrue(sharing.incoming.value.isEmpty())
        assertEquals(emptyList<String>(), RouteScan.find(app.dataDir, Route.points))
        sharing.refresh()
        assertTrue(sharing.incoming.value.isNotEmpty())
        sharing.clear()
        assertTrue(sharing.incoming.value.isEmpty())
        assertEquals(emptyList<String>(), RouteScan.find(app.dataDir, Route.points))
        // Nothing of receiving is stored at all: the share store was never written by it.
        assertTrue(!File(app.noBackupFilesDir, "location-shares.bin").exists() || !RouteScan.contains(File(app.noBackupFilesDir, "location-shares.bin").readBytes(), Route.points))
    }
}
