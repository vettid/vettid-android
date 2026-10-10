package com.vettid.core.push.fcm

import com.vettid.core.notify.PushAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The FCM stub (ANDROID-PLAN 0.1.23, Notification modes 11): "not available yet" without configuration or Play services. */
class FcmPushProviderTest {
    private fun provider(installed: Boolean, configured: Boolean = false) =
        FcmPushProvider(context = android.app.Application(), configured = configured, installed = { installed })

    @Test
    fun notAvailableYetWithoutConfiguration() {
        assertEquals(PushAvailability.NOT_AVAILABLE_YET, provider(installed = true).availability())
        assertNull(runBlocking { provider(installed = true).token() })
    }

    @Test
    fun playServicesMissingIsSaid() {
        assertEquals(PushAvailability.MISSING_SERVICES, provider(installed = false).availability())
        assertEquals(PushAvailability.MISSING_SERVICES, provider(installed = false, configured = true).availability())
    }
}
