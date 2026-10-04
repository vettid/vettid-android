package com.vettid.core.crypto.kdf

import com.vettid.core.crypto.CryptoException
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id (RFC 9106) with the parameter rules of VAULT-MESSAGING §3.3.1 and
 * §3.5.1: new derivations use t = 3, m = 64 MiB, p = 1; parameters below
 * t = 1 or m = 8 MiB are refused. The vault runs the PIN and password KDFs
 * itself; the app uses this for local derivations and to check parameters.
 */
object Argon2id {
    const val DEFAULT_T = 3
    const val DEFAULT_M_KIB = 64 * 1024
    const val DEFAULT_P = 1
    const val MIN_T = 1
    const val MIN_M_KIB = 8 * 1024
    const val SALT_SIZE = 16

    /** Argon2id(password, salt, t, m KiB, p) → [length] bytes. */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        t: Int = DEFAULT_T,
        mKiB: Int = DEFAULT_M_KIB,
        p: Int = DEFAULT_P,
        length: Int = 32,
    ): ByteArray {
        checkParams(t, mKiB, p)
        if (salt.size < 8) throw CryptoException.Key("argon2 salt")
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(t)
            .withMemoryAsKB(mKiB)
            .withParallelism(p)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(length)
        gen.generateBytes(password, out)
        return out
    }

    /** Refuses parameters below the floors of §3.3.1 / §3.5.1. */
    fun checkParams(t: Int, mKiB: Int, p: Int) {
        if (t < MIN_T || mKiB < MIN_M_KIB || p < 1 || mKiB < 8 * p) throw CryptoException.Key("argon2 parameters")
    }
}
