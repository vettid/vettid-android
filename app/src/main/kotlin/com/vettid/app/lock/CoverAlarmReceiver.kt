package com.vettid.app.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vettid.core.data.lock.CoverAlarm
import com.vettid.core.notify.FeedNotifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The cover's alarm (owner decision 2026-11-02, [CoverAlarm]): an inexact `AlarmManager.set(RTC, ...)` at the lock's
 * background deadline, no exact-alarm permission. Its PendingIntent is immutable and explicit to [CoverAlarmReceiver],
 * which is not exported, and carries nothing.
 */
class AndroidCoverAlarm(private val context: Context) : CoverAlarm {
    private val alarms: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    private fun intent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, CoverAlarmReceiver::class.java).setAction(CoverAlarmReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun set(atEpochMs: Long) {
        alarms?.set(AlarmManager.RTC, atEpochMs, intent())
    }

    override fun cancel() {
        alarms?.cancel(intent())
    }
}

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
