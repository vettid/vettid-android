package com.vettid.core.crypto

/**
 * An 8-byte key id (VAULT-MESSAGING §4.4). Kids are lookup hints, not
 * authenticators; they are compared in constant time anyway.
 */
class Kid(bytes: ByteArray) {
    private val b: ByteArray = bytes.copyOf()

    init {
        if (bytes.size != Suite.KID_SIZE) throw CryptoException.Key("kid size")
    }

    fun bytes(): ByteArray = b.copyOf()

    val isAnonymous: Boolean get() = Bytes.constantTimeEquals(b, ByteArray(Suite.KID_SIZE))

    override fun equals(other: Any?): Boolean = other is Kid && Bytes.constantTimeEquals(b, other.b)

    override fun hashCode(): Int = b.contentHashCode()

    /** Lowercase hex. */
    override fun toString(): String = Bytes.hex(b)

    companion object {
        /** The all-zero kid of an anonymous sender. */
        val ANONYMOUS = Kid(ByteArray(Suite.KID_SIZE))

        /** SHA-256("vettid/vms/2/kid" || ek)[0:8], for a static KEM key or the ETK. */
        fun of(ek: ByteArray): Kid = Kid(Bytes.labeledHash(Labels.KID, ek).copyOf(Suite.KID_SIZE))

        /** Parses 16 lowercase hex characters. */
        fun parseHex(s: String): Kid {
            if (!Bytes.isLowerHex(s, 2 * Suite.KID_SIZE)) throw CryptoException.Format("kid")
            return Kid(Bytes.unhex(s))
        }
    }
}
