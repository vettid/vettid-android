package com.vettid.core.data.vault

import android.content.Context
import android.provider.Settings

/**
 * (VAULT-MESSAGING 0.88.0 §11.14.10 rule 5 "Giving up the wait") Which boot `SystemClock.elapsedRealtime` counts from:
 * `Settings.Global.BOOT_COUNT`, or null when the platform does not give it (the setting missing or unreadable). The
 * device stores it with a transfer's `hs.resp` stamp ([com.vettid.core.vault.DeviceConfig.bootId]), so that a monotonic
 * reading is compared only with one taken on the same boot.
 */
internal object BootCount {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a setting that cannot be read: no boot id
    fun read(context: Context): Long? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
    } catch (e: Exception) {
        null
    }
}
