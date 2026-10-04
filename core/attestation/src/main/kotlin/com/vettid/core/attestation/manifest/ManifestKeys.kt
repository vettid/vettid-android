package com.vettid.core.attestation.manifest

/**
 * The manifest public keys pinned in the app (§11.10.1: apps and images MAY
 * pin two keys to allow rotation; `key_id` selects one).
 *
 * PLACEHOLDERS: VettID's production manifest key pair (A) and its rotation
 * key (B) are created in vettid-vault phase W3 / operations task O3, in a
 * hardware key store. Until then both slots are empty, so [PRODUCTION] pins
 * nothing and every manifest fails closed with an unknown key id: the app
 * cannot be talked into trusting a release before the real keys exist.
 * Filling a slot is an app release: paste the SubjectPublicKeyInfo (DER,
 * standard base64) of the key, and the `key_id` it must produce, and extend
 * ManifestKeysTest.
 *
 * Never pin the §16 test key (private scalar 32 x 0x21 is public) in a
 * release build. Tests and the local dev stack pass their keys explicitly.
 */
object ManifestKeys {
    /** Key A (SPKI DER, base64). Placeholder until W3/O3. */
    val KEY_A_SPKI_B64: String? = null

    /** Key B, the rotation key (SPKI DER, base64). Placeholder until W3/O3. */
    val KEY_B_SPKI_B64: String? = null

    /** The pinned keys of this build (empty until W3/O3). */
    val PRODUCTION: List<ManifestKey> by lazy { listOfNotNull(KEY_A_SPKI_B64, KEY_B_SPKI_B64).map { ManifestKey.fromBase64(it) } }
}
