package com.vettid.core.attestation

import com.vettid.core.attestation.nitro.CborEncoder
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Date

fun ecKeyPair(curve: String = "secp384r1"): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

/** A test PKI shaped like AWS Nitro's: root → intermediate → leaf, all P-384. Test only. */
class TestCa(val notBefore: Instant = Instant.parse("2026-01-01T00:00:00Z"), val life: Duration = Duration.ofDays(3650)) {
    val rootPair = ecKeyPair()
    val interPair = ecKeyPair()
    var leafPair = ecKeyPair()
    val root = cert("CN=test.nitro-root", rootPair, "CN=test.nitro-root", rootPair.public, ca = true)
    val inter = cert("CN=test.nitro-root", rootPair, "CN=test.nitro-inter", interPair.public, ca = true)
    var leaf = cert("CN=test.nitro-inter", interPair, "CN=test.nitro-leaf", leafPair.public, ca = false)

    private var serial = 1L

    fun cert(
        issuer: String,
        issuerPair: KeyPair,
        subject: String,
        pub: PublicKey,
        ca: Boolean,
        lifetime: Duration = life,
    ): X509Certificate {
        val b = JcaX509v3CertificateBuilder(
            X500Name(issuer),
            BigInteger.valueOf(serial++),
            Date.from(notBefore),
            Date.from(notBefore.plus(lifetime)),
            X500Name(subject),
            pub,
        )
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        b.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature))
        val signer = JcaContentSignerBuilder("SHA384withECDSA").build(issuerPair.private)
        return JcaX509CertificateConverter().getCertificate(b.build(signer))
    }
}

/** Builds COSE_Sign1 attestation documents like the NSM does. Test only. */
class FakeNsm(
    val ca: TestCa = TestCa(),
    val pcr0: String = "ab".repeat(48),
    val pcr1: String = "11".repeat(48),
    val pcr2: String = "22".repeat(48),
) {
    fun attest(
        at: Instant,
        userData: ByteArray? = null,
        nonce: ByteArray? = null,
        publicKey: ByteArray? = null,
        alg: Long = -35,
        signer: KeyPair = ca.leafPair,
    ): ByteArray {
        val pcrs = listOf(pcr0, pcr1, pcr2, "00".repeat(48))
        val p = CborEncoder().map(9)
            .text("module_id").text("i-test-enc0123")
            .text("digest").text("SHA384")
            .text("timestamp").uint(at.toEpochMilli())
            .text("pcrs").map(pcrs.size)
        pcrs.forEachIndexed { i, h -> p.uint(i.toLong()).bytes(hex(h)) }
        p.text("certificate").bytes(ca.leaf.encoded)
            .text("cabundle").array(2).bytes(ca.root.encoded).bytes(ca.inter.encoded)
        fun opt(k: String, v: ByteArray?) = if (v == null) p.text(k).nul() else p.text(k).bytes(v)
        opt("public_key", publicKey)
        opt("user_data", userData)
        opt("nonce", nonce)
        val payload = p.bytes()
        val prot = CborEncoder().map(1).int(1).int(alg).bytes()
        val sigStructure = CborEncoder().array(4).text("Signature1").bytes(prot).bytes(ByteArray(0)).bytes(payload).bytes()
        val der = Signature.getInstance("SHA384withECDSA").run {
            initSign(signer.private)
            update(sigStructure)
            sign()
        }
        return CborEncoder().tag(18).array(4).bytes(prot).map(0).bytes(payload).bytes(derToRaw(der, 48)).bytes()
    }

    companion object {
        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        fun derToRaw(der: ByteArray, n: Int): ByteArray {
            val seq = ASN1Sequence.getInstance(der)
            fun fixed(i: Int): ByteArray {
                val v = ASN1Integer.getInstance(seq.getObjectAt(i)).positiveValue.toByteArray()
                val t = if (v.size > n) v.copyOfRange(v.size - n, v.size) else v
                return ByteArray(n - t.size) + t
            }
            return fixed(0) + fixed(1)
        }
    }
}

/** A vector file from :core:crypto's test resources (copied from vettid-vault, see vectors/SOURCE.md). */
fun vector(name: String): JsonObject {
    val s = FakeNsm::class.java.getResourceAsStream("/vectors/$name") ?: error("missing vector $name (classpath)")
    return StrictJson.parseObject(s.use { it.readBytes() })
}
