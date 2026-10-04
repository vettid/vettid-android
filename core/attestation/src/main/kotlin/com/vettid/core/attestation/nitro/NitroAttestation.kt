package com.vettid.core.attestation.nitro

import com.vettid.core.attestation.AttestationException
import com.vettid.core.crypto.Bytes
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Date

/** PCR0–PCR2 as lowercase hex (§11.2 step 2). */
data class Measurements(val pcr0: String, val pcr1: String, val pcr2: String) {
    /** Any of PCR0–2 all zero: a debug-mode enclave. */
    val isDebug: Boolean get() = listOf(pcr0, pcr1, pcr2).any { p -> p.all { it == '0' } }

    /** Constant-time comparison. */
    fun matches(o: Measurements): Boolean =
        Bytes.constantTimeEquals((pcr0 + pcr1 + pcr2).toByteArray(), (o.pcr0 + o.pcr1 + o.pcr2).toByteArray())

    /** The three PCRs joined, as the app stores an enrollment's expected measurements. */
    fun joined(): String = pcr0 + pcr1 + pcr2
}

/** A verified Nitro attestation document. */
class NitroDocument internal constructor(
    val moduleId: String,
    val timestamp: Instant,
    val pcrs: Map<Int, ByteArray>,
    val publicKey: ByteArray?,
    val userData: ByteArray?,
    val nonce: ByteArray?,
) {
    fun pcrHex(i: Int): String = pcrs[i]?.let { Bytes.hex(it) } ?: ""

    val measurements: Measurements get() = Measurements(pcrHex(0), pcrHex(1), pcrHex(2))

    /** At most [maxAge] old and not more than [skew] in the future. */
    fun checkFresh(now: Instant, maxAge: Duration, skew: Duration) {
        if (timestamp.isAfter(now.plus(skew)) || Duration.between(timestamp, now) > maxAge) throw AttestationException.Stale()
    }

    fun checkUserData(want: ByteArray) {
        if (!Bytes.constantTimeEquals(userData ?: ByteArray(0), want)) throw AttestationException.UserData()
    }

    fun checkNonce(want: ByteArray) {
        if (!Bytes.constantTimeEquals(nonce ?: ByteArray(0), want)) throw AttestationException.Nonce()
    }
}

/**
 * Verifies AWS Nitro Enclaves attestation documents (VAULT-MESSAGING §11.2,
 * §11.3): a COSE_Sign1 (RFC 9052) signed with ES384 by a certificate that
 * chains to the pinned AWS Nitro root, evaluated at the document's own
 * timestamp (signing certificates are short-lived; the caller bounds the
 * document's age with [NitroDocument.checkFresh]).
 *
 * Ported from the v1 app's `NitroAttestationVerifier` and aligned with
 * vettid-vault `vms/nitro`: the root is pinned as the trust anchor (not by
 * its CN), the protected header must name ES384, the payload's PCR0–2 must
 * be SHA-384 sized, and the CBOR is parsed strictly.
 */
class NitroVerifier(private val roots: List<X509Certificate> = listOf(NitroRoot.certificate)) {
    @Suppress("CyclomaticComplexMethod")
    fun verify(doc: ByteArray): NitroDocument {
        if (roots.isEmpty()) throw AttestationException.Chain()
        if (doc.isEmpty() || doc.size > MAX_DOCUMENT) throw AttestationException.Format("attestation size")
        val v = CborValue.decode(doc).untag(COSE_SIGN1_TAG)
        val arr = (v as? CborValue.Array)?.items ?: throw AttestationException.Format("COSE_Sign1")
        if (arr.size != 4) throw AttestationException.Format("COSE_Sign1")
        val prot = arr[0] as? CborValue.Bytes ?: throw AttestationException.Format("protected header")
        if (arr[1] !is CborValue.Map) throw AttestationException.Format("unprotected header")
        val payload = arr[2] as? CborValue.Bytes ?: throw AttestationException.Format("payload")
        val sig = arr[3] as? CborValue.Bytes ?: throw AttestationException.Format("signature")
        if (sig.value.size != ES384_SIG_SIZE) throw AttestationException.Format("signature")
        val ph = CborValue.decode(prot.value) as? CborValue.Map ?: throw AttestationException.Format("protected header")
        val alg = ph.lookupInt(COSE_HEADER_ALG)
        if (ph.entries.size != 1 || alg != CborValue.NInt(-1 - COSE_ALG_ES384)) throw AttestationException.Format("algorithm")

        val (d, leaf, bundle) = parsePayload(payload.value)
        verifyChain(leaf, bundle, d.timestamp)
        val pub = leaf.publicKey as? ECPublicKey ?: throw AttestationException.Chain()
        if (pub.params.curve.field.fieldSize != P384_BITS) throw AttestationException.Chain()

        // Sig_structure = ["Signature1", protected, external_aad = h'', payload]
        val sigStructure = CborEncoder().array(4).text("Signature1").bytes(prot.value).bytes(ByteArray(0)).bytes(payload.value).bytes()
        val ok = try {
            Signature.getInstance("SHA384withECDSA").run {
                initVerify(pub)
                update(sigStructure)
                verify(rawToDer(sig.value))
            }
        } catch (_: GeneralSecurityException) {
            false
        }
        if (!ok) throw AttestationException.Signature("attestation")
        return d
    }

    private fun verifyChain(leaf: X509Certificate, bundle: List<X509Certificate>, at: Instant) {
        try {
            val anchors = roots.map { TrustAnchor(it, null) }.toSet()
            val rootDers = roots.map { it.encoded }
            // The cabundle is ordered root first; the path runs leaf first, without the anchor.
            val path = listOf(leaf) + bundle.reversed().filter { c -> rootDers.none { it.contentEquals(c.encoded) } }
            val params = PKIXParameters(anchors).apply {
                isRevocationEnabled = false // Nitro certificates carry no CRL/OCSP
                date = Date.from(at)
            }
            CertPathValidator.getInstance("PKIX").validate(CertificateFactory.getInstance("X.509").generateCertPath(path), params)
        } catch (_: GeneralSecurityException) {
            throw AttestationException.Chain()
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun parsePayload(b: ByteArray): Triple<NitroDocument, X509Certificate, List<X509Certificate>> {
        val m = CborValue.decode(b) as? CborValue.Map ?: throw AttestationException.Format("payload")
        val moduleId = m.textAt("module_id")
        if (moduleId.isEmpty()) throw AttestationException.Format("module_id")
        if (m.textAt("digest") != "SHA384") throw AttestationException.Format("digest")
        val ts = m.uintAt("timestamp")
        if (ts == 0L || ts > (1L shl 53)) throw AttestationException.Format("timestamp")
        val pcrMap = m.lookup("pcrs") as? CborValue.Map ?: throw AttestationException.Format("pcrs")
        if (pcrMap.entries.isEmpty() || pcrMap.entries.size > 32) throw AttestationException.Format("pcrs")
        val pcrs = HashMap<Int, ByteArray>()
        for ((k, v) in pcrMap.entries) {
            val idx = (k as? CborValue.UInt)?.value ?: throw AttestationException.Format("pcr index")
            val value = (v as? CborValue.Bytes)?.value ?: throw AttestationException.Format("pcr value")
            if (idx > 31 || value.size !in setOf(PCR_SIZE, 32, 64)) throw AttestationException.Format("pcr")
            pcrs[idx.toInt()] = value
        }
        for (i in 0..2) if (pcrs[i]?.size != PCR_SIZE) throw AttestationException.Format("pcr$i")
        val leaf = cert(m.bytesAt("certificate"))
        val bun = m.lookup("cabundle") as? CborValue.Array ?: throw AttestationException.Format("cabundle")
        if (bun.items.isEmpty() || bun.items.size > MAX_CA_BUNDLE) throw AttestationException.Format("cabundle")
        val bundle = bun.items.map { cert((it as? CborValue.Bytes)?.value ?: throw AttestationException.Format("cabundle")) }
        val d = NitroDocument(
            moduleId = moduleId,
            timestamp = Instant.ofEpochMilli(ts),
            pcrs = pcrs,
            publicKey = optBytes(m, "public_key", MAX_PUBLIC_KEY),
            userData = optBytes(m, "user_data", MAX_FIELD),
            nonce = optBytes(m, "nonce", MAX_FIELD),
        )
        return Triple(d, leaf, bundle)
    }

    private fun optBytes(m: CborValue.Map, key: String, max: Int): ByteArray? = when (val e = m.lookup(key)) {
        null, CborValue.Null -> null
        is CborValue.Bytes -> if (e.value.size > max) throw AttestationException.Format(key) else e.value
        else -> throw AttestationException.Format(key)
    }

    private fun cert(der: ByteArray): X509Certificate = try {
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    } catch (_: CertificateException) {
        throw AttestationException.Format("certificate")
    }

    companion object {
        const val MAX_DOCUMENT = 32 * 1024
        const val PCR_SIZE = 48
        private const val MAX_CA_BUNDLE = 8
        private const val MAX_FIELD = 1024
        private const val MAX_PUBLIC_KEY = 2048
        private const val COSE_SIGN1_TAG = 18L
        private const val COSE_HEADER_ALG = 1L
        private const val COSE_ALG_ES384 = -35L
        private const val ES384_SIG_SIZE = 96
        private const val P384_BITS = 384

        /** SHA-256 of a certificate's DER, lowercase hex (for pin checks). */
        fun fingerprint(c: X509Certificate): String = Bytes.hex(MessageDigest.getInstance("SHA-256").digest(c.encoded))

        /** IEEE P1363 r || s to DER (for JCA verifiers). */
        fun rawToDer(raw: ByteArray): ByteArray {
            val half = raw.size / 2
            fun int(x: ByteArray): ByteArray {
                var i = 0
                while (i < x.size - 1 && x[i].toInt() == 0) i++
                val t = x.copyOfRange(i, x.size)
                return if ((t[0].toInt() and 0x80) != 0) byteArrayOf(0) + t else t
            }
            val r = int(raw.copyOfRange(0, half))
            val s = int(raw.copyOfRange(half, raw.size))
            val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
            val len = if (body.size < 128) byteArrayOf(body.size.toByte()) else byteArrayOf(0x81.toByte(), body.size.toByte())
            return byteArrayOf(0x30) + len + body
        }
    }
}
