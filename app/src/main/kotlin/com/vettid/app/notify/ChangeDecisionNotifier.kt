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
import com.vettid.app.ui.changeDecidedText
import com.vettid.core.data.vault.ChangeCheck
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.notify.Channels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Whether an activity of VettID is started (the app is in front); kept by the application's lifecycle callbacks. */
object AppForeground {
    @Volatile
    var visible: Boolean = false
}

/**
 * VAULT-MESSAGING 0.45.0 §11.14.9 step 7 (ANDROID-PLAN 0.1.46): the decision of a PIN or password change whose answer
 * was lost, as a notification while the app is in the background (in front, the shell's notice says it). The text is
 * the notice's; a tap opens the app, where the notice is shown until dismissed. Cancelled once the notice is dismissed.
 * Without the notification permission nothing is posted.
 */
class ChangeDecisionNotifier(
    private val context: Context,
    private val vault: VaultRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            vault.changeCheck.collect { c ->
                runCatching {
                    when {
                        c == null -> NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                        !c.open && !AppForeground.visible && canPost() -> post(c)
                    }
                }
            }
        }
    }

    private fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @android.annotation.SuppressLint("MissingPermission") // checked in canPost
    private fun post(c: ChangeCheck) {
        runCatching { Channels.ensure(context) }
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, flags)
        val text = context.getString(changeDecidedText(c))
        val n = NotificationCompat.Builder(context, Channels.SECURITY)
            .setSmallIcon(R.drawable.ic_stat_vault_update)
            .setContentTitle(context.getString(R.string.root_change_channel_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    private companion object {
        const val NOTIFICATION_ID = 1002
    }
}
