package com.vettid.core.crypto.session

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Kid
import com.vettid.core.crypto.Labels
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.kdf.Hkdf

/**
 * The §6.3 key schedule of one handshake:
 *
 * ```
 * th1      = SHA-256("vettid/vms/2/th1" || env_init)
 * th       = SHA-256("vettid/vms/2/th"  || env_init || resp_header)
 * prk      = HKDF-Extract(salt = "vettid/vms/2/session", ikm = K_e || K_s)
 * k_i2r    = HKDF-Expand(prk, "vettid/vms/2/i2r" || th, 32)      … and r2i, kids, rk, epoch_id
 * sas      = uint32be(HKDF(ikm = K_s, salt = "vettid/vms/2/sas", info = th1, 4)) mod 1e6
 * ```
 *
 * Secret; [destroy] wipes it.
 */
class Schedule private constructor(
    val th1: ByteArray,
    val th: ByteArray,
    internal val prk: ByteArray,
    internal val kI2R: ByteArray,
    internal val kR2I: ByteArray,
    val kidI2R: Kid,
    val kidR2I: Kid,
    internal val rk: ByteArray,
    val epochId: ByteArray,
) {
    fun destroy() = Bytes.wipe(prk, kI2R, kR2I, rk)

    override fun toString(): String = "Schedule[redacted]"

    companion object {
        /** Runs §6.3 from K_s, K_e and the transcript hashes. */
        fun derive(ks: ByteArray, ke: ByteArray, th1: ByteArray, th: ByteArray): Schedule {
            if (ks.size != Suite.KEY_SIZE || ke.size != Suite.KEY_SIZE) throw CryptoException.Key("schedule key size")
            val ikm = Bytes.concat(ke, ks)
            val prk = try {
                Hkdf.extract(Labels.SESSION.toByteArray(), ikm)
            } finally {
                Bytes.wipe(ikm)
            }
            fun expand(label: String, n: Int) = Hkdf.expand(prk, Bytes.concat(label.toByteArray(), th), n)
            return Schedule(
                th1 = th1.copyOf(),
                th = th.copyOf(),
                prk = prk,
                kI2R = expand(Labels.I2R, Suite.KEY_SIZE),
                kR2I = expand(Labels.R2I, Suite.KEY_SIZE),
                kidI2R = Kid(expand(Labels.KID_I2R, Suite.KID_SIZE)),
                kidR2I = Kid(expand(Labels.KID_R2I, Suite.KID_SIZE)),
                rk = expand(Labels.RK, Suite.KEY_SIZE),
                epochId = expand(Labels.EPOCH, Suite.EPOCH_ID_SIZE),
            )
        }

        fun th1(envInit: ByteArray): ByteArray = Bytes.labeledHash(Labels.TH1, envInit)

        /** [respHeader] is hs.resp bytes[0:1140]. */
        fun th(envInit: ByteArray, respHeader: ByteArray): ByteArray = Bytes.labeledHash(Labels.TH, envInit, respHeader)

        /** The short authentication string: 6 zero-padded digits (§6.3). */
        fun sas(ks: ByteArray, th1: ByteArray): String {
            if (ks.size != Suite.KEY_SIZE) throw CryptoException.Key("sas key size")
            val b = Hkdf.derive(ks, Labels.SAS.toByteArray(), th1, 4)
            val v = Bytes.readUintBE(b, 0, 4) % 1_000_000L
            return v.toString().padStart(6, '0')
        }

        fun signResp(ikR: Ed25519PrivateKey, th: ByteArray): ByteArray = ikR.sign(Labels.SIG_RESP, th)

        fun signFin(ikI: Ed25519PrivateKey, th: ByteArray): ByteArray = ikI.sign(Labels.SIG_FIN, th)

        fun verifyResp(ikR: ByteArray, th: ByteArray, sig: ByteArray): Boolean = Ed25519.verify(ikR, Labels.SIG_RESP, th, sig)

        fun verifyFin(ikI: ByteArray, th: ByteArray, sig: ByteArray): Boolean = Ed25519.verify(ikI, Labels.SIG_FIN, th, sig)
    }
}
