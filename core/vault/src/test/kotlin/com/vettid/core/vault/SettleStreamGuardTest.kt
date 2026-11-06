package com.vettid.core.vault

import com.vettid.core.relay.MailboxCollector
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * (#307 follow-up 9) The transfer's settlement window knows the lease of a long poll (s + wait + V) only. A WebSocket stream
 * leases each pushed message from its push time, so it would need its own rule: for a stream, wait = 0 and s is the last
 * moment it was known open (the cut time on a cut, the WAKE on a freeze while it is open, since the relay can push into the
 * socket during the freeze, "now" at `stop()`). The app collects with LONG_POLL only, so the rule is not wired; this test
 * fails if the app's collection could run as a stream without it: the default mode changed, or any app source (every
 * source set but the tests, and the test-only `core/testing`) other than `MailboxCollector.kt` (which defines the mode)
 * names `WEBSOCKET`, picks a mode by value (`Mode.entries`, `Mode.values()`, `Mode.valueOf`, `enumValues<Mode>()`,
 * `enumEntries<Mode>()`) or assigns `collectMode`, in Kotlin, Kotlin script (build files too) and Java sources.
 */
class SettleStreamGuardTest {
    @Test
    fun theAppsCollectionIsALongPoll() {
        val cfg = DeviceConfig(
            name = "phone", relayUrl = "https://relay.invalid", http = OkHttpClient(), store = InMemoryDeviceStateStore(),
        )
        assertEquals(
            "the settlement's lease rule is for long polls: wire the stream rule before changing this",
            MailboxCollector.Mode.LONG_POLL,
            cfg.collectMode,
        )
    }

    @Test
    fun noProductionSourceTurnsTheStreamOn() {
        val root = File("../..").canonicalFile
        assertTrue("run from the module directory: $root", File(root, "settings.gradle.kts").exists())
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.relativeTo(root).path != "core/testing" }
            .filter { it.isFile && it.extension in SOURCES && ("/src/" in it.path || it.extension == "kts") }
            .filter { "/src/test/" !in it.path && "/src/androidTest/" !in it.path && "/src/testFixtures/" !in it.path }
            .filter { it.relativeTo(root).path != MODE_DEFINED_IN }
            .filter { f -> f.readText().let { text -> STREAM_ON.any { it.containsMatchIn(text) } } }
            .map { it.relativeTo(root).path }
            .toList()
        assertEquals("the settlement has no stream lease rule: wire it before turning the stream on", emptyList<String>(), offenders)
    }

    /** The collector's own default is the long poll too (a collector made without a mode). */
    @Test
    fun theCollectorsOwnDefaultIsALongPoll() {
        val src = File("../../$MODE_DEFINED_IN").readText()
        assertTrue("MailboxCollector's default mode", Regex("""val mode: Mode = Mode\.LONG_POLL\b""").containsMatchIn(src))
    }

    /** The rule itself, on lines that would turn the stream on. */
    @Test
    fun theScanCatchesEveryWayToTheStream() {
        val on = listOf(
            "collectMode = MailboxCollector.Mode.WEBSOCKET",
            "val m = MailboxCollector.Mode.valueOf(name)",
            "MailboxCollector.Mode.entries.last()",
            "DeviceConfig(name = n, collectMode = mode)",
            "cfg.collectMode=x",
            "enumValues<MailboxCollector.Mode>().last()",
            "val m = enumValues < Mode > ()",
            "MailboxCollector.Mode.values()[1]",
        )
        val off = listOf("val collectMode: MailboxCollector.Mode = MailboxCollector.Mode.LONG_POLL,", "own, cfg.collectMode, cfg.pollWait")
        on.forEach { line -> assertTrue(line, STREAM_ON.any { it.containsMatchIn(line) }) }
        off.forEach { line -> assertTrue(line, STREAM_ON.none { it.containsMatchIn(line) }) }
    }

    private companion object {
        /** Where the mode is defined and used (its stream branch): the only source outside core/testing that may name it. */
        const val MODE_DEFINED_IN = "core/relay/src/main/kotlin/com/vettid/core/relay/MailboxCollector.kt"

        /** Any way to set a mode other than the default: the stream by name, a mode chosen by value, an assigned mode. */
        val STREAM_ON = listOf(
            Regex("""\bWEBSOCKET\b"""),
            Regex("""\bMode\s*\.\s*(entries|values\s*\(|valueOf\s*\()"""),
            Regex("""\bcollectMode\s*=(?!=)"""),
            Regex("""\benumValues\s*<\s*([\w.]*\.)?Mode\s*>"""),
            Regex("""\benumEntries\s*<\s*([\w.]*\.)?Mode\s*>"""),
        )

        /** Kotlin, Kotlin script (the build files) and Java sources. */
        val SOURCES = setOf("kt", "kts", "java")
    }
}
