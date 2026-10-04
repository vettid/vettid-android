package com.vettid.core.relay

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.session.Mailbox
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The client side of the relay's authentication (RELAY-PROTOCOL §3.2, §4.1),
 * as vettid-relay's `relayauth`: mailbox ids, canonical request digests and
 * the three signed-request headers.
 */
object RelayAuth {
    const val HEADER_KEY = "X-VettID-Key"
    const val HEADER_TIMESTAMP = "X-VettID-Timestamp"
    const val HEADER_SIG = "X-VettID-Sig"

    /** hex(SHA-256("")): the body hash of bodiless requests. */
    const val EMPTY_BODY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private val seconds = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    /** lowercase(base32(SHA-256(pub))) without padding, first 26 characters (§3.2). */
    fun mailboxId(pub: ByteArray): String = Mailbox.id(pub)

    /** Whether [s] has the shape of a mailbox id. */
    fun isValidMailboxId(s: String): Boolean = s.length == Mailbox.LENGTH && s.all { it in 'a'..'z' || it in '2'..'7' }

    /** Canonical (standard, padded) base64 of a public key: a token's `sub`, the `X-VettID-Key` header. */
    fun encodeKey(pub: ByteArray): String = Base64s.encodeStd(pub)

    /** Strictly decodes a base64 Ed25519 public key, or null. */
    fun decodeKey(s: String): ByteArray? {
        if (s.length != KEY_B64_LEN) return null
        return try {
            Base64s.decodeStd(s, Suite.ED25519_PUBLIC_SIZE)
        } catch (_: CryptoException) {
            null
        }
    }

    /** SHA-256 over the exact body bytes (empty for bodiless requests). */
    fun bodyHash(body: ByteArray?): ByteArray = Bytes.sha256(body ?: ByteArray(0))

    /** METHOD "\n" PATH "\n" timestamp "\n" lowercase-hex(SHA-256(body)). */
    fun canonical(method: String, path: String, timestamp: String, bodyHash: ByteArray): ByteArray =
        (method.uppercase() + "\n" + path + "\n" + timestamp + "\n" + Bytes.hex(bodyHash)).toByteArray(Charsets.UTF_8)

    /** SHA-256(canonical): what is Ed25519-signed. */
    fun digest(method: String, path: String, timestamp: String, bodyHash: ByteArray): ByteArray =
        Bytes.sha256(canonical(method, path, timestamp, bodyHash))

    /** Ed25519(key, digest). Deterministic. */
    fun signRequest(key: Ed25519PrivateKey, method: String, path: String, timestamp: String, bodyHash: ByteArray): ByteArray =
        key.signRaw(digest(method, path, timestamp, bodyHash))

    /**
     * RFC 3339 UTC with millisecond precision and trailing zeros trimmed, as
     * Go's RFC3339Nano renders it: whole seconds are `2026-06-10T12:00:00Z`.
     * Fractional seconds keep two requests in the same second from
     * colliding in the relay's replay cache (§4.1).
     */
    fun timestamp(t: Instant): String {
        val ms = t.truncatedTo(ChronoUnit.MILLIS)
        val base = seconds.format(ms)
        val frac = ms.nano / NANOS_PER_MILLI
        if (frac == 0) return base + "Z"
        return base + "." + "%03d".format(frac).trimEnd('0') + "Z"
    }

    /** The signed-request headers for one attempt; the timestamp header is signed verbatim. */
    fun headers(key: Ed25519PrivateKey, method: String, path: String, t: Instant, bodyHash: ByteArray): Map<String, String> {
        val ts = timestamp(t)
        return mapOf(
            HEADER_KEY to encodeKey(key.publicKey),
            HEADER_TIMESTAMP to ts,
            HEADER_SIG to Base64s.encodeStd(signRequest(key, method, path, ts, bodyHash)),
        )
    }

    private const val KEY_B64_LEN = 44
    private const val NANOS_PER_MILLI = 1_000_000
}
