package com.vettid.core.calls

import com.vettid.core.crypto.call.CallWire
import org.junit.Assert.assertEquals
import org.junit.Test
import org.webrtc.PeerConnection

/** The peer connection's configuration of one call ([WebRtcMediaEngine.configuration]). */
class RtcConfigurationTest {
    private val servers = listOf(
        CallWire.IceServer(listOf("stun:turn.example.org:3478")),
        CallWire.IceServer(
            listOf("turn:turn.example.org:3478?transport=udp", "turns:turn.example.org:5349?transport=tcp"),
            "user",
            "secret",
        ),
    )

    @Test
    fun unusedRelayAllocationsAreReleased() {
        val c = WebRtcMediaEngine.configuration(servers)
        assertEquals(PeerConnection.PortPrunePolicy.PRUNE_BASED_ON_PRIORITY, c.turnPortPrunePolicy)
    }

    @Test
    fun theRestOfTheConfigurationIsUnchanged() {
        val c = WebRtcMediaEngine.configuration(servers)
        assertEquals(PeerConnection.SdpSemantics.UNIFIED_PLAN, c.sdpSemantics)
        assertEquals(PeerConnection.BundlePolicy.MAXBUNDLE, c.bundlePolicy)
        assertEquals(PeerConnection.RtcpMuxPolicy.REQUIRE, c.rtcpMuxPolicy)
        assertEquals(PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY, c.continualGatheringPolicy)
        assertEquals(PeerConnection.TcpCandidatePolicy.DISABLED, c.tcpCandidatePolicy)
        assertEquals(2, c.iceServers.size)
        assertEquals("user", c.iceServers[1].username)
        assertEquals("secret", c.iceServers[1].password)
        assertEquals("", c.iceServers[0].username)
    }

    /** (C5 test switch) Relay only: no host or reflexive candidate, so the call connects through the TURN server or not at all. */
    @Test
    fun relayOnlyUsesRelayedCandidatesAlone() {
        assertEquals(PeerConnection.IceTransportsType.ALL, WebRtcMediaEngine.configuration(servers).iceTransportsType)
        assertEquals(PeerConnection.IceTransportsType.RELAY, WebRtcMediaEngine.configuration(servers, relayOnly = true).iceTransportsType)
    }

    /** Relay only is a test option: not plain, so only a test build with the development signer may set it. */
    @Test
    fun relayOnlyIsATestOption() {
        assertEquals(true, MediaConfig().plain)
        assertEquals(false, MediaConfig(relayOnly = true).plain)
    }

    /**
     * The check next to the engine: a build that is not a test build makes no engine with any test option, whatever
     * asked (it refuses before the native library is loaded or the context used).
     */
    @Test
    fun anEngineFactoryOfABuildThatIsNotATestBuildRefusesEveryTestOption() {
        val factory = WebRtcMediaEngine.factory(android.content.ContextWrapper(null), testBuild = false)
        for (c in listOf(
            MediaConfig(relayOnly = true),
            MediaConfig(dtlsPqcTrial = true),
            MediaConfig(faults = MediaFaults(keepRemoteSsrc = true)),
            MediaConfig(faults = MediaFaults(misstateOwnSsrc = true)),
            MediaConfig(faults = MediaFaults(sendUnencrypted = true)),
            MediaConfig(faults = MediaFaults(wrongSendKey = true)),
        )) {
            org.junit.Assert.assertThrows("$c", IllegalStateException::class.java) { factory.create(c) }
        }
    }
}
