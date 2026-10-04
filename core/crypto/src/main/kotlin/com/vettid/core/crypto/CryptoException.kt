package com.vettid.core.crypto

/**
 * Every failure of the protocol code. Messages are fixed strings naming the
 * kind of failure; they never carry input bytes, plaintext or key material.
 */
sealed class CryptoException(message: String) : Exception(message) {
    /** Malformed input (wire format, JSON, sizes, encodings). */
    class Format(what: String) : CryptoException("malformed: $what")

    /** Wrong key size, invalid key, or a destroyed key. */
    class Key(what: String) : CryptoException("key: $what")

    /** An AEAD or HPKE open failed. */
    class Decrypt : CryptoException("decryption failed")

    /** A signature did not verify. */
    class Signature(what: String) : CryptoException("signature: $what")

    /** Suite negotiation failures (§4.1, §13.4). */
    class Suite(what: String) : CryptoException("suite: $what")

    /** A protocol rule was violated (sender, kid, purpose, state). */
    class Protocol(what: String) : CryptoException("protocol: $what")

    /** A message failed its freshness rules (§5.3, §8.4). */
    class Time(what: String) : CryptoException("time: $what")

    /** An HPKE context or handshake was used after it was spent. */
    class Used(what: String) : CryptoException("already used: $what")
}
