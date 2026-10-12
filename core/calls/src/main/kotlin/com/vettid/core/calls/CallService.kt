package com.vettid.core.calls

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vettid.core.notify.Channels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the call service shows and can do; the calls controller is it. */
interface CallServiceHost {
    val state: StateFlow<CallState>

    /**
     * The name to show for [connectionId] in the call's notification, or null (it then says "VettID call"): null
     * too when the member's notifications show no names (the same choice the message notifications follow).
     */
    fun displayName(connectionId: String): String?

    fun hangUp()
}

/**
 * Keeps a call alive, and its microphone, when the app is no longer in front: a foreground service of types
 * `phoneCall` and `microphone` for the life of a call, with one silent, ongoing notification (the connection's name,
 * "Call in progress", Hang up). It is started from the visible app (the member tapped Call or Answer); a call that
 * rings with the app closed is C3. It holds nothing of the call: it follows [CallServiceHost.state] and stops when
 * no call is live.
 */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("ReturnCount")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = host
        if (intent?.action == ACTION_HANG_UP) current?.hangUp()
        val live = current?.state?.value as? CallState.Live
        if (current == null || live == null) {
            // Started with nothing to show (the process was restarted, or the call ended meanwhile): go away at once.
            foreground(null, null)
            stop()
            return START_NOT_STICKY
        }
        if (!foreground(current, live)) {
            // Android refused the service for this call: without it the call would lose the microphone (or the
            // app be ended for not showing the service it promised). The call ends instead.
            current.hangUp()
            stop()
            return START_NOT_STICKY
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            current.state.collect { s ->
                val shown = s is CallState.Live && s.phase != CallPhase.INCOMING && foreground(current, s)
                if (!shown) stop()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Shows the service. With both types; if Android refuses the microphone type (the permission was taken away,
     * or the app is not in front), with `phoneCall` alone, which keeps the call's process alive. False if neither
     * was taken.
     */
    private fun foreground(host: CallServiceHost?, live: CallState.Live?): Boolean {
        val n = notification(host, live)
        val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return listOf(both, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL).any { types ->
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, types) }.isSuccess
        }
    }

    private fun stop() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun notification(host: CallServiceHost?, live: CallState.Live?): Notification {
        Channels.ensure(this)
        val name = live?.let { host?.displayName(it.connectionId) } ?: getString(R.string.calls_notification_unnamed)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val hangUp = PendingIntent.getService(
            this,
            1,
            Intent(this, CallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // On the lock screen: that a call is going on, never with whom (the public version has no name).
        val public = NotificationCompat.Builder(this, Channels.CALLS)
            .setSmallIcon(com.vettid.core.notify.R.drawable.ic_stat_vettid)
            .setContentTitle(getString(R.string.calls_notification_public))
            .setContentText(getString(R.string.calls_notification_ongoing))
            .build()
        return NotificationCompat.Builder(this, Channels.CALLS)
            .setSmallIcon(com.vettid.core.notify.R.drawable.ic_stat_vettid)
            .setContentText(getString(R.string.calls_notification_ongoing))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(Person.Builder().setName(name).build(), hangUp))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x7CA11
        private const val ACTION_HANG_UP = "com.vettid.core.calls.HANG_UP"

        /** The calls controller of this process; the service has nothing to show without it. */
        @Volatile
        var host: CallServiceHost? = null

        /** Starts the service for the live call. Only from the visible app (a tap on Call or Answer). */
        fun start(context: Context) {
            val app = context.applicationContext
            runCatching { ContextCompat.startForegroundService(app, Intent(app, CallService::class.java)) }
        }
    }
}
