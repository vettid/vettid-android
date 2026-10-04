package com.vettid.core.attestation.android

import com.vettid.core.attestation.AttestationException
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.altchan.DeviceAttest
import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.ASN1TaggedObject
import java.security.cert.X509Certificate

/** Security levels of the Android key attestation schema. */
enum class SecurityLevel(val value: Int) {
    SOFTWARE(0),
    TRUSTED_ENVIRONMENT(1),
    STRONG_BOX(2),
    ;

    companion object {
        fun of(v: Int) = entries.firstOrNull { it.value == v } ?: throw AttestationException.Format("security level")
    }
}

/** RootOfTrust (hardware-enforced tag 704). */
class RootOfTrust(verifiedBootKey: ByteArray, val deviceLocked: Boolean, val verifiedBootState: Int) {
    private val bootKey = verifiedBootKey.copyOf()

    fun verifiedBootKey(): ByteArray = bootKey.copyOf()

    companion object {
        const val VERIFIED = 0
        const val SELF_SIGNED = 1
        const val UNVERIFIED = 2
        const val FAILED = 3
    }
}

/**
 * The Android key attestation extension (OID 1.3.6.1.4.1.11129.2.1.17,
 * KeyDescription) of the device attestation key's leaf certificate. The
 * enclave verifies the chain and these fields (VAULT-MESSAGING §11.7); the
 * app parses them only to check, before enrolling, that the key it is about
 * to present is hardware-backed and carries the request's challenge, and to
 * show attestation details in Settings.
 */
class KeyDescription private constructor(
    val attestationVersion: Int,
    val attestationSecurityLevel: SecurityLevel,
    val keyMintSecurityLevel: SecurityLevel,
    challenge: ByteArray,
    /** Hardware-enforced purposes (KeyMint KeyPurpose: 2 = SIGN, 3 = VERIFY). */
    val purposes: Set<Int>,
    /** Hardware-enforced algorithm (3 = EC). */
    val algorithm: Int?,
    /** Hardware-enforced EC curve (1 = P-256). */
    val ecCurve: Int?,
    /** Hardware-enforced origin (0 = GENERATED). */
    val origin: Int?,
    val rootOfTrust: RootOfTrust?,
) {
    private val challenge = challenge.copyOf()

    fun challenge(): ByteArray = challenge.copyOf()

    /**
     * The §11.7 properties the enclave requires, checked locally: version ≥ 3,
     * TEE or StrongBox for both levels, the expected challenge, an EC P-256
     * signing-only key generated in hardware, and a locked device with a
     * verified (or self-signed, for GrapheneOS) boot. Returns the failed
     * checks (empty when everything holds).
     */
    @Suppress("CyclomaticComplexMethod") // one line per §11.7 requirement
    fun problems(expectedChallenge: ByteArray): List<String> = buildList {
        if (attestationVersion < MIN_ATTESTATION_VERSION) add("attestation version")
        if (attestationSecurityLevel == SecurityLevel.SOFTWARE) add("attestation security level")
        if (keyMintSecurityLevel == SecurityLevel.SOFTWARE) add("key security level")
        if (!Bytes.constantTimeEquals(challenge, expectedChallenge)) add("challenge")
        if (SIGN !in purposes || purposes.any { it != SIGN && it != VERIFY }) add("purposes")
        if (algorithm != ALGORITHM_EC || ecCurve != CURVE_P256) add("key type")
        if (origin != ORIGIN_GENERATED) add("origin")
        val rot = rootOfTrust
        if (rot == null || !rot.deviceLocked) add("device locked")
        if (rot != null && rot.verifiedBootState != RootOfTrust.VERIFIED && rot.verifiedBootState != RootOfTrust.SELF_SIGNED) {
            add("verified boot")
        }
    }

    companion object {
        const val OID = "1.3.6.1.4.1.11129.2.1.17"
        const val MIN_ATTESTATION_VERSION = 3
        const val SIGN = 2
        const val VERIFY = 3
        const val ALGORITHM_EC = 3
        const val CURVE_P256 = 1
        const val ORIGIN_GENERATED = 0
        private const val TAG_PURPOSE = 1
        private const val TAG_ALGORITHM = 2
        private const val TAG_EC_CURVE = 10
        private const val TAG_ORIGIN = 702
        private const val TAG_ROOT_OF_TRUST = 704

        fun parse(leaf: X509Certificate): KeyDescription {
            val ext = leaf.getExtensionValue(OID) ?: throw AttestationException.Format("no key attestation extension")
            return parse(ASN1OctetString.getInstance(ext).octets)
        }

        /** Parses the extension's content (the DER KeyDescription). */
        @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
        fun parse(der: ByteArray): KeyDescription {
            try {
                val seq = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der))
                if (seq.size() < 8) throw AttestationException.Format("KeyDescription")
                val hw = authList(seq.getObjectAt(7))
                val rot = hw[TAG_ROOT_OF_TRUST]?.let { r ->
                    val s = ASN1Sequence.getInstance(r)
                    RootOfTrust(
                        ASN1OctetString.getInstance(s.getObjectAt(0)).octets,
                        ASN1Boolean.getInstance(s.getObjectAt(1)).isTrue,
                        ASN1Enumerated.getInstance(s.getObjectAt(2)).value.toInt(),
                    )
                }
                return KeyDescription(
                    attestationVersion = ASN1Integer.getInstance(seq.getObjectAt(0)).value.toInt(),
                    attestationSecurityLevel = SecurityLevel.of(ASN1Enumerated.getInstance(seq.getObjectAt(1)).value.toInt()),
                    keyMintSecurityLevel = SecurityLevel.of(ASN1Enumerated.getInstance(seq.getObjectAt(3)).value.toInt()),
                    challenge = ASN1OctetString.getInstance(seq.getObjectAt(4)).octets,
                    purposes = hw[TAG_PURPOSE]?.let { p -> ASN1Set.getInstance(p).map { intOf(it) }.toSet() } ?: emptySet(),
                    algorithm = hw[TAG_ALGORITHM]?.let { ASN1Integer.getInstance(it).value.toInt() },
                    ecCurve = hw[TAG_EC_CURVE]?.let { ASN1Integer.getInstance(it).value.toInt() },
                    origin = hw[TAG_ORIGIN]?.let { ASN1Integer.getInstance(it).value.toInt() },
                    rootOfTrust = rot,
                )
            } catch (e: AttestationException) {
                throw e
            } catch (_: Exception) {
                // BC's ASN.1 layer signals malformed input with several unchecked types.
                throw AttestationException.Format("KeyDescription")
            }
        }

        private fun intOf(e: ASN1Encodable): Int = ASN1Integer.getInstance(e).value.toInt()

        private fun authList(e: ASN1Encodable): Map<Int, ASN1Encodable> =
            ASN1Sequence.getInstance(e).associate { t ->
                val tagged = ASN1TaggedObject.getInstance(t)
                tagged.tagNo to tagged.explicitBaseObject
            }

        /** The `device_attest` member for a chain from the Keystore (leaf first, §11.7). */
        fun deviceAttest(chain: List<X509Certificate>): DeviceAttest = DeviceAttest.android(chain.map { it.encoded })
    }
}
