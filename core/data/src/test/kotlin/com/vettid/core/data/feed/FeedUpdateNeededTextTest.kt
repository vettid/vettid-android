// Test data: the approved sentences are clearer on one line.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.feed

import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * VAULT-MESSAGING 0.87.0 §6.3.1 "An app from before release 29", §10.9 `device.update_needed`: another of the member's
 * devices needs an update. The texts are the owner's, word for word (2026-11-06 named, 2026-11-10 without a name; both
 * apps, as vettid-ios #238 `anotherDeviceNeedsAnUpdate`); the name comes from `device.list`, bidi-isolated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedUpdateNeededTextTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private fun item(deviceId: String?) =
        FeedItem("i", 1, "device.update_needed", "2026-11-10T12:00:00Z", "active", "high", deviceId = deviceId)

    private fun title(deviceId: String?, names: FeedNames, details: Boolean = true) = FeedKinds.title(item(deviceId), names, details).resolve(res)

    private val any = "One of your devices needs an update to keep working with your vault. Update the app on it."

    @Test
    fun theOwnersWordsWordForWord() {
        assertEquals("%1\$s needs an update to keep working with your vault. Update the app.", res.getString(com.vettid.core.data.R.string.data_feed_device_update_needed_named))
        assertEquals(any, res.getString(com.vettid.core.data.R.string.data_feed_device_update_needed))
    }

    @Test
    fun theDeviceByItsNameElseTheWordingThatNamesNone() {
        val listed = FeedNames(devices = FeedNames.deviceNames(
            listOf(
                com.vettid.core.data.devices.DeviceInfo("d1", "desktop", "Al's iPad", null, null, null, null),
                com.vettid.core.data.devices.DeviceInfo("d2", "desktop", "", null, null, null, null),
            ),
        ))
        assertEquals("⁨Al's iPad⁩ needs an update to keep working with your vault. Update the app.", title("d1", listed))
        // A notification with names only names the device too (a device's name is not a detail).
        assertEquals("⁨Al's iPad⁩ needs an update to keep working with your vault. Update the app.", title("d1", listed, details = false))
        assertEquals("the list not read", any, title("d1", FeedNames()))
        assertEquals("listed without a name", any, title("d2", listed))
        assertEquals("not listed", any, title("gone", listed))
        assertEquals("no device id", any, title(null, listed))
        // A name that is nothing once cleaned names nothing.
        assertEquals(any, title("d3", FeedNames(devices = mapOf("d3" to "​ "))))
        // A name cannot reorder the sentence: it is isolated.
        assertTrue(title("d4", FeedNames(devices = mapOf("d4" to "‮evil"))).startsWith("⁨"))
    }

    @Test
    fun aKnownKindThatLeadsToDevices() {
        assertTrue(FeedKinds.known("device.update_needed"))
        assertTrue("device.update_needed" in FeedKinds.KNOWN)
        assertEquals(FeedTarget.DeviceList, FeedKinds.target(item("d1")))
        assertEquals(FeedTarget.DeviceList, FeedKinds.target(item(null)))
        assertTrue(!FeedKinds.notAvailable("device.update_needed") && !FeedKinds.historyOnly("device.update_needed"))
    }
}
