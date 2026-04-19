package com.vettid.app.features.calling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vettid.app.MainActivity
import com.vettid.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that shows a full-screen notification for incoming calls.
 * Ensures incoming calls are visible even when the app is backgrounded.
 */
@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject
    lateinit var callManager: CallManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val TAG = "CallForegroundService"
        private const val CHANNEL_ID = "vettid_incoming_call"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_ANSWER = "com.vettid.app.ACTION_ANSWER_CALL"
        private const val ACTION_DECLINE = "com.vettid.app.ACTION_DECLINE_CALL"
        private const val EXTRA_CALLER_NAME = "caller_name"
        private const val EXTRA_CALL_TYPE = "call_type"
        private const val EXTRA_CALL_ID = "call_id"

        fun showIncomingCall(context: Context, callerName: String, callType: String, callId: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALL_TYPE, callType)
                putExtra(EXTRA_CALL_ID, callId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CallForegroundService", e)
            }
        }

        fun dismiss(context: Context) {
            try {
                context.stopService(Intent(context, CallForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop CallForegroundService", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent.action
        if (action == ACTION_ANSWER) {
            // Fire answer on CallManager's long-lived scope — the service
            // stops itself below, so launching on the service's own scope
            // would cancel the coroutine mid-SDP-exchange and leave the
            // call ringing even though the user tapped Answer.
            callManager.acceptFromNotification()
            // Bring the app to the foreground so the active-call UI can
            // render. Routing is driven by CallManager's showCallUI flow.
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_DECLINE) {
            callManager.rejectFromNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
        val callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "voice"
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""

        val notification = buildIncomingCallNotification(callerName, callType, callId)
        startForeground(NOTIFICATION_ID, notification)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.d(TAG, "CallForegroundService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming voice and video calls"
                setSound(null, null) // Ringtone handled by CallManager
                enableVibration(false) // Vibration handled by CallManager
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildIncomingCallNotification(callerName: String, callType: String, callId: String): Notification {
        // Full-screen intent to open the app
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            action = "INCOMING_CALL"
            putExtra(EXTRA_CALL_ID, callId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Answer action
        val answerIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_ANSWER
            putExtra(EXTRA_CALL_ID, callId)
        }
        val answerPendingIntent = PendingIntent.getService(
            this, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline action
        val declineIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_DECLINE
            putExtra(EXTRA_CALL_ID, callId)
        }
        val declinePendingIntent = PendingIntent.getService(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callTypeText = if (callType == "VIDEO") "Video Call" else "Voice Call"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            // ic_notification is the monochrome white-on-transparent tower —
            // status-bar icons must use alpha-only assets or Android renders
            // them as a white square.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(callerName)
            .setContentText("Incoming $callTypeText")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(0, "Decline", declinePendingIntent)
            .addAction(0, "Answer", answerPendingIntent)
            .build()
    }
}
