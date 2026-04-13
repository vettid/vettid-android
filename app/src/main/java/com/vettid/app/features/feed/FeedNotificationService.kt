package com.vettid.app.features.feed

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vettid.app.MainActivity
import com.vettid.app.R
import com.vettid.app.core.nats.FeedNotification
import com.vettid.app.core.nats.IncomingMessage
import com.vettid.app.core.nats.NotificationImportance
import com.vettid.app.core.nats.OwnerSpaceClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for handling feed push notifications via NATS.
 *
 * Responsibilities:
 * - Listen to feed.new and feed.updated NATS events
 * - Show system notifications when app is in background
 * - Emit in-app events when app is in foreground
 * - Manage badge count
 */
@Singleton
class FeedNotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ownerSpaceClient: OwnerSpaceClient
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activityCount = 0
    private val isInForeground: Boolean get() = activityCount > 0

    // In-app notification events (for foreground)
    private val _inAppNotifications = MutableSharedFlow<InAppFeedNotification>(extraBufferCapacity = 32)
    val inAppNotifications: SharedFlow<InAppFeedNotification> = _inAppNotifications.asSharedFlow()

    // Feed update events (for real-time UI updates)
    private val _feedUpdates = MutableSharedFlow<FeedUpdate>(extraBufferCapacity = 32)
    val feedUpdates: SharedFlow<FeedUpdate> = _feedUpdates.asSharedFlow()

    companion object {
        private const val TAG = "FeedNotificationService"
        private const val CHANNEL_ID_URGENT = "feed_urgent"
        private const val CHANNEL_ID_DEFAULT = "feed_default"
        private const val CHANNEL_ID_SILENT = "feed_silent"
        private const val NOTIFICATION_GROUP = "feed_notifications"

        // Intent extras for notification tap handling
        const val EXTRA_EVENT_ID = "feed_event_id"
        const val EXTRA_EVENT_TYPE = "feed_event_type"
        const val EXTRA_SOURCE_ID = "feed_source_id"
        const val EXTRA_OPEN_FEED = "open_feed"
    }

    init {
        // Register activity lifecycle callbacks to track foreground/background
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    activityCount++
                    if (activityCount == 1) {
                        Log.d(TAG, "App moved to foreground")
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    activityCount--
                    if (activityCount == 0) {
                        Log.d(TAG, "App moved to background")
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
        )

        // Create notification channels
        createNotificationChannels()

        // Log notification permission status for diagnostics
        logNotificationStatus()

        // Start listening to feed notifications
        startListening()
    }

    /**
     * Log notification permission and channel status for debugging
     * notification delivery issues across devices.
     */
    private fun logNotificationStatus() {
        val notifManager = NotificationManagerCompat.from(context)
        val enabled = notifManager.areNotificationsEnabled()
        val permissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true // Pre-Android 13, notifications enabled by default
        }

        Log.i(TAG, "Notification status: enabled=$enabled, permission=$permissionGranted, " +
            "sdk=${Build.VERSION.SDK_INT}, model=${Build.MODEL}")

        if (!enabled) {
            Log.w(TAG, "NOTIFICATIONS DISABLED in system settings — user won't receive any notifications")
        }
        if (!permissionGranted) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted — notifications will fail with SecurityException")
        }

        // Check individual channels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val systemManager = context.getSystemService(NotificationManager::class.java)
            for (channelId in listOf(CHANNEL_ID_URGENT, CHANNEL_ID_DEFAULT, CHANNEL_ID_SILENT)) {
                val channel = systemManager.getNotificationChannel(channelId)
                if (channel != null) {
                    Log.d(TAG, "Channel $channelId: importance=${channel.importance}, " +
                        "enabled=${channel.importance != NotificationManager.IMPORTANCE_NONE}")
                } else {
                    Log.w(TAG, "Channel $channelId: NOT FOUND")
                }
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)

            // Urgent channel (heads-up)
            val urgentChannel = NotificationChannel(
                CHANNEL_ID_URGENT,
                "Urgent Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important alerts requiring immediate attention"
                enableVibration(true)
                setShowBadge(true)
            }

            // Default channel
            val defaultChannel = NotificationChannel(
                CHANNEL_ID_DEFAULT,
                "Feed Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Activity updates and messages"
                setShowBadge(true)
            }

            // Silent channel
            val silentChannel = NotificationChannel(
                CHANNEL_ID_SILENT,
                "Low Priority",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background updates"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(
                listOf(urgentChannel, defaultChannel, silentChannel)
            )
        }
    }

    private var isListening = false

    /**
     * Start listening for feed notifications via NATS.
     * Safe to call multiple times — only starts once.
     */
    fun startListening() {
        if (isListening) return
        isListening = true
        scope.launch {
            ownerSpaceClient.feedNotifications.collect { notification ->
                handleNotification(notification)
            }
        }
        // Listen for incoming messages directly — these have connectionId for deep-linking
        scope.launch {
            ownerSpaceClient.incomingMessages.collect { message ->
                if (!isInForeground) {
                    showMessageNotification(message)
                }
            }
        }
    }

    private suspend fun handleNotification(notification: FeedNotification) {
        Log.d(TAG, "Received feed notification: ${notification.eventId}")

        when (notification) {
            is FeedNotification.NewEvent -> handleNewEvent(notification)
            is FeedNotification.EventUpdated -> handleEventUpdated(notification)
        }
    }

    /**
     * Notification policy per event type.
     */
    private enum class NotifyPolicy {
        SILENT,           // No notification, just feed refresh + badge update
        FOREGROUND_ONLY,  // In-app snackbar only, never OS notification
        DEFAULT,          // Foreground = snackbar, Background = OS notification
        ALWAYS            // Both in-app AND OS notification (urgent: calls, security)
    }

    /**
     * Per-event-type notification rules.
     * Events not listed here default to NotifyPolicy.DEFAULT.
     */
    private val notificationPolicy = mapOf(
        // Silent — no notification, just update feed
        "guide" to NotifyPolicy.SILENT,
        "message.sent" to NotifyPolicy.SILENT,
        "message.read" to NotifyPolicy.SILENT,
        "connection.created" to NotifyPolicy.SILENT,
        "connection.accepted" to NotifyPolicy.SILENT,
        "connection.initiated" to NotifyPolicy.SILENT,
        "call.completed" to NotifyPolicy.SILENT,
        "call.ended" to NotifyPolicy.SILENT,
        "call.answered" to NotifyPolicy.SILENT,
        "call.rejected" to NotifyPolicy.SILENT,
        "backup.complete" to NotifyPolicy.SILENT,
        "handler.complete" to NotifyPolicy.SILENT,
        "vault.status" to NotifyPolicy.SILENT,
        "agent.secret.approved" to NotifyPolicy.SILENT,
        "agent.secret.auto_approved" to NotifyPolicy.SILENT,
        "agent.secret.denied" to NotifyPolicy.SILENT,
        "agent.action.completed" to NotifyPolicy.SILENT,
        "agent.action.denied" to NotifyPolicy.SILENT,
        "agent.message.sent" to NotifyPolicy.SILENT,
        "agent.approval.responded" to NotifyPolicy.SILENT,
        "agent.connection.approved" to NotifyPolicy.SILENT,
        "agent.connection.denied" to NotifyPolicy.SILENT,
        "connection.rotated" to NotifyPolicy.SILENT,

        // Foreground only — snackbar but no OS notification
        "connection.revoked" to NotifyPolicy.FOREGROUND_ONLY,

        // Always notify — even in foreground (urgent events)
        "call.incoming" to NotifyPolicy.ALWAYS,
        "security.alert" to NotifyPolicy.ALWAYS,
    )

    /**
     * Notification group for grouping related notifications.
     */
    private fun getNotificationGroup(eventType: String): String? = when {
        eventType.startsWith("connection.") -> "vettid_connections"
        eventType.startsWith("message.") -> "vettid_messages"
        eventType.startsWith("agent.") -> "vettid_agents"
        eventType.startsWith("call.") -> "vettid_calls"
        eventType.startsWith("wallet.") || eventType.startsWith("payment.") -> "vettid_payments"
        else -> null
    }

    private suspend fun handleNewEvent(event: FeedNotification.NewEvent) {
        // Always emit feed update for real-time UI refresh
        _feedUpdates.emit(FeedUpdate.NewEvent(event.eventId, event.eventType))

        val policy = notificationPolicy[event.eventType] ?: NotifyPolicy.DEFAULT

        when (policy) {
            NotifyPolicy.SILENT -> {
                // No notification — just badge update
            }
            NotifyPolicy.FOREGROUND_ONLY -> {
                if (isInForeground) {
                    emitInAppNotification(event)
                }
            }
            NotifyPolicy.DEFAULT -> {
                if (isInForeground) {
                    emitInAppNotification(event)
                } else {
                    showSystemNotification(event)
                }
            }
            NotifyPolicy.ALWAYS -> {
                // Always show OS notification (calls, security alerts)
                showSystemNotification(event)
                if (isInForeground) {
                    emitInAppNotification(event)
                }
            }
        }

        updateBadgeCount()
    }

    private suspend fun emitInAppNotification(event: FeedNotification.NewEvent) {
        _inAppNotifications.emit(
            InAppFeedNotification(
                eventId = event.eventId,
                title = event.title,
                message = event.message,
                eventType = event.eventType,
                priority = event.priority,
                hasAction = event.actionType != null
            )
        )
    }

    private suspend fun handleEventUpdated(event: FeedNotification.EventUpdated) {
        // Emit feed update for real-time UI refresh
        _feedUpdates.emit(FeedUpdate.StatusChanged(event.eventId, event.newStatus))

        // Clear notification if archived or deleted
        if (event.newStatus in listOf("archived", "deleted")) {
            cancelNotification(event.eventId)
        }

        // Update badge count
        updateBadgeCount()
    }

    private fun showSystemNotification(event: FeedNotification.NewEvent) {
        val channelId = when (event.importance) {
            NotificationImportance.URGENT -> CHANNEL_ID_URGENT
            NotificationImportance.HIGH -> CHANNEL_ID_DEFAULT
            NotificationImportance.DEFAULT -> CHANNEL_ID_DEFAULT
            NotificationImportance.LOW -> CHANNEL_ID_SILENT
        }

        val priority = when (event.importance) {
            NotificationImportance.URGENT -> NotificationCompat.PRIORITY_HIGH
            NotificationImportance.HIGH -> NotificationCompat.PRIORITY_DEFAULT
            NotificationImportance.DEFAULT -> NotificationCompat.PRIORITY_LOW
            NotificationImportance.LOW -> NotificationCompat.PRIORITY_MIN
        }

        // Create intent that deep-links to the relevant screen after PIN unlock
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_FEED, true)
            putExtra(EXTRA_EVENT_ID, event.eventId)
            putExtra(EXTRA_EVENT_TYPE, event.eventType)
            event.sourceId?.let { putExtra(EXTRA_SOURCE_ID, it) }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            event.eventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(getNotificationIcon(event.eventType))
            .setContentTitle(event.title)
            .setContentText(event.message ?: getDefaultMessage(event.eventType))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(getNotificationGroup(event.eventType) ?: NOTIFICATION_GROUP)
            .setCategory(getNotificationCategory(event.eventType))
            .apply {
                // Add action buttons for actionable events
                if (event.actionType == "accept_decline") {
                    addAction(
                        NotificationCompat.Action.Builder(
                            R.drawable.ic_launcher_foreground,
                            "Accept",
                            createActionPendingIntent(event.eventId, "accept")
                        ).build()
                    )
                    addAction(
                        NotificationCompat.Action.Builder(
                            R.drawable.ic_launcher_foreground,
                            "Decline",
                            createActionPendingIntent(event.eventId, "decline")
                        ).build()
                    )
                }
            }
            .build()

        val notifManager = NotificationManagerCompat.from(context)
        if (!notifManager.areNotificationsEnabled()) {
            Log.w(TAG, "Skipping notification for ${event.eventId} — notifications disabled in system settings")
            return
        }
        try {
            notifManager.notify(event.eventId.hashCode(), notification)
            Log.d(TAG, "Showed notification: type=${event.eventType}, title=${event.title}, fg=$isInForeground")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted for ${event.eventId} — " +
                "sdk=${Build.VERSION.SDK_INT}, model=${Build.MODEL}", e)
        }
    }

    /**
     * Show a notification for an incoming message with deep-link to the conversation.
     * This is triggered directly by the new-message push (not feed.new), so it always
     * has the connectionId for navigation.
     */
    private fun showMessageNotification(message: IncomingMessage) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_FEED, true)
            putExtra(EXTRA_EVENT_TYPE, "message.received")
            putExtra(EXTRA_SOURCE_ID, message.connectionId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            message.messageId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val preview = message.content?.take(100) ?: "New message"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DEFAULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New Message")
            .setContentText(preview)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup("vettid_messages")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val notifManager = NotificationManagerCompat.from(context)
        if (!notifManager.areNotificationsEnabled()) return
        try {
            notifManager.notify(message.messageId.hashCode(), notification)
            Log.d(TAG, "Showed message notification for connection ${message.connectionId}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted for message", e)
        }
    }

    private fun createActionPendingIntent(eventId: String, action: String): PendingIntent {
        val intent = Intent(context, FeedActionReceiver::class.java).apply {
            this.action = "com.vettid.app.FEED_ACTION"
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra("action", action)
        }
        return PendingIntent.getBroadcast(
            context,
            "$eventId-$action".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun getNotificationIcon(eventType: String): Int {
        return R.drawable.ic_notification
    }

    private fun getDefaultMessage(eventType: String): String {
        return when {
            eventType.startsWith("call.") -> "Tap to view"
            eventType.startsWith("message.") -> "New message"
            eventType.startsWith("connection.") -> "Connection update"
            eventType.startsWith("security.") -> "Security alert"
            eventType.startsWith("transfer.") -> "Transfer request"
            else -> "Tap to view"
        }
    }

    private fun getNotificationCategory(eventType: String): String {
        return when {
            eventType.startsWith("call.") -> NotificationCompat.CATEGORY_CALL
            eventType.startsWith("message.") -> NotificationCompat.CATEGORY_MESSAGE
            eventType.startsWith("security.") -> NotificationCompat.CATEGORY_ALARM
            else -> NotificationCompat.CATEGORY_EVENT
        }
    }

    private fun cancelNotification(eventId: String) {
        NotificationManagerCompat.from(context).cancel(eventId.hashCode())
    }

    private fun updateBadgeCount() {
        // Badge count is updated via the Feed screen observing feed state
        // This is a placeholder for future ShortcutBadger integration
    }

    /**
     * Show a welcome notification after the user grants notification permission.
     * Validates the notification pipeline works end-to-end.
     */
    fun showWelcomeNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_FEED, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DEFAULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Welcome to VettID")
            .setContentText("Your vault is set up. You'll receive notifications for important events here.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify("welcome".hashCode(), notification)
            Log.d(TAG, "Showed welcome notification")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted for welcome", e)
        }
    }

    /**
     * Clear all feed notifications.
     * Call this when user opens the Feed screen.
     */
    fun clearAllNotifications() {
        NotificationManagerCompat.from(context).cancelAll()
    }
}

/**
 * In-app notification for when the app is in foreground.
 * Display as toast/snackbar.
 */
data class InAppFeedNotification(
    val eventId: String,
    val title: String,
    val message: String?,
    val eventType: String,
    val priority: Int,
    val hasAction: Boolean
)

/**
 * Feed update events for real-time UI updates.
 */
sealed class FeedUpdate {
    data class NewEvent(val eventId: String, val eventType: String) : FeedUpdate()
    data class StatusChanged(val eventId: String, val newStatus: String) : FeedUpdate()
}
