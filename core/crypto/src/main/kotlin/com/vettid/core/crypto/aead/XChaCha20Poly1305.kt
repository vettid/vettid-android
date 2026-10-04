package com.vettid.core.crypto.aead

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Randomness
import com.vettid.core.crypto.Suite
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.XChaCha20Poly1305 as BcXChaCha
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha): session-mode envelopes,
 * claim bundles, claim-check blobs and critical-item values (§4.2, §5.5, §6.4,
 * §10.7). A fresh random 192-bit nonce per message.
 */
object XChaCha20Poly1305 {
    /** A fresh random 24-byte nonce. */
    fun newNonce(): ByteArray = Randomness.bytes(Suite.X_NONCE_SIZE)

    /** Returns ciphertext || tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = cipher(true, key, nonce, aad)
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    /** Opens ciphertext || tag; the tag is checked in constant time by the AEAD. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < Suite.TAG_SIZE) throw CryptoException.Decrypt()
        val c = cipher(false, key, nonce, aad)
        val out = ByteArray(c.getOutputSize(ciphertext.size))
        try {
            val n = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            c.doFinal(out, n)
        } catch (_: InvalidCipherTextException) {
            out.fill(0)
            throw CryptoException.Decrypt()
        }
        return out
    }

    private fun cipher(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray): BcXChaCha {
        if (key.size != Suite.KEY_SIZE || nonce.size != Suite.X_NONCE_SIZE) throw CryptoException.Key("xchacha size")
        val c = BcXChaCha()
        c.init(encrypt, AEADParameters(KeyParameter(key), Suite.TAG_SIZE * 8, nonce, aad))
        return c
    }
}
