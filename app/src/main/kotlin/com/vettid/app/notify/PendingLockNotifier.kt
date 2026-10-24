package com.vettid.app.notify

import android.Manifest
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
import com.vettid.app.lock.PendingLockJob
import com.vettid.core.data.vault.LockNotice
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.notify.Channels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The member's pending lock outside the app's screens (owner decision 2026-10-23, release L's Q4: fail secure, a
 * durable retry until the vault confirms, a plain notice meanwhile):
 *
 * - while a lock is pending, the [PendingLockJob] is scheduled (it brings the process back if it was killed), and
 *   it is cancelled once the lock is over;
 * - while it is in doubt (a try failed) and VettID is not in front, the unlock screen's own words, "Your vault may
 *   still be open", as a notification on the Security channel, posted once per lock and gone when the lock is over.
 *
 * Without the notification permission nothing is posted; the job runs all the same.
 */
class PendingLockNotifier(
    private val context: Context,
    private val vault: VaultRepository,
    private val scope: CoroutineScope,
) {
    /** Posted for the lock now pending: once per lock. */
    private var posted = false

    fun start() {
        scope.launch {
            vault.lockNotice.map { state(it) }.distinctUntilChanged().collect { s ->
                runCatching {
                    when {
                        s == State.NONE -> PendingLockJob.cancel(context)
                        !PendingLockJob.scheduled(context) -> PendingLockJob.schedule(context)
                    }
                }
            }
        }
        // In doubt and VettID not in front (now, or once the member leaves it): the notification, once per lock.
        scope.launch {
            val states = combine(vault.lockNotice.map { state(it) }, AppForeground.visibleFlow) { s, front -> s to front }
            states.distinctUntilChanged().collect { (s, front) ->
                runCatching {
                    when {
                        s == State.NONE -> {
                            posted = false
                            cancel()
                        }
                        s == State.IN_DOUBT && !front && !posted && canPost() -> {
                            post()
                            posted = true
                        }
                    }
                }
            }
        }
    }

    /** What the notifier does for a [LockNotice]. */
    enum class State { NONE, PENDING, IN_DOUBT }

    private fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @android.annotation.SuppressLint("MissingPermission") // checked in canPost
    private fun post() {
        runCatching { Channels.ensure(context) }
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, flags)
        val text = context.getString(com.vettid.feature.onboarding.R.string.unlock_lock_unconfirmed_body)
        val n = NotificationCompat.Builder(context, Channels.SECURITY)
            .setSmallIcon(R.drawable.ic_stat_vault_update)
            .setContentTitle(context.getString(com.vettid.feature.onboarding.R.string.unlock_lock_unconfirmed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    private fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val NOTIFICATION_ID = 1003

        /** [LockNotice] → what is shown and scheduled. */
        fun state(n: LockNotice): State = when {
            n !is LockNotice.Unconfirmed -> State.NONE
            n.inDoubt -> State.IN_DOUBT
            else -> State.PENDING
        }
    }
}
