package com.vettid.core.map

import android.content.Context
import org.maplibre.android.MapLibre
import org.maplibre.android.log.Logger
import org.maplibre.android.offline.OfflineManager

/**
 * How MapLibre is set up for this app. The values are what the privacy promise needs, and `MapSdkTest` holds them:
 * - **no tile and no position on disk**: the SDK's ambient cache (the only thing it writes while a map is shown) is held to
 *   [AMBIENT_CACHE_BYTES] = 0 bytes before any map exists, and cleared; no offline region is ever made. The SDK's database file
 *   may exist in the app's own files directory, empty of tiles;
 * - **no log**: [Logger] is set to NONE (it could log coordinates);
 * - **no location**: no location component, engine or permission use; the received position is drawn by this module.
 */
object MapSdk {
    /** The ambient (tile) cache's size. Zero: nothing a map loads is kept. */
    const val AMBIENT_CACHE_BYTES = 0L

    /** The settings, as plain values so that a test can read them without the SDK. */
    val config: Config = Config(ambientCacheBytes = AMBIENT_CACHE_BYTES, loggingOff = true)

    class Config(val ambientCacheBytes: Long, val loggingOff: Boolean)

    @Volatile
    private var ready = false

    /** Sets the SDK up once per process, before the first map is made. */
    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        Logger.setVerbosity(Logger.NONE)
        MapLibre.getInstance(context.applicationContext)
        val cache = OfflineManager.getInstance(context.applicationContext)
        cache.setMaximumAmbientCacheSize(config.ambientCacheBytes, NO_CALLBACK)
        cache.clearAmbientCache(NO_CALLBACK)
        ready = true
    }

    private val NO_CALLBACK = object : OfflineManager.FileSourceCallback {
        override fun onSuccess() = Unit

        override fun onError(message: String) = Unit
    }
}
