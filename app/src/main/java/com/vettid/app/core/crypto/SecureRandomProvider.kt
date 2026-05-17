package com.vettid.app.core.crypto

import java.security.SecureRandom

/**
 * Process-wide cached SecureRandom.
 *
 * SECURITY: Java's `SecureRandom()` constructor seeds from the OS
 * entropy pool on first use and can spend tens of ms warming up.
 * Per-call instantiation paid that cost on every nonce/salt
 * generation; on hot paths (PIN unlock, message encrypt, attestation
 * nonce) this was both wasteful and a subtle correctness concern —
 * different code paths could end up using *different* SecureRandom
 * implementations if Provider preference changed between calls.
 *
 * `SecureRandom` is documented as thread-safe (java.util.Random
 * synchronizes its core methods, and Java's default SecureRandom
 * implementation honors that contract), so a single shared instance
 * is correct + cheaper than re-instantiating per call.
 *
 * Usage:
 *   val nonce = ByteArray(12)
 *   SecureRandomProvider.shared.nextBytes(nonce)
 *
 * Do NOT shadow with `val secureRandom = SecureRandom()` — that
 * defeats the whole point. If you find a per-call site that this
 * doc-comment doesn't cover, route it through `shared`.
 */
object SecureRandomProvider {
    /**
     * Prefer `getInstanceStrong()` (blocking-on-first-use but
     * guaranteed strong) with a fallback to the default constructor
     * for older devices that don't expose a strong provider. Mirrors
     * the pattern CryptoManager.secureRandom established — bringing
     * the singleton in line with the highest-entropy site we already
     * had, rather than backsliding to `SecureRandom()`.
     */
    val shared: SecureRandom by lazy {
        try {
            SecureRandom.getInstanceStrong()
        } catch (e: Exception) {
            SecureRandom()
        }
    }
}
