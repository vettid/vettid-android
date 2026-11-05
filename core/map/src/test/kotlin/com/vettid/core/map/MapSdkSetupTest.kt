package com.vettid.core.map

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The SDK is set up in order, and a map is made only when the cache has been held to zero. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapSdkSetupTest {
    private class Engine(val cacheOk: Boolean = true, val deferCache: Boolean = false, val crash: Boolean = false) : SdkEngine {
        val log = mutableListOf<String>()
        var finish: ((Boolean) -> Unit)? = null
        var bytes = -1L

        override fun silenceLogging() {
            log += "log"
        }

        override fun initialise(context: Context) {
            if (crash) throw UnsatisfiedLinkError("native")
            log += "init"
        }

        override fun installNetwork(context: Context) {
            log += "network"
        }

        override fun holdCache(context: Context, bytes: Long, done: (Boolean) -> Unit) {
            log += "cache"
            this.bytes = bytes
            if (deferCache) finish = done else done(cacheOk)
        }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun nothingRunsUntilPrepareIsCalled() {
        val setup = MapSdkSetup(Engine())
        assertEquals(MapSdkState.IDLE, setup.state.value)
    }

    @Test
    fun loggingIsOffFirstThenTheSdkTheNetworkRuleAndTheZeroCache() {
        val e = Engine()
        val setup = MapSdkSetup(e)
        setup.prepare(context)
        assertEquals(listOf("log", "init", "network", "cache"), e.log)
        assertEquals(0L, e.bytes)
        assertEquals(MapSdkState.READY, setup.state.value)
    }

    @Test
    fun noMapUntilTheCacheSettingHasWorked() {
        val e = Engine(deferCache = true)
        val setup = MapSdkSetup(e)
        setup.prepare(context)
        assertEquals(MapSdkState.PREPARING, setup.state.value)
        setup.prepare(context)
        assertEquals("not started twice", 1, e.log.count { it == "init" })
        e.finish!!(true)
        assertEquals(MapSdkState.READY, setup.state.value)
    }

    @Test
    fun aFailedCacheSettingOrNativeLoadIsFailedNotReady() {
        val bad = MapSdkSetup(Engine(cacheOk = false))
        bad.prepare(context)
        assertEquals(MapSdkState.FAILED, bad.state.value)
        val crash = MapSdkSetup(Engine(crash = true))
        crash.prepare(context)
        assertEquals(MapSdkState.FAILED, crash.state.value)
    }
}
