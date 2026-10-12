package com.vettid.core.crypto.call

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.hpke.HpkeRecipient
import com.vettid.core.crypto.hpke.HpkeSender
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.hpke.KemPublicKey
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import com.vettid.core.crypto.json.asString
import com.vettid.core.crypto.kdf.Hkdf
import java.time.Instant

/**
 * The wire crypto of call signalling (VAULT-MESSAGING §10.10), the Kotlin counterpart of vettid-vault's
 * `vms/callwire`, checked against its vectors (`calls.json`).
 *
 * The media key never passes through a vault: the calling device makes an ephemeral KEM key and keeps its private
 * half, the answering device encapsulates to it, and the vaults only pass `ek` and `enc` along. Each share is signed
 * by the device that made it and vouched for by that device's vault; the other device checks both signatures under
 * the connection's identity key before it uses the share.
 */
object CallWire {
    const val LABEL_CALL = "vettid/vms/2/call"
    const val LABEL_CALL_KEY = "vettid/vms/2/call-key"
    const val LABEL_ICE = "vettid/vms/2/ice"
    const val LABEL_SHARE = "vettid/vms/2/call-share"
    const val LABEL_VOUCH = "vettid/vms/2/call-vouch"
    const val MEDIA_SALT = "vettid-call-v1"
    const val ROLE_OFFER = "offer"
    const val ROLE_ANSWER = "answer"
    const val MEDIA_AUDIO = "audio"
    const val MEDIA_VIDEO = "video"

    /** `k_call`. */
    const val KEY_SIZE = 32
    const val MAX_ICE_SERVERS = 8
    const val MAX_URLS = 4
    const val MAX_URL = 512
    const val MAX_ICE_FIELD = 512
    const val MAX_ICE_CONFIG = 8192
    const val MAX_CALL_ID = 64
    private const val ICE_CONFIG_FORMAT = 1L
    private const val MAX_EXP = 1L shl 40
    private val schemes = listOf("stun:", "stuns:", "turn:", "turns:")

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun callInfo(callId: String): ByteArray {
        if (callId.isEmpty()) throw CryptoException.Key("call id")
        return (LABEL_CALL + callId).toByteArray(Charsets.UTF_8)
    }

    /** The calling device's ephemeral KEM key: its public half (`ek`, 1,216 bytes) goes into `call.start`. */
    fun newOfferKey(): KemPrivateKey = KemPrivateKey.generate()

    /** `k_call = HKDF-SHA-256(ikm = K, salt = "vettid-call-v1", info = call_id, L = 32)`; [k] is wiped. */
    private fun mediaKey(k: ByteArray, callId: String): ByteArray = try {
        Hkdf.derive(k, ascii(MEDIA_SALT), callId.toByteArray(Charsets.UTF_8), KEY_SIZE)
    } finally {
        Bytes.wipe(k)
    }

    /** What the answering device gets: [enc] for `call.answer` and [kCall], which stays on the device. */
    class Answered(val enc: ByteArray, val kCall: ByteArray)

    /**
     * Run by the answering device: `(enc, ctx) = SetupBaseS(ek, "vettid/vms/2/call" || call_id)`,
     * `K = ctx.Export("vettid/vms/2/call-key", 32)`, and `k_call` from K.
     */
    fun answer(ek: ByteArray, callId: String): Answered {
        if (ek.size != Suite.EK_SIZE) throw CryptoException.Key("call ek size")
        val sender = HpkeSender.setup(KemPublicKey.parse(ek), callInfo(callId))
        return Answered(sender.enc(), mediaKey(sender.export(LABEL_CALL_KEY, KEY_SIZE), callId))
    }

    /** Run by the calling device on the answer's `enc` (1,120 bytes): the same `k_call`. */
    fun accept(offerKey: KemPrivateKey, enc: ByteArray, callId: String): ByteArray {
        if (enc.size != Suite.ENC_SIZE) throw CryptoException.Key("call enc size")
        return mediaKey(HpkeRecipient.setup(enc, offerKey, callInfo(callId)).export(LABEL_CALL_KEY, KEY_SIZE), callId)
    }

    /**
     * The signed bytes of a share: `role || 0x00 || call_id || 0x00 || media || 0x00 || share`. For [ROLE_OFFER] the
     * share is `ek` and [media] is `audio` or `video`; for [ROLE_ANSWER] it is `enc` and [media] is empty.
     */
    fun shareMessage(role: String, callId: String, media: String, share: ByteArray): ByteArray {
        val zero = byteArrayOf(0)
        return Bytes.concat(ascii(role), zero, callId.toByteArray(Charsets.UTF_8), zero, ascii(media), zero, share)
    }

    /** The device's signature over a share (`ek_sig`, `enc_sig`): Ed25519(device ik, "vettid/vms/2/call-share" || m). */
    fun signShare(deviceIk: Ed25519PrivateKey, message: ByteArray): ByteArray = deviceIk.sign(LABEL_SHARE, message)

    /** What the signing device's vault signs: `device_ik (32) || m`. */
    fun vouchMessage(deviceIk: ByteArray, message: ByteArray): ByteArray = Bytes.concat(deviceIk, message)

    /**
     * Checks a vouched share from the other side of a call: the device's signature under [deviceIk] and its vault's
     * under [vaultIk] (the connection's identity key, `peer_ik`). A share that fails MUST NOT be used.
     */
    fun verifyShare(vaultIk: ByteArray, deviceIk: ByteArray, message: ByteArray, deviceSig: ByteArray, vaultSig: ByteArray) {
        if (!Ed25519.verify(deviceIk, LABEL_SHARE, message, deviceSig) ||
            !Ed25519.verify(vaultIk, LABEL_VOUCH, vouchMessage(deviceIk, message), vaultSig)
        ) {
            throw CryptoException.Signature("call share")
        }
    }

    /** One entry of an ICE configuration; [username] and [credential] are both set or both null. */
    data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
        override fun toString(): String = "IceServer(urls=$urls, ${if (credential == null) "no credential" else "[redacted]"})"
    }

    /** The vault-signed ICE configuration of one call. */
    data class IceConfig(val callId: String, val exp: Instant, val servers: List<IceServer>)

    private fun validUrl(u: String): Boolean =
        u.toByteArray(Charsets.UTF_8).size <= MAX_URL && u.all { it.code in 0x21..0x7e } &&
            schemes.any { u.startsWith(it) && u.length > it.length }

    private fun validField(s: String?): Boolean =
        s == null || (s.isNotEmpty() && s.toByteArray(Charsets.UTF_8).size <= MAX_ICE_FIELD && s.none { it.code < 0x20 || it.code == 0x7f })

    /** The configuration's canonical bytes (members in the order shown in §10.10, no whitespace). */
    fun marshalIce(c: IceConfig): ByteArray {
        val bad = c.callId.isEmpty() || c.callId.toByteArray(Charsets.UTF_8).size > MAX_CALL_ID || c.exp.epochSecond !in 1..MAX_EXP ||
            c.servers.size > MAX_ICE_SERVERS ||
            c.servers.any { s ->
                s.urls.isEmpty() || s.urls.size > MAX_URLS || !s.urls.all(::validUrl) || !validField(s.username) ||
                    !validField(s.credential) || (s.username == null) != (s.credential == null)
            }
        if (bad) throw CryptoException.Format("ice config")
        val servers = c.servers.map { s ->
            val b = JsonBuilder().raw("urls", JsonBuilder.array(s.urls.map(JsonBuilder::quote)))
            if (s.username != null && s.credential != null) b.string("username", s.username).string("credential", s.credential)
            b.build()
        }
        return JsonBuilder().uint("v", ICE_CONFIG_FORMAT).string("call_id", c.callId).uint("exp", c.exp.epochSecond)
            .raw("ice_servers", JsonBuilder.array(servers)).bytes()
    }

    /** Parses configuration bytes strictly: they must be in canonical form. */
    fun parseIce(config: ByteArray): IceConfig {
        if (config.size > MAX_ICE_CONFIG) throw CryptoException.Format("ice config size")
        val o = StrictJson.parseObject(config)
        o.uint("v", ICE_CONFIG_FORMAT, ICE_CONFIG_FORMAT)
        val servers = o.array("ice_servers").map { raw ->
            val s = raw.asObject()
            val urls = s.array("urls").map { it.asString() }
            IceServer(urls, s.optString("username")?.ifEmpty { null }, s.optString("credential")?.ifEmpty { null })
        }
        val c = IceConfig(o.string("call_id"), Instant.ofEpochSecond(o.uint("exp", 1, MAX_EXP)), servers)
        if (!marshalIce(c).contentEquals(config)) throw CryptoException.Format("ice config not canonical")
        return c
    }

    /**
     * Run by an owner device on the ICE configuration its vault sent with a call: the signature under the vault's
     * current identity key, the strict format, the call id and the expiry. A device MUST NOT use servers from any
     * other source.
     */
    fun verifyIce(vaultIk: ByteArray, config: ByteArray, sig: ByteArray, callId: String, now: Instant): IceConfig {
        Ed25519.requireValid(vaultIk, LABEL_ICE, config, sig, "ice config")
        val c = parseIce(config)
        if (c.callId != callId) throw CryptoException.Protocol("ice config of another call")
        if (!now.isBefore(c.exp)) throw CryptoException.Time("ice config expired")
        return c
    }
}
