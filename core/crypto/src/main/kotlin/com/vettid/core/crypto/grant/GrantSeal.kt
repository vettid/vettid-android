package com.vettid.core.crypto.grant

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.hpke.HpkeRecipient
import com.vettid.core.crypto.hpke.HpkeSender
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.hpke.KemPublicKey

/**
 * A granted item's content sealed to the fetching device's one-time reply key (VAULT-MESSAGING §10.12, the
 * reference's `vms/sharewire`): `value_sealed = enc (1,120) || Seal(aad = "", content)` with
 * `SetupBaseS(reply_key, info = "vettid/vms/2/grant" || 0x00 || grant_id || 0x00 || fetch_id)`.
 */
object GrantSeal {
    const val LABEL = "vettid/vms/2/grant"
    const val MAX_VALUE = 65_536

    private fun info(grantId: String, fetchId: String) = "$LABEL\u0000$grantId\u0000$fetchId".toByteArray()

    fun openValue(reply: KemPrivateKey, grantId: String, fetchId: String, sealed: ByteArray): ByteArray {
        if (sealed.size < Suite.ENC_SIZE + Suite.TAG_SIZE || sealed.size > Suite.ENC_SIZE + MAX_VALUE + Suite.TAG_SIZE) {
            throw CryptoException.Decrypt()
        }
        val r = HpkeRecipient.setup(sealed.copyOf(Suite.ENC_SIZE), reply, info(grantId, fetchId))
        return r.open(ByteArray(0), sealed.copyOfRange(Suite.ENC_SIZE, sealed.size))
    }

    /** The granting vault's side of [openValue] (tests and tooling). */
    fun sealValue(reply: KemPublicKey, grantId: String, fetchId: String, value: ByteArray): ByteArray {
        if (value.size > MAX_VALUE) throw CryptoException.Format("value too large")
        val s = HpkeSender.setup(reply, info(grantId, fetchId))
        return Bytes.concat(s.enc(), s.seal(ByteArray(0), value))
    }
}
