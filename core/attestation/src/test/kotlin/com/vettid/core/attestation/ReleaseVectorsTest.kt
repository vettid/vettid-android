package com.vettid.core.attestation

import com.vettid.core.attestation.manifest.ManifestKey
import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.attestation.manifest.ReleaseStatus
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * VAULT-MESSAGING §11.10 vectors (release.json, from vettid-vault): the
 * manifest signature and key id, the 0.10.0 manifest-by-hash, and the
 * release-approval signature, checked as vettid-vault's verify_test.go does;
 * the deterministic (RFC 6979) ECDSA signatures are also reproduced byte for
 * byte from the test scalars.
 */
class ReleaseVectorsTest {
    private val d = vector("release.json")
    private val key = ManifestKey(Base64s.decodeStd(d.string("manifest_key_spki_b64")))
    private val verifier = ManifestVerifier(listOf(key))

    private fun rfc6979(scalarHex: String, msg: ByteArray): Pair<BigInteger, BigInteger> {
        val x9 = ECNamedCurveTable.getByName("P-256")
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(BigInteger(1, Bytes.unhex(scalarHex)), ECDomainParameters(x9)))
        val sig = signer.generateSignature(Bytes.sha256(msg))
        return sig[0] to sig[1]
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val t = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        return ByteArray(32 - t.size) + t
    }

    @Test
    fun manifestSignature() {
        val mb = d.string("manifest").toByteArray()
        assertEquals(1060, mb.size)
        assertEquals(1060L, d.uint("manifest_len", 0, 1L shl 20))
        assertEquals(d.string("manifest_sha256_hex"), Bytes.hex(Bytes.sha256(mb)))
        assertEquals(d.string("manifest_signed_digest_hex"), Bytes.hex(ReleaseManifest.signedDigest(mb)))
        assertEquals(d.string("manifest_key_id"), key.keyId)
        val m = verifier.verify(d.string("served").toByteArray())
        assertEquals(7L, m.serial)
        assertEquals(2, m.releases.size)
        assertEquals(ReleaseStatus.DEPRECATED, m.releases[0].status)
        assertEquals(ReleaseStatus.ACTIVE, m.releases[1].status)
        assertEquals(4L, m.newest()!!.number)
        assertEquals(d.string("manifest_sha256_hex"), m.sha256Hex)
        // RFC 6979 with the test scalar reproduces the vector's r || s.
        val (r, s) = rfc6979(d.string("manifest_key_scalar_hex"), Bytes.concat("vettid/pcr-manifest/1".toByteArray(), byteArrayOf(0), mb))
        assertArrayEquals(Base64s.decodeStd(d.string("manifest_sig_b64")), fixed32(r) + fixed32(s))
    }

    @Test
    fun manifest0100ByHash() {
        val n = d.obj("manifest_0_10_0")
        val nb = n.string("manifest").toByteArray()
        assertEquals(n.uint("manifest_len", 0, 1L shl 20).toInt(), nb.size)
        assertEquals(n.string("manifest_sha256_hex"), Bytes.hex(Bytes.sha256(nb)))
        assertEquals("manifests/" + n.string("manifest_sha256_hex") + ".json", n.string("object_key"))
        assertEquals(n.string("manifest_signed_digest_hex"), Bytes.hex(ReleaseManifest.signedDigest(nb)))
        val m = verifier.verifyByHash(n.string("served").toByteArray(), n.string("manifest_sha256_hex"), 8)
        assertEquals(3, m.releases.size)
        assertEquals(ReleaseStatus.REMOVED, m.releases[0].status)
        assertNotNull(m.releases[0].endsAt)
        assertEquals(ReleaseStatus.RETIRED, m.releases[1].status)
        assertNull(m.releases[2].endsAt)
        val order = "\"status\":\"removed\",\"published_at\":\"2025-09-01T00:00:00Z\",\"ends_at\":\"2026-09-01T00:00:00Z\",\"notes\""
        assertTrue(n.string("manifest").contains(order))
        val (r, s) = rfc6979(d.string("manifest_key_scalar_hex"), Bytes.concat("vettid/pcr-manifest/1".toByteArray(), byteArrayOf(0), nb))
        assertArrayEquals(Base64s.decodeStd(n.string("manifest_sig_b64")), fixed32(r) + fixed32(s))
        // The wrong hash or serial is refused.
        val served = n.string("served").toByteArray()
        val hash = n.string("manifest_sha256_hex")
        assertThrows(AttestationException.Hash::class.java) { verifier.verifyByHash(served, d.string("manifest_sha256_hex"), 8) }
        assertThrows(AttestationException.Hash::class.java) { verifier.verifyByHash(served, hash, 7) }
    }

    @Test
    fun approvalSignature() {
        val a = d.obj("approval")
        val msg = a.string("signing_string").toByteArray()
        assertEquals(a.string("signing_string_sha256_hex"), Bytes.hex(Bytes.sha256(msg)))
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64s.decodeStd(a.string("device_key_spki_b64"))))
        val der = Base64s.decodeStd(a.string("sig_der_b64"))
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(msg); verify(der) })
        // RFC 6979 with the test device scalar reproduces the DER signature.
        val (r, s) = rfc6979(a.string("device_key_scalar_hex"), msg)
        val v = ASN1EncodableVector().apply { add(ASN1Integer(r)); add(ASN1Integer(s)) }
        assertArrayEquals(der, DERSequence(v).encoded)
    }
}
