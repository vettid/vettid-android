@file:Suppress("MaxLineLength")

package com.vettid.core.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing about the map reaches a log, a crash report or a saved state (ANDROID-PLAN §4 "Local state"): no logging call in
 * `:core:map`, in the map server's rules and settings, or in the position screen; the SDK's own logging is switched off by the one
 * line [MapSdk] has (the only mention of `Logger`).
 */
class MapPrivacyTest {
    private fun code(f: File) = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    private val files: List<File> =
        File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
            File("../data/src/main/kotlin/com/vettid/core/data/map").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
            listOf(
                File("../../feature/settings/src/main/kotlin/com/vettid/feature/settings/MapServerSettings.kt"),
                File("../../feature/connections/src/main/kotlin/com/vettid/feature/connections/LocationViews.kt"),
            )

    @Test
    fun noLoggingCallAnywhereInTheMapCode() {
        assertTrue(files.size >= 6 && files.all { it.isFile })
        val banned = listOf(
            "android.util.Log", "Log.", "println(", "printStackTrace", "Timber", "System.out", "System.err", "java.util.logging",
            "stackTraceToString", "Crashlytics", "recordException",
        )
        for (f in files) {
            val c = code(f)
            for (b in banned) assertFalse("${f.name} calls $b", c.contains(b))
            // The SDK's logger: only the line that turns it off, in MapSdk.
            val logger = c.lines().filter { it.contains("Logger") }
            if (f.name == "MapSdk.kt") {
                assertTrue(logger.all { it.trim() == "Logger.setVerbosity(Logger.NONE)" || it.trim() == "import org.maplibre.android.log.Logger" })
            } else {
                assertTrue("${f.name} uses Logger", logger.isEmpty())
            }
        }
    }

    @Test
    fun noPositionOrCameraInASavedState() {
        for (f in files) {
            val c = code(f)
            assertFalse("${f.name} uses rememberSaveable", c.contains("rememberSaveable"))
            assertFalse("${f.name} uses onSaveInstanceState", c.contains("onSaveInstanceState"))
        }
        // The one SavedStateHandle of the position screen carries the connection's id (the route argument), never a position.
        val views = code(File("../../feature/connections/src/main/kotlin/com/vettid/feature/connections/LocationViews.kt"))
        assertFalse(views.contains("savedState[\"") || views.contains("savedState.set") || views.contains("savedState.getStateFlow"))
    }
}
