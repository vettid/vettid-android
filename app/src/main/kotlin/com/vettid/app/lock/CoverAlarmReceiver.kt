package com.vettid.app.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.vettid.core.data.lock.CoverAlarm
import com.vettid.core.notify.FeedNotifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The cover's alarm (owner decision 2026-11-02, [CoverAlarm]): an inexact `AlarmManager.set(ELAPSED_REALTIME, ...)` (monotonic, not waking; a date set back
 * moves no deadline: the lead's decision of 2026-11-03) at the lock's background deadline, no exact-alarm permission. Its PendingIntent is immutable and explicit to [CoverAlarmReceiver],
 * which is not exported, and carries nothing.
 */
class AndroidCoverAlarm(
    private val context: Context,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
) : CoverAlarm {
    private val alarms: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    private fun intent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, CoverAlarmReceiver::class.java).setAction(CoverAlarmReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * An inexact `set` may fire up to about 75% of the wait late (45 minutes for an hour). From [WINDOW_FROM_MS] of wait,
     * `setWindow` bounds it to [WINDOW_MS] after the deadline: the shortest window allowed without the exact-alarm permission.
     */
    override fun set(atElapsedMs: Long) {
        if (atElapsedMs - elapsed() >= WINDOW_FROM_MS) {
            alarms?.setWindow(AlarmManager.ELAPSED_REALTIME, atElapsedMs, WINDOW_MS, intent())
        } else {
            alarms?.set(AlarmManager.ELAPSED_REALTIME, atElapsedMs, intent())
        }
    }

    override fun cancel() {
        alarms?.cancel(intent())
    }
}

private const val WINDOW_FROM_MS = 15 * 60_000L
private const val WINDOW_MS = 10 * 60_000L

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CoverAlarmEntry {
    fun feedNotifier(): FeedNotifier
}

/**
 * The alarm fired, early or late, with the process alive or not (a new process starts its app lock locked, which is a
 * cover): the notifier looks at the cover (`FeedNotifier.recheckCover`), which asks the app lock itself.
 * In a new process nothing is tracked yet; what an earlier process left showing is taken away when the vault is seen
 * not open (`FeedNotifier.cancelItems` / `cancelLeftovers`).
 */
class CoverAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        handle(intent) {
            EntryPointAccessors.fromApplication(context.applicationContext, CoverAlarmEntry::class.java).feedNotifier().recheckCover()
        }
    }

    companion object {
        const val ACTION = "com.vettid.app.action.COVER_ALARM"

        /** Only this action does anything; [recheck] is `FeedNotifier.recheckCover`. */
        internal fun handle(intent: Intent, recheck: () -> Unit) {
            if (intent.action == ACTION) recheck()
        }
    }
}

/**
 * The wall clock was changed (`Intent.ACTION_TIME_CHANGED`, a protected system broadcast, registered in
 * `VettIdApplication`): the app lock decides again at once and the notifier looks at the cover, so a date set back
 * while the app is in the background puts names away without waiting for a timer or the next screen-on.
 */
class ClockChangedReceiver(private val onChanged: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIME_CHANGED) onChanged()
    }
}
