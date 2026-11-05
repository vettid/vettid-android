package com.vettid.core.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.log.Logger
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource

/** Whether the SDK is set up: a map is made only when [READY]. */
enum class MapSdkState { IDLE, PREPARING, READY, FAILED }

/** The SDK's steps, behind an interface so that their order and outcome are unit-tested without native code. */
interface SdkEngine {
    fun silenceLogging()

    fun initialise(context: Context)

    /** Holds the tile cache to zero bytes and clears it; [done] says whether that worked. */
    fun holdCache(context: Context, bytes: Long, done: (Boolean) -> Unit)

    /** Our own client (https to the noted server only, a generic User-Agent) and the rule for the SDK's own file sources. */
    fun installNetwork(context: Context)
}

/** Sets the SDK up once, in order, and says when a map may be made. */
class MapSdkSetup(private val engine: SdkEngine) {
    private val flow = MutableStateFlow(MapSdkState.IDLE)
    val state: StateFlow<MapSdkState> = flow.asStateFlow()

    /** Starts the setup (main thread); does nothing while it runs or when it is done. A failed setup is tried again. */
    @Synchronized
    fun prepare(context: Context) {
        if (flow.value == MapSdkState.PREPARING || flow.value == MapSdkState.READY) return
        flow.value = MapSdkState.PREPARING
        try {
            engine.silenceLogging()
            engine.initialise(context.applicationContext)
            engine.installNetwork(context.applicationContext)
            engine.holdCache(context.applicationContext, MapSdk.AMBIENT_CACHE_BYTES) { ok ->
                flow.value = if (ok) MapSdkState.READY else MapSdkState.FAILED
            }
        } catch (_: RuntimeException) {
            flow.value = MapSdkState.FAILED
        } catch (_: LinkageError) { // the native library did not load
            flow.value = MapSdkState.FAILED
        }
    }
}

/**
 * How MapLibre is set up for this app. The values are what the privacy promise needs, and `MapSdkTest` holds them:
 * - **no tile and no position on disk**: the SDK's ambient cache (the only thing it writes while a map is shown) is held to
 *   [AMBIENT_CACHE_BYTES] = 0 bytes before any map exists, and cleared; no offline region is ever made. The SDK's database file
 *   may exist in the app's own files directory, empty of tiles. **No map is made until that has worked** ([MapSdkState.READY]);
 * - **no log**: [Logger] is set to NONE (it could log coordinates);
 * - **only the noted server**: [MapRequestPolicy] and [MapNetwork] (https, that host and port, no redirect, generic User-Agent);
 * - **no location**: no location component, engine or permission use; the received position is drawn by this module.
 * Nothing runs before the member has answered the note: [prepare] is called by "Show map" and by the map itself.
 */
object MapSdk {
    /** The ambient (tile) cache's size. Zero: nothing a map loads is kept. */
    const val AMBIENT_CACHE_BYTES = 0L

    /** The settings, as plain values so that a test can read them without the SDK. */
    val config: Config = Config(ambientCacheBytes = AMBIENT_CACHE_BYTES, loggingOff = true)

    class Config(val ambientCacheBytes: Long, val loggingOff: Boolean)

    private val setup by lazy { MapSdkSetup(MapLibreEngine) }

    val state: StateFlow<MapSdkState> get() = setup.state

    fun prepare(context: Context) = setup.prepare(context)

    /** The one server the map may reach: the authority of the style address. Set before a map is made. */
    fun allowOnly(styleUrl: String) {
        MapRequestPolicy.allowed = MapRequestPolicy.authorityOf(styleUrl)
    }
}

private object MapLibreEngine : SdkEngine {
    override fun silenceLogging() {
        Logger.setVerbosity(Logger.NONE)
    }

    override fun initialise(context: Context) {
        MapLibre.getInstance(context)
    }

    override fun installNetwork(context: Context) {
        HttpRequestUtil.setOkHttpClient(MapNetwork.client())
        FileSource.getInstance(context).setResourceTransform { _, url -> MapRequestPolicy.transform(url) }
    }

    override fun holdCache(context: Context, bytes: Long, done: (Boolean) -> Unit) {
        val cache = OfflineManager.getInstance(context)
        cache.setMaximumAmbientCacheSize(
            bytes,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    cache.clearAmbientCache(NO_CALLBACK)
                    done(true)
                }

                override fun onError(message: String) = done(false)
            },
        )
    }

    private val NO_CALLBACK = object : OfflineManager.FileSourceCallback {
        override fun onSuccess() = Unit

        override fun onError(message: String) = Unit
    }
}
