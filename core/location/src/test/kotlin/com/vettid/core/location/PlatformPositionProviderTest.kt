// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import org.robolectric.RuntimeEnvironment
import com.vettid.core.data.location.PositionRequest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/** The platform's positions (ANDROID-PLAN §4 "Location", "Provider"): the fused provider, the fallbacks, and the fix as the app reads it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlatformPositionProviderTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(LocationManager::class.java)

    private fun location(provider: String, lat: Double, lon: Double, accuracy: Float? = 12f, time: Long = 1_000L) = Location(provider).apply {
        latitude = lat
        longitude = lon
        accuracy?.let { this.accuracy = it }
        this.time = time
    }

    @Test
    fun theFusedProviderIsTheOneAskedWhenThePlatformHasIt() {
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        assertEquals(listOf(LocationManager.FUSED_PROVIDER), PlatformPositionProvider.providersOf(manager))
    }

    @Test
    fun withoutFusedGpsAndTheNetworkProviderAreAsked() {
        // Robolectric's manager has no fused provider until the test adds it: the fallbacks are GPS and the network provider.
        val providers = PlatformPositionProvider.providersOf(manager)
        assertTrue(providers.toString(), LocationManager.FUSED_PROVIDER !in providers)
        assertTrue(providers.toString(), providers.all { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER })
    }

    @Test
    fun aFixKeepsOnlyWhatThePlatformSet() {
        val bare = PlatformPositionProvider.fixOf(location("fused", 1.5, 2.5, accuracy = null, time = 0L), Instant.parse("2026-11-03T12:00:00Z"))
        assertNull(bare.accuracyM)
        assertNull(bare.altitudeM)
        assertNull(bare.speedMps)
        assertNull(bare.headingDeg)
        // No time on the fix: dated now.
        assertEquals(Instant.parse("2026-11-03T12:00:00Z"), bare.at)
        val full = location("fused", 1.5, 2.5, 8f, time = 5_000L).apply {
            altitude = 120.0
            speed = 2f
            bearing = 360f
        }
        val f = PlatformPositionProvider.fixOf(full, Instant.EPOCH)
        assertEquals(8.0, f.accuracyM!!, 0.0)
        assertEquals(120.0, f.altitudeM!!, 0.0)
        assertEquals(2.0, f.speedMps!!, 0.0)
        // 360 degrees is north: the vault takes 0 up to less than 360.
        assertEquals(0.0, f.headingDeg!!, 0.0)
        assertEquals(Instant.ofEpochMilli(5_000L), f.at)
    }

    @Test
    fun fixesArriveFromTheFusedProviderAsTheyAreSimulated() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        val provider = PlatformPositionProvider(app)
        val got = withTimeout(5_000) {
            val first = async(Dispatchers.Default) { provider.fixes(PositionRequest(1_000, highAccuracy = true)).take(1).toList() }
            // Deliver once the listener is registered.
            while (shadowOf(manager).getLocationUpdateListeners(LocationManager.FUSED_PROVIDER).isEmpty()) {
                delay(10)
                shadowOf(Looper.getMainLooper()).idle()
            }
            shadowOf(manager).simulateLocation(location(LocationManager.FUSED_PROVIDER, 48.0, 11.0))
            shadowOf(Looper.getMainLooper()).idle()
            first.await()
        }
        assertEquals(1, got.size)
        assertEquals(48.0, got.first().lat, 0.0)
    }
}
