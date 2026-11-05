// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.location

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import com.vettid.core.data.location.NoLocation
import com.vettid.core.notify.Channels
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The plain notice for a service that is not running (ANDROID-PLAN §4 "Location"): shown, opens the app, taken away again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AndroidLocationPlatformTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)

    private fun platform(names: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())): AndroidLocationPlatform {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = app.packageName
                name = "${app.packageName}.MainActivity"
                applicationInfo = app.applicationInfo
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, info)
        return AndroidLocationPlatform(app, NoLocation, names)
    }

    @Test
    fun aServiceThatIsNotRunningIsSaidInAPlainNoticeThatOpensTheApp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val p = platform()
        p.showNotRunning(true)
        val n = shadowOf(manager).allNotifications.single()
        assertEquals(Channels.LOCATION, n.channelId)
        assertEquals("Not sending: open VettID to carry on.", n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertNotNull("it opens the app", n.contentIntent)
        assertTrue(n.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0)
        p.showNotRunning(false)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun withoutTheNotificationPermissionNothingIsPosted() {
        platform().showNotRunning(true)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    private fun title(n: android.app.Notification) = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()

    @Test
    fun aStalledShareIsSaidWithTheNameWhenNamesMayBeShownAndTakenAwayAgain() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val p = platform(MutableStateFlow(mapOf("c1" to "Samira")))
        p.showStalled("c1", 25)
        val n = shadowOf(manager).allNotifications.single()
        assertEquals(Channels.LOCATION, n.channelId)
        assertEquals("Your location hasn't reached ${com.vettid.core.data.account.AccountNames.isolate("Samira")} for 25 minutes.", title(n))
        assertNotNull("it opens the app", n.contentIntent)
        assertEquals("the lock screen never names anybody", "Your location isn't getting through.", title(n.publicVersion))
        // The same notice is updated, not doubled.
        p.updateStalled("c1", 26)
        assertEquals("Your location hasn't reached ${com.vettid.core.data.account.AccountNames.isolate("Samira")} for 26 minutes.", title(shadowOf(manager).allNotifications.single()))
        p.clearStalled("c1")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun aStalledShareNamesNobodyUnderTheCover() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // Under the app lock's cover (or with names hidden) the names map is empty.
        platform().showStalled("c1", 25)
        val n = shadowOf(manager).allNotifications.single()
        assertEquals("Your location isn't getting through.", title(n))
        assertTrue(!title(n).contains("Samira"))
    }

    private fun active() = manager.activeNotifications.toList()

    @Test
    fun aDismissedStallNoticeIsNotPostedAgainByLaterMinutes() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val p = platform()
        p.showStalled("c1", 16)
        val a = active().single()
        manager.cancel(a.tag, a.id) // the member swipes it away
        p.updateStalled("c1", 17)
        p.updateStalled("c1", 18)
        assertTrue(active().isEmpty())
        // A new stall is a new notice.
        p.clearStalled("c1")
        p.showStalled("c1", 16)
        assertEquals(1, active().size)
    }

    @Test
    fun aNamedNoticeLosesTheNameWhenTheCoverComesUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val names = MutableStateFlow(mapOf("c1" to "Samira"))
        val p = platform(names)
        p.showStalled("c1", 20)
        assertTrue(title(active().single().notification).contains("Samira"))
        names.value = emptyMap() // the app lock's cover
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Your location isn't getting through.", title(active().single().notification))
        // Back to names: said again, still the one notice.
        names.value = mapOf("c1" to "Samira")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(title(active().single().notification).contains("Samira"))
    }

    @Test
    fun aCoverDoesNotBringBackANoticeTheMemberDismissed() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val names = MutableStateFlow(mapOf("c1" to "Samira"))
        val p = platform(names)
        p.showStalled("c1", 20)
        val a = active().single()
        manager.cancel(a.tag, a.id)
        names.value = emptyMap()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(active().isEmpty())
    }

    @Test
    fun noticesLeftByAProcessThatDiedAreTakenAwayAtTheStart() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val before = platform()
        before.showStalled("c1", 20)
        before.showNotRunning(true)
        assertEquals(2, active().size)
        platform() // a new process
        assertTrue(active().isEmpty())
    }
}
