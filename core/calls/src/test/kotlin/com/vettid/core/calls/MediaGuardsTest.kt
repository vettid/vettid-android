@file:Suppress("MaxLineLength") // One statistics entry a line.

package com.vettid.core.calls

import com.vettid.core.crypto.call.MediaSuite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaGuardsTest {
    /** Stands in for the stack's logging class: the same two static switches. */
    @Suppress("unused")
    private object Quiet {
        @JvmStatic
        private var loggingEnabled = false

        @JvmStatic
        private var loggable: Any? = null
    }

    private class NoSwitches

    private fun set(cls: Class<*>, name: String, value: Any?) = cls.getDeclaredField(name).apply { isAccessible = true }.set(null, value)

    @Test
    fun theGuardPassesOnlyWhileBothSwitchesAreOff() {
        val cls = Quiet::class.java
        MediaLogGuard.check(cls)
        set(cls, "loggingEnabled", true)
        assertThrows(MediaLoggingException::class.java) { MediaLogGuard.check(cls) }
        set(cls, "loggingEnabled", false)
        set(cls, "loggable", Any())
        assertThrows(MediaLoggingException::class.java) { MediaLogGuard.check(cls) }
        set(cls, "loggable", null)
        MediaLogGuard.check(cls)
        // A library whose switches are not where they were is not trusted until it has been read again.
        assertThrows(MediaLoggingException::class.java) { MediaLogGuard.check(NoSwitches::class.java) }
    }

    /** The pinned library has the two switches, off, and the guard reads them (no native code is needed for that). */
    @Test
    fun theGuardReadsThePinnedLibrary() {
        MediaLogGuard.check()
        val cls = Class.forName(MediaLogGuard.LOGGING_CLASS)
        try {
            set(cls, MediaLogGuard.ENABLED_FIELD, true)
            assertThrows(MediaLoggingException::class.java) { MediaLogGuard.check() }
        } finally {
            set(cls, MediaLogGuard.ENABLED_FIELD, false)
        }
        MediaLogGuard.check()
    }

    /** No source file of the repository switches the media stack's logging on, in any module or build type. */
    @Test
    fun noSourceFileTurnsTheMediaStacksLoggingOn() {
        var root = File("").absoluteFile
        while (!File(root, "settings.gradle.kts").isFile) root = root.parentFile ?: error("repository root not found")
        val allowed = setOf("MediaLogGuard.kt", "MediaGuardsTest.kt")
        val sources = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" && it.name != "local" }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "kts") && it.name !in allowed }
            .toList()
        assertTrue("the walk found the sources", sources.size > 200 && sources.any { it.name == "WebRtcMediaEngine.kt" })
        val offenders = sources.flatMap { f -> MediaLogGuard.FORBIDDEN.filter { f.readText().contains(it) }.map { "${f.relativeTo(root)}: $it" } }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun theShippedEngineOffersTheSuiteItsLibraryCanDo() {
        assertEquals(listOf(MediaSuite.AES_128_GCM), WebRtcMediaEngine.SUITES)
        assertNull("the stack is not started by loading the class", WebRtcRuntime.dtlsPqcTrial)
        assertEquals("WebRTC-EnableDtlsPqc/Enabled/", WebRtcRuntime.DTLS_PQC_TRIAL)
    }

    @Test
    fun faultsAreOffAndTheSsrcFaultOnlyRenumbers() {
        assertFalse(MediaConfig().faults.any)
        assertTrue(MediaFaults(keepRemoteSsrc = true).any && MediaFaults(sendUnencrypted = true).any)
        assertEquals(
            "v=0\r\na=ssrc:1235 cname:x\r\na=ssrc:1235 msid:a b\r\na=mid:0\r\n",
            MediaFaultSdp.misstateSsrc("v=0\r\na=ssrc:1234 cname:x\r\na=ssrc:1234 msid:a b\r\na=mid:0\r\n"),
        )
    }

    @Test
    fun diagnosticsFromAStatisticsReport() {
        val report = listOf(
            StatEntry("T1", "transport", mapOf("selectedCandidatePairId" to "P1", "dtlsState" to "connected", "tlsVersion" to "FEFC",
                "dtlsCipher" to "TLS_AES_128_GCM_SHA256", "srtpCipher" to "AES_CM_128_HMAC_SHA1_80", "dtlsRole" to "client", "bytesSent" to 10L)),
            StatEntry("P1", "candidate-pair", mapOf("localCandidateId" to "L1", "remoteCandidateId" to "R1", "state" to "succeeded", "currentRoundTripTime" to 0.009)),
            StatEntry("L1", "local-candidate", mapOf("candidateType" to "host", "protocol" to "udp", "networkType" to "wifi", "address" to "192.0.2.1")),
            StatEntry("R1", "remote-candidate", mapOf("candidateType" to "relay", "protocol" to "udp", "address" to "192.0.2.2")),
            StatEntry("I1", "inbound-rtp", mapOf("kind" to "audio", "audioLevel" to 0.25, "packetsReceived" to 50L, "codecId" to "C1")),
            StatEntry("O1", "outbound-rtp", mapOf("kind" to "audio", "packetsSent" to 60L, "codecId" to "C1")),
            StatEntry("S1", "media-source", mapOf("kind" to "audio", "audioLevel" to 0.5)),
            StatEntry("C1", "codec", mapOf("mimeType" to "audio/opus")),
        )
        val d = MediaDiagnostics.from(report)
        assertEquals(0.5, d.micLevel, 0.0)
        assertEquals(0.25, d.remoteLevel, 0.0)
        assertEquals(60L, d.packetsSent)
        assertEquals(1, d.inboundAudioStreams)
        val second = StatEntry("I2", "inbound-rtp", mapOf("kind" to "audio", "packetsReceived" to 3L))
        assertEquals(2, MediaDiagnostics.from(report + second).inboundAudioStreams)
        assertEquals(50L, d.packetsReceived)
        assertEquals("audio/opus", d.sendCodec)
        assertEquals(CandidateInfo("host", "udp", "wifi"), d.local)
        assertEquals(true, d.relayed)
        assertEquals(9.0, d.roundTripMs!!, 1e-9)
        assertEquals(
            mapOf("dtlsCipher" to "TLS_AES_128_GCM_SHA256", "dtlsRole" to "client", "dtlsState" to "connected",
                "srtpCipher" to "AES_CM_128_HMAC_SHA1_80", "tlsVersion" to "FEFC"),
            d.transport,
        )
        assertFalse("no address is kept", d.toString().contains("192.0.2"))
        assertNull(MediaDiagnostics.from(emptyList()).relayed)
    }
}
