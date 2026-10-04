package com.vettid.core.crypto.session

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Labels
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.hpke.KemPublicKey
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonObject

/**
 * An `identity.rotate` statement (§3.4), one link of a rotation chain:
 *
 * ```
 * {"v":1,"suite":2,"old_ik","new_ik","new_kem","sig_old","sig_new"}
 * m       = old_ik (32) || new_ik (32) || new_kem (1216)
 * sig_old = Ed25519(old_ik, "vettid/vms/2/rotate" || m)
 * sig_new = Ed25519(new_ik, "vettid/vms/2/rotate" || m)
 * ```
 */
class Rotation(oldIk: ByteArray, newIk: ByteArray, val newKem: KemPublicKey, sigOld: ByteArray, sigNew: ByteArray) {
    private val oldIk = oldIk.copyOf()
    private val newIk = newIk.copyOf()
    private val sigOld = sigOld.copyOf()
    private val sigNew = sigNew.copyOf()

    fun oldIk(): ByteArray = oldIk.copyOf()

    fun newIk(): ByteArray = newIk.copyOf()

    private fun message(): ByteArray = Bytes.concat(oldIk, newIk, newKem.bytes())

    /** Checks both signatures and that the keys differ. */
    fun verify() {
        if (oldIk.size != Suite.ED25519_PUBLIC_SIZE || newIk.size != Suite.ED25519_PUBLIC_SIZE || Ed25519.equalPublic(oldIk, newIk)) {
            throw CryptoException.Signature("rotation")
        }
        val m = message()
        if (!Ed25519.verify(oldIk, Labels.ROTATE, m, sigOld) || !Ed25519.verify(newIk, Labels.ROTATE, m, sigNew)) {
            throw CryptoException.Signature("rotation")
        }
    }

    fun marshal(): String = JsonBuilder()
        .uint("v", 1)
        .uint("suite", Suite.SUITE_2.toLong())
        .base64("old_ik", oldIk)
        .base64("new_ik", newIk)
        .base64("new_kem", newKem.bytes())
        .base64("sig_old", sigOld)
        .base64("sig_new", sigNew)
        .build()

    companion object {
        /** Signs a rotation from [old] to [new], announcing [newKem]. */
        fun create(old: Ed25519PrivateKey, new: Ed25519PrivateKey, newKem: KemPublicKey): Rotation {
            if (Ed25519.equalPublic(old.publicKey, new.publicKey)) throw CryptoException.Signature("rotation")
            val m = Bytes.concat(old.publicKey, new.publicKey, newKem.bytes())
            return Rotation(old.publicKey, new.publicKey, newKem, old.sign(Labels.ROTATE, m), new.sign(Labels.ROTATE, m))
        }

        /** Parses strictly; does not verify. */
        fun parse(o: JsonObject): Rotation {
            o.uint("v", 1, 1)
            Suite.check(o.uint("suite", 0, 255).toInt(), 0)
            return Rotation(
                o.base64("old_ik", Suite.ED25519_PUBLIC_SIZE),
                o.base64("new_ik", Suite.ED25519_PUBLIC_SIZE),
                KemPublicKey.parse(o.base64("new_kem", Suite.EK_SIZE)),
                o.base64("sig_old", Suite.ED25519_SIGNATURE_SIZE),
                o.base64("sig_new", Suite.ED25519_SIGNATURE_SIZE),
            )
        }

        /**
         * Follows a chain from a stored identity key (§3.4, §6.6): each link's
         * old_ik equals the current key and both signatures verify; at most 32
         * links. Returns the final ik and the KEM key of the last link (null for
         * an empty chain, which resolves to [start]).
         */
        fun resolveChain(start: ByteArray, chain: List<Rotation>): Pair<ByteArray, KemPublicKey?> {
            if (start.size != Suite.ED25519_PUBLIC_SIZE || chain.size > HsLimits.MAX_ROTATIONS) {
                throw CryptoException.Signature("rotation chain")
            }
            var cur = start
            var kem: KemPublicKey? = null
            for (r in chain) {
                r.verify()
                if (!Ed25519.equalPublic(r.oldIk, cur)) throw CryptoException.Signature("rotation chain")
                cur = r.newIk
                kem = r.newKem
            }
            return cur.copyOf() to kem
        }
    }
}
