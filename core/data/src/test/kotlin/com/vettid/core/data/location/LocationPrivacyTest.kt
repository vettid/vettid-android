// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.location

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Positions are never stored (ANDROID-PLAN §4 "Local state"): the app keeps on disk only the outgoing `share_id`s with their
 * `connection_id` and `expires_at`, and a position is held only until it is sent. A check over the stored settings' schema, over
 * what the sources do with a position, and over what the types print.
 */
class LocationPrivacyTest {
    @Test
    fun theStoredSettingsHoldTheShareIdTheConnectionIdAndTheExpiryOnly() {
        val d = StoredShare.serializer().descriptor
        val names = (0 until d.elementsCount).map { d.getElementName(it) }
        assertEquals(listOf("share_id", "connection_id", "expires_at_ms"), names)
        // Two strings and a whole number: no member that could hold a coordinate (a double or a float, a list, an object).
        val kinds = (0 until d.elementsCount).map { d.getElementDescriptor(it).kind }
        assertEquals(listOf(PrimitiveKind.STRING, PrimitiveKind.STRING, PrimitiveKind.LONG), kinds)
        assertTrue(kinds.none { it == PrimitiveKind.DOUBLE || it == PrimitiveKind.FLOAT || it !is PrimitiveKind && it is SerialKind })
        val banned = listOf("lat", "lon", "accuracy", "altitude", "speed", "heading", "bearing", "position", "coordinate", "fix")
        names.forEach { n -> banned.forEach { b -> assertFalse("$n", n.contains(b)) } }
    }

    @Test
    fun onlyTheStoredShareIsSerializable() {
        val sources = File("src/main/kotlin/com/vettid/core/data/location").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size >= 5)
        val serializable = sources.filter { f -> f.readLines().any { it.trim().startsWith("@Serializable") } }
        assertEquals(listOf("ShareStore.kt"), serializable.map { it.name })
        // Nothing in the sharing logic reaches saved state, a parcel, a preference or a file but the one store.
        val banned = listOf("SavedStateHandle", "Parcelable", "DataStore", "SharedPreferences", "openFileOutput", "FileOutputStream", "writeText", "writeBytes")
        for (f in sources.filter { it.name != "ShareStore.kt" }) {
            val code = codeOf(f)
            for (b in banned) assertFalse("${f.name} uses $b", code.contains(b))
        }
    }

    @Test
    fun theTypesPrintTheirNamesOnly() {
        val fix = Fix(52.5200, 13.4050, 12.5, 34.0, 1.5, 90.0, Instant.EPOCH)
        assertEquals("Fix", "$fix")
        assertEquals("StoredShare", "${StoredShare("s1", "c1", 1)}")
        val text = listOf(fix.toString(), PositionRequest(1000, true).toString(), ShareRequest("c1").toString())
        for (t in text) listOf("52.52", "13.405", "12.5").forEach { assertFalse(t, t.contains(it)) }
    }

    @Test
    fun theSharingLogicHasNoLoggingCall() {
        val sources = File("src/main/kotlin/com/vettid/core/data/location").walkTopDown().filter { it.extension == "kt" }.toList()
        for (f in sources) {
            val code = codeOf(f)
            for (b in LOGGING) assertFalse("${f.name} calls $b", code.contains(b))
        }
    }

    private fun codeOf(f: File) =
        f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")

    companion object {
        /** Calls that put text in a log, a stack trace or a crash report. */
        val LOGGING = listOf(
            "android.util.Log", "Log.", "println(", "printStackTrace", "Timber", "System.out", "System.err",
            "java.util.logging", "Logger", "stackTraceToString",
        )
    }
}
