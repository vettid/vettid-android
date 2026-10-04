package com.vettid.core.crypto

import java.util.Base64

/**
 * Canonical base64 (VAULT-MESSAGING §5.3): standard base64 with padding for
 * JSON members, unpadded base64url (RFC 4648 §5) for QR codes and links.
 * Decoding rejects CR, LF and every non-canonical encoding (missing or extra
 * padding, non-zero trailing bits), like the Go reference's strictjson.
 */
object Base64s {
    private val std = Base64.getEncoder()
    private val rawUrl = Base64.getUrlEncoder().withoutPadding()

    fun encodeStd(b: ByteArray): String = std.encodeToString(b)

    fun encodeRawUrl(b: ByteArray): String = rawUrl.encodeToString(b)

    /** Decodes canonical standard base64; [n] >= 0 requires that length. */
    fun decodeStd(s: String, n: Int = -1): ByteArray = decode(s, n, Base64.getDecoder(), std)

    /** Decodes canonical unpadded base64url; [n] >= 0 requires that length. */
    fun decodeRawUrl(s: String, n: Int = -1): ByteArray = decode(s, n, Base64.getUrlDecoder(), rawUrl)

    private fun decode(s: String, n: Int, dec: Base64.Decoder, enc: Base64.Encoder): ByteArray {
        if (s.any { it == '\r' || it == '\n' }) throw CryptoException.Format("base64")
        val b = try {
            dec.decode(s)
        } catch (_: IllegalArgumentException) {
            throw CryptoException.Format("base64")
        }
        if (n >= 0 && b.size != n) throw CryptoException.Format("base64 length")
        if (enc.encodeToString(b) != s) throw CryptoException.Format("base64 not canonical")
        return b
    }
}
