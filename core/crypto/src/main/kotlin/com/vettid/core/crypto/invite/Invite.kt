package com.vettid.core.crypto.invite

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Labels
import com.vettid.core.crypto.Randomness
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.aead.XChaCha20Poly1305
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.session.Principal
import com.vettid.core.crypto.session.Relay
import java.time.Instant

/** QR `t` kinds (§6.4): connection, app transfer, desktop, agent. */
enum class InviteKind(val qr: String, val bundle: String) {
    CONNECTION("c", "connection"),
    APP("p", "app"),
    DESKTOP("d", "desktop"),
    AGENT("a", "agent"),
    ;

    companion object {
        fun ofQr(t: String): InviteKind = entries.firstOrNull { it.qr == t } ?: throw CryptoException.Format("qr kind")
    }
}

/**
 * The QR / link payload (§6.4), compact JSON with members in spec order;
 * `h` and `k` and the link itself are unpadded base64url.
 */
class InviteQr(val kind: InviteKind, val relay: String, val claimId: String, hash: ByteArray, key: ByteArray, val exp: Long) {
    private val hash = hash.copyOf()
    private val key = key.copyOf()

    fun hash(): ByteArray = hash.copyOf()

    fun key(): ByteArray = key.copyOf()

    private fun validate() {
        Relay.validateUrl(relay)
        if (!validClaimId(claimId) || hash.size != 32 || key.size != Suite.KEY_SIZE || exp <= 0) throw CryptoException.Format("qr")
    }

    fun marshal(): ByteArray {
        validate()
        return JsonBuilder()
            .uint("v", VERSION)
            .string("t", kind.qr)
            .string("r", relay)
            .string("c", claimId)
            .string("h", Base64s.encodeRawUrl(hash))
            .string("k", Base64s.encodeRawUrl(key))
            .uint("e", exp)
            .bytes()
    }

    fun link(): String = Base64s.encodeRawUrl(marshal())

    companion object {
        const val VERSION = 2L
        const val CLAIM_ID_LEN = 26
        private const val MAX_LINK = 1024

        private fun validClaimId(s: String) = s.length == CLAIM_ID_LEN && s.all { it in 'a'..'z' || it in '2'..'7' }

        fun parse(b: ByteArray): InviteQr {
            val o = StrictJson.parseObject(b)
            o.uint("v", VERSION, VERSION)
            val q = InviteQr(
                kind = InviteKind.ofQr(o.string("t")),
                relay = o.string("r"),
                claimId = o.string("c"),
                hash = Base64s.decodeRawUrl(o.string("h"), 32),
                key = Base64s.decodeRawUrl(o.string("k"), Suite.KEY_SIZE),
                exp = o.uint("e", 1, StrictJson.MAX_SAFE_INTEGER),
            )
            q.validate()
            return q
        }

        fun parseLink(s: String): InviteQr {
            val b = Base64s.decodeRawUrl(s)
            if (b.size > MAX_LINK) throw CryptoException.Format("qr")
            return parse(b)
        }
    }
}

/** The claim bundle (§6.4). */
class InviteBundle(
    val kind: InviteKind,
    val inviteId: String,
    val remote: Boolean,
    val vault: Principal,
    val token: String,
    /** Whole seconds. */
    val exp: Instant,
    val hintName: String? = null,
    /**
     * (0.34.0, release R) A transfer bundle's (`app`) release capabilities (§6.4, §11.10.9), bound by the QR's `h`; null
     * when absent (a release before R). No other kind carries it.
     */
    val capabilities: List<String>? = null,
    /**
     * (0.43.0, STAGING-TIME-SCALE) A transfer bundle's release time scale (§11.10.9), bound by the QR's `h`; null when
     * absent (a release before the scale: 1). No other kind carries it.
     */
    val timeScale: Long? = null,
) {
    /** The capabilities and the time scale of a transfer bundle's release (absent members: none, and 1). */
    val traits: com.vettid.core.crypto.altchan.ReleaseTraits
        get() = com.vettid.core.crypto.altchan.ReleaseTraits(
            capabilities.orEmpty(),
            timeScale ?: com.vettid.core.crypto.altchan.TimeScale.REAL,
        )

    /** (0.43.0) `time_scale`: a transfer bundle's only, an integer from 1 to [com.vettid.core.crypto.altchan.TimeScale.MAX]. */
    private fun validateTimeScale() {
        val s = timeScale ?: return
        if (kind != InviteKind.APP || s !in com.vettid.core.crypto.altchan.TimeScale.REAL..com.vettid.core.crypto.altchan.TimeScale.MAX) {
            throw CryptoException.Format("bundle time_scale")
        }
    }

    private fun validate() {
        if (remote && kind != InviteKind.CONNECTION) throw CryptoException.Format("bundle remote")
        if (capabilities != null && kind != InviteKind.APP) throw CryptoException.Format("bundle capabilities")
        validateTimeScale()
        if (!Ulid.isValid(inviteId) || !Relay.isValidToken(token) || exp.nano != 0) throw CryptoException.Format("bundle")
        if (hintName != null && hintName.toByteArray().size > MAX_HINT_NAME) throw CryptoException.Format("bundle hint")
        if (vault.ik().size != Suite.ED25519_PUBLIC_SIZE) throw CryptoException.Format("bundle")
        vault.relay.validate()
    }

    fun marshal(): ByteArray {
        validate()
        val b = JsonBuilder()
            .uint("v", VERSION)
            .uint("suite", Suite.SUITE_2.toLong())
            .string("kind", kind.bundle)
            .string("invite_id", inviteId)
            .bool("remote", remote)
            .raw("vault", vault.marshal())
            .string("token", token)
            .string("exp", Timestamps.formatSeconds(exp))
        capabilities?.let { com.vettid.core.crypto.altchan.Capabilities.write(b, it) }
        timeScale?.let { com.vettid.core.crypto.altchan.TimeScale.write(b, it) }
        if (!hintName.isNullOrEmpty()) b.raw("hint", JsonBuilder().string("name", hintName).build())
        return b.bytes()
    }

    companion object {
        const val VERSION = 1L
        const val MAX_HINT_NAME = 128

        fun parse(raw: ByteArray): InviteBundle {
            val o = StrictJson.parseObject(raw)
            o.uint("v", VERSION, VERSION)
            Suite.check(o.uint("suite", 0, 255).toInt(), 0)
            val kindWire = o.string("kind")
            val b = InviteBundle(
                kind = InviteKind.entries.firstOrNull { it.bundle == kindWire } ?: throw CryptoException.Format("bundle kind"),
                inviteId = o.string("invite_id"),
                remote = o.bool("remote"),
                vault = Principal.parse(o.obj("vault")),
                token = o.string("token"),
                exp = Timestamps.parseSeconds(o.string("exp")),
                hintName = o.optObj("hint")?.optString("name"),
                capabilities = if (o.has(com.vettid.core.crypto.altchan.Capabilities.MEMBER)) {
                    com.vettid.core.crypto.altchan.Capabilities.parse(o)
                } else {
                    null
                },
                timeScale = if (o.has(com.vettid.core.crypto.altchan.TimeScale.MEMBER)) {
                    com.vettid.core.crypto.altchan.TimeScale.parse(o)
                } else {
                    null
                },
            )
            b.validate()
            return b
        }

        /**
         * Encrypts bundle JSON under a fresh k_b:
         * `blob = nonce(24) || XChaCha20-Poly1305(k_b, nonce, "vettid/vms/2/bundle", json)`.
         * Returns (blob, k_b, SHA-256(blob)).
         */
        fun seal(json: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
            val kb = Randomness.bytes(Suite.KEY_SIZE)
            val nonce = XChaCha20Poly1305.newNonce()
            val blob = Bytes.concat(nonce, XChaCha20Poly1305.seal(kb, nonce, Labels.BUNDLE.toByteArray(), json))
            return Triple(blob, kb, Bytes.sha256(blob))
        }

        /**
         * Verifies a fetched claim blob against the QR: SHA-256(blob) = `h` in
         * constant time before anything is decrypted; decrypts under `k`; the
         * bundle's kind matches `t`, its exp equals `e`, and it has not expired.
         */
        fun open(blob: ByteArray, q: InviteQr, now: Instant): InviteBundle {
            if (!Bytes.constantTimeEquals(Bytes.sha256(blob), q.hash())) throw CryptoException.Protocol("bundle commitment")
            if (blob.size < Suite.X_NONCE_SIZE + Suite.TAG_SIZE) throw CryptoException.Decrypt()
            val pt = XChaCha20Poly1305.open(
                q.key(),
                blob.copyOf(Suite.X_NONCE_SIZE),
                Labels.BUNDLE.toByteArray(),
                blob.copyOfRange(Suite.X_NONCE_SIZE, blob.size),
            )
            val b = parse(pt)
            if (b.kind != q.kind) throw CryptoException.Protocol("bundle kind")
            if (b.exp.epochSecond != q.exp) throw CryptoException.Format("bundle exp")
            if (!now.isBefore(b.exp)) throw CryptoException.Time("invite expired")
            return b
        }
    }
}
