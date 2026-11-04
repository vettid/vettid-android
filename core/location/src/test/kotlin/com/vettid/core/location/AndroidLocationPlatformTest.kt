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

    private fun platform(): AndroidLocationPlatform {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = app.packageName
                name = "${app.packageName}.MainActivity"
                applicationInfo = app.applicationInfo
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, info)
        return AndroidLocationPlatform(app, NoLocation, MutableStateFlow(emptyMap()))
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
}
