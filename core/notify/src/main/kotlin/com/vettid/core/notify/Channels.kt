package com.vettid.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The notification channels (ANDROID-PLAN 0.1.23, Notification modes 6): stable ids, translated names, created at
 * first start; the member changes each in Android settings. Every notification is private on the lock screen
 * ([FeedNotifier]). "Vault updates" (`vault_updates`, 0.1.19) belongs to the app's release notice and keeps its id.
 */
object Channels {
    const val MESSAGES = "messages"
    const val REQUESTS = "requests"
    const val CALLS = "calls"
    const val SECURITY = "security"
    const val OWNER_CHECK = "owner_check"
    const val ACTIVITY = "activity"
    const val VAULT_UPDATES = "vault_updates"
    const val CONNECTION = "connection"

    /** id → (name, description, importance). */
    @Suppress("MaxLineLength") // one channel per row
    private val all = listOf(
        Triple(MESSAGES, R.string.notify_channel_messages to R.string.notify_channel_messages_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(REQUESTS, R.string.notify_channel_requests to R.string.notify_channel_requests_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(CALLS, R.string.notify_channel_calls to R.string.notify_channel_calls_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(SECURITY, R.string.notify_channel_security to R.string.notify_channel_security_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(OWNER_CHECK, R.string.notify_channel_owner_check to R.string.notify_channel_owner_check_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(ACTIVITY, R.string.notify_channel_activity to R.string.notify_channel_activity_body, NotificationManager.IMPORTANCE_LOW),
        Triple(CONNECTION, R.string.notify_channel_connection to R.string.notify_channel_connection_body, NotificationManager.IMPORTANCE_MIN),
    )

    val ids: List<String> get() = all.map { it.first }

    /** Creates (or renames, in a new language) the channels; the member's own changes to them are kept by Android. */
    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            all.map { (id, texts, importance) ->
                NotificationChannel(id, context.getString(texts.first), importance).apply {
                    description = context.getString(texts.second)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    if (id == CONNECTION) setShowBadge(false)
                }
            },
        )
    }
}
