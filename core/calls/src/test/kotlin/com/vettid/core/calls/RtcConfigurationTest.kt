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
}
