package com.vettid.core.attestation.manifest

/**
 * The manifest public keys pinned in the app (§11.10.1: apps and images MAY
 * pin two keys to allow rotation; `key_id` selects one).
 *
 * VettID's production keys (VAULT-RELEASES §6.1, O3), the same two that the
 * production enclave images pin (vettid-vault enclave/releasecfg/prod.json
 * `manifest_keys`) and vettid.org's `manifestKeys`:
 *  - key A: AWS KMS ECC_NIST_P256 in vettid-vault-prod, signs every manifest;
 *  - key B: an offline YubiKey (PIV, P-256, PIN and touch on every use),
 *    the standby if key A is ever lost.
 * Changing either is an app release; ManifestKeysTest pins their key ids.
 *
 * Never pin the §16 test key (private scalar 32 x 0x21 is public) in a
 * release build. Tests and the local dev stack pass their keys explicitly.
 */
object ManifestKeys {
    /** Key A (SPKI DER, base64); key_id 4353463f85c4012f. */
    const val KEY_A_SPKI_B64: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7xDU6CSVsFDJvP7UXigsN9SDB+KIppU85Y3DRvo0oMQZYKHZ1S/3OaNdFk2/HJX+hohYdyU6QIFPXiDBirflCQ=="

    /** Key B, the offline standby (SPKI DER, base64); key_id 1abd49da96970b6e. */
    const val KEY_B_SPKI_B64: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIcIodW3liYaxACuOdB0If8igq/oWfVOcLGbWTy1vUNzTlvsVNe3ljfqpqfTCZwtR611EYk7UPTCfjaEEMPLxfQ=="

    /** The pinned keys of production builds. */
    val PRODUCTION: List<ManifestKey> by lazy { listOf(KEY_A_SPKI_B64, KEY_B_SPKI_B64).map { ManifestKey.fromBase64(it) } }
}
