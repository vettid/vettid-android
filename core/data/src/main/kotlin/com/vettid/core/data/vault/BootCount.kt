package com.vettid.core.data.vault

import android.content.Context
import android.provider.Settings

/**
 * (VAULT-MESSAGING 0.88.0 §11.14.10 rule 5 "Giving up the wait") Which boot `SystemClock.elapsedRealtime` counts from:
 * `Settings.Global.BOOT_COUNT`, or null when the platform does not give it (the setting missing or unreadable). The
 * device stores it with a transfer's `hs.resp` stamp ([com.vettid.core.vault.DeviceConfig.bootId]), so that a monotonic
 * reading is compared only with one taken on the same boot.
 *
 * (Review F7) Read once per process and kept: the count cannot change while the process lives (a reboot ends it), and
 * the device asks for it inside `handleResp`, under its lock, before `hs.fin` goes out, where no ContentResolver call
 * belongs. The callers read it when they build the device's config ([of]), outside any lock.
 *
 * For PR 2's clock: on some builds the setting is missing or does not advance across a reboot. Treat a stamp as the same
 * boot only when `bootNow != null && bootNow == respBoot && elapsedNow >= respElapsedMs`; otherwise use the wall clock.
 */
internal object BootCount {
    @Volatile
    private var cached: Result<Long?>? = null

    /** The boot count, read at the first call of the process and kept (null: not given by the platform). */
    fun of(context: Context): Long? = (cached ?: Result.success(read(context)).also { cached = it }).getOrNull()

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a setting that cannot be read: no boot id
    private fun read(context: Context): Long? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
    } catch (e: Exception) {
        null
    }
}
