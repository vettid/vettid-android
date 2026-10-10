// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.altchan

import com.vettid.core.attestation.VerifiedEnclave
import com.vettid.core.attestation.manifest.ManifestKey
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.attestation.nitro.Measurements
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Kid
import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.altchan.Descriptor
import com.vettid.core.crypto.altchan.DeviceAssertion
import com.vettid.core.crypto.altchan.DeviceAttest
import com.vettid.core.crypto.envelope.Envelope
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.envelope.Padding
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.hpke.KemPublicKey
import com.vettid.core.crypto.json.JsonBuilder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant

/** Test-only helpers: a signed manifest, an enclave whose ETK we hold, sealed results. */
object TestSupport {
    val pcr0 = "a3".repeat(48)
    val pcr1 = "13".repeat(48)
    val pcr2 = "23".repeat(48)

    val manifestPair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val manifestKey = ManifestKey(manifestPair.public.encoded)

    fun manifestBytes(serial: Long, number: Long = 3, pcr0: String = this.pcr0, status: String = "active"): ByteArray {
        val e = JsonBuilder().uint("release", number).string("pcr0", pcr0).string("pcr1", pcr1).string("pcr2", pcr2)
            .string("seal_key", "arn:aws:kms:us-east-1:111122223333:key/test").string("status", status)
            .string("published_at", "2026-10-01T00:00:00Z").string("notes", "https://vettid.org/releases/$number").build()
        return JsonBuilder().uint("v", 1).uint("serial", serial).string("issued_at", "2026-10-01T00:00:00Z").raw("releases", "[$e]").bytes()
    }

    fun served(m: ByteArray, pair: KeyPair = manifestPair): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(ReleaseManifest.LABEL.toByteArray())
            update(0)
            update(m)
            sign()
        }
        val keyId = ManifestKey(pair.public.encoded).keyId
        return JsonBuilder().string("manifest", Base64s.encodeStd(m)).base64("sig", derToRaw(der)).string("key_id", keyId).bytes()
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        // SEQUENCE { INTEGER r, INTEGER s } with short lengths (P-256).
        var i = 2
        fun int(): ByteArray {
            check(der[i] == 0x02.toByte())
            val n = der[i + 1].toInt()
            val v = der.copyOfRange(i + 2, i + 2 + n).dropWhile { it == 0.toByte() }.toByteArray()
            i += 2 + n
            return ByteArray(32 - v.size) + v
        }
        return int() + int()
    }

    /** An enclave instance whose ETK private key the test holds. */
    class Enclave(val instanceId: String = "inst-a", val number: Long = 3, val pcr0: String = TestSupport.pcr0) {
        val etk: KemPrivateKey = KemPrivateKey.generate()
        val descriptor: ByteArray = Descriptor.marshal(instanceId, etk.publicKey, pcr0, Instant.now().plusSeconds(3600))

        fun verified(m: ReleaseManifest): VerifiedEnclave =
            VerifiedEnclave(Descriptor.parse(descriptor), Measurements(pcr0, pcr1, pcr2), m.byPcr0(pcr0) ?: error("not in manifest"))

        /** Opens a sealed request (12,288 padded) and returns its inner. */
        fun open(env: ByteArray): Inner {
            val e = Envelope.parse(env)
            check(e.senderKid == Kid.ANONYMOUS)
            val (padded, _) = Envelope.openSealed(e, etk)
            return Inner.parse(Padding.unpadFixed(padded, Padding.ALT_CHANNEL_REQUEST), Mode.SEALED)
        }
    }

    /** A sealed 5,252-byte result for [requestId], sealed to [kem]. */
    fun sealResult(kem: KemPublicKey, type: String, requestId: String, body: String, ts: Instant = Instant.now()): ByteArray {
        val inner = Inner(id = com.vettid.core.crypto.envelope.Ulid.new(), type = type, ts = ts, re = requestId, status = Inner.STATUS_OK, body = body.toByteArray())
        val padded = Padding.padFixed(inner.marshal(Mode.SEALED), Padding.ALT_CHANNEL)
        return Envelope.sealSealed(kem, Kid.ANONYMOUS, padded).first.also { check(it.size == AltChannel.RESULT_ENVELOPE_SIZE) }
    }

    /** A software attester (test only): a P-256 key and a one-certificate "chain". */
    class SoftAttester : Attester {
        val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        var lastChallenge: ByteArray? = null

        override fun attest(challenge: ByteArray): DeviceAttest {
            lastChallenge = challenge
            return DeviceAttest.android(listOf(pair.public.encoded))
        }

        override fun assert(message: ByteArray): DeviceAssertion = DeviceAssertion.android(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(message)
                sign()
            },
        )

        fun verify(message: ByteArray, sig: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update(message)
            verify(sig)
        }
    }
}
