package com.vettid.core.relay

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Suite

/**
 * PASETO v4.public with an empty footer and an empty implicit assertion
 * (RELAY-PROTOCOL §5.1), as vettid-relay's `relayauth` implements it.
 * Ed25519 is deterministic, so the same key and message bytes always give
 * the same token (§9.2).
 */
object Paseto {
    const val HEADER = "v4.public."
    const val MAX_TOKEN_LEN = 4096

    /** Pre-Authentication Encoding. */
    internal fun pae(vararg pieces: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(le64(pieces.size.toLong()))
        for (p in pieces) {
            out.write(le64(p.size.toLong()))
            out.write(p)
        }
        return out.toByteArray()
    }

    private fun le64(n: Long): ByteArray {
        val v = n and Long.MAX_VALUE
        return ByteArray(8) { i -> (v ushr (8 * i)).toByte() }
    }

    /** One spelling per token: only the base64url alphabet (no CR/LF, padding or footers). */
    private fun isBase64UrlChar(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    /** Signs the exact message bytes [m]. */
    fun sign(key: Ed25519PrivateKey, m: ByteArray): String {
        val sig = key.signRaw(pae(HEADER.toByteArray(), m, ByteArray(0), ByteArray(0)))
        return HEADER + Base64s.encodeRawUrl(Bytes.concat(m, sig))
    }

    /** Checks the form and signature against [pub]; returns the signed message bytes. */
    fun verify(token: String, pub: ByteArray): ByteArray {
        if (token.length > MAX_TOKEN_LEN || !token.startsWith(HEADER) || pub.size != Suite.ED25519_PUBLIC_SIZE) {
            throw TokenException.Invalid()
        }
        val rest = token.substring(HEADER.length)
        if (!rest.all(::isBase64UrlChar)) throw TokenException.Invalid()
        val raw = try {
            Base64s.decodeRawUrl(rest)
        } catch (_: CryptoException) {
            throw TokenException.Invalid()
        }
        if (raw.size < Suite.ED25519_SIGNATURE_SIZE) throw TokenException.Invalid()
        val m = raw.copyOfRange(0, raw.size - Suite.ED25519_SIGNATURE_SIZE)
        val sig = raw.copyOfRange(raw.size - Suite.ED25519_SIGNATURE_SIZE, raw.size)
        if (!Ed25519.verifyRaw(pub, pae(HEADER.toByteArray(), m, ByteArray(0), ByteArray(0)), sig)) throw TokenException.Invalid()
        return m
    }
}

/** Token failures (the relay's `token_invalid` and `token_expired`). */
sealed class TokenException(message: String) : Exception(message) {
    class Invalid : TokenException("token invalid")

    class Expired : TokenException("token expired")
}
