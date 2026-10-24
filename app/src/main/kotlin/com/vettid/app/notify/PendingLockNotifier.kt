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
import com.vettid.feature.onboarding.R as OnboardingR
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
 *   still be open", as a notification on the Security channel, posted once per lock and gone when the lock is
 *   confirmed;
 * - (release L) when the lock ended because the vault was opened again since, the unlock screen's "Your vault was
 *   opened again" in its place: the vault is not known to be locked, so the notice never just disappears then.
 *
 * Without the notification permission nothing is posted; the job runs all the same.
 */
class PendingLockNotifier(
    private val context: Context,
    private val vault: VaultRepository,
    private val scope: CoroutineScope,
) {
    /**
     * What was posted last: the lock it was for (`u:<askedAt>`), or the opened-again notice (`o`); null: nothing. Kept
     * on the phone, so that a new process does not post again what the member already saw (and maybe swiped away).
     */
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var lastAsked: Long?
        get() = runCatching { prefs.getLong(KEY_LAST_ASKED, -1).takeIf { it >= 0 } }.getOrNull()
        set(v) {
            runCatching { prefs.edit().apply { if (v == null) remove(KEY_LAST_ASKED) else putLong(KEY_LAST_ASKED, v) }.apply() }
        }
    private var posted: String?
        get() = runCatching { prefs.getString(KEY_POSTED, null) }.getOrNull()
        set(v) {
            runCatching { prefs.edit().apply { if (v == null) remove(KEY_POSTED) else putString(KEY_POSTED, v) }.apply() }
        }

    fun start() {
        scope.launch {
            vault.lockNotice.map { state(it) }.distinctUntilChanged().collect { s ->
                runCatching {
                    when {
                        s == State.NONE || s == State.OPENED_AGAIN -> PendingLockJob.cancel(context)
                        !PendingLockJob.scheduled(context) -> PendingLockJob.schedule(context)
                    }
                }
            }
        }
        // In doubt and VettID not in front (now, or once the member leaves it): the notification, once per lock.
        scope.launch {
            val states = combine(vault.lockNotice, AppForeground.visibleFlow) { n, front -> n to front }
            states.distinctUntilChanged().collect { (n, front) ->
                runCatching {
                    // Keyed by the lock itself, so that a second lock is posted even if the state between was missed.
                    // The lock an "opened again" is about: the last one seen pending (kept on the phone).
                    if (n is LockNotice.Unconfirmed) lastAsked = n.askedAt.toEpochMilli()
                    val key = keyOf(n, lastAsked)
                    when {
                        // Confirmed (or nothing to lock): the notice goes.
                        state(n) == State.NONE -> {
                            posted = null
                            cancel()
                        }
                        key != null && key != posted && !front && canPost() -> {
                            post(opened = n == LockNotice.OpenedAgain)
                            posted = key
                        }
                    }
                }
            }
        }
    }

    /** What the notifier does for a [LockNotice]. */
    enum class State { NONE, PENDING, IN_DOUBT, OPENED_AGAIN }

    private fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @android.annotation.SuppressLint("MissingPermission") // checked in canPost
    private fun post(opened: Boolean) {
        runCatching { Channels.ensure(context) }
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, flags)
        val title = if (opened) OnboardingR.string.unlock_lock_opened_again_title else OnboardingR.string.unlock_lock_unconfirmed_title
        val body = if (opened) OnboardingR.string.unlock_lock_opened_again_body else OnboardingR.string.unlock_lock_unconfirmed_body
        val text = context.getString(body)
        val n = NotificationCompat.Builder(context, Channels.SECURITY)
            .setSmallIcon(R.drawable.ic_stat_vault_update)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .apply {
                // (Owner decision 2026-10-24) "Lock it now": a fresh lock of the vault as it is now.
                if (opened) {
                    val again = Intent(context, com.vettid.app.lock.LockAgainReceiver::class.java)
                        .setAction(com.vettid.app.lock.LockAgainReceiver.ACTION)
                    val action = PendingIntent.getBroadcast(context, NOTIFICATION_ID, again, flags)
                    addAction(0, context.getString(OnboardingR.string.unlock_lock_again), action)
                }
            }
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
        private const val PREFS = "pending_lock_notice"
        private const val KEY_POSTED = "posted"

        private const val KEY_LAST_ASKED = "last_asked"

        /**
         * Which notice [n] is, for posting it once: a lock in doubt by when it was asked; "opened again" by the lock it
         * ended ([lastAsked], when it was asked); else none.
         */
        fun keyOf(n: LockNotice, lastAsked: Long?): String? = when (n) {
            is LockNotice.Unconfirmed -> "u:${n.askedAt.toEpochMilli()}".takeIf { n.inDoubt }
            LockNotice.OpenedAgain -> "o:${lastAsked ?: ""}"
            else -> null
        }

        /** [LockNotice] → what is shown and scheduled. */
        fun state(n: LockNotice): State = when {
            n == LockNotice.OpenedAgain -> State.OPENED_AGAIN
            n !is LockNotice.Unconfirmed -> State.NONE
            n.inDoubt -> State.IN_DOUBT
            else -> State.PENDING
        }
    }
}
