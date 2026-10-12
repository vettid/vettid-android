package com.vettid.app.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * C0 SPIKE: a foreground service of type `microphone`, started from the visible spike screen, so that the call's
 * audio continues with the screen off or another app in front. It does nothing itself: the call lives in
 * [SpikeSession]. A real call service (C1) is of type `phoneCall` with Telecom; see the PR's findings.
 */
class CallsSpikeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
            .onSuccess { SpikeSession.note("foreground service (microphone) running") }
            .onFailure {
                SpikeSession.note("foreground service refused: ${it.javaClass.simpleName}")
                stopSelf()
            }
        if (SpikeSession.engine.value == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Calls spike", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, CallsSpikeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("VettID calls spike")
            .setContentText("A test call is open")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CHANNEL = "calls_spike"
        const val NOTIFICATION_ID = 4200
    }
}
