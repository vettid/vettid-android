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
 * source set but the tests) outside the relay module (which defines the mode) and the test-only `core/testing` names
 * `WEBSOCKET`.
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
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "testing" }
            .filter { it.isFile && it.extension == "kt" && "/src/" in it.path }
            .filter { "/src/test/" !in it.path && "/src/androidTest/" !in it.path }
            .filter { "/core/relay/" !in it.path }
            .filter { "WEBSOCKET" in it.readText() }
            .map { it.relativeTo(root).path }
            .toList()
        assertEquals("the settlement has no stream lease rule: wire it before turning the stream on", emptyList<String>(), offenders)
    }
}
