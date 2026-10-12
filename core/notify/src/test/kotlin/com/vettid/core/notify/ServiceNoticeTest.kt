package com.vettid.core.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.vettid.core.data.vault.AppPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The on-phone service's notification never outlives the service (owner report 2026-10-11: a phone that had given
 * its vault away kept an unswipeable "VettID is connecting"): the service's own end, the mode controller's stop, and
 * the sweep of a phone without a vault.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServiceNoticeTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val status = ServiceStatus()
    private val built = mutableListOf<KeeperStatus>()
    private val notice = ServiceNotice(app, status) {
        built += it
        ongoing(app.getString(VaultConnectionService.statusText(it)))
    }

    private fun ongoing(title: String): Notification = NotificationCompat.Builder(app, Channels.CONNECTION)
        .setSmallIcon(R.drawable.ic_stat_vettid).setContentTitle(title).setOngoing(true).build()

    private fun active(): List<Int> = nm.activeNotifications.map { it.id }

    @Before
    fun channels() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Channels.ensure(app)
    }

    @Test
    fun aStatusUpdatesTheNotificationWhileTheServiceRuns() {
        notice.show(KeeperStatus.CONNECTED)
        assertEquals(listOf(VaultConnectionService.NOTIFICATION_ID), active())
        assertEquals(KeeperStatus.CONNECTED, status.status.value)
    }

    @Test
    fun removingCancelsItAndClearsTheStatus() {
        notice.show(KeeperStatus.CONNECTED)
        notice.remove()
        assertTrue(active().isEmpty())
        assertNull(status.status.value)
    }

    @Test
    fun aStatusThatArrivesAfterTheStopIsNotPosted() {
        // The race: the keeper's last status is delivered on another thread while the service is going.
        notice.show(KeeperStatus.CONNECTED)
        notice.remove()
        built.clear()
        notice.show(KeeperStatus.CONNECTING)
        notice.show(KeeperStatus.LOCKED)
        assertTrue("nothing is re-posted", active().isEmpty())
        assertTrue("not even built", built.isEmpty())
        assertNull("Settings does not show a stopped service as running", status.status.value)
        notice.remove() // the service's onDestroy after its own stop: harmless
        assertTrue(active().isEmpty())
    }

    @Test
    fun stoppingTheServiceCancelsANotificationThatOutlivedIt() {
        // A phone already in the reported state: no service, its notification still listed.
        nm.notify(VaultConnectionService.NOTIFICATION_ID, ongoing("VettID is connecting"))
        assertEquals(listOf(VaultConnectionService.NOTIFICATION_ID), active())
        AndroidServiceControl(app).stop()
        assertTrue(active().isEmpty())
        assertEquals(VaultConnectionService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
    }

    @Test
    fun aPhoneWithoutAVaultKeepsNoNotificationAtAll() = runTest {
        // Left by an earlier build or a wipe: the service's, an item's (tagged), "Vault update available" (id 1001).
        nm.notify(VaultConnectionService.NOTIFICATION_ID, ongoing("VettID is connecting"))
        nm.notify("f1", AndroidPoster.NOTIFICATION_ID, ongoing("New message from Sam Lee"))
        nm.notify(FeedNotifier.TAG_LOCKED, AndroidPoster.NOTIFICATION_ID, ongoing("Your vault is locked"))
        nm.notify(1001, ongoing("Vault update available"))
        val phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
        NoVaultSweep(backgroundScope, phase) { nm.cancelAll() }.start()
        runCurrent()
        assertEquals("nothing while the phase is unknown", 4, shadowOf(nm).size())
        phase.value = AppPhase.SignedOut // the next app start of a phone with no vault
        runCurrent()
        assertEquals(0, shadowOf(nm).size())
    }

    @Test
    fun theSweepLeavesAPhoneWithAVaultAloneAndRunsAgainAfterAWipe() = runTest {
        var sweeps = 0
        val phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
        NoVaultSweep(backgroundScope, phase) { sweeps++ }.start()
        for (p in listOf(AppPhase.Locked, AppPhase.Unlocked, AppPhase.Locked, AppPhase.Unlocked)) {
            phase.value = p
            runCurrent()
        }
        assertEquals(0, sweeps)
        phase.value = AppPhase.SignedOut // transferred away, wiped, deleted, erased by the member
        runCurrent()
        assertEquals(1, sweeps)
    }
}
