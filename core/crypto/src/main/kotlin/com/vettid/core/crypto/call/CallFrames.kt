package com.vettid.core.crypto.call

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.kdf.Hkdf
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Media suite 1's frame encryption, written out in Kotlin (docs/calls/CALL-MEDIA.md; read from webrtc-sdk/webrtc
 * `api/crypto/frame_crypto_transformer.{h,cc}` at commit 73cb8180, the source of
 * `io.github.webrtc-sdk:android:150.7871.01`). The app never encrypts media with this: the media stack's native
 * frame cryptor does. It exists so that the format has an executable description with test vectors that another
 * platform can reproduce, and so that a build can check on a phone that the native library does what the document
 * says.
 */
object CallFrames {
    /** The AES key of suite 1: always 128 bits, whatever the length of the key material. */
    const val FRAME_KEY_SIZE = 16
    const val TAG_SIZE = 16
    const val IV_SIZE = 12

    /** After the IV: its length (12) and the key index. */
    const val TRAILER_SIZE = 2

    /** Left in the clear and authenticated: the first byte of an audio frame (for Opus, the TOC byte). */
    const val AUDIO_CLEAR_SIZE = 1

    /** What a frame grows by: tag, IV, trailer. */
    const val OVERHEAD = TAG_SIZE + IV_SIZE + TRAILER_SIZE

    /** The key index VettID uses: a key ring of one. */
    const val KEY_INDEX = 0
    private const val INFO_SIZE = 128
    private const val COUNTER_MODULUS = 0xFFFF

    /**
     * The AES-128-GCM key the frame cryptor derives from a direction key: the first 16 bytes of
     * `HKDF-SHA-256(ikm = material, salt = "vettid/vms/2/call-frame", info = 128 zero bytes)`.
     */
    fun frameKey(material: ByteArray, salt: ByteArray = CallMedia.LABEL_FRAME.toByteArray(Charsets.US_ASCII)): ByteArray =
        Hkdf.derive(material, salt, ByteArray(INFO_SIZE), FRAME_KEY_SIZE)

    /**
     * The sender's IV: SSRC, RTP timestamp, and the timestamp minus a per-SSRC frame counter (modulo 65535), each
     * 32 bits big-endian. The receiver reads the IV from the frame and never recomputes it.
     */
    fun iv(ssrc: Int, timestamp: Int, sendCount: Int): ByteArray =
        ByteBuffer.allocate(IV_SIZE).putInt(ssrc).putInt(timestamp).putInt(timestamp - (sendCount % COUNTER_MODULUS)).array()

    /**
     * An encrypted frame: `clear header | ciphertext | tag(16) | IV(12) | 12 | key index`. The header (the first
     * [clearBytes] of [frame]) is the AEAD's associated data.
     */
    fun seal(frameKey: ByteArray, frame: ByteArray, clearBytes: Int, iv: ByteArray, keyIndex: Int = KEY_INDEX): ByteArray {
        if (frameKey.size != FRAME_KEY_SIZE || iv.size != IV_SIZE || clearBytes !in 0..frame.size) throw CryptoException.Key("frame sizes")
        val header = frame.copyOfRange(0, clearBytes)
        val sealed = gcm(Cipher.ENCRYPT_MODE, frameKey, iv, header).doFinal(frame, clearBytes, frame.size - clearBytes)
        return Bytes.concat(header, sealed, iv, byteArrayOf(IV_SIZE.toByte(), keyIndex.toByte()))
    }

    /** The frame inside [sealed], or null if it is not a frame encrypted under [frameKey]. */
    fun open(frameKey: ByteArray, sealed: ByteArray, clearBytes: Int): ByteArray? {
        val bodyEnd = sealed.size - TRAILER_SIZE - IV_SIZE
        if (clearBytes < 0 || bodyEnd - clearBytes < TAG_SIZE || sealed[sealed.size - TRAILER_SIZE].toInt() != IV_SIZE) return null
        val iv = sealed.copyOfRange(bodyEnd, bodyEnd + IV_SIZE)
        val header = sealed.copyOfRange(0, clearBytes)
        return try {
            Bytes.concat(header, gcm(Cipher.DECRYPT_MODE, frameKey, iv, header).doFinal(sealed, clearBytes, bodyEnd - clearBytes))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, iv))
            updateAAD(aad)
        }
}
