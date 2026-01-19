package com.vettid.app.features.feed

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vettid.app.MainActivity
import com.vettid.app.R
import com.vettid.app.core.nats.FeedNotification
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

        // Start listening to feed notifications
        startListening()
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

    private fun startListening() {
        scope.launch {
            ownerSpaceClient.feedNotifications.collect { notification ->
                handleNotification(notification)
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

    private suspend fun handleNewEvent(event: FeedNotification.NewEvent) {
        // Emit feed update for real-time UI refresh
        _feedUpdates.emit(FeedUpdate.NewEvent(event.eventId, event.eventType))

        if (isInForeground) {
            // Show in-app notification (toast/snackbar)
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
        } else {
            // Show system notification
            showSystemNotification(event)
        }

        // Update badge count
        updateBadgeCount()
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

        // Create intent to open Feed screen
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_FEED, true)
            putExtra(EXTRA_EVENT_ID, event.eventId)
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
            .setGroup(NOTIFICATION_GROUP)
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

        try {
            NotificationManagerCompat.from(context)
                .notify(event.eventId.hashCode(), notification)
            Log.d(TAG, "Showed system notification for ${event.eventId}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted", e)
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
        // Use app icon for now - in production, use event-type specific icons
        return R.drawable.ic_launcher_foreground
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
