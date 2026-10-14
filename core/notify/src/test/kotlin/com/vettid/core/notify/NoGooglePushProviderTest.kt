package com.vettid.core.notify

import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.vault.AppPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `foss` distribution's push provider (README "F-Droid build"): no Google push in this build, and nothing runs for it. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoGooglePushProviderTest {
    @Test
    fun reportsThatThisBuildHasNoGooglePush() = runTest {
        assertEquals(PushAvailability.NOT_IN_THIS_BUILD, NoGooglePushProvider.availability())
        assertNull("no token, ever", NoGooglePushProvider.token())
        NoGooglePushProvider.deleteToken() // nothing to forget, and it does not throw
        assertEquals("the platform of push.register is unchanged, though never sent", "fcm", NoGooglePushProvider.platform)
    }

    @Test
    fun keepsNoCallbacks() {
        NoGooglePushProvider.onWake = { true }
        NoGooglePushProvider.onNewToken = { error("never called") }
        NoGooglePushProvider.onChallenge = { error("never called") }
        assertNull("nothing can wake this build", NoGooglePushProvider.onWake)
        assertNull(NoGooglePushProvider.onNewToken)
        assertNull("no registration, so no challenge (PUSH-GATEWAY 0.5.0)", NoGooglePushProvider.onChallenge)
    }

    @Test
    fun theOnPhoneServiceRunsAndNoRegistrationIsEverStarted() = runTest {
        val service = object : ServiceControl {
            var on = false

            override fun start(): Boolean {
                on = true
                return true
            }

            override fun stop() {
                on = false
            }
        }
        val registration = object : PushControl {
            override val status = MutableStateFlow(PushStatus())
            var enabled = 0

            override fun enable() {
                enabled++
            }

            override fun disable(wiped: Boolean) = Unit

            override suspend fun refresh() = Unit
        }
        val mode = MutableStateFlow(NotificationMode.SERVICE)
        val phase = MutableStateFlow<AppPhase>(AppPhase.Unlocked)
        val c = ModeController(backgroundScope, mode, phase, service, NoGooglePushProvider, registration)
        c.start()
        runCurrent()
        assertTrue("the on-phone service is intact in this build", service.on)
        assertEquals(0, registration.enabled)
        mode.value = NotificationMode.OFF
        runCurrent()
        assertFalse(service.on)
        assertEquals(0, registration.enabled)
    }
}
