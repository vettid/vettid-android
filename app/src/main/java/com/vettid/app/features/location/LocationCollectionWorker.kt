package com.vettid.app.features.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.storage.AppPreferencesStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Background worker for periodic location collection.
 * Uses device-native LocationManager (no Google Play Services dependency).
 * Captures location, checks displacement threshold, and sends to vault.
 */
@HiltWorker
class LocationCollectionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val appPreferencesStore: AppPreferencesStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "LocationCollectionWorker"
        private const val WORK_NAME = "location_collection"
        private const val WORK_NAME_HIGH_FREQ = "location_collection_high_freq"
        private const val LOCATION_TIMEOUT_MS = 30_000L
        private const val MIN_PERIODIC_MINUTES = 15

        /**
         * Schedule periodic location collection.
         * For intervals >= 15 min, uses PeriodicWorkRequest.
         * For intervals < 15 min, uses chained OneTimeWorkRequests.
         * Always captures an initial location immediately.
         */
        fun schedule(context: Context, frequencyMinutes: Int) {
            // Always capture initial location immediately
            captureNow(context)

            if (frequencyMinutes >= MIN_PERIODIC_MINUTES) {
                // Cancel any high-frequency chain
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_HIGH_FREQ)

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<LocationCollectionWorker>(
                    frequencyMinutes.toLong(), TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        1, TimeUnit.MINUTES
                    )
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        workRequest
                    )
            } else {
                // Cancel periodic work, use high-frequency chain
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                scheduleHighFrequency(context, frequencyMinutes)
            }

            Log.i(TAG, "Scheduled location collection every $frequencyMinutes minutes")
        }

        /**
         * Schedule a single immediate location capture.
         */
        fun captureNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<LocationCollectionWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.i(TAG, "Scheduled immediate location capture")
        }

        /**
         * Schedule high-frequency (< 15 min) location collection using one-shot chains.
         */
        private fun scheduleHighFrequency(context: Context, frequencyMinutes: Int) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<LocationCollectionWorker>()
                .setConstraints(constraints)
                .setInitialDelay(frequencyMinutes.toLong(), TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_HIGH_FREQ,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
        }

        /**
         * Ensure periodic location collection is scheduled (no-op if already running).
         * Uses KEEP policy to avoid resetting the timer on an existing healthy worker.
         */
        fun ensureScheduled(context: Context, frequencyMinutes: Int) {
            if (frequencyMinutes >= MIN_PERIODIC_MINUTES) {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<LocationCollectionWorker>(
                    frequencyMinutes.toLong(), TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        1, TimeUnit.MINUTES
                    )
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        workRequest
                    )

                Log.i(TAG, "Ensured location collection scheduled (every $frequencyMinutes minutes)")
            } else {
                scheduleHighFrequency(context, frequencyMinutes)
            }
        }

        /**
         * Cancel all location collection work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_HIGH_FREQ)
            Log.i(TAG, "Cancelled location collection")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting location collection")

        // Check if still enabled
        if (!appPreferencesStore.isLocationTrackingEnabled()) {
            Log.d(TAG, "Location tracking disabled, skipping")
            return Result.success()
        }

        val frequency = appPreferencesStore.getLocationFrequency()

        // Check foreground location permission
        val hasForegroundPermission = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasForegroundPermission) {
            Log.w(TAG, "Location permission not granted, skipping")
            return Result.success()
        }

        // Check background location permission (required on Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackgroundPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasBackgroundPermission) {
                Log.w(TAG, "Background location permission not granted, skipping")
                return Result.success()
            }
        }

        // Check NATS connectivity — don't burn retries if vault is unreachable
        if (!connectionManager.isConnected()) {
            Log.w(TAG, "NATS not connected, skipping location collection (will retry next period)")
            return Result.success()
        }

        val result = try {
            val location = getCurrentLocation()
            if (location == null) {
                Log.w(TAG, "Failed to get current location")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            } else {
                processLocation(location)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location collection exception", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        // For high-frequency mode (< 15 min), re-schedule the next capture
        if (frequency.minutes < MIN_PERIODIC_MINUTES && result == Result.success()) {
            scheduleHighFrequency(applicationContext, frequency.minutes)
        }

        return result
    }

    private suspend fun getCurrentLocation(): Location? {
        val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE)
            as? LocationManager ?: return null

        // Determine provider based on precision
        val precision = appPreferencesStore.getLocationPrecision()
        val provider = if (precision == LocationPrecision.EXACT &&
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        ) {
            LocationManager.GPS_PROVIDER
        } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            LocationManager.NETWORK_PROVIDER
        } else if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            Log.w(TAG, "No location provider available")
            return null
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getLocationApi30(locationManager, provider)
            } else {
                getLocationLegacy(locationManager, provider)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
            null
        }
    }

    @Suppress("MissingPermission")
    private suspend fun getLocationApi30(
        locationManager: LocationManager,
        provider: String
    ): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }

            locationManager.getCurrentLocation(
                provider,
                cancellationSignal,
                Executors.newSingleThreadExecutor()
            ) { location ->
                continuation.resume(location)
            }

            // Timeout fallback
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                if (!continuation.isCompleted) {
                    cancellationSignal.cancel()
                    continuation.resume(null)
                }
            }, LOCATION_TIMEOUT_MS)
        }
    }

    @Suppress("MissingPermission")
    private suspend fun getLocationLegacy(
        locationManager: LocationManager,
        provider: String
    ): Location? {
        // On older APIs, use getLastKnownLocation as fallback
        return locationManager.getLastKnownLocation(provider)
    }

    private suspend fun processLocation(location: Location): Result {
        val precision = appPreferencesStore.getLocationPrecision()

        // Round coordinates to configured precision
        val factor = Math.pow(10.0, precision.decimalPlaces.toDouble())
        val lat = (location.latitude * factor).roundToInt() / factor
        val lon = (location.longitude * factor).roundToInt() / factor

        // Check displacement threshold
        val lastLat = appPreferencesStore.getLastKnownLatitude()
        val lastLon = appPreferencesStore.getLastKnownLongitude()
        val threshold = appPreferencesStore.getDisplacementThreshold()

        if (lastLat != 0.0 || lastLon != 0.0) {
            val displacement = haversineDistance(lastLat, lastLon, lat, lon)
            if (displacement < threshold.meters) {
                Log.d(TAG, "Displacement ${displacement.roundToInt()}m < threshold ${threshold.meters}m, skipping")
                return Result.success()
            }
        }

        // Build payload for vault
        val payload = JsonObject().apply {
            addProperty("latitude", lat)
            addProperty("longitude", lon)
            if (location.hasAccuracy()) {
                addProperty("accuracy", location.accuracy)
            }
            if (location.hasAltitude()) {
                addProperty("altitude", location.altitude)
            }
            if (location.hasSpeed()) {
                addProperty("speed", location.speed)
            }
            addProperty("timestamp", location.time / 1000) // Convert millis to seconds
            addProperty("source", location.provider ?: "unknown")
        }

        // Send to vault
        val response = ownerSpaceClient.sendAndAwaitResponse(
            "location.add", payload, 15000L
        )

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success) {
                    // Update last known location
                    appPreferencesStore.setLastKnownLocation(lat, lon)
                    appPreferencesStore.setLastCaptureTime(System.currentTimeMillis() / 1000)
                    Log.i(TAG, "Location stored in vault: ($lat, $lon)")
                    Result.success()
                } else {
                    Log.e(TAG, "Vault rejected location: ${response.error}")
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
            }
            is VaultResponse.Error -> {
                Log.e(TAG, "Vault error (code=${response.code}): ${response.message}")
                // Don't retry permanent errors (handler not found, invalid payload)
                if (response.code == "HANDLER_NOT_FOUND" || response.code == "INVALID_PAYLOAD") {
                    Log.e(TAG, "Permanent error, not retrying")
                    Result.failure()
                } else if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
            else -> {
                Log.w(TAG, "Unexpected vault response type: $response")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }

    /**
     * Haversine distance between two coordinates in meters.
     */
    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
