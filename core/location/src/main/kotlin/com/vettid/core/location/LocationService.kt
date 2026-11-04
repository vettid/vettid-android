package com.vettid.core.location

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vettid.core.data.location.ServiceState
import com.vettid.core.notify.Channels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the location service shows and can do: the sharing logic is it (`LocationSharing`), with the names that may be said. */
interface LocationServiceSource {
    val state: StateFlow<ServiceState>

    /** First names by connection id; empty while the app lock's cover is up (no name is then said). */
    val names: StateFlow<Map<String, String>>

    /** The notification's **Stop all**. */
    fun stopAll()
}

/**
 * Keeps the phone sending positions with the screen off (ANDROID-PLAN §4 "Location", "A foreground service"): a foreground service
 * of type `location` for as long as this phone is the source of an active share, with one silent, ongoing notification on the
 * **Location sharing** channel ("Sharing your location with {First} until {time}", **Stop all**; "Paused until your daily check."
 * with **Check now** while the check is due). It is started only from the member's tap on **Start sharing**, while the app is visible,
 * so the while-in-use permission is enough. It holds nothing of the positions: the logic runs in the app's scope and the service
 * only follows [LocationServiceSource.state] and stops when no share is left. Both actions and the notification's tap open the
 * app (its main screen: the Location screen comes with L1b).
 */
class LocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = source
        if (intent?.action == ACTION_STOP_ALL) current?.stopAll()
        val state = current?.state?.value
        // The service must show itself at once (it was started as a foreground service), even when it is about to go.
        val shown = foreground(state ?: ServiceState(), current?.names?.value.orEmpty())
        if (current == null || state == null || !state.active || !shown) {
            stop()
            return START_NOT_STICKY
        }
        following?.cancel()
        following = scope.launch {
            combine(current.state, current.names) { s, n -> s to n }.collect { (s, n) ->
                if (!s.active || !foreground(s, n)) stop()
            }
        }
        return START_NOT_STICKY
    }

    /** Shows (or updates) the notification as a foreground service of type `location`; false when Android refuses it. */
    private fun foreground(state: ServiceState, names: Map<String, String>): Boolean = runCatching {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(state, names), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }.isSuccess

    private fun stop() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun notification(state: ServiceState, names: Map<String, String>): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val builder = NotificationCompat.Builder(this, Channels.LOCATION)
            .setSmallIcon(com.vettid.core.notify.R.drawable.ic_stat_vettid)
            .setContentTitle(SharingText.title(this, state, names))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // On the lock screen: that a location is shared, never with whom.
            .setPublicVersion(
                NotificationCompat.Builder(this, Channels.LOCATION)
                    .setSmallIcon(com.vettid.core.notify.R.drawable.ic_stat_vettid)
                    .setContentTitle(getString(R.string.core_location_notification_unnamed))
                    .setSilent(true)
                    .build(),
            )
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state.paused) {
            // "Check now" opens the app, where the daily check covers the screen.
            builder.addAction(0, getString(R.string.core_location_check_now), open)
        }
        val stopAll = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.addAction(0, getString(R.string.core_location_stop_all), stopAll)
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x10CA7
        internal const val ACTION_STOP_ALL = "com.vettid.core.location.STOP_ALL"

        /** The sharing of this process; the service has nothing to show without it. */
        @Volatile
        var source: LocationServiceSource? = null

        /** Starts the service. Only from the visible app (the member's tap on Start sharing, or the app coming to the front). */
        fun start(context: Context): Boolean {
            val app = context.applicationContext
            return runCatching { ContextCompat.startForegroundService(app, Intent(app, LocationService::class.java)) }.isSuccess
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, LocationService::class.java)) }
        }
    }
}
