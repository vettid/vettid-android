package com.vettid.core.notify

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vettid.core.data.vault.BackgroundVault
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The on-phone service's state for Settings → Notifications: null while it does not run. */
class ServiceStatus {
    private val flow = MutableStateFlow<KeeperStatus?>(null)
    val status: StateFlow<KeeperStatus?> = flow.asStateFlow()

    fun set(s: KeeperStatus?) {
        flow.value = s
    }
}

/**
 * The on-phone service (ANDROID-PLAN 0.1.23 D7, Notification modes 3, "On-phone service"): a foreground service of
 * type `specialUse` that keeps the app's own end-to-end encrypted relay connection open, so that the vault's messages
 * are collected, decrypted on the phone and turned into notifications while VettID is in the background. No push
 * service, no wake_ref, no Google: the relay sees only this device's usual collects. Its own notification is on the
 * Connection channel (importance MIN) and never names a connection. [ConnectionKeeper] does the work.
 */
@AndroidEntryPoint
class VaultConnectionService : Service() {
    @Inject
    lateinit var vault: BackgroundVault

    @Inject
    lateinit var serviceStatus: ServiceStatus

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var network: NetworkWatch? = null
    private val notice by lazy { ServiceNotice(this, serviceStatus) { notification(it) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Channels.ensure(this)
        foreground(KeeperStatus.CONNECTING)
        if (job == null) {
            val net = NetworkWatch(this).also { network = it }
            val keeper = ConnectionKeeper(vault, net.validated)
            job = scope.launch {
                launch { keeper.status.collect { notice.show(it) } }
                keeper.run()
                // No vault on this phone (signed out, wiped): nothing to keep, and nothing of the service stays.
                removeNotice()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        network?.close()
        network = null
        scope.cancel()
        removeNotice()
        super.onDestroy()
    }

    /**
     * The service's notification goes with it, and stays gone: a status that arrives after this is not shown
     * ([ServiceNotice]). Without it, a status posted while Android was already taking the service down came back as
     * an ordinary ongoing notification that nothing removed (owner report 2026-10-11: "VettID is connecting" on a
     * phone that had given its vault away).
     */
    private fun removeNotice() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        notice.remove()
    }

    private fun foreground(s: KeeperStatus) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(s), type)
    }

    private fun notification(s: KeeperStatus): Notification = NotificationCompat.Builder(this, Channels.CONNECTION)
        .setSmallIcon(R.drawable.ic_stat_vettid)
        .setContentTitle(getString(statusText(s)))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        .setContentIntent(NotifyIntents.settings(this))
        .build()

    companion object {
        const val NOTIFICATION_ID = 2000

        fun statusText(s: KeeperStatus): Int = when (s) {
            KeeperStatus.CONNECTED -> R.string.notify_service_connected
            KeeperStatus.WAITING_FOR_NETWORK -> R.string.notify_service_waiting
            KeeperStatus.LOCKED -> R.string.notify_service_locked
            KeeperStatus.CHECK_DUE -> R.string.notify_service_check_due
            KeeperStatus.CONNECTING -> R.string.notify_service_connecting
        }
    }
}

/**
 * The on-phone service's own notification after its first showing (`startForeground`): a status update re-posts it
 * ([show]), and [remove] cancels it for good. Both hold one lock, so an update that races the service's end is either
 * posted before the cancel or not at all: nothing can post notification [VaultConnectionService.NOTIFICATION_ID] after
 * [remove]. The same goes for [ServiceStatus] (Settings → Notifications).
 */
class ServiceNotice(
    private val context: Context,
    private val status: ServiceStatus,
    private val build: (KeeperStatus) -> Notification,
) {
    private var removed = false

    @android.annotation.SuppressLint("MissingPermission") // without the permission Android shows it in the task manager only
    @Synchronized
    fun show(s: KeeperStatus) {
        if (removed) return
        status.set(s)
        runCatching { NotificationManagerCompat.from(context).notify(VaultConnectionService.NOTIFICATION_ID, build(s)) }
    }

    @Synchronized
    fun remove() {
        removed = true
        status.set(null)
        runCatching { NotificationManagerCompat.from(context).cancel(VaultConnectionService.NOTIFICATION_ID) }
    }
}

/** [ServiceControl] for [VaultConnectionService]. */
class AndroidServiceControl(context: Context) : ServiceControl {
    private val app = context.applicationContext

    override fun start(): Boolean = try {
        ContextCompat.startForegroundService(app, Intent(app, VaultConnectionService::class.java))
        true
    } catch (_: ForegroundServiceStartNotAllowedException) {
        // In the background outside the exemptions (boot, an app update, a visible app): tried again in front.
        false
    } catch (_: IllegalStateException) {
        false
    }

    /** Stops it, and cancels a notification of it that outlived it (left by a build before [ServiceNotice]). */
    override fun stop() {
        app.stopService(Intent(app, VaultConnectionService::class.java))
        runCatching { NotificationManagerCompat.from(app).cancel(VaultConnectionService.NOTIFICATION_ID) }
    }
}

/**
 * The default network, validated (it reaches the internet): the service retries at once when one comes, and does not
 * try while there is none (ANDROID-PLAN 0.1.23, Notification modes 3).
 */
class NetworkWatch(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val flow = MutableStateFlow(validated(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }))
    val validated: StateFlow<Boolean> = flow.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            flow.value = validated(caps)
        }

        override fun onLost(n: Network) {
            flow.value = false
        }
    }

    init {
        cm.registerDefaultNetworkCallback(callback)
    }

    fun close() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun validated(caps: NetworkCapabilities?): Boolean =
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Starts the on-phone service after the phone starts (`BOOT_COMPLETED`, sent once the member first unlocks the phone)
 * and after an app update (`MY_PACKAGE_REPLACED`): both are exemptions from Android 12's background-start limit.
 * Nothing starts without an enrolled vault or in another mode ([BootStart]).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var boot: BootStart

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                boot.onBoot()
            } finally {
                pending.finish()
            }
        }
    }
}

/** What [BootReceiver] runs: the mode's path, if the phone has a vault (the app's own wiring). */
fun interface BootStart {
    suspend fun onBoot()
}
