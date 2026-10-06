package com.vettid.core.crypto.altchan

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Kid
import com.vettid.core.crypto.Labels
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.envelope.Envelope
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.envelope.Padding
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.hpke.KemPublicKey
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.session.RelayAddr
import java.time.Instant

/**
 * The pure crypto and wire helpers of the alternate channel (§11) that the
 * §16 vectors pin: attestation user_data, the device-attestation challenge,
 * the unlock and release-approval signing strings, and sealed requests.
 */
object AltChannel {
    const val TYPE_ENROLL = "vault.enroll"
    const val TYPE_UNLOCK = "vault.unlock"

    /** Every sealed result: a 4,096-byte padded inner in a sealed envelope (5,252 bytes). */
    const val RESULT_ENVELOPE_SIZE = Envelope.OVERHEAD_SEALED + Padding.ALT_CHANNEL

    /** A sealed enroll or unlock request (13,444 bytes). */
    const val REQUEST_ENVELOPE_SIZE = Envelope.OVERHEAD_SEALED + Padding.ALT_CHANNEL_REQUEST

    /** user_data of an ETK descriptor's attestation: SHA-256("vettid/vms/2/etk" || descriptor) (§11.2). */
    fun etkUserData(descriptor: ByteArray): ByteArray = Bytes.labeledHash(Labels.ETK, descriptor)

    /** user_data of vault.enrolled's attestation: SHA-256("vettid/vms/2/vault" || vault_bundle) (§11.3). */
    fun vaultUserData(vaultBundle: ByteArray): ByteArray = Bytes.labeledHash(Labels.VAULT, vaultBundle)

    /**
     * The device-attestation challenge (§11.7):
     * SHA-256("vettid/vms/2/devatt" || request_id || vault_id_or_empty || ts).
     */
    fun devattChallenge(requestId: String, vaultId: String, ts: String): ByteArray {
        if (!Ulid.isValid(requestId) || !validVaultId(vaultId, allowEmpty = true)) throw CryptoException.Format("devatt field")
        Timestamps.parseMillis(ts)
        return Bytes.labeledHash(Labels.DEVATT, requestId.toByteArray(), vaultId.toByteArray(), ts.toByteArray())
    }

    /** 6–32 ASCII digits (VAULT-MESSAGING 0.10.1 §11.3). */
    fun isValidPin(p: String): Boolean = p.length in 6..32 && p.all { it in '0'..'9' }

    internal fun validVaultId(s: String, allowEmpty: Boolean): Boolean =
        if (s.isEmpty()) allowEmpty else s.length <= 128 && s.all { it.code in 0x21..0x7e }

    /** Inputs of the unlock signing string (§11.4). */
    class UnlockFields(
        val userGuid: String,
        val vaultId: String,
        val requestId: String,
        val ts: String,
        val etkKid: Kid,
        val minStateSeq: Long,
        val minHeaderSeq: Long,
        val pin: String,
        val token: String,
        /** hex(SHA-256(manifest bytes)): the request's manifest_sha256. */
        val manifestSha256: String,
        /** The release_update target, "" without one. */
        val toPcr0: String = "",
        val cancelRecovery: Boolean = false,
    )

    /**
     * The string `sig` covers in vault.unlock (§11.4): 12 lines (13 with
     * cancel_recovery) joined by "\n", no trailing newline, decimal integers,
     * lowercase hex.
     */
    @Suppress("CyclomaticComplexMethod")
    fun unlockSigningString(f: UnlockFields): String {
        if (!validVaultId(f.userGuid, false) || !validVaultId(f.vaultId, false) || !Ulid.isValid(f.requestId)) {
            throw CryptoException.Format("unlock field")
        }
        Timestamps.parseMillis(f.ts)
        if (f.pin.isEmpty() || f.token.isEmpty() || (f.pin + f.token).any { it == '\r' || it == '\n' }) {
            throw CryptoException.Format("unlock field")
        }
        if (!Bytes.isLowerHex(f.manifestSha256, 64)) throw CryptoException.Format("unlock field")
        if (f.toPcr0.isNotEmpty() && !Bytes.isLowerHex(f.toPcr0, 96)) throw CryptoException.Format("unlock field")
        val lines = mutableListOf(
            Labels.UNLOCK,
            f.userGuid,
            f.vaultId,
            f.requestId,
            f.ts,
            f.etkKid.toString(),
            f.minStateSeq.toString(),
            f.minHeaderSeq.toString(),
            Bytes.hex(Bytes.sha256(f.pin.toByteArray())),
            Bytes.hex(Bytes.sha256(f.token.toByteArray())),
            f.manifestSha256,
            f.toPcr0,
        )
        if (f.cancelRecovery) lines.add("cancel_recovery")
        return lines.joinToString("\n")
    }

    /**
     * The release-approval signing string (§11.10.3):
     * label \n vault_id \n request_id \n from_pcr0 \n to_pcr0 \n to_release \n manifest_serial.
     */
    fun approvalSigningString(vaultId: String, requestId: String, fromPcr0: String, toPcr0: String, toRelease: Long, serial: Long): String {
        if (!validVaultId(vaultId, false) || !Ulid.isValid(requestId) || !Bytes.isLowerHex(fromPcr0, 96) ||
            !Bytes.isLowerHex(toPcr0, 96) || toRelease <= 0 || serial <= 0
        ) {
            throw CryptoException.Format("approval field")
        }
        return listOf(Labels.RELEASE_APPROVAL, vaultId, requestId, fromPcr0, toPcr0, toRelease.toString(), serial.toString())
            .joinToString("\n")
    }

    /**
     * Seals an enroll or unlock request to the ETK (§11.3, §11.4): inner id =
     * request_id, sender_kid all-zero, padded to exactly 12,288 bytes.
     */
    fun sealRequest(etk: KemPublicKey, type: String, requestId: String, ts: Instant, body: ByteArray): ByteArray {
        val json = Inner(id = requestId, type = type, ts = ts, body = body).marshal(Mode.SEALED)
        val padded = Padding.padFixed(json, Padding.ALT_CHANNEL_REQUEST)
        try {
            return Envelope.sealSealed(etk, Kid.ANONYMOUS, padded).first
        } finally {
            Bytes.wipe(padded, json)
        }
    }
}

/** The `vault.enroll` body (§11.3), members in spec order. */
class EnrollRequest(
    val userGuid: String,
    val requestId: String,
    nonce: ByteArray,
    val pin: String,
    ik: ByteArray,
    val kem: KemPublicKey,
    val relay: RelayAddr,
    val openToken: String,
    val name: String,
    val attest: DeviceAttest,
    val manifestSha256: String,
    val manifestSerial: Long,
    /** The app key (§11.3, §11.12, 0.15.0): SPKI DER of a P-256 key; REQUIRED since 0.15.0, null only for older vectors. */
    apiKey: ByteArray? = null,
) {
    private val nonce = nonce.copyOf()
    private val ik = ik.copyOf()
    private val apiKey = apiKey?.copyOf()

    fun marshal(): ByteArray {
        if (nonce.size != 32 || !AltChannel.isValidPin(pin) || !Bytes.isLowerHex(manifestSha256, 64) || manifestSerial < 1) {
            throw CryptoException.Format("enroll request")
        }
        val app = JsonBuilder()
            .base64("ik", ik)
            .base64("kem", kem.bytes())
            .raw("relay", JsonBuilder().string("url", relay.url).string("mailbox", relay.mailbox).base64("pk", relay.pk()).build())
            .string("open_token", openToken)
            .string("name", name)
            .raw("device_attest", attest.marshal())
            .also { b -> apiKey?.let { b.base64("api_key", it) } }
            .build()
        return JsonBuilder()
            .string("user_guid", userGuid)
            .string("request_id", requestId)
            .base64("nonce", nonce)
            .string("pin", pin)
            .raw("app", app)
            .string("manifest_sha256", manifestSha256)
            .uint("manifest_serial", manifestSerial)
            .bytes()
    }
}

/** The `vault.unlock` body (§11.4), members in spec order. */
class UnlockRequest(
    val userGuid: String,
    val vaultId: String,
    val requestId: String,
    deviceIk: ByteArray,
    val pin: String,
    val minStateSeq: Long,
    val minHeaderSeq: Long,
    val token: String,
    val assertion: DeviceAssertion,
    val manifestSha256: String,
    val manifestSerial: Long,
    val update: ReleaseUpdate? = null,
    val cancelRecovery: Boolean = false,
    sig: ByteArray,
) {
    private val deviceIk = deviceIk.copyOf()
    private val sig = sig.copyOf()

    /** `release_update` (§11.10.3). */
    class ReleaseUpdate(val to: String, val toRelease: Long, val approval: DeviceAssertion)

    fun marshal(): ByteArray {
        if (!AltChannel.isValidPin(pin) || !Bytes.isLowerHex(manifestSha256, 64) || manifestSerial < 1 ||
            sig.size != Suite.ED25519_SIGNATURE_SIZE
        ) {
            throw CryptoException.Format("unlock request")
        }
        val b = JsonBuilder()
            .string("user_guid", userGuid)
            .string("vault_id", vaultId)
            .string("request_id", requestId)
            .base64("device_ik", deviceIk)
            .string("pin", pin)
            .uint("min_state_seq", minStateSeq)
            .uint("min_header_seq", minHeaderSeq)
            .string("token", token)
            .raw("device_assertion", assertion.marshal())
            .string("manifest_sha256", manifestSha256)
            .uint("manifest_serial", manifestSerial)
        update?.let {
            b.raw(
                "release_update",
                JsonBuilder().string("to", it.to).uint("to_release", it.toRelease).raw("approval", it.approval.marshal()).build(),
            )
        }
        if (cancelRecovery) b.bool("cancel_recovery", true)
        return b.base64("sig", sig).bytes()
    }
}

/** The ETK descriptor (§11.2), parsed strictly from the exact served bytes. */
class Descriptor private constructor(
    val instanceId: String,
    val kid: Kid,
    val etk: KemPublicKey,
    /** PCR0 (96 lowercase hex). */
    val release: String,
    val notAfter: Instant,
    private val raw: ByteArray,
) {
    fun bytes(): ByteArray = raw.copyOf()

    companion object {
        private const val MAX = 4096

        fun isValidInstanceId(s: String): Boolean =
            s.length in 1..48 && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

        /** A non-debug lowercase-hex SHA-384 PCR. */
        fun isValidPcr(s: String): Boolean = Bytes.isLowerHex(s, 96) && s.any { it != '0' }

        fun parse(b: ByteArray): Descriptor {
            if (b.isEmpty() || b.size > MAX) throw CryptoException.Format("descriptor")
            val o = StrictJson.parseObject(b)
            o.uint("v", 1, 1)
            o.uint("suite", 2, 2)
            val instanceId = o.string("instance_id")
            if (!isValidInstanceId(instanceId)) throw CryptoException.Format("descriptor instance_id")
            val kidHex = o.string("kid")
            val kid = Kid.parseHex(kidHex)
            val etk = KemPublicKey.parse(o.base64("etk", Suite.EK_SIZE))
            if (etk.kid != kid) throw CryptoException.Format("descriptor kid")
            val release = o.string("release")
            if (!isValidPcr(release)) throw CryptoException.Format("descriptor release")
            val notAfter = Timestamps.parseSeconds(o.string("not_after"))
            return Descriptor(instanceId, kid, etk, release, notAfter, b.copyOf())
        }

        /** Writes a descriptor in §11.2 member order (tests and tooling). */
        fun marshal(instanceId: String, etk: KemPublicKey, release: String, notAfter: Instant): ByteArray = JsonBuilder()
            .uint("v", 1)
            .uint("suite", Suite.SUITE_2.toLong())
            .string("instance_id", instanceId)
            .string("kid", etk.kid.toString())
            .string("etk", Base64s.encodeStd(etk.bytes()))
            .string("release", release)
            .string("not_after", Timestamps.formatSeconds(notAfter))
            .bytes()
    }
}
