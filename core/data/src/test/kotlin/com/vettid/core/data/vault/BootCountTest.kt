package com.vettid.core.data.vault

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** (Review F7) The boot count is read once per process and kept: a later read gives the first value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BootCountTest {
    @Test
    fun theBootCountIsReadOncePerProcess() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 5)
        assertEquals(5L, BootCount.of(app))
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 6)
        assertEquals("kept from the first read", 5L, BootCount.of(app))
    }
}
