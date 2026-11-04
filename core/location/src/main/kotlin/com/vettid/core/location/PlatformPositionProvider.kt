package com.vettid.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import androidx.core.content.ContextCompat
import com.vettid.core.data.location.Fix
import com.vettid.core.data.location.PositionProvider
import com.vettid.core.data.location.PositionRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.time.Instant

/**
 * The phone's positions from the platform's `LocationManager` (ANDROID-PLAN §4 "Location", "Provider"): the `fused` provider of
 * Android 12 and later, which this app's minSdk (31) guarantees to exist in the platform (it needs no Google Play services, so the
 * `foss` build and GrapheneOS without Google work the same); where a phone lacks it, GPS and the network provider. No Google
 * location API. A fix is handed on and kept nowhere here; nothing is logged.
 */
class PlatformPositionProvider(context: Context) : PositionProvider {
    private val app = context.applicationContext

    // A missing permission is a SecurityException, which ends the flow (the collector stops): checked there, not here.
    @SuppressLint("MissingPermission")
    override fun fixes(request: PositionRequest): Flow<Fix> = callbackFlow {
        val manager = checkNotNull(app.getSystemService(LocationManager::class.java)) { "no location service" }
        val executor = ContextCompat.getMainExecutor(app)
        val listener = LocationListener { location -> trySend(fixOf(location, Instant.now())) }
        val quality = if (request.highAccuracy) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
        val updates = LocationRequest.Builder(request.intervalMs).setQuality(quality).build()
        // A provider the phone has: the fused one, else every other that is on. A permission refusal is a SecurityException,
        // which ends this flow (the collector stops; the permission is the member's).
        val providers = providersOf(manager)
        try {
            for (p in providers) manager.requestLocationUpdates(p, updates, executor, listener)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // The second provider refused: the first one's listener is not left behind.
            manager.removeUpdates(listener)
            throw e
        }
        awaitClose { manager.removeUpdates(listener) }
    }.conflate()

    internal companion object {
        /** `fused` when the platform has it, else GPS and the network provider (those that exist). */
        fun providersOf(manager: LocationManager): List<String> {
            val all = manager.allProviders
            return if (LocationManager.FUSED_PROVIDER in all) {
                listOf(LocationManager.FUSED_PROVIDER)
            } else {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
            }
        }

        /** A platform [Location] as a [Fix]: members the platform did not set are left out; a fix without a time is dated [now]. */
        fun fixOf(location: Location, now: Instant): Fix = Fix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = location.accuracy.toDouble().takeIf { location.hasAccuracy() },
            altitudeM = location.altitude.takeIf { location.hasAltitude() },
            speedMps = location.speed.toDouble().takeIf { location.hasSpeed() },
            headingDeg = location.bearing.toDouble().takeIf { location.hasBearing() }?.let { if (it >= FULL_TURN) it % FULL_TURN else it },
            at = if (location.time > 0) Instant.ofEpochMilli(location.time) else now,
            elapsedNanos = location.elapsedRealtimeNanos.takeIf { it > 0 },
        )

        private const val FULL_TURN = 360.0
    }
}
