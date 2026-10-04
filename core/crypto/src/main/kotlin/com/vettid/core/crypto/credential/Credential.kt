package com.vettid.core.crypto.credential

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Kid
import com.vettid.core.crypto.Labels
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.hpke.HpkeRecipient
import com.vettid.core.crypto.hpke.HpkeSender
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.hpke.KemPublicKey
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonObject
import java.time.Instant

/**
 * A one-time transaction key as the vault issues it (§3.5.4):
 * `{"utk_id": "<16 lowercase hex>", "ek": "<b64 1,216 bytes>", "expires_at": "<ts>"}`.
 */
class Utk(val id: String, val ek: KemPublicKey, val expiresAt: Instant) {
    companion object {
        fun parse(o: JsonObject): Utk {
            val id = o.string("utk_id")
            if (!Bytes.isLowerHex(id, 16)) throw CryptoException.Format("utk_id")
            return Utk(id, KemPublicKey.parse(o.base64("ek", Suite.EK_SIZE)), Timestamps.parseMillis(o.string("expires_at")))
        }
    }
}

/**
 * The Protean Credential's wire crypto on the app side (§3.5.4): critical
 * payloads sealed to a single-use vault key (UTK) inside the session, and
 * critical values the vault seals to a one-time reply key. One HPKE context
 * per message.
 */
object CredentialSeal {
    const val MAX_PAYLOAD = 16 * 1024
    const val MAX_VALUE = 64 * 1024

    private fun utkInfo(vaultId: String, utkId: String) = "${Labels.UTK}\u0000$vaultId\u0000$utkId".toByteArray()

    private fun utkAad(type: String, innerId: String) = "$type\u0000$innerId".toByteArray()

    private fun replyInfo(vaultId: String, innerId: String) = "${Labels.REPLY}\u0000$vaultId\u0000$innerId".toByteArray()

    /**
     * `sealed = enc (1,120) || ct`, with
     * `SetupBaseS(ek_UTK, info = "vettid/vms/2/utk" || 0 || vault_id || 0 || utk_id)` and
     * `ct = Seal(aad = type || 0 || inner id, pt = payload JSON)`. The payload
     * is bound to the request type and id, so it cannot be moved.
     */
    fun sealPayload(utk: Utk, vaultId: String, type: String, innerId: String, payload: ByteArray): ByteArray {
        if (payload.size > MAX_PAYLOAD) throw CryptoException.Format("utk payload too large")
        val s = HpkeSender.setup(utk.ek, utkInfo(vaultId, utk.id))
        return Bytes.concat(s.enc(), s.seal(utkAad(type, innerId), payload))
    }

    /** The vault side of [sealPayload] (tests and tooling). */
    fun openPayload(ltk: KemPrivateKey, vaultId: String, utkId: String, type: String, innerId: String, sealed: ByteArray): ByteArray {
        if (sealed.size < Suite.ENC_SIZE + Suite.TAG_SIZE || sealed.size > Suite.ENC_SIZE + MAX_PAYLOAD + Suite.TAG_SIZE) {
            throw CryptoException.Decrypt()
        }
        val r = HpkeRecipient.setup(sealed.copyOf(Suite.ENC_SIZE), ltk, utkInfo(vaultId, utkId))
        return r.open(utkAad(type, innerId), sealed.copyOfRange(Suite.ENC_SIZE, sealed.size))
    }

    /**
     * Opens a reply-sealed value with the request's one-time key:
     * `SetupBaseR(enc, reply, info = "vettid/vms/2/reply" || 0 || vault_id || 0 || inner id)`, aad empty.
     */
    fun openValue(reply: KemPrivateKey, vaultId: String, innerId: String, sealed: ByteArray): ByteArray {
        if (sealed.size < Suite.ENC_SIZE + Suite.TAG_SIZE || sealed.size > Suite.ENC_SIZE + MAX_VALUE + Suite.TAG_SIZE) {
            throw CryptoException.Decrypt()
        }
        val r = HpkeRecipient.setup(sealed.copyOf(Suite.ENC_SIZE), reply, replyInfo(vaultId, innerId))
        return r.open(ByteArray(0), sealed.copyOfRange(Suite.ENC_SIZE, sealed.size))
    }

    /** The vault side of [openValue] (tests and tooling). */
    fun sealValue(reply: KemPublicKey, vaultId: String, innerId: String, value: ByteArray): ByteArray {
        if (value.size > MAX_VALUE) throw CryptoException.Format("value too large")
        val s = HpkeSender.setup(reply, replyInfo(vaultId, innerId))
        return Bytes.concat(s.enc(), s.seal(ByteArray(0), value))
    }
}

/**
 * The header of a credential blob (§3.5.2):
 * `blob = 0x01 || version (8, BE) || kid(CEK) (8) || enc (1,120) || ct`.
 * The app stores the blob as opaque bytes; it reads the header to know which
 * version it holds.
 */
class CredentialBlobHeader(val version: Long, val cekKid: Kid) {
    companion object {
        const val FORMAT = 0x01
        const val HEADER_SIZE = 17

        fun parse(blob: ByteArray): CredentialBlobHeader {
            if (blob.size < HEADER_SIZE + Suite.ENC_SIZE + Suite.TAG_SIZE || blob[0].toInt() != FORMAT) {
                throw CryptoException.Format("credential blob")
            }
            val version = Bytes.readUintBE(blob, 1, 8)
            if (version < 1) throw CryptoException.Format("credential blob version")
            return CredentialBlobHeader(version, Kid(blob.copyOfRange(9, HEADER_SIZE)))
        }
    }
}

/**
 * A credential-key rotation statement (§3.5.5), delivered to connections that
 * pinned the member's key:
 * `{"v":1,"old_key","new_key","sig_old","sig_new"}`, m = old_key || new_key,
 * each signature Ed25519(key, "vettid/vms/2/credential-rotate" || m).
 */
class CredentialKeyRotation(oldKey: ByteArray, newKey: ByteArray, sigOld: ByteArray, sigNew: ByteArray) {
    private val oldKey = oldKey.copyOf()
    private val newKey = newKey.copyOf()
    private val sigOld = sigOld.copyOf()
    private val sigNew = sigNew.copyOf()

    fun oldKey(): ByteArray = oldKey.copyOf()

    fun newKey(): ByteArray = newKey.copyOf()

    fun verify() {
        if (Ed25519.equalPublic(oldKey, newKey)) throw CryptoException.Signature("credential rotation")
        val m = Bytes.concat(oldKey, newKey)
        if (!Ed25519.verify(oldKey, Labels.CREDENTIAL_ROTATE, m, sigOld) || !Ed25519.verify(newKey, Labels.CREDENTIAL_ROTATE, m, sigNew)) {
            throw CryptoException.Signature("credential rotation")
        }
    }

    fun marshal(): String = JsonBuilder()
        .uint("v", 1)
        .base64("old_key", oldKey)
        .base64("new_key", newKey)
        .base64("sig_old", sigOld)
        .base64("sig_new", sigNew)
        .build()

    companion object {
        const val MAX_CHAIN = 32

        fun create(old: Ed25519PrivateKey, new: Ed25519PrivateKey): CredentialKeyRotation {
            if (Ed25519.equalPublic(old.publicKey, new.publicKey)) throw CryptoException.Signature("credential rotation")
            val m = Bytes.concat(old.publicKey, new.publicKey)
            val sigOld = old.sign(Labels.CREDENTIAL_ROTATE, m)
            return CredentialKeyRotation(old.publicKey, new.publicKey, sigOld, new.sign(Labels.CREDENTIAL_ROTATE, m))
        }

        fun parse(o: JsonObject): CredentialKeyRotation {
            o.uint("v", 1, 1)
            return CredentialKeyRotation(
                o.base64("old_key", Suite.ED25519_PUBLIC_SIZE),
                o.base64("new_key", Suite.ED25519_PUBLIC_SIZE),
                o.base64("sig_old", Suite.ED25519_SIGNATURE_SIZE),
                o.base64("sig_new", Suite.ED25519_SIGNATURE_SIZE),
            )
        }

        /** Follows a non-empty chain (≤ 32 links) from the pinned key; returns the key it leads to. */
        fun followChain(pinned: ByteArray, chain: List<CredentialKeyRotation>): ByteArray {
            if (pinned.size != Suite.ED25519_PUBLIC_SIZE || chain.isEmpty() || chain.size > MAX_CHAIN) {
                throw CryptoException.Signature("credential chain")
            }
            var cur = pinned
            for (r in chain) {
                if (!Ed25519.equalPublic(r.oldKey, cur)) throw CryptoException.Signature("credential chain")
                r.verify()
                cur = r.newKey
            }
            return cur.copyOf()
        }
    }
}
