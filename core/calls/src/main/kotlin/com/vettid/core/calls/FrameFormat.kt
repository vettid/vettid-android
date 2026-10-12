package com.vettid.core.calls

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The media stack's frame encryption, written out in Kotlin (docs/calls/C0-FRAME-FORMAT.md; read from
 * webrtc-sdk/webrtc `api/crypto/frame_crypto_transformer.{h,cc}` at commit 73cb8180, the source of
 * `io.github.webrtc-sdk:android:150.7871.01`). The app never encrypts media with this: the native `FrameCryptor`
 * does. It exists so that the format has an executable description with tests, and so that the spike screen can
 * check on a phone that the native library does what the document says ([openPacket], [ratchet]).
 */
object FrameFormat {
    /** The key the app hands to the key provider (CALLS-PLAN: `k_call`). */
    const val MATERIAL_BYTES = 32

    /** The AES key the stack derives from it: always 128 bits, whatever the length of the material. */
    const val FRAME_KEY_BYTES = 16
    const val TAG_BYTES = 16
    const val IV_BYTES = 12

    /** After the IV: its length (12) and the key index. */
    const val TRAILER_BYTES = 2

    /** Left in the clear and authenticated: the first byte of an audio frame (for Opus, the TOC byte). */
    const val AUDIO_CLEAR_BYTES = 1

    /** What a frame grows by: tag, IV, trailer. */
    const val OVERHEAD_BYTES = TAG_BYTES + IV_BYTES + TRAILER_BYTES

    private const val HASH_BYTES = 32
    private const val INFO_BYTES = 128
    private const val TAG_BITS = TAG_BYTES * 8
    private const val COUNTER_MODULUS = 0xFFFF
    private const val HMAC = "HmacSHA256"

    /** HKDF's `info` in the stack's key derivation: 128 zero bytes. */
    private val info = ByteArray(INFO_BYTES)

    /** HKDF-SHA256 (RFC 5869). An empty [salt] is HashLen zero bytes, as the RFC and BoringSSL have it. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..HASH_BYTES * UByte.MAX_VALUE.toInt()) { "length" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            val n = minOf(block.size, length - done)
            block.copyInto(out, done, 0, n)
            done += n
            counter++
        }
        return out
    }

    /** The AES-128-GCM key for [material] (key derivation HKDF; [salt] is the key provider's "ratchet salt"). */
    fun frameKey(material: ByteArray, salt: ByteArray): ByteArray = hkdf(material, salt, info, FRAME_KEY_BYTES)

    /** The next key material after a ratchet step (not used by VettID's 1:1 calls: one fresh key a call). */
    fun ratchet(material: ByteArray, salt: ByteArray): ByteArray = hkdf(material, salt, info, MATERIAL_BYTES)

    /**
     * The sender's IV: SSRC, RTP timestamp, and the timestamp minus a per-SSRC frame counter (modulo 65535), each
     * 32 bits big-endian. The receiver reads the IV from the frame and never recomputes it.
     */
    fun iv(ssrc: Int, timestamp: Int, sendCount: Int): ByteArray =
        ByteBuffer.allocate(IV_BYTES).putInt(ssrc).putInt(timestamp).putInt(timestamp - (sendCount % COUNTER_MODULUS)).array()

    /**
     * An encrypted frame: `clear header | ciphertext | tag(16) | IV(12) | 12 | key index`. The header (the first
     * [clearBytes] of [frame]) is the AEAD's associated data. H.264 and H.265 frames are escaped after this
     * (RBSP); audio, VP8, VP9 and AV1 frames are not.
     */
    fun seal(frameKey: ByteArray, frame: ByteArray, clearBytes: Int, iv: ByteArray, keyIndex: Int): ByteArray {
        require(frameKey.size == FRAME_KEY_BYTES && iv.size == IV_BYTES && clearBytes in 0..frame.size) { "sizes" }
        val header = frame.copyOfRange(0, clearBytes)
        val sealed = gcm(Cipher.ENCRYPT_MODE, frameKey, iv, header).doFinal(frame, clearBytes, frame.size - clearBytes)
        return header + sealed + iv + byteArrayOf(IV_BYTES.toByte(), keyIndex.toByte())
    }

    /** The frame inside [sealed], or null if it is not a frame encrypted under [frameKey]. */
    fun open(frameKey: ByteArray, sealed: ByteArray, clearBytes: Int): ByteArray? {
        val bodyEnd = sealed.size - TRAILER_BYTES - IV_BYTES
        if (bodyEnd - clearBytes < TAG_BYTES || sealed[sealed.size - TRAILER_BYTES].toInt() != IV_BYTES) return null
        val iv = sealed.copyOfRange(bodyEnd, bodyEnd + IV_BYTES)
        val header = sealed.copyOfRange(0, clearBytes)
        return try {
            header + gcm(Cipher.DECRYPT_MODE, frameKey, iv, header).doFinal(sealed, clearBytes, bodyEnd - clearBytes)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** A data packet of the stack's `DataPacketCryptor`: the same cipher and key, no associated data. */
    fun openPacket(frameKey: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray? = try {
        gcm(Cipher.DECRYPT_MODE, frameKey, iv, ByteArray(0)).doFinal(payload)
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
        }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)
}
