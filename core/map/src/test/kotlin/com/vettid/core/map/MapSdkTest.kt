package com.vettid.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The map's privacy configuration: no tile kept, no log, no location component, no offline download, no saved state. */
class MapSdkTest {
    private fun code(name: String) = File("src/main/kotlin/com/vettid/core/map/$name").readLines()
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    @Test
    fun theAmbientCacheIsHeldToZeroAndLoggingIsOff() {
        assertEquals(0L, MapSdk.AMBIENT_CACHE_BYTES)
        assertEquals(0L, MapSdk.config.ambientCacheBytes)
        assertTrue(MapSdk.config.loggingOff)
        val sdk = code("MapSdk.kt")
        // The SDK is told the configuration before a map is made, and the cache is cleared.
        assertTrue(sdk.contains("setMaximumAmbientCacheSize(config.ambientCacheBytes"))
        assertTrue(sdk.contains("clearAmbientCache("))
        assertTrue(sdk.contains("Logger.setVerbosity(Logger.NONE)"))
        assertTrue(sdk.indexOf("Logger.setVerbosity") < sdk.indexOf("MapLibre.getInstance"))
        assertTrue(sdk.indexOf("setMaximumAmbientCacheSize") > sdk.indexOf("MapLibre.getInstance"))
    }

    @Test
    fun noOfflineRegionNoLocationComponentNoSavedState() {
        for (f in File("src/main/kotlin/com/vettid/core/map").listFiles().orEmpty()) {
            val c = code(f.name)
            for (b in listOf(
                "createOfflineRegion", "setOfflineMapboxTileCountLimit", "putResourceWithUrl", "mergeOfflineRegions", "LocationComponent",
                "locationComponent", "LocationEngine", "onSaveInstanceState", "rememberSaveable", "SavedStateHandle", "SharedPreferences",
                "DataStore", "setResourcesCachePath", "com.google", "gms",
            )) {
                assertFalse("${f.name} uses $b", c.contains(b))
            }
        }
    }

    @Test
    fun theViewIsNotSaved() {
        val view = code("MapLibreSurface.kt")
        assertTrue(view.contains("isSaveEnabled = false"))
        assertTrue(view.contains("isSaveFromParentEnabled = false"))
    }

    @Test
    fun theBuildFileHasNoGoogleCoordinate() {
        val build = File("build.gradle.kts").readText()
        assertFalse(build.contains("play-services"))
        assertFalse(build.contains("gms"))
        assertFalse(build.contains("firebase"))
    }
}
