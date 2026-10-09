package com.vettid.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vettid.app.MainActivity
import com.vettid.app.R
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.vault.ReleaseNotices
import com.vettid.core.data.vault.ReleaseUpdateOffer
import com.vettid.core.data.vault.ReleaseUpdateRepository
import com.vettid.core.data.vault.UpdateNoticeKind
import com.vettid.feature.onboarding.releaseDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The local "Vault updates" notification (owner decision 2026-10-09): once per release, when the offer the app read
 * (app start, return to the foreground, after an unlock) names a release newer than the last one notified. Nothing
 * is pushed (push is deferred); without the notification permission (API 33+) nothing is posted, and nothing asks
 * for it here (the shell asks once, in context). A tap opens the update screen ([ACTION_OPEN_UPDATE]).
 */
class ReleaseUpdateNotifier(
    private val context: Context,
    private val updates: ReleaseUpdateRepository,
    private val prefs: PreferencesRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            updates.offer.filterNotNull().collect { o ->
                runCatching { maybeNotify(o) }.onFailure {
                    android.util.Log.w("VettID", "release notification not posted: ${it.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun maybeNotify(o: ReleaseUpdateOffer) {
        if (!ReleaseNotices.shouldNotify(o, prefs.preferences.first().releaseNotified) || !canPost()) return
        post(o)
        prefs.setReleaseNotified(o.target.number)
    }

    private fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @android.annotation.SuppressLint("MissingPermission") // checked in canPost
    private fun post(o: ReleaseUpdateOffer) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.release_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.release_channel_description) },
        )
        val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_UPDATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when (val ends = o.endsAt?.takeIf { o.kind == UpdateNoticeKind.ENDING }) {
            null -> context.getString(R.string.release_notification_available, o.target.number.toInt())
            else -> context.getString(R.string.release_notification_ending, releaseDate(ends), o.target.number.toInt())
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vault_update)
            .setContentTitle(context.getString(R.string.release_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    companion object {
        const val CHANNEL_ID = "vault_updates"
        const val ACTION_OPEN_UPDATE = "com.vettid.app.action.OPEN_RELEASE_UPDATE"
        private const val NOTIFICATION_ID = 1001
    }
}
