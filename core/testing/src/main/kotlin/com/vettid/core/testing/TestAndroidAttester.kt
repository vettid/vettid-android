package com.vettid.core.testing

import com.vettid.core.altchan.Attester
import com.vettid.core.crypto.altchan.DeviceAssertion
import com.vettid.core.crypto.altchan.DeviceAttest
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * TEST ONLY. A Kotlin port of vettid-vault's `internal/enclavetest` Android
 * attester: a software P-256 "device attestation key" whose certificate
 * chain is issued by the TEST Android attestation CA that the dev enclave
 * pins (root: P-384, private scalar 48 × 0x41; intermediate: P-256, 32 ×
 * 0x42; both public, fixed seeds). The leaf carries a key description of a
 * StrongBox P-256 signing key of `com.vettid.app` with the test signing
 * digest, on a locked device with verified boot.
 *
 * The dev enclave's device policy pins only this test CA, the release
 * package name and the test signing digest, so a real phone's attestation
 * (Google roots, `com.vettid.app.dev`, the debug key, GrapheneOS
 * `SelfSigned`) cannot pass it; instrumented tests against the dev stack
 * use this attester instead (devstack/README.md, "Device attestation").
 * It lives in test source sets only and never ships in an APK.
 */
class TestAndroidAttester : Attester {
    private val key: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    override fun attest(challenge: ByteArray): DeviceAttest = DeviceAttest.android(TestAndroidCa.attest(key.public, challenge))

    override fun assert(message: ByteArray): DeviceAssertion = DeviceAssertion.android(
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(message)
            sign()
        },
    )
}

/** TEST ONLY: the dev enclave's Android attestation CA (vettid-vault enclavetest.TestAndroidCA). */
object TestAndroidCa {
    const val PACKAGE = "com.vettid.app"
    val SIGNER: ByteArray = MessageDigest.getInstance("SHA-256").digest("VettID TEST ONLY app signing certificate".toByteArray())
    private val OID_KEY_DESCRIPTION = ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")
    private val EPOCH: Instant = Instant.parse("2020-01-01T00:00:00Z")
    private val serial = AtomicLong(System.currentTimeMillis())

    private fun testKey(curve: String, seed: Int, size: Int): KeyPair {
        val spec = ECNamedCurveTable.getParameterSpec(curve)
        val d = BigInteger(1, ByteArray(size) { seed.toByte() })
        val q = spec.g.multiply(d).normalize()
        val params = java.security.AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(curve)) }
            .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val kf = KeyFactory.getInstance("EC")
        val priv: PrivateKey = kf.generatePrivate(ECPrivateKeySpec(d, params))
        val point = ECPoint(q.affineXCoord.toBigInteger(), q.affineYCoord.toBigInteger())
        val pub: PublicKey = kf.generatePublic(ECPublicKeySpec(point, params))
        return KeyPair(pub, priv)
    }

    private val rootKey = testKey("secp384r1", 0x41, 48)
    private val interKey = testKey("secp256r1", 0x42, 32)
    private val rootName = X500Name("O=VettID TEST ONLY,CN=TEST Android attestation root")
    private val interName = X500Name("O=VettID TEST ONLY,CN=TEST Android attestation intermediate")

    private fun sign(
        b: X509v3CertificateBuilder,
        alg: String,
        k: PrivateKey,
    ): ByteArray = b.build(JcaContentSignerBuilder(alg).build(k)).encoded

    private fun years(n: Long): Date = Date.from(EPOCH.plus(n * 365L, ChronoUnit.DAYS))

    private val root: ByteArray by lazy {
        val b = JcaX509v3CertificateBuilder(rootName, BigInteger.ONE, Date.from(EPOCH), years(40), rootName, rootKey.public)
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        sign(b, "SHA384withECDSA", rootKey.private)
    }

    private val inter: ByteArray by lazy {
        val b = JcaX509v3CertificateBuilder(rootName, BigInteger.valueOf(2), Date.from(EPOCH), years(30), interName, interKey.public)
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign))
        sign(b, "SHA384withECDSA", rootKey.private)
    }

    /** A chain (leaf first) attesting [pub] with [challenge]. */
    fun attest(pub: PublicKey, challenge: ByteArray): List<ByteArray> {
        val b = JcaX509v3CertificateBuilder(
            interName, BigInteger.valueOf(serial.incrementAndGet()), Date.from(EPOCH), Date.from(EPOCH.plus(30 * 365L, ChronoUnit.DAYS)),
            X500Name("CN=Android Keystore Key"), pub,
        )
        b.addExtension(Extension.keyUsage, false, KeyUsage(KeyUsage.digitalSignature))
        b.addExtension(OID_KEY_DESCRIPTION, false, keyDescription(challenge))
        return listOf(sign(b, "SHA256withECDSA", interKey.private), inter, root)
    }

    // --- a minimal DER writer for the attestation extension (enclavetest's dSeq, dExp, ...) ---

    private fun tlv(tag: ByteArray, vararg content: ByteArray): ByteArray {
        val c = ByteArrayOutputStream().apply { content.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            c.size < 0x80 -> out.write(c.size)
            c.size <= 0xff -> {
                out.write(0x81)
                out.write(c.size)
            }
            else -> {
                out.write(0x82)
                out.write(c.size shr 8)
                out.write(c.size and 0xff)
            }
        }
        out.write(c)
        return out.toByteArray()
    }

    private fun seq(vararg c: ByteArray) = tlv(byteArrayOf(0x30), *c)
    private fun set(vararg c: ByteArray) = tlv(byteArrayOf(0x31), *c)
    private fun oct(b: ByteArray) = tlv(byteArrayOf(0x04), b)
    private fun bool(v: Boolean) = tlv(byteArrayOf(0x01), byteArrayOf(if (v) 0xff.toByte() else 0))
    private fun int(v: Long): ByteArray = tlv(byteArrayOf(0x02), BigInteger.valueOf(v).toByteArray())
    private fun enum(v: Long): ByteArray = int(v).also { it[0] = 0x0a }
    private fun nul() = byteArrayOf(0x05, 0x00)

    /** [tag] EXPLICIT, context-specific constructed, high-tag form above 30. */
    private fun exp(tag: Int, inner: ByteArray): ByteArray {
        if (tag < 31) return tlv(byteArrayOf((0xa0 or tag).toByte()), inner)
        val num = ArrayList<Int>()
        var t = tag
        while (t > 0) {
            num.add(0, t and 0x7f)
            t = t shr 7
        }
        for (i in 0 until num.size - 1) num[i] = num[i] or 0x80
        return tlv(byteArrayOf(0xbf.toByte()) + num.map { it.toByte() }.toByteArray(), inner)
    }

    @Suppress("SpreadOperator") // test fixture, a few elements
    private fun keyDescription(challenge: ByteArray): ByteArray {
        val appId = exp(709, oct(seq(set(seq(oct(PACKAGE.toByteArray()), int(1))), set(oct(SIGNER)))))
        val rootOfTrust = seq(oct(ByteArray(32)), bool(true), enum(0), oct(ByteArray(32))) // locked, Verified
        val hw = listOf(
            exp(1, set(int(2))), // purpose SIGN
            exp(2, int(3)), // algorithm EC
            exp(3, int(256)), // key size
            exp(5, set(int(4))), // digest SHA-256
            exp(10, int(1)), // curve P-256
            exp(503, nul()), // noAuthRequired
            exp(702, int(0)), // origin GENERATED
            exp(704, rootOfTrust),
            exp(705, int(140000)), // OS version
            exp(706, int(202609)), // OS patch level
        )
        val sw = listOf(exp(701, int(1790000000000)), appId) // creation time, application id
        return seq(
            int(300), enum(2), int(300), enum(2), // attestation and KeyMint versions and levels: StrongBox
            oct(challenge), oct(ByteArray(0)), seq(*sw.toTypedArray()), seq(*hw.toTypedArray()),
        )
    }
}
