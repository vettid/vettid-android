// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.location

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import com.vettid.core.data.location.ServiceState
import com.vettid.core.notify.Channels
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/** The foreground service (ANDROID-PLAN §4 "Location", "A foreground service"): type, channel, texts, actions, and when it goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class LocationServiceTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val until = Instant.now().plusSeconds(3_600)

    private class Source : LocationServiceSource {
        override val state = MutableStateFlow(ServiceState())
        override val names = MutableStateFlow(mapOf("c1" to "Sam"))
        var stopAlls = 0
        var ended = 0

        override fun serviceEnded() {
            ended++
        }

        override fun stopAll() {
            stopAlls++
            state.value = ServiceState()
        }
    }

    private val source = Source()

    /** The app's launcher activity, which a tap on the notification (and Check now) opens. */
    @Before
    fun launcher() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = app.packageName
                name = "${app.packageName}.MainActivity"
                applicationInfo = app.applicationInfo
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, info)
    }

    @After
    fun tearDown() {
        LocationService.source = null
    }

    private fun start(action: String? = null) = Robolectric.buildService(LocationService::class.java, Intent(app, LocationService::class.java).also { i -> action?.let { i.action = it } })
        .create().startCommand(0, 1)


    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun actions(n: Notification) = n.actions.orEmpty().map { it.title.toString() }

    @Test
    fun itRunsAsALocationForegroundServiceOnItsOwnSilentChannel() {
        LocationService.source = source
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)))
        val controller = start()
        val shadow = shadowOf(controller.get())
        val n = shadow.lastForegroundNotification
        assertNotNull(n)
        assertEquals(Channels.LOCATION, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        // The name is bidi-isolated in a sentence (VAULT-MESSAGING §10.8).
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().replace("\u2068", "").replace("\u2069", "")
        assertTrue(title, title.startsWith("Sharing your location with Sam until "))
        assertEquals(listOf("Stop all"), actions(n))
        assertNotNull("tapping it opens the app", n.contentIntent)
        // No name on the lock screen.
        assertEquals("Sharing your location", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())

    }

    @Test
    fun severalPeopleAreCounted() {
        LocationService.source = source
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until), ServiceState.Target("c2", until)))
        val n = shadowOf(start().get()).lastForegroundNotification
        assertEquals("Sharing your location with 2 people", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun pausedOffersCheckNowAndStopAll() {
        LocationService.source = source
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)), paused = true)
        val n = shadowOf(start().get()).lastForegroundNotification
        assertEquals("Paused until your daily check.", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Check now", "Stop all"), actions(n))
    }

    @Test
    fun whenLocationIsOffTheNotificationSaysSoAndOffersAndroidsSettings() {
        LocationService.source = source
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)), issue = com.vettid.core.data.location.SendIssue.NO_PERMISSION)
        val service = start().get()
        idle()
        val n = shadowOf(service).lastForegroundNotification
        assertEquals("Not sending: location is off for VettID.", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Open settings", "Stop all"), actions(n))
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)), issue = com.vettid.core.data.location.SendIssue.STOP_PENDING)
        idle()
        val after = shadowOf(service).lastForegroundNotification
        assertEquals("Not sending until your vault has stopped a share.", after.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Stop all"), actions(after))
    }

    @Test
    fun theLockScreenVersionFollowsTheStateWithoutNames() {
        LocationService.source = source
        fun public(state: ServiceState): String {
            source.state.value = state
            val n = shadowOf(start().get()).lastForegroundNotification
            return n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        }
        val target = listOf(ServiceState.Target("c1", until))
        assertEquals("Sharing your location", public(ServiceState(target)))
        assertEquals("Paused until your daily check.", public(ServiceState(target, paused = true)))
        assertEquals(
            "Not sending: location is off on this phone.",
            public(ServiceState(target, issue = com.vettid.core.data.location.SendIssue.LOCATION_OFF)),
        )
        assertEquals(
            "Not sending until your vault has stopped a share.",
            public(ServiceState(target, issue = com.vettid.core.data.location.SendIssue.STOP_PENDING)),
        )
    }

    @Test
    fun theNotificationFollowsTheState() {
        LocationService.source = source
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)))
        val service = start().get()
        idle()
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)), paused = true)
        idle()
        assertEquals(
            "Paused until your daily check.",
            shadowOf(service).lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        // Under the app lock's cover no name is said.
        source.names.value = emptyMap()
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)))
        idle()
        assertEquals(
            "Sharing your location",
            shadowOf(service).lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
    }

    @Test
    fun stopAllIsTheNotificationsActionAndEndsTheService() {
        LocationService.source = source
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)))
        val controller = start(LocationService.ACTION_STOP_ALL)
        idle()
        assertEquals(1, source.stopAlls)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun anAskToStopShowsTheServiceThenEndsItAndIsNotAFailure() {
        LocationService.source = source
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)))
        val controller = start(LocationService.ACTION_STOP)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(0, source.ended)
        controller.destroy()
        assertEquals("destroyed on request: not told as a failure", 0, source.ended)
    }

    @Test
    fun aServiceDestroyedWhileSharesAreActiveTellsTheSharing() {
        LocationService.source = source
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)))
        val controller = start()
        idle()
        controller.destroy()
        assertEquals(1, source.ended)
    }

    @Test
    fun itGoesWhenNoShareIsLeft() {
        LocationService.source = source
        source.state.value = ServiceState(listOf(ServiceState.Target("c1", until)))
        val service = start().get()
        idle()
        assertFalse(shadowOf(service).isStoppedBySelf)
        source.state.value = ServiceState()
        idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun startedWithNothingToShowItGoesAtOnce() {
        LocationService.source = null
        val service = start().get()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(LocationService.source)
    }
}
