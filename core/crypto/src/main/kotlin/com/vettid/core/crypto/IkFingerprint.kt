package com.vettid.core.crypto

/**
 * The fingerprint of a vault's identity key (VAULT-MESSAGING 0.18.0 §10.8, vector in §16): what a connection's
 * details show for its pinned `ik`, identifying the peer's vault (the one the SAS was compared with), not a person.
 *
 * `fp = SHA-256("vettid/vms/2/ik-fp" || ik)`, `ik` the 32 raw bytes, shown as its first 16 bytes in lowercase
 * hex, in 8 groups of 4 digits separated by spaces.
 */
object IkFingerprint {
    /** The identity key's size (an Ed25519 public key). */
    const val IK_SIZE = 32

    private const val SHOWN_BYTES = 16
    private const val GROUP = 4

    /** The full SHA-256 fingerprint of [ik] (32 raw bytes). */
    fun digest(ik: ByteArray): ByteArray {
        if (ik.size != IK_SIZE) throw CryptoException.Format("ik")
        return Bytes.labeledHash(Labels.IK_FP, ik)
    }

    /** The fingerprint as shown: `9a1f bb7d 873e eafb 494b ef94 f072 7b25`. */
    fun format(ik: ByteArray): String = Bytes.hex(digest(ik).copyOf(SHOWN_BYTES)).chunked(GROUP).joinToString(" ")

    /** [format] of a standard-base64 `ik`; null when it is not the base64 of 32 bytes. */
    fun formatB64(ikB64: String): String? = try {
        format(Base64s.decodeStd(ikB64, IK_SIZE))
    } catch (_: CryptoException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
