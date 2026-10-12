package com.vettid.core.calls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameFormatTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun hkdfMatchesRfc5869() {
        val ikm = ByteArray(22) { 0x0b }
        // Test case 1.
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            FrameFormat.hkdf(ikm, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42),
        )
        // Test case 3: no salt, no info.
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            FrameFormat.hkdf(ikm, ByteArray(0), ByteArray(0), 42),
        )
    }

    @Test
    fun theFrameKeyIsTheFirstHalfOfTheRatchetStep() {
        // Both are HKDF over the same input, salt and info, 16 and 32 bytes long (C0-FRAME-FORMAT §3, §7).
        val material = ByteArray(32) { it.toByte() }
        val salt = "salt".toByteArray()
        val key = FrameFormat.frameKey(material, salt)
        assertEquals(16, key.size)
        assertArrayEquals(key, FrameFormat.ratchet(material, salt).copyOf(16))
    }

    @Test
    fun ivIsSsrcTimestampAndTimestampMinusCounter() {
        assertArrayEquals(hex("0a0b0c0d" + "00010000" + "0000fffd"), FrameFormat.iv(0x0a0b0c0d, 0x10000, 3))
        // The counter wraps at 65535, not 65536.
        assertArrayEquals(FrameFormat.iv(1, 500, 0), FrameFormat.iv(1, 500, 0xFFFF))
    }

    @Test
    fun anAudioFrameKeepsItsFirstByteAndGrowsByThirtyBytes() {
        val key = FrameFormat.frameKey(ByteArray(32) { 7 }, ByteArray(0))
        val frame = ByteArray(60) { (it * 3).toByte() }
        val iv = FrameFormat.iv(0x11223344, 960, 0)
        val sealed = FrameFormat.seal(key, frame, FrameFormat.AUDIO_CLEAR_BYTES, iv, 0)

        assertEquals(frame.size + FrameFormat.OVERHEAD_BYTES, sealed.size)
        assertEquals(frame[0], sealed[0])
        assertArrayEquals(iv, sealed.copyOfRange(sealed.size - 14, sealed.size - 2))
        assertEquals(12, sealed[sealed.size - 2].toInt())
        assertEquals(0, sealed[sealed.size - 1].toInt())
        assertArrayEquals(frame, FrameFormat.open(key, sealed, FrameFormat.AUDIO_CLEAR_BYTES))
    }

    @Test
    fun aWrongKeyAChangedHeaderOrAPlainFrameDoesNotOpen() {
        val key = FrameFormat.frameKey(ByteArray(32) { 7 }, ByteArray(0))
        val frame = ByteArray(40) { it.toByte() }
        val sealed = FrameFormat.seal(key, frame, 1, FrameFormat.iv(1, 2, 3), 0)

        assertNull(FrameFormat.open(FrameFormat.frameKey(ByteArray(32) { 8 }, ByteArray(0)), sealed, 1))
        assertNull(FrameFormat.open(key, sealed.copyOf().also { it[0] = (it[0] + 1).toByte() }, 1))
        assertNull(FrameFormat.open(key, sealed.copyOf().also { it[5] = (it[5] + 1).toByte() }, 1))
        assertNull(FrameFormat.open(key, frame, 1))
        assertNull(FrameFormat.open(key, ByteArray(1), 1))
    }

    @Test
    fun aDataPacketOpensWithoutAssociatedData() {
        val key = FrameFormat.frameKey(ByteArray(32) { 1 }, ByteArray(0))
        val iv = FrameFormat.iv(0, 5, 0)
        val message = "hello".toByteArray()
        val sealed = FrameFormat.seal(key, message, 0, iv, 0)
        val payload = sealed.copyOf(message.size + FrameFormat.TAG_BYTES)
        assertArrayEquals(message, FrameFormat.openPacket(key, iv, payload))
        assertNull(FrameFormat.openPacket(key, FrameFormat.iv(0, 6, 0), payload))
    }
}
