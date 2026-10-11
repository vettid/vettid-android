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
import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.ReleaseNotesRepository
import com.vettid.core.data.vault.ReleaseNotices
import com.vettid.core.data.vault.ReleaseUpdateOffer
import com.vettid.core.data.vault.ReleaseUpdateRepository
import com.vettid.core.data.vault.UpdateNoticeKind
import com.vettid.feature.onboarding.releaseDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The local "Vault updates" notification (owner decision 2026-10-09): once per release, when the offer the app read
 * (app start, return to the foreground, after an unlock) names a release newer than the last one notified. Nothing
 * is pushed (push is deferred); without the notification permission (API 33+) nothing is posted, and nothing asks
 * for it here (the shell asks once, in context). A tap opens the update screen ([ACTION_OPEN_UPDATE]).
 *
 * ANDROID-PLAN 0.1.31: before posting, What's new is looked up (the same rules and limits as the update screen); with
 * an entry the text is "Release N: <summary>" (an end-date warning keeps its date first and adds the summary). The
 * notification is not updated afterwards.
 *
 * It is cancelled once it no longer stands ([ReleaseNotices.notificationStale]: the update is done, or the offer read
 * after an unlock or at app start no longer offers that release). The release stays recorded as notified, so it is
 * never posted again.
 */
class ReleaseUpdateNotifier(
    private val context: Context,
    private val updates: ReleaseUpdateRepository,
    private val prefs: PreferencesRepository,
    private val scope: CoroutineScope,
    private val releaseNotes: ReleaseNotesRepository,
) {
    fun start() {
        scope.launch {
            combine(updates.offer, updates.offerKnown, updates.progress) { o, known, p -> Triple(o, known, p) }.collect { (o, known, p) ->
                runCatching {
                    val last = prefs.preferences.first().releaseNotified
                    // A notification for a release the vault runs (or that is no longer offered) goes (S9 canary).
                    if (ReleaseNotices.notificationStale(o, known, last, p)) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                    if (o != null) maybeNotify(o, last)
                }.onFailure {
                    android.util.Log.w("VettID", "release notification not updated: ${it.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun maybeNotify(o: ReleaseUpdateOffer, lastNotified: Long) {
        if (!ReleaseNotices.shouldNotify(o, lastNotified) || !canPost()) return
        val notes = withTimeoutOrNull(NOTES_WAIT_MS) { releaseNotes.whatsNew(o.target, o.between) } as? ReleaseNotes.Available
        post(o, notes?.entry?.summary)
        prefs.setReleaseNotified(o.target.number)
    }

    private fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @android.annotation.SuppressLint("MissingPermission") // checked in canPost
    private fun post(o: ReleaseUpdateOffer, summary: String?) {
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
        val number = o.target.number.toInt()
        val whatsNew = summary?.let { context.getString(R.string.release_notification_summary, number, it) }
        val ending = o.endsAt?.takeIf { o.kind == UpdateNoticeKind.ENDING }?.let {
            context.getString(R.string.release_notification_ending, releaseDate(it), number)
        }
        val text = ending ?: whatsNew ?: context.getString(R.string.release_notification_available, number)
        val big = if (ending != null && whatsNew != null) "$ending\n\n$whatsNew" else text
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vault_update)
            .setContentTitle(context.getString(R.string.release_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
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

        /** The log fetch has its own 10 s timeout; this bounds the whole look-up. */
        private const val NOTES_WAIT_MS = 12_000L
    }
}
