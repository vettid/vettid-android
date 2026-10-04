package com.vettid.core.crypto

import java.security.SecureRandom

/**
 * The one randomness source of the protocol code: key seeds, nonces, ULID
 * entropy and HPKE encapsulation randomness all come from here.
 *
 * Production code always draws from [SecureRandom]. The §16 test vectors need
 * deterministic draws ("Implementations supply it through a deterministic test
 * hook"): [withDeterministic] substitutes a source for the duration of a block.
 * It is `internal`, so no other module (and no release code path) can reach it;
 * only this module's unit tests use it.
 */
object Randomness {
    private val secure = SecureRandom()

    @Volatile
    private var override: ((Int) -> ByteArray)? = null

    /** Returns [n] random bytes. */
    fun bytes(n: Int): ByteArray {
        override?.let { src ->
            val b = src(n)
            check(b.size == n)
            return b
        }
        val b = ByteArray(n)
        secure.nextBytes(b)
        return b
    }

    /** TEST VECTORS ONLY: every draw inside [block] comes from [source]. */
    internal fun <T> withDeterministic(source: (Int) -> ByteArray, block: () -> T): T {
        val prev = override
        override = source
        try {
            return block()
        } finally {
            override = prev
        }
    }
}
