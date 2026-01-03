package com.vettid.app.core.notifications

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
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultEvent
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.ProteanCredentialManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that maintains NATS connection for real-time security alerts.
 *
 * This service:
 * 1. Keeps NATS connection alive in the background
 * 2. Listens for recovery_requested events from the vault
 * 3. Shows high-priority notifications for security events
 * 4. Allows user to cancel unauthorized recovery attempts
 *
 * The persistent notification shows "Vault protection active" to indicate
 * the app is monitoring for security events.
 */
@AndroidEntryPoint
class VaultProtectionService : Service() {

    companion object {
        private const val TAG = "VaultProtection"

        // Notification channels
        const val PROTECTION_CHANNEL_ID = "vettid_protection"
        const val PROTECTION_CHANNEL_NAME = "Vault Protection"
        const val SECURITY_CHANNEL_ID = "vettid_security"
        const val SECURITY_CHANNEL_NAME = "Security Alerts"

        // Notification IDs
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val RECOVERY_ALERT_NOTIFICATION_ID = 1001

        // Intent actions
        const val ACTION_START = "com.vettid.app.START_PROTECTION"
        const val ACTION_STOP = "com.vettid.app.STOP_PROTECTION"
        const val ACTION_CANCEL_RECOVERY = "com.vettid.app.CANCEL_RECOVERY"

        // Intent extras
        const val EXTRA_REQUEST_ID = "recovery_request_id"

        /**
         * Start the vault protection service.
         */
        fun start(context: Context) {
            val intent = Intent(context, VaultProtectionService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the vault protection service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, VaultProtectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    @Inject lateinit var connectionManager: NatsConnectionManager
    @Inject lateinit var ownerSpaceClient: OwnerSpaceClient
    @Inject lateinit var credentialStore: CredentialStore
    @Inject lateinit var credentialManager: ProteanCredentialManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VaultProtectionService created")
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting vault protection service")
                startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
                startListening()
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping vault protection service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_CANCEL_RECOVERY -> {
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                if (requestId != null) {
                    handleCancelRecovery(requestId)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "VaultProtectionService destroyed")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Low-priority channel for foreground service
            val protectionChannel = NotificationChannel(
                PROTECTION_CHANNEL_ID,
                PROTECTION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when vault protection is active"
                setShowBadge(false)
            }

            // High-priority channel for security alerts
            val securityChannel = NotificationChannel(
                SECURITY_CHANNEL_ID,
                SECURITY_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important security notifications"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannels(listOf(protectionChannel, securityChannel))
        }
    }

    private fun createForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PROTECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // TODO: Use shield icon
            .setContentTitle("VettID")
            .setContentText("Vault protection active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Start listening for security events via NATS.
     */
    private fun startListening() {
        serviceScope.launch {
            // Subscribe to vault events
            ownerSpaceClient.subscribeToVault()

            // Listen for recovery events
            ownerSpaceClient.vaultEvents
                .catch { e ->
                    Log.e(TAG, "Error in vault event stream", e)
                }
                .collect { event ->
                    handleVaultEvent(event)
                }
        }

        Log.d(TAG, "Started listening for security events")
    }

    /**
     * Handle vault events, filtering for security-related ones.
     */
    private fun handleVaultEvent(event: VaultEvent) {
        when (event) {
            is VaultEvent.RecoveryRequested -> {
                Log.i(TAG, "Recovery requested: ${event.requestId}")
                showRecoveryAlert(event.requestId, event.email)
            }
            is VaultEvent.RecoveryCancelled -> {
                Log.i(TAG, "Recovery cancelled: ${event.requestId}")
                dismissRecoveryAlert()
            }
            is VaultEvent.RecoveryCompleted -> {
                Log.w(TAG, "Recovery completed on another device: ${event.requestId}")
                dismissRecoveryAlert()
                // TODO: Show warning that credentials were recovered elsewhere
            }
            else -> {
                // Ignore other vault events
            }
        }
    }

    /**
     * Show a high-priority notification for recovery request.
     */
    private fun showRecoveryAlert(requestId: String, email: String?) {
        Log.i(TAG, "Showing recovery alert for request: $requestId")

        // Cancel action
        val cancelIntent = Intent(this, VaultProtectionService::class.java).apply {
            action = ACTION_CANCEL_RECOVERY
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Open app action
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "recovery")
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = if (email != null) {
            "Someone requested to recover your credentials for $email. If this wasn't you, tap Cancel immediately."
        } else {
            "Someone requested to recover your credentials. If this wasn't you, tap Cancel immediately."
        }

        val notification = NotificationCompat.Builder(this, SECURITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // TODO: Warning icon
            .setContentTitle("⚠️ Recovery Requested")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true) // Can't swipe away - must take action
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel Recovery",
                cancelPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_view,
                "View Details",
                openPendingIntent
            )
            .build()

        notificationManager.notify(RECOVERY_ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Dismiss the recovery alert notification.
     */
    private fun dismissRecoveryAlert() {
        notificationManager.cancel(RECOVERY_ALERT_NOTIFICATION_ID)
        Log.d(TAG, "Recovery alert dismissed")
    }

    /**
     * Handle the cancel recovery action.
     */
    private fun handleCancelRecovery(requestId: String) {
        Log.i(TAG, "Cancelling recovery: $requestId")

        serviceScope.launch {
            try {
                // Get auth token from credential store
                val authToken = credentialStore.getAuthToken()
                if (authToken == null) {
                    Log.e(TAG, "Cannot cancel recovery - not authenticated")
                    // TODO: Show notification asking user to open app and authenticate
                    return@launch
                }

                val result = credentialManager.cancelRecovery(requestId, authToken)
                result.onSuccess {
                    Log.i(TAG, "Recovery cancelled successfully")
                    dismissRecoveryAlert()

                    // Show success notification
                    showCancelSuccessNotification()
                }.onFailure { error ->
                    Log.e(TAG, "Failed to cancel recovery", error)
                    // TODO: Show error notification
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling recovery", e)
            }
        }
    }

    private fun showCancelSuccessNotification() {
        val notification = NotificationCompat.Builder(this, SECURITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recovery Cancelled")
            .setContentText("The recovery request has been cancelled. Your credentials are safe.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(RECOVERY_ALERT_NOTIFICATION_ID, notification)
    }
}
