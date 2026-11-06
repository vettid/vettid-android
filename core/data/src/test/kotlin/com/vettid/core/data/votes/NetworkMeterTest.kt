package com.vettid.core.data.votes

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/** #314 item 1: Wi-Fi (unmetered) is told by `NET_CAPABILITY_NOT_METERED`; no network or no capabilities is unknown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkMeterTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private fun caps(unmetered: Boolean): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (unmetered) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    @Test
    fun unmeteredMeteredAndUnknown() {
        val network = cm.activeNetwork!!
        shadowOf(cm).setNetworkCapabilities(network, caps(unmetered = true))
        assertEquals(true, NetworkMeter.unmetered(context))
        shadowOf(cm).setNetworkCapabilities(network, caps(unmetered = false))
        assertEquals(false, NetworkMeter.unmetered(context))
        // No capabilities for the network, or no network at all: unknown (counted as metered by the votes).
        shadowOf(cm).setNetworkCapabilities(network, null)
        assertNull(NetworkMeter.unmetered(context))
        shadowOf(cm).setActiveNetworkInfo(null)
        assertNull(NetworkMeter.unmetered(context))
    }
}
