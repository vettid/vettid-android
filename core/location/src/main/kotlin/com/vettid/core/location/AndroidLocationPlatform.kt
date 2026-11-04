package com.vettid.core.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vettid.core.notify.Channels
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.location.LocationManager
import com.vettid.core.data.location.Fix
import com.vettid.core.data.location.LocationPlatform
import com.vettid.core.data.location.LocationRepository
import com.vettid.core.data.location.PositionProvider
import com.vettid.core.data.location.SendIssue
import kotlinx.coroutines.flow.StateFlow

/**
 * The Android side of [LocationPlatform]: the platform's positions and the foreground service. [names] are the first names that
 * the ongoing notification may say (empty under the app lock's cover).
 */
class AndroidLocationPlatform(
    context: Context,
    repository: LocationRepository,
    names: StateFlow<Map<String, String>>,
    override val positions: PositionProvider = PlatformPositionProvider(context),
) : LocationPlatform {
    private val app = context.applicationContext

    init {
        LocationService.source = object : LocationServiceSource {
            override val state = repository.serviceState
            override val names = names

            override fun stopAll() = repository.stopAll()

            override fun serviceEnded() = repository.onServiceEnded()
        }
    }

    override fun startService(): Boolean = LocationService.start(app)

    override fun stopService() = LocationService.stop(app)

    /** A plain notice on the Location sharing channel, opening the app (nothing runs to show the service's own). */
    override fun showNotRunning(show: Boolean) {
        val manager = NotificationManagerCompat.from(app)
        if (!show) {
            manager.cancel(NOT_RUNNING_ID)
            return
        }
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        Channels.ensure(app)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { PendingIntent.getActivity(app, 0, it, flags) }
        val text = app.getString(com.vettid.core.data.R.string.data_location_issue_service)
        fun builder() = NotificationCompat.Builder(app, Channels.LOCATION)
            .setSmallIcon(com.vettid.core.notify.R.drawable.ic_stat_vettid)
            .setContentTitle(text)
            .setSilent(true)
        val n = builder()
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(builder().build())
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOT_RUNNING_ID, n) }
    }

    private companion object {
        const val NOT_RUNNING_ID = 0x10CA8
    }

    override fun issue(): SendIssue {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = app.getSystemService(LocationManager::class.java)
        return when {
            !fine && !coarse -> SendIssue.NO_PERMISSION
            manager != null && !manager.isLocationEnabled -> SendIssue.LOCATION_OFF
            else -> SendIssue.NONE
        }
    }

    override fun precise(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Where this phone is now, for the distance and direction of a received position. **Only when the member has already allowed
     * location** (the permission is never asked for here) and the phone's location switch is on: one fix is asked for from the
     * platform (`getCurrentLocation`, the best provider the phone has), waited for at most [HERE_WAIT_MS], and dropped by the
     * caller once the offset is worked out. A stale position the system remembers is never read.
     */
    override suspend fun currentFix(): Fix? {
        val manager = app.getSystemService(LocationManager::class.java)
        val provider = manager?.takeIf { allowedToLocate() && it.isLocationEnabled }
            ?.let { PlatformPositionProvider.providersOf(it).firstOrNull() }
        return if (manager == null || provider == null) null else fixNow(manager, provider)
    }

    /** Either location permission is already granted (never asked for here). */
    private fun allowedToLocate(): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }

    @android.annotation.SuppressLint("MissingPermission") // [allowedToLocate] was checked by the caller
    private suspend fun fixNow(manager: LocationManager, provider: String): Fix? = try {
        kotlinx.coroutines.withTimeoutOrNull(HERE_WAIT_MS) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, app.mainExecutor) { location ->
                    cont.resumeWith(Result.success(location?.let { PlatformPositionProvider.fixOf(it, java.time.Instant.now()) }))
                }
            }
        }
    } catch (_: SecurityException) {
        null
    }
}

private const val HERE_WAIT_MS = 5_000L
