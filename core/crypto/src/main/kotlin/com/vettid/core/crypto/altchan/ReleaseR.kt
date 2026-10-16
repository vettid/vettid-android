package com.vettid.core.crypto.altchan

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Kid
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import java.text.Normalizer
import java.time.Instant

/**
 * The phone-held unlock secret of VAULT-MESSAGING 0.33.0 §11.14.1 (release R):
 *
 * ```
 * unlock_secret = HMAC-SHA-256(phone key, "vettid/vms/2/unlock-secret" || 0x00 || vault_id || 0x00 || epoch (8, big-endian))
 * ```
 *
 * The phone key is a non-exportable Keystore HMAC key, so the HMAC is computed by the phone's hardware
 * ([compute] takes it as a function); the secret is held in memory only while a request is built.
 */
object UnlockSecret {
    const val LABEL = "vettid/vms/2/unlock-secret"
    const val SIZE = 32

    /**
     * The HMAC's message for [vaultId] at [epoch] (1 after enrollment, a transfer or a recovery). A transfer's new
     * phone does not know the vault id when it sends its `hs.init` and uses "" there and at every later unlock, as
     * vettid-vault's reference client does: only the phone ever computes the secret.
     */
    fun message(vaultId: String, epoch: Long): ByteArray {
        if (epoch < 1) throw CryptoException.Format("unlock secret input")
        val e = ByteArray(EPOCH_BYTES) { i -> (epoch ushr (8 * (EPOCH_BYTES - 1 - i))).toByte() }
        return LABEL.toByteArray(Charsets.US_ASCII) + 0 + vaultId.toByteArray(Charsets.UTF_8) + 0 + e
    }

    /** The secret, from the phone key's HMAC [hmac] over [message]. */
    fun compute(vaultId: String, epoch: Long, hmac: (ByteArray) -> ByteArray): ByteArray {
        val out = hmac(message(vaultId, epoch))
        if (out.size != SIZE) throw CryptoException.Key("unlock secret size")
        return out
    }

    private const val EPOCH_BYTES = 8
}

/** Passwords from release R (§11.14.7): one form, NFC, before any use. */
object Passwords {
    const val MAX_BYTES = 1_024

    /** [password] in Unicode NFC, as the enclave normalizes it. */
    fun nfc(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFC)

    /**
     * The `secrets` byte string of §11.14.3: `len(PIN) (2) || PIN || len(password) (2) || NFC(password)`, lengths
     * big-endian in bytes.
     */
    fun recoverySecrets(pin: String, password: String): ByteArray {
        val p = pin.toByteArray(Charsets.US_ASCII)
        val w = nfc(password).toByteArray(Charsets.UTF_8)
        if (p.isEmpty() || p.size > MAX_LEN || w.isEmpty() || w.size > MAX_BYTES) throw CryptoException.Format("recovery secrets")
        return len(p.size) + p + len(w.size) + w
    }

    private fun len(n: Int) = byteArrayOf((n ushr 8).toByte(), n.toByte())

    private const val MAX_LEN = 0xFFFF
}

/**
 * `vault.recovery.open` (§11.14.6, release R): the registered phone opens its recovery with the PIN, the password
 * and its unlock secret, sealed to the ETK and signed by its device key; the result is sealed to its `kem`.
 */
object RecoveryOpen {
    const val TYPE = "vault.recovery.open"
    const val TYPE_RESULT = "vault.recovery.open.result"
    const val LABEL = "vettid/vms/2/recovery-open"

    /** What `sig` covers. */
    class Fields(
        val userGuid: String,
        val vaultId: String,
        val requestId: String,
        val recoveryId: String,
        val ts: String,
        val etkKid: Kid,
        val secrets: ByteArray,
        val token: String,
        /** hex(SHA-256(manifest bytes)): the request's manifest_sha256. */
        val manifestSha256: String,
        val unlockSecret: ByteArray,
    )

    /**
     * label \n user_guid \n vault_id \n request_id \n recovery_id \n ts \n etk_kid_hex \n hex(SHA-256(secrets)) \n
     * hex(SHA-256(token)) \n hex(SHA-256(manifest_bytes)) \n hex(SHA-256(unlock_secret)); no trailing newline.
     */
    fun signingString(f: Fields): String {
        val parts = listOf(f.userGuid, f.vaultId, f.requestId, f.recoveryId, f.ts, f.token)
        if (parts.any { it.isEmpty() || it.contains('\r') || it.contains('\n') } || !Bytes.isLowerHex(f.manifestSha256, HEX_64)) {
            throw CryptoException.Format("recovery open field")
        }
        if (f.unlockSecret.size != UnlockSecret.SIZE) throw CryptoException.Format("recovery open secret")
        return listOf(
            LABEL, f.userGuid, f.vaultId, f.requestId, f.recoveryId, f.ts, f.etkKid.toString(),
            Bytes.hex(Bytes.sha256(f.secrets)), Bytes.hex(Bytes.sha256(f.token.toByteArray(Charsets.UTF_8))), f.manifestSha256,
            Bytes.hex(Bytes.sha256(f.unlockSecret)),
        ).joinToString("\n")
    }

    /** The request body in §11.14.6 member order; [password] is sent in NFC. */
    @Suppress("LongParameterList")
    fun marshal(
        userGuid: String,
        vaultId: String,
        requestId: String,
        recoveryId: String,
        deviceIk: ByteArray,
        pin: String,
        password: String,
        unlockSecret: ByteArray,
        token: String,
        assertion: DeviceAssertion,
        manifestSha256: String,
        manifestSerial: Long,
        sig: ByteArray,
    ): ByteArray {
        if (!AltChannel.isValidPin(pin) || unlockSecret.size != UnlockSecret.SIZE || !Ulid.isValid(requestId) || manifestSerial < 1) {
            throw CryptoException.Format("recovery open request")
        }
        return JsonBuilder().string("user_guid", userGuid).string("vault_id", vaultId).string("request_id", requestId)
            .string("recovery_id", recoveryId).base64("device_ik", deviceIk).string("pin", pin).string("password", Passwords.nfc(password))
            .base64("unlock_secret", unlockSecret).string("token", token).raw("device_assertion", assertion.marshal())
            .string("manifest_sha256", manifestSha256).uint("manifest_serial", manifestSerial).base64("sig", sig).bytes()
    }

    /** `vault.recovery.open.result`. [used] is attempts.used when the counter answered ([counted]). */
    class Result(
        val ok: Boolean,
        val code: String,
        val stateSeq: Long,
        val headerSeq: Long,
        val token: String,
        vaultBundle: ByteArray?,
        val release: String,
        val releaseNumber: Long,
        val unlockId: String?,
        val counted: Boolean,
        val used: Int,
        val retryAt: Instant?,
        /**
         * (0.34.0) Whether THIS request used an attempt (the spend happened), as the vault says; null from a vault
         * that does not send it. The app never infers it from the code, `attempts` or `retry_at`.
         */
        val attemptUsed: Boolean? = null,
    ) {
        private val bundle = vaultBundle?.copyOf()

        fun vaultBundle(): ByteArray? = bundle?.copyOf()

        companion object {
            const val ATTEMPTS = 10

            fun parse(o: JsonObject): Result {
                val ok = o.bool("ok")
                val attempts = o.optObj("attempts")
                val used = attempts?.uint("used", 0, ATTEMPTS.toLong())?.toInt() ?: 0
                val retry = o.optString("retry_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val attemptUsed = if (o.has("attempt_used")) o.bool("attempt_used") else null
                return if (ok) {
                    Result(
                        true, "", o.uint("state_seq", 0, StrictJson.MAX_SAFE_INTEGER), o.uint("header_seq", 0, StrictJson.MAX_SAFE_INTEGER),
                        o.string("token"), Base64s.decodeStd(o.string("vault_bundle")), o.optString("release") ?: "",
                        o.optUint("release_number", 0, StrictJson.MAX_SAFE_INTEGER) ?: 0, o.optString("unlock_id"),
                        attempts != null, used, retry, attemptUsed,
                    )
                } else {
                    Result(false, o.string("code"), 0, 0, "", null, "", 0, null, attempts != null, used, retry, attemptUsed)
                }
            }

            fun parse(raw: ByteArray): Result = parse(StrictJson.parseObject(raw))
        }
    }

    private const val HEX_64 = 64
}
