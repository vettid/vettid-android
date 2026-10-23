package com.vettid.core.calls

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.call.CallFrames
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.crypto.kdf.Hkdf
import com.vettid.core.vault.CallCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.DataPacketCryptor
import org.webrtc.DataPacketCryptorFactory
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The media stack's native frame cryptor, checked on a phone against this repository's own description of the
 * format (CallFrames, docs/calls/CALL-MEDIA.md §4–§5, VAULT-MESSAGING §10.10): for both media suites, the frames a
 * real [WebRtcMediaEngine] sends are read off the wire and opened in Kotlin with the frame key derived as the
 * specification says (HKDF-SHA-256, L = 16 or 32), and frames sealed in Kotlin are played by the native receiver.
 *
 * Two engines run in this process, each on a stack built by this test with DTLS-SRTP switched off
 * (`PeerConnectionFactory.Options.disableEncryption`, test only: nothing in the app can build such a stack) so that
 * the RTP payload, which is the frame cryptor's output, can be read. They reach each other only through [Relay], a
 * UDP relay on this phone's own IPv4 address, which records every RTP packet and can replace its payload. Each engine
 * is driven exactly as a call drives it (createOffer, startGathering, acceptOffer, setKeys, acceptAnswer), so the
 * frames are those of the production path: the cryptors are made for the preferred suite (2) when the SDP is made,
 * and remade by setKeys when the call is on suite 1.
 *
 * Its own APK (`com.vettid.core.calls.test`): it touches no installed VettID app. Microphone and speaker are muted
 * in the stack: the frames carry silence. Keys in the log are the specification's TEST ONLY vectors; random keys
 * are never printed.
 */
@RunWith(AndroidJUnit4::class)
class FrameFormatDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val s1 = MediaSuite.AES_128_GCM
    private val s2 = MediaSuite.AES_256_GCM
    private val random = SecureRandom()

    @Before
    fun microphone() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun suite2FramesAreAes256GcmUnderTheSpecificationsFrameKey() {
        // VAULT-MESSAGING 0.28.0 vectors (TEST ONLY): k_dir("caller") for offered "2 1", chosen "2", and its frame key.
        val kCaller = hex(VECTOR_K_DIR_SUITE2)
        assertEquals(VECTOR_FRAME_KEY_SUITE2, Bytes.hex(CallFrames.frameKey(kCaller, s2)))
        val kCallee = key()
        call(s2, s2, kCaller, kCallee) { c ->
            val a = frames("suite 2, caller", c.relay.fromA, kCaller, s2)
            val b = frames("suite 2, callee", c.relay.fromB, kCallee, s2)
            // The caller's frames open under the vector's 32-byte key itself, not only under a recomputation of it.
            val vectorKey = hex(VECTOR_FRAME_KEY_SUITE2)
            assertTrue(c.relay.fromA.all { CallFrames.open(vectorKey, it.payload, CallFrames.AUDIO_CLEAR_SIZE) != null })
            c.receivedOk()
            report("suite 2: $a; $b")
        }
    }

    @Test
    fun suite1FramesAreAes128GcmUnderTheSpecificationsFrameKeyAfterTheCryptorsWereRemade() {
        val kCaller = hex(VECTOR_K_DIR_SUITE1)
        assertEquals(VECTOR_FRAME_KEY_SUITE1, Bytes.hex(CallFrames.frameKey(kCaller, s1)))
        val kCallee = key()
        call(s1, s1, kCaller, kCallee) { c ->
            val a = frames("suite 1, caller", c.relay.fromA, kCaller, s1)
            val b = frames("suite 1, callee", c.relay.fromB, kCallee, s1)
            val vectorKey = hex(VECTOR_FRAME_KEY_SUITE1)
            assertTrue(c.relay.fromA.all { CallFrames.open(vectorKey, it.payload, CallFrames.AUDIO_CLEAR_SIZE) != null })
            c.receivedOk()
            report("suite 1 (cryptors remade from 2 by setKeys): $a; $b")
        }
    }

    @Test
    fun theNativeSuite2ReceiverPlaysFramesSealedInKotlinAndDropsThemUnderTheSixteenByteKey() {
        for (sealSuite in listOf(s2, s1)) {
            val kCaller = key()
            val kCallee = key()
            val native = CallFrames.frameKey(kCaller, s2)
            // Sealed in Kotlin under the suite-2 frame key (or, as a fault, under the suite-1 one, L = 16), with an
            // IV of its own (another SSRC value and counter): the receiver must read the IV from the frame.
            val sealKey = CallFrames.frameKey(kCaller, sealSuite)
            val sealed = AtomicInteger()
            val reseal: (Rtp) -> ByteArray? = { rtp ->
                CallFrames.open(native, rtp.payload, CallFrames.AUDIO_CLEAR_SIZE)?.let { frame ->
                    val iv = CallFrames.iv(rtp.ssrc xor IV_SSRC_MASK, rtp.timestamp, IV_COUNTER_BASE + sealed.getAndIncrement())
                    rtp.withPayload(CallFrames.seal(sealKey, frame, CallFrames.AUDIO_CLEAR_SIZE, iv))
                }
            }
            call(s2, s2, kCaller, kCallee, rewriteAtoB = reseal) { c ->
                val wanted = if (sealSuite == s2) CryptorState.OK else CryptorState.DECRYPTION_FAILED
                c.await { c.receiveStates(c.eventsB).contains(wanted) }
                val states = c.receiveStates(c.eventsB)
                assertTrue("frames were sealed in Kotlin", sealed.get() >= MIN_FRAMES)
                if (sealSuite == s2) {
                    assertTrue("the native receiver decrypted Kotlin's frames: $states", CryptorState.OK in states)
                    assertFalse(states.toString(), CryptorState.DECRYPTION_FAILED in states)
                } else {
                    assertTrue("a 16-byte-key frame fails in a suite-2 receiver: $states", CryptorState.DECRYPTION_FAILED in states)
                    assertFalse(states.toString(), CryptorState.OK in states)
                }
                report("Kotlin-sealed under suite ${sealSuite.id}'s key into a suite-2 receiver: ${sealed.get()} frames, receiver $states")
            }
        }
    }

    @Test
    fun aSuite1SenderAndASuite2ReceiverHearNothing() {
        val kCaller = key()
        val kCallee = key()
        // Same direction keys; the caller set them for suite 2, the callee for suite 1.
        call(s2, s1, kCaller, kCallee) { c ->
            c.await { CryptorState.DECRYPTION_FAILED in c.receiveStates(c.eventsA) && CryptorState.DECRYPTION_FAILED in c.receiveStates(c.eventsB) }
            val a = frames("mismatch, caller (suite 2)", c.relay.fromA, kCaller, s2)
            val b = frames("mismatch, callee (suite 1)", c.relay.fromB, kCallee, s1)
            for (events in listOf(c.eventsA, c.eventsB)) {
                val states = c.receiveStates(events)
                assertTrue(states.toString(), CryptorState.DECRYPTION_FAILED in states)
                assertFalse("nothing decrypted: $states", CryptorState.OK in states)
            }
            report("mismatch: $a; $b; both receivers DECRYPTION_FAILED, never OK")
        }
    }

    /**
     * The engine never ratchets (window 0, no call of `ratchetKey`): this is the library's ratchet step under the
     * engine's own key provider, for the record. Material' = HKDF-SHA-256(material, frame label, 128 zero bytes,
     * L = 32) for both suites; the frame key after it is the suite's length.
     */
    @Test
    fun theLibrarysRatchetStepUnderTheEnginesKeyProvider() {
        WebRtcRuntime.start(context, MediaConfig())
        val lines = mutableListOf<String>()
        for (suite in listOf(s2, s1)) {
            val provider = WebRtcMediaEngine.keyProvider(suite)
            val cryptor = DataPacketCryptorFactory.createDataPacketCryptor(FrameCryptorAlgorithm.AES_GCM, provider)
            try {
                val material = key()
                val message = "VettID frame format device test".toByteArray()
                assertTrue(provider.setKey(PARTICIPANT, 0, material.copyOf()))
                val before = cryptor.encrypt(PARTICIPANT, 0, message)
                assertArrayEquals(message, open(CallFrames.frameKey(material, suite), before))
                assertNull(open(CallFrames.frameKey(material, other(suite)), before))
                val next = provider.ratchetKey(PARTICIPANT, 0)
                assertArrayEquals(Hkdf.derive(material, suite.frameLabel.toByteArray(Charsets.US_ASCII), ByteArray(INFO_SIZE), RATCHET_SIZE), next)
                val after = cryptor.encrypt(PARTICIPANT, 0, message)
                assertArrayEquals(message, open(CallFrames.frameKey(next, suite), after))
                assertNull(open(CallFrames.frameKey(next, other(suite)), after))
                lines += "suite ${suite.id}: ratchet material = HKDF L=32 MATCH; frame key after it ${suite.frameKeySize} bytes MATCH"
            } finally {
                cryptor.dispose()
                provider.dispose()
            }
        }
        report(lines.joinToString("; "))
    }

    // --- the call ---

    private class Call(val relay: Relay) {
        val eventsA = CopyOnWriteArrayList<MediaEvent>()
        val eventsB = CopyOnWriteArrayList<MediaEvent>()

        fun receiveStates(events: List<MediaEvent>) =
            events.filterIsInstance<MediaEvent.Cryptor>().filter { it.direction == MediaDirection.RECEIVE }.map { it.state }

        fun sendStates(events: List<MediaEvent>) =
            events.filterIsInstance<MediaEvent.Cryptor>().filter { it.direction == MediaDirection.SEND }.map { it.state }

        fun receivedOk() {
            for ((who, events) in listOf("caller" to eventsA, "callee" to eventsB)) {
                val received = receiveStates(events)
                assertTrue("$who decrypted: $received", CryptorState.OK in received)
                assertFalse("$who: $received", CryptorState.DECRYPTION_FAILED in received)
                assertTrue("$who encrypted: ${sendStates(events)}", CryptorState.OK in sendStates(events))
            }
        }

        fun await(condition: () -> Boolean) = runBlocking {
            withTimeoutOrNull(EVENT_WAIT_MS) {
                while (!condition()) delay(POLL_MS)
            }
        }
    }

    /** One call between two engines: the caller [a] sets its keys for [suiteA], the callee for [suiteB]. */
    private fun call(
        suiteA: MediaSuite,
        suiteB: MediaSuite,
        kCaller: ByteArray,
        kCallee: ByteArray,
        rewriteAtoB: ((Rtp) -> ByteArray?)? = null,
        verify: (Call) -> Unit,
    ) = runBlocking {
        val relay = Relay(rewriteAtoB)
        val c = Call(relay)
        val a = engine()
        val b = engine()
        val jobs = listOf(
            launch(Dispatchers.IO) { a.events.collect { c.eventsA += it } },
            launch(Dispatchers.IO) { b.events.collect { c.eventsB += it } },
        )
        try {
            val offer = a.createOffer()
            a.startGathering(emptyList())
            val answer = b.acceptOffer(offer, emptyList())
            b.setKeys(suiteB, kCallee.copyOf(), kCaller.copyOf())
            a.setKeys(suiteA, kCaller.copyOf(), kCallee.copyOf())
            a.acceptAnswer(answer)
            withTimeoutOrNull(EVENT_WAIT_MS) {
                while (c.eventsA.none { it is MediaEvent.GatheringDone } || c.eventsB.none { it is MediaEvent.GatheringDone }) delay(POLL_MS)
            }
            val forB = c.eventsA.filterIsInstance<MediaEvent.LocalCandidate>().firstNotNullOfOrNull { relay.caller(it.candidate) }
            val forA = c.eventsB.filterIsInstance<MediaEvent.LocalCandidate>().firstNotNullOfOrNull { relay.callee(it.candidate) }
            assertNotNull("an IPv4 UDP host address on the caller (Wi-Fi on?)", forB)
            assertNotNull("an IPv4 UDP host address on the callee", forA)
            b.addRemoteCandidates(listOf(forB!!))
            a.addRemoteCandidates(listOf(forA!!))
            withTimeoutOrNull(EVENT_WAIT_MS) {
                while (relay.fromA.size < MIN_FRAMES || relay.fromB.size < MIN_FRAMES) delay(POLL_MS)
            }
            assertTrue("caller frames on the wire: ${relay.fromA.size}; ${c.eventsA}", relay.fromA.size >= MIN_FRAMES)
            assertTrue("callee frames on the wire: ${relay.fromB.size}; ${c.eventsB}", relay.fromB.size >= MIN_FRAMES)
            verify(c)
        } finally {
            a.close()
            b.close()
            relay.close()
            delay(CLOSE_MS)
            jobs.forEach { it.cancel() }
        }
    }

    private fun engine(): WebRtcMediaEngine {
        WebRtcRuntime.start(context, MediaConfig())
        val audio = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        audio.setMicrophoneMute(true)
        audio.setSpeakerMute(true)
        val options = PeerConnectionFactory.Options().apply {
            disableEncryption = true
            disableNetworkMonitor = true
        }
        val stack = PeerConnectionFactory.builder().setOptions(options).setAudioDeviceModule(audio).createPeerConnectionFactory()
        return WebRtcMediaEngine.onStack(stack, audio)
    }

    /**
     * Every frame of [frames] (one sender's, in RTP order): opens under the [suite]'s frame key of [kDir] and under
     * no key of the other length; one clear byte, authenticated; the trailer `12, 0`; the IV `SSRC || timestamp ||
     * timestamp - n`. Returns what was seen of n, the per-SSRC counter.
     */
    private fun frames(who: String, frames: List<Rtp>, kDir: ByteArray, suite: MediaSuite): String {
        val key = CallFrames.frameKey(kDir, suite)
        val otherKey = CallFrames.frameKey(kDir, other(suite))
        assertEquals(suite.frameKeySize, key.size)
        val sorted = frames.sortedBy { it.sequence }
        val counters = mutableListOf<Long>()
        for (f in sorted) {
            val p = f.payload
            val opened = CallFrames.open(key, p, CallFrames.AUDIO_CLEAR_SIZE)
            assertNotNull("$who: a frame opens under the ${key.size}-byte key", opened)
            assertNull("$who: and not under the ${otherKey.size}-byte one", CallFrames.open(otherKey, p, CallFrames.AUDIO_CLEAR_SIZE))
            assertEquals(p.size - CallFrames.OVERHEAD, opened!!.size)
            assertEquals("the first byte is in the clear", p[0], opened[0])
            val changed = p.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertNull("$who: and authenticated", CallFrames.open(key, changed, CallFrames.AUDIO_CLEAR_SIZE))
            assertEquals(CallFrames.IV_SIZE, p[p.size - 2].toInt())
            assertEquals(CallFrames.KEY_INDEX, p[p.size - 1].toInt())
            val iv = ByteBuffer.wrap(p, p.size - CallFrames.TRAILER_SIZE - CallFrames.IV_SIZE, CallFrames.IV_SIZE)
            assertEquals("$who: IV starts with the SSRC", f.ssrc, iv.int)
            assertEquals("$who: then the RTP timestamp", f.timestamp, iv.int)
            counters += (f.timestamp.toLong() - iv.int.toLong()) and U32
        }
        val steps = counters.zipWithNext { x, y -> (y - x + COUNTER_MODULUS) % COUNTER_MODULUS }
        // After its first frame, the counter goes up by one a frame (mod 65535): no frame was lost on this path.
        assertTrue("$who: counter steps ${steps.drop(1).distinct()}", steps.drop(1).all { it == 1L })
        return "$who: ${frames.size} frames MATCH (${key.size}-byte key; ${otherKey.size}-byte key: none), " +
            "n = ${counters.take(COUNTERS_SHOWN)}…"
    }

    private fun open(key: ByteArray, p: DataPacketCryptor.EncryptedPacket): ByteArray? =
        CallFrames.open(key, p.payload + p.iv + byteArrayOf(CallFrames.IV_SIZE.toByte(), p.keyIndex.toByte()), 0)

    private fun other(suite: MediaSuite) = if (suite == s1) s2 else s1

    private fun key() = ByteArray(K_DIR_SIZE).also { random.nextBytes(it) }

    private fun report(line: String) {
        Log.i(TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("vettid", line) })
    }

    /** One RTP packet as the relay saw it. */
    class Rtp private constructor(private val packet: ByteArray, private val headerEnd: Int, private val payloadEnd: Int) {
        val sequence: Int get() = ((packet[2].toInt() and BYTE) shl BITS_8) or (packet[3].toInt() and BYTE)
        val timestamp: Int get() = ByteBuffer.wrap(packet, TS_AT, INT_SIZE).int
        val ssrc: Int get() = ByteBuffer.wrap(packet, SSRC_AT, INT_SIZE).int
        val payload: ByteArray get() = packet.copyOfRange(headerEnd, payloadEnd)

        /** This packet with another payload (and no padding). */
        fun withPayload(p: ByteArray): ByteArray {
            val header = packet.copyOfRange(0, headerEnd)
            header[0] = (header[0].toInt() and PADDING_BIT.inv()).toByte()
            return header + p
        }

        companion object {
            /** An RTP packet (not STUN, not RTCP), or null. */
            fun parse(b: ByteArray): Rtp? {
                if (b.size < RTP_HEADER || (b[0].toInt() and BYTE) shr VERSION_SHIFT != 2) return null
                val second = b[1].toInt() and BYTE
                if (second in RTCP_TYPES) return null
                var end = RTP_HEADER + INT_SIZE * (b[0].toInt() and CSRC_COUNT)
                if (b[0].toInt() and EXTENSION_BIT != 0) {
                    if (b.size < end + INT_SIZE) return null
                    val words = ((b[end + 2].toInt() and BYTE) shl BITS_8) or (b[end + 3].toInt() and BYTE)
                    end += INT_SIZE + INT_SIZE * words
                }
                val padding = if (b[0].toInt() and PADDING_BIT != 0) b[b.size - 1].toInt() and BYTE else 0
                val payloadEnd = b.size - padding
                return if (payloadEnd > end) Rtp(b, end, payloadEnd) else null
            }
        }
    }

    /**
     * The only path between the two engines: each is told the other is at one of the relay's ports on this phone's
     * own IPv4 address. Records every RTP packet each way; [rewriteAtoB] may replace (or drop: null) the caller's.
     */
    class Relay(private val rewriteAtoB: ((Rtp) -> ByteArray?)?) : Closeable {
        private val forA = DatagramSocket(0) // the caller sends here: for it, this is the callee
        private val forB = DatagramSocket(0)

        @Volatile private var a: InetSocketAddress? = null

        @Volatile private var b: InetSocketAddress? = null
        val fromA = CopyOnWriteArrayList<Rtp>()
        val fromB = CopyOnWriteArrayList<Rtp>()
        private val pumps = listOf(pump(forA, forB, { b }, fromA, rewriteAtoB), pump(forB, forA, { a }, fromB, null))

        /** The caller's [c] as the callee is to see it, or null for any but its first IPv4 UDP host address. */
        fun caller(c: CallCandidate): CallCandidate? = rewrite(c, forB.localPort, a) { a = it }

        fun callee(c: CallCandidate): CallCandidate? = rewrite(c, forA.localPort, b) { b = it }

        private fun rewrite(c: CallCandidate, port: Int, known: InetSocketAddress?, learn: (InetSocketAddress) -> Unit): CallCandidate? {
            val t = c.candidate.split(' ').toMutableList()
            if (known != null || t.size < CANDIDATE_TYPE + 1 || !t[2].equals("udp", ignoreCase = true) || t[CANDIDATE_TYPE] != "host") return null
            val ip = runCatching { InetAddress.getByName(t[CANDIDATE_IP]) }.getOrNull()
            if (ip !is Inet4Address || ip.isLoopbackAddress) return null
            learn(InetSocketAddress(ip, t[CANDIDATE_PORT].toInt()))
            t[CANDIDATE_PORT] = port.toString()
            return c.copy(candidate = t.joinToString(" "))
        }

        private fun pump(
            from: DatagramSocket,
            out: DatagramSocket,
            to: () -> InetSocketAddress?,
            seen: MutableList<Rtp>,
            change: ((Rtp) -> ByteArray?)?,
        ) = thread(name = "vettid-test-relay", isDaemon = true) {
            val buffer = ByteArray(MAX_PACKET)
            while (!from.isClosed) {
                val p = DatagramPacket(buffer, buffer.size)
                try {
                    from.receive(p)
                } catch (_: IOException) {
                    break
                }
                var data = buffer.copyOf(p.length)
                val rtp = Rtp.parse(data)
                if (rtp != null) {
                    seen += rtp
                    if (change != null) data = change(rtp) ?: continue
                }
                val dest = to() ?: continue
                try {
                    out.send(DatagramPacket(data, data.size, dest))
                } catch (_: IOException) {
                    // A closed relay: the test is over.
                }
            }
        }

        override fun close() {
            forA.close()
            forB.close()
            pumps.forEach { it.join(CLOSE_MS) }
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(HEX).toByte() }

    private companion object {
        const val TAG = "VettIDFrameFormat"
        const val PARTICIPANT = "send"
        const val VECTOR_K_DIR_SUITE1 = "df07dfb74e30ab8b952a864b0214911649859b9d371d50cc04daa84d5c952c08"
        const val VECTOR_FRAME_KEY_SUITE1 = "df487affce123283952f691628c1c2de"
        const val VECTOR_K_DIR_SUITE2 = "129e7ea015351c448613b72d821141780fbc2681c9562f2b47894d999c609617"
        const val VECTOR_FRAME_KEY_SUITE2 = "e2637ed9e4ab88d26b3f34869104e58d355e1eb7b6c5b7d3aea521bda4fd83da"
        const val MIN_FRAMES = 50
        const val EVENT_WAIT_MS = 15_000L
        const val POLL_MS = 50L
        const val CLOSE_MS = 500L
        const val K_DIR_SIZE = 32
        const val INFO_SIZE = 128
        const val RATCHET_SIZE = 32
        const val IV_SSRC_MASK = 0x5a5a5a5a
        const val IV_COUNTER_BASE = 4242
        const val COUNTER_MODULUS = 65535L
        const val COUNTERS_SHOWN = 4
        const val U32 = 0xFFFFFFFFL
        const val HEX = 16
        const val BYTE = 0xFF
        const val BITS_8 = 8
        const val INT_SIZE = 4
        const val TS_AT = 4
        const val SSRC_AT = 8
        const val RTP_HEADER = 12
        const val VERSION_SHIFT = 6
        const val CSRC_COUNT = 0x0F
        const val EXTENSION_BIT = 0x10
        const val PADDING_BIT = 0x20
        val RTCP_TYPES = 192..223
        const val MAX_PACKET = 2048
        const val CANDIDATE_IP = 4
        const val CANDIDATE_PORT = 5
        const val CANDIDATE_TYPE = 7
    }
}
