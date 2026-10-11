package com.vettid.core.altchan

import com.vettid.core.crypto.altchan.DeviceAssertion
import com.vettid.core.crypto.altchan.DeviceAttest
import com.vettid.core.keystore.DeviceAttestationKey

/**
 * The app's hardware device-attestation key (§11.7): attests a fresh key
 * bound to a request's challenge (enrollment, pairing, transfer, recovery),
 * then signs unlock challenges and release approvals with it
 * (SHA256withECDSA, DER).
 */
interface Attester {
    /** Generates a fresh attested key for [challenge] (32 bytes) and returns its chain. */
    fun attest(challenge: ByteArray): DeviceAttest

    /** Signs [message] (an unlock challenge's 32 bytes, or an approval string's bytes). */
    fun assert(message: ByteArray): DeviceAssertion

    /**
     * The certificate chain of the device key as it is now (leaf first, DER), or null when there is no key or
     * the chain cannot be read (VAULT-MESSAGING 0.27.0 §11.7.1): the same certificates [attest] returned for
     * that key, which Android Keystore keeps with it. An unlock re-sends it while the vault has not recorded
     * this app's signer. Never throws.
     */
    fun chain(): List<ByteArray>? = null
}

/**
 * The production attester: an Android Keystore key in StrongBox (or the
 * TEE), from `:core:keystore`. The enclave verifies its chain up to
 * Google's pinned roots, the app's package and signing digest, and the boot
 * state (§11.7; GrapheneOS: SelfSigned with a pinned boot key).
 */
class KeystoreAttester(private val key: DeviceAttestationKey = DeviceAttestationKey()) : Attester {
    override fun attest(challenge: ByteArray): DeviceAttest = DeviceAttest.android(key.generate(challenge).chain.map { it.encoded })

    override fun assert(message: ByteArray): DeviceAssertion = DeviceAssertion.android(key.sign(message))

    @Suppress("TooGenericExceptionCaught") // a Keystore that cannot give the chain: the unlock goes without it
    override fun chain(): List<ByteArray>? = try {
        if (key.exists()) key.chain().map { it.encoded }.takeIf { it.isNotEmpty() } else null
    } catch (_: Exception) {
        null
    }
}
