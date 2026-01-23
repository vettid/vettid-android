package com.vettid.app.features.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.features.services.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the notifications screen.
 *
 * Handles:
 * - Loading notifications
 * - Mark as read
 * - Delete notifications
 * - System notification display
 *
 * Issue #36 [AND-023] - Notification display.
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _notifications = MutableStateFlow<List<ServiceNotification>>(emptyList())
    val notifications: StateFlow<List<ServiceNotification>> = _notifications.asStateFlow()

    val unreadCount: StateFlow<Int> = notifications.map { list ->
        list.count { !it.isRead }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val notificationManager = NotificationManagerCompat.from(context)

    companion object {
        private const val CHANNEL_ID = "service_notifications"
        private const val CHANNEL_NAME = "Service Notifications"
    }

    init {
        createNotificationChannel()
        loadNotifications()
    }

    /**
     * Create the notification channel for Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from connected services"
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Load notifications from storage.
     */
    private fun loadNotifications() {
        viewModelScope.launch {
            _isLoading.value = true

            // TODO: Implement actual loading from storage/NATS
            // For now, return empty list
            _notifications.value = emptyList()

            _isLoading.value = false
        }
    }

    /**
     * Handle incoming notification from a service.
     */
    fun handleNotification(notification: ServiceNotification) {
        _notifications.update { current ->
            listOf(notification) + current
        }

        showSystemNotification(notification)
    }

    /**
     * Show a system notification.
     */
    private fun showSystemNotification(notification: ServiceNotification) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: Use app icon
                .setContentTitle(notification.serviceName)
                .setContentText(notification.title)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(notification.body)
                )
                .setPriority(notification.priority.androidPriority)
                .setAutoCancel(true)

            notificationManager.notify(notification.id.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // Notification permission not granted
            android.util.Log.w("NotificationsVM", "Notification permission not granted", e)
        }
    }

    /**
     * Mark a notification as read.
     */
    fun markAsRead(notificationId: String) {
        _notifications.update { current ->
            current.map { notification ->
                if (notification.id == notificationId) {
                    notification.copy(isRead = true)
                } else {
                    notification
                }
            }
        }
    }

    /**
     * Mark all notifications as read.
     */
    fun markAllAsRead() {
        _notifications.update { current ->
            current.map { it.copy(isRead = true) }
        }
    }

    /**
     * Delete a notification.
     */
    fun delete(notificationId: String) {
        _notifications.update { current ->
            current.filter { it.id != notificationId }
        }

        // Also cancel system notification
        notificationManager.cancel(notificationId.hashCode())
    }

    /**
     * Clear all notifications.
     */
    fun clearAll() {
        _notifications.value = emptyList()
        notificationManager.cancelAll()
    }
}
