// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing about location reaches a log (ANDROID-PLAN §4 "Local state"): no logging call in this module, in the sharing logic of
 * `:core:data` or in the requests of `:core:vault`; no crash report, stack trace text or saved state of a position; no Google
 * location API (the `foss` build must work as `full` does).
 */
class LocationModulePrivacyTest {
    private fun sources(vararg dirs: String): List<File> =
        dirs.flatMap { d -> File(d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun codeOf(f: File) =
        f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")

    @Test
    fun noLoggingCallInTheModuleTheSharingLogicOrTheRequests() {
        val files = sources("src/main/kotlin", "../data/src/main/kotlin/com/vettid/core/data/location") +
            File("../vault/src/main/kotlin/com/vettid/core/vault/LocationApi.kt")
        assertTrue(files.size >= 8)
        val banned = listOf(
            "android.util.Log", "Log.", "println(", "printStackTrace", "Timber", "System.out", "System.err",
            "java.util.logging", "Logger", "stackTraceToString", "Crashlytics", "recordException",
        )
        for (f in files) {
            val code = codeOf(f)
            for (b in banned) assertFalse("${f.name} calls $b", code.contains(b))
        }
    }

    @Test
    fun noGoogleLocationApiAndNoPositionKeptAnywhere() {
        val files = sources("src/main/kotlin")
        val banned = listOf(
            "com.google", "gms", "FusedLocationProviderClient", "LocationServices", "SavedStateHandle", "DataStore", "SharedPreferences",
            "openFileOutput", "FileOutputStream", "writeText", "writeBytes", "getLastKnownLocation", "ACCESS_BACKGROUND_LOCATION",
        )
        for (f in files) {
            val code = codeOf(f)
            for (b in banned) assertFalse("${f.name} uses $b", code.contains(b))
        }
    }

    @Test
    fun theBuildFileHasNoGoogleCoordinate() {
        val build = File("build.gradle.kts").readText()
        assertFalse(build.contains("play-services"))
        assertFalse(build.contains("com.google.android.gms"))
        assertFalse(build.contains("firebase"))
    }
}
