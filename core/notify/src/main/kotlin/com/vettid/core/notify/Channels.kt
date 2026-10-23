package com.vettid.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import androidx.annotation.RawRes

/**
 * The notification channels (ANDROID-PLAN 0.1.23, Notification modes 6): stable ids, translated names, created at
 * first start; the member changes each in Android settings. Every notification is private on the lock screen
 * ([FeedNotifier]). "Vault updates" ([VAULT_UPDATES], 0.1.19) belongs to the app's release notice, which creates it.
 *
 * Sounds (owner decision 2026-10-23): VettID's own sounds (`res/raw/vettid_*`, sources in `tools/sounds`). Android
 * fixes a channel's sound when the channel is first created, so every channel whose sound changed got a new id once
 * (`<id>_v2`); the old ids ([RETIRED]) are deleted at start. Activity and Connection stay silent with their ids.
 * Calls is for ringing alone (the ringtone, looping); a missed call goes to [MISSED_CALLS].
 */
object Channels {
    const val MESSAGES = "messages_v2"
    const val REQUESTS = "requests_v2"
    const val CALLS = "calls_v2"

    /** Missed calls (owner decision 2026-10-23): their own channel, with the message sound; a new id. */
    const val MISSED_CALLS = "missed_calls_v2"
    const val SECURITY = "security_v2"
    const val OWNER_CHECK = "owner_check_v2"
    const val ACTIVITY = "activity"
    const val VAULT_UPDATES = "vault_updates_v2"
    const val CONNECTION = "connection"

    /** The ids the channels had before their sounds; deleted at start, never posted to. */
    val RETIRED = listOf("messages", "requests", "calls", "security", "owner_check", "vault_updates")

    /** Each channel's sound (a raw resource); a channel not named here is silent. */
    val SOUNDS: Map<String, Int> = mapOf(
        MESSAGES to R.raw.vettid_message,
        REQUESTS to R.raw.vettid_urgent,
        CALLS to R.raw.vettid_ringtone,
        MISSED_CALLS to R.raw.vettid_message,
        SECURITY to R.raw.vettid_urgent,
        OWNER_CHECK to R.raw.vettid_urgent,
        VAULT_UPDATES to R.raw.vettid_general,
    )

    /** id → (name, description, importance). */
    @Suppress("MaxLineLength") // one channel per row
    private val all = listOf(
        Triple(MESSAGES, R.string.notify_channel_messages to R.string.notify_channel_messages_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(REQUESTS, R.string.notify_channel_requests to R.string.notify_channel_requests_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(CALLS, R.string.notify_channel_calls to R.string.notify_channel_calls_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(MISSED_CALLS, R.string.notify_channel_missed_calls to R.string.notify_channel_missed_calls_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(SECURITY, R.string.notify_channel_security to R.string.notify_channel_security_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(OWNER_CHECK, R.string.notify_channel_owner_check to R.string.notify_channel_owner_check_body, NotificationManager.IMPORTANCE_HIGH),
        Triple(ACTIVITY, R.string.notify_channel_activity to R.string.notify_channel_activity_body, NotificationManager.IMPORTANCE_LOW),
        Triple(CONNECTION, R.string.notify_channel_connection to R.string.notify_channel_connection_body, NotificationManager.IMPORTANCE_MIN),
    )

    /** The channels [ensure] creates ([VAULT_UPDATES] is the release notice's own). */
    val ids: List<String> get() = all.map { it.first }

    /** Every channel the app posts to. */
    val allIds: List<String> get() = ids + VAULT_UPDATES

    /**
     * Deletes the retired ids, then creates (or renames, in a new language) the channels; the member's own changes
     * to them are kept by Android.
     */
    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        RETIRED.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        nm.createNotificationChannels(
            all.map { (id, texts, importance) ->
                NotificationChannel(id, context.getString(texts.first), importance).apply {
                    description = context.getString(texts.second)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    if (id == CONNECTION) setShowBadge(false)
                    // Ringing in vibrate mode buzzes; every other notification on Calls is silent (the ongoing call).
                    if (id == CALLS) enableVibration(true)
                    applySound(context, this)
                }
            },
        )
    }

    /** Sets [channel]'s VettID sound, or none for a channel without one (Android's default would play otherwise). */
    fun applySound(context: Context, channel: NotificationChannel) {
        val res = SOUNDS[channel.id]
        if (res == null) {
            channel.setSound(null, null)
        } else {
            channel.setSound(soundUri(context, res), attributes(channel.id))
        }
    }

    /** `android.resource://<package>/<resId>`: the package is the running app's (release, staging, foss, debug). */
    fun soundUri(context: Context, @RawRes res: Int): Uri = Uri.parse("android.resource://${context.packageName}/$res")

    /** The ringtone rings as a ringtone (ring volume, ringer mode); the other sounds as notifications. */
    fun attributes(id: String): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (id == CALLS) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}
