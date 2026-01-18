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
import com.vettid.app.core.nats.DeviceInfo
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
        const val TRANSFER_ALERT_NOTIFICATION_ID = 1002
        const val FRAUD_ALERT_NOTIFICATION_ID = 1003

        // Intent actions
        const val ACTION_START = "com.vettid.app.START_PROTECTION"
        const val ACTION_STOP = "com.vettid.app.STOP_PROTECTION"
        const val ACTION_CANCEL_RECOVERY = "com.vettid.app.CANCEL_RECOVERY"
        const val ACTION_APPROVE_TRANSFER = "com.vettid.app.APPROVE_TRANSFER"
        const val ACTION_DENY_TRANSFER = "com.vettid.app.DENY_TRANSFER"

        // Intent extras
        const val EXTRA_REQUEST_ID = "recovery_request_id"
        const val EXTRA_TRANSFER_ID = "transfer_id"

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
            ACTION_APPROVE_TRANSFER -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)
                if (transferId != null) {
                    handleApproveTransfer(transferId)
                }
            }
            ACTION_DENY_TRANSFER -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)
                if (transferId != null) {
                    handleDenyTransfer(transferId)
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
            // Recovery events
            is VaultEvent.RecoveryRequested -> {
                Log.i(TAG, "Recovery requested: ${event.requestId}")
                showRecoveryAlert(event.requestId, event.email)
            }
            is VaultEvent.RecoveryCancelled -> {
                Log.i(TAG, "Recovery cancelled: ${event.requestId}, reason: ${event.reason}")
                dismissRecoveryAlert()
                // If cancelled due to fraud detection, the fraud alert will be shown separately
            }
            is VaultEvent.RecoveryCompleted -> {
                Log.w(TAG, "Recovery completed on another device: ${event.requestId}")
                dismissRecoveryAlert()
                showRecoveryCompletedWarning()
            }

            // Transfer events (Issue #31)
            is VaultEvent.TransferRequested -> {
                Log.i(TAG, "Transfer requested: ${event.transferId}")
                showTransferRequestAlert(event.transferId, event.targetDeviceInfo)
            }
            is VaultEvent.TransferApproved -> {
                Log.i(TAG, "Transfer approved: ${event.transferId}")
                dismissTransferAlert()
                showTransferApprovedNotification()
            }
            is VaultEvent.TransferDenied -> {
                Log.i(TAG, "Transfer denied: ${event.transferId}")
                dismissTransferAlert()
            }
            is VaultEvent.TransferCompleted -> {
                Log.i(TAG, "Transfer completed: ${event.transferId}")
                dismissTransferAlert()
            }
            is VaultEvent.TransferExpired -> {
                Log.i(TAG, "Transfer expired: ${event.transferId}")
                dismissTransferAlert()
                showTransferExpiredNotification()
            }

            // Fraud detection events (Issue #32)
            is VaultEvent.RecoveryFraudDetected -> {
                Log.w(TAG, "Recovery fraud detected: ${event.requestId}, reason: ${event.reason}")
                showFraudDetectedNotification(event.requestId, event.reason)
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

    /**
     * Show warning that credentials were recovered on another device.
     */
    private fun showRecoveryCompletedWarning() {
        val notification = NotificationCompat.Builder(this, SECURITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Credentials Recovered Elsewhere")
            .setContentText("Your credentials were recovered on another device. If this wasn't you, contact support immediately.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Your credentials were recovered on another device. " +
                "If this wasn't you, your account may be compromised. " +
                "Contact support immediately."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(RECOVERY_ALERT_NOTIFICATION_ID, notification)
    }

    // MARK: - Transfer Notifications (Issue #31)

    /**
     * Show a high-priority notification for transfer request.
     * This notification appears on the OLD device when a new device requests a transfer.
     */
    private fun showTransferRequestAlert(transferId: String, deviceInfo: DeviceInfo) {
        Log.i(TAG, "Showing transfer alert for: $transferId from ${deviceInfo.model}")

        // Approve action
        val approveIntent = Intent(this, VaultProtectionService::class.java).apply {
            action = ACTION_APPROVE_TRANSFER
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        val approvePendingIntent = PendingIntent.getService(
            this,
            10,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Deny action
        val denyIntent = Intent(this, VaultProtectionService::class.java).apply {
            action = ACTION_DENY_TRANSFER
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        val denyPendingIntent = PendingIntent.getService(
            this,
            11,
            denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Open app to transfer approval screen
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "transfer_approval")
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            12,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deviceDescription = buildString {
            append(deviceInfo.model)
            if (deviceInfo.osVersion.isNotEmpty()) {
                append(" (${deviceInfo.osVersion})")
            }
            deviceInfo.location?.let { append(" from $it") }
        }

        val bodyText = "$deviceDescription is requesting your credentials. " +
                "This request expires in 15 minutes. " +
                "Only approve if you initiated this transfer."

        val notification = NotificationCompat.Builder(this, SECURITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Credential Transfer Request")
            .setContentText("$deviceDescription is requesting your credentials")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true) // Can't swipe away - must take action
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Approve",
                approvePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Deny",
                denyPendingIntent
            )
            .build()

        notificationManager.notify(TRANSFER_ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Dismiss the transfer alert notification.
     */
    private fun dismissTransferAlert() {
        notificationManager.cancel(TRANSFER_ALERT_NOTIFICATION_ID)
        Log.d(TAG, "Transfer alert dismissed")
    }

    /**
     * Show notification that transfer was approved.
     */
    private fun showTransferApprovedNotification() {
        val notification = NotificationCompat.Builder(this, SECURITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Transfer Approved")
            .setContentText("Your credentials are being transferred to the new device.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(TRANSFER_ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Show notification that transfer request expired.
     */
    private fun showTransferExpiredNotification() {
        val notification = NotificationCompat.Builder(this, SECURITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Transfer Request Expired")
            .setContentText("The credential transfer request has expired. The new device will need to request again.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(TRANSFER_ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Handle approve transfer action from notification.
     */
    private fun handleApproveTransfer(transferId: String) {
        Log.i(TAG, "Approving transfer: $transferId")

        serviceScope.launch {
            try {
                // TODO: Require biometric authentication before approving
                // For now, send approval via NATS
                val result = ownerSpaceClient.sendToVault(
                    "transfer.approve",
                    com.google.gson.JsonObject().apply {
                        addProperty("transfer_id", transferId)
                        addProperty("approved", true)
                    }
                )

                result.onSuccess {
                    Log.i(TAG, "Transfer approval sent: $transferId")
                    dismissTransferAlert()
                    showTransferApprovedNotification()
                }.onFailure { error ->
                    Log.e(TAG, "Failed to approve transfer", error)
                    // Keep notification visible so user can retry
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error approving transfer", e)
            }
        }
    }

    /**
     * Handle deny transfer action from notification.
     */
    private fun handleDenyTransfer(transferId: String) {
        Log.i(TAG, "Denying transfer: $transferId")

        serviceScope.launch {
            try {
                val result = ownerSpaceClient.sendToVault(
                    "transfer.deny",
                    com.google.gson.JsonObject().apply {
                        addProperty("transfer_id", transferId)
                    }
                )

                result.onSuccess {
                    Log.i(TAG, "Transfer denial sent: $transferId")
                    dismissTransferAlert()
                }.onFailure { error ->
                    Log.e(TAG, "Failed to deny transfer", error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error denying transfer", e)
            }
        }
    }

    // MARK: - Fraud Detection Notifications (Issue #32)

    /**
     * Show notification when recovery was auto-cancelled due to credential usage.
     */
    @Suppress("UNUSED_PARAMETER") // reason may be used for more detailed messaging in future
    private fun showFraudDetectedNotification(requestId: String, reason: String) {
        Log.i(TAG, "Showing fraud detection alert for: $requestId")

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "security")
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            20,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = "A recovery request was automatically cancelled because your credential " +
                "was used from this device. This may indicate someone tried to recover your " +
                "credentials without your knowledge. Your credentials remain secure."

        val notification = NotificationCompat.Builder(this, SECURITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recovery Auto-Cancelled")
            .setContentText("A suspicious recovery attempt was blocked.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_view,
                "View Details",
                openPendingIntent
            )
            .build()

        notificationManager.notify(FRAUD_ALERT_NOTIFICATION_ID, notification)
    }
}
