package com.vettid.core.calls

import java.security.MessageDigest
import java.security.SecureRandom

/** The spike's 32-byte key, typed or pasted as 64 hexadecimal characters on both phones. */
object CallKey {
    private const val HEX_CHARS = FrameFormat.MATERIAL_BYTES * 2
    private const val CHECK_BYTES = 4
    private const val RADIX = 16

    /** The key for [text] (spaces, colons and hyphens ignored), or null unless it is exactly 32 bytes of hex. */
    fun parse(text: String): ByteArray? {
        val hex = text.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (hex.length != HEX_CHARS || hex.any { Character.digit(it, RADIX) < 0 }) return null
        return ByteArray(FrameFormat.MATERIAL_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(RADIX).toByte() }
    }

    fun random(): ByteArray = ByteArray(FrameFormat.MATERIAL_BYTES).also { SecureRandom().nextBytes(it) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Eight characters that are the same on both phones exactly when the keys are: safe to show and to say. */
    fun check(key: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(key).copyOf(CHECK_BYTES))

    /** A key that is certainly not [key] (the wrong-key test): every bit flipped. */
    fun wrong(key: ByteArray): ByteArray = ByteArray(key.size) { (key[it].toInt() xor BYTE_MASK).toByte() }

    private const val BYTE_MASK = 0xFF
}
