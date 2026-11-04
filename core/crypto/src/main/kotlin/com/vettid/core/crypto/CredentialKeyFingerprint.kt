package com.vettid.core.crypto

/**
 * The fingerprint of a member's credential key (VAULT-MESSAGING 0.83.8 §10.8, vector in §16): what Settings → Vault status
 * shows, and what the other admins of a service may ask the member to read out. Not a secret.
 *
 * `ck_fp = SHA-256("vettid/vms/2/ck-fp" || key)`, `key` the 32 raw bytes of the credential key (an Ed25519 public key),
 * shown as its first 8 bytes in lowercase hex, in 4 groups of 4 digits separated by spaces.
 */
object CredentialKeyFingerprint {
    /** The credential key's size (an Ed25519 public key). */
    const val KEY_SIZE = 32

    private const val SHOWN_BYTES = 8
    private const val GROUP = 4

    /** The full SHA-256 fingerprint of [key] (32 raw bytes). */
    fun digest(key: ByteArray): ByteArray {
        if (key.size != KEY_SIZE) throw CryptoException.Format("key")
        return Bytes.labeledHash(Labels.CK_FP, key)
    }

    /** The fingerprint as shown: `e939 754d 09aa a93a`. */
    fun format(key: ByteArray): String = Bytes.hex(digest(key).copyOf(SHOWN_BYTES)).chunked(GROUP).joinToString(" ")

    /** [format] of a standard-base64 `key`; null when it is not the base64 of 32 bytes. */
    fun formatB64(keyB64: String): String? = try {
        format(Base64s.decodeStd(keyB64, KEY_SIZE))
    } catch (_: CryptoException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
