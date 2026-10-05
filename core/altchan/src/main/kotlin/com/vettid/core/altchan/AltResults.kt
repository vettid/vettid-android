package com.vettid.core.altchan

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.altchan.Descriptor
import com.vettid.core.crypto.envelope.Envelope
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.envelope.Padding
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import java.io.IOException

/** A sealed result could not be opened or parsed (or answers another request). */
class AltResultException(what: String) : IOException("alternate channel: $what")

/** Sealed results of the alternate channel (§11.3, §11.4, §11.11.3). */
object AltResults {
    const val TYPE_ENROLL_RESULT = "vault.enroll.result"
    const val TYPE_UNLOCK_RESULT = "vault.unlock.result"
    const val TYPE_RECOVERY_RESULT = "vault.recovery.result"

    /**
     * Opens a 5,252-byte sealed result addressed to [kem]: a sealed envelope
     * whose inner is [type], answers [requestId] (`re`) and is padded to
     * exactly 4,096 bytes. Returns the body.
     */
    fun open(raw: ByteArray, kem: KemPrivateKey, type: String, requestId: String): ByteArray {
        if (raw.size != AltChannel.RESULT_ENVELOPE_SIZE) throw AltResultException("result size")
        try {
            val env = Envelope.parse(raw)
            if (env.mode != Mode.SEALED) throw AltResultException("result mode")
            val (padded, _) = Envelope.openSealed(env, kem)
            val inner = Inner.parse(Padding.unpadFixed(padded, Padding.ALT_CHANNEL), Mode.SEALED)
            if (inner.type != type || inner.re != requestId) throw AltResultException("result does not answer the request")
            return inner.body
        } catch (_: CryptoException) {
            throw AltResultException("result unreadable")
        }
    }

    internal fun obj(b: ByteArray): JsonObject = try {
        StrictJson.parseObject(b)
    } catch (_: CryptoException) {
        throw AltResultException("result body")
    }
}

/** `vault.enroll.result` (§11.3); success also arrives as vault.enrolled over the relay. */
data class EnrollResult(val ok: Boolean, val code: String?, val vaultId: String?) {
    companion object {
        fun parse(body: ByteArray): EnrollResult = wrap {
            val o = AltResults.obj(body)
            if (o.bool("ok")) EnrollResult(true, null, o.string("vault_id")) else EnrollResult(false, o.string("code"), null)
        }
    }
}

/** `vault.unlock.result` (§11.4). */
data class UnlockResult(
    val ok: Boolean,
    val code: String? = null,
    val stateSeq: Long = 0,
    val headerSeq: Long = 0,
    val retryAfterSeconds: Long = 0,
    val token: String? = null,
    val release: String? = null,
    val releaseNumber: Long = 0,
    val releaseStatus: String? = null,
    val manifestSerial: Long = 0,
    val update: Update? = null,
    val recoveryCancelled: Boolean = false,
    val vaultBundle: ByteArray? = null,
) {
    /** The `update` member: `moved`, `abandoned` or `refused` (with a code). */
    data class Update(val to: String, val result: String, val code: String?)

    companion object {
        private const val MAX = StrictJson.MAX_SAFE_INTEGER
        private const val MAX_BUNDLE = 2048

        fun parse(body: ByteArray): UnlockResult = wrap {
            val o = AltResults.obj(body)
            val ok = o.bool("ok")
            val headerSeq = o.uint("header_seq", 0, MAX)
            if (!ok) {
                return@wrap UnlockResult(false, o.string("code"), headerSeq = headerSeq, retryAfterSeconds = o.uint("retry_after", 0, MAX))
            }
            val release = o.string("release")
            if (!Descriptor.isValidPcr(release)) throw AltResultException("result release")
            val bundle = if (o.has("vault_bundle")) o.base64("vault_bundle") else null
            if (bundle != null && bundle.size > MAX_BUNDLE) throw AltResultException("result vault_bundle")
            UnlockResult(
                ok = true,
                stateSeq = o.uint("state_seq", 0, MAX),
                headerSeq = headerSeq,
                token = o.optString("token"),
                release = release,
                releaseNumber = o.uint("release_number", 1, MAX),
                releaseStatus = o.string("release_status"),
                manifestSerial = o.uint("manifest_serial", 0, MAX),
                update = o.optObj("update")?.let { Update(it.string("to"), it.string("result"), it.optString("code")) },
                recoveryCancelled = if (o.has("recovery_cancelled")) o.bool("recovery_cancelled") else false,
                vaultBundle = bundle,
            )
        }
    }
}

/** `vault.recovery.result` (§11.11.3). */
data class RecoveryResult(val ok: Boolean, val code: String?) {
    companion object {
        fun parse(body: ByteArray): RecoveryResult = wrap {
            val o = AltResults.obj(body)
            if (o.bool("ok")) RecoveryResult(true, null) else RecoveryResult(false, o.string("code"))
        }
    }
}

/**
 * The recovery QR the portal shows and the new app scans (§11.11.2):
 * `{"v":1,"t":"r","vault_id","recovery_id","code"}`, parsed as strictly as
 * vettid-vault's `ParseRecoveryQR` (and the code's alphabet checked too).
 */
data class RecoveryCode(val vaultId: String, val recoveryId: String, val code: String) {
    override fun toString(): String = "RecoveryCode($vaultId, $recoveryId)"

    companion object {
        fun parseQr(b: ByteArray): RecoveryCode = wrap {
            val o = AltResults.obj(b)
            if (o.uint("v", 1, 1) != 1L || o.string("t") != "r") throw AltResultException("recovery QR")
            val c = RecoveryCode(o.string("vault_id"), o.string("recovery_id"), o.string("code"))
            if (!RecoveryCodes.isValid(c.code) || !Ulid.isValid(c.recoveryId) || !validVaultId(c.vaultId)) {
                throw AltResultException("recovery QR")
            }
            c
        }

        /** A scanned QR's text (the compact JSON), or null when it is not a recovery QR. */
        fun parseScanned(text: String): RecoveryCode? = try {
            parseQr(text.trim().toByteArray(Charsets.UTF_8))
        } catch (_: AltResultException) {
            null
        }

        /** As the enclave's `validVaultID`: 1–128 printable ASCII characters. */
        private fun validVaultId(s: String) = s.length in 1..MAX_VAULT_ID && s.all { it.code in PRINTABLE_MIN..PRINTABLE_MAX }

        private const val MAX_VAULT_ID = 128
        private const val PRINTABLE_MIN = 0x21
        private const val PRINTABLE_MAX = 0x7e
    }
}

/**
 * The recovery code's form (§11.11.2): 20 random bytes as 32 Crockford base32
 * characters (`0-9A-HJKMNP-TV-Z`, upper case, no padding). The portal also shows
 * it as text in groups of four, for typing.
 */
object RecoveryCodes {
    const val LENGTH = 32
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val GROUP = 4

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    /**
     * A typed code in its canonical form, or null when it is not one: spaces and
     * hyphens are dropped, letters upper-cased, and the Crockford look-alikes read
     * as digits (I and L as 1, O as 0), which the canonical form never contains.
     */
    fun normalize(typed: String): String? {
        val sb = StringBuilder(LENGTH)
        for (ch in typed) {
            when (val c = ch.uppercaseChar()) {
                ' ', '-', '\t', '\n', '\r', '\u00a0' -> Unit
                'I', 'L' -> sb.append('1')
                'O' -> sb.append('0')
                else -> if (c in ALPHABET) sb.append(c) else return null
            }
            if (sb.length > LENGTH) return null
        }
        return sb.toString().takeIf { it.length == LENGTH }
    }

    /** The characters typed so far, in groups of four (`ABCD EFGH …`). */
    fun grouped(code: String): String = code.chunked(GROUP).joinToString(" ")
}

private inline fun <T> wrap(f: () -> T): T = try {
    f()
} catch (_: CryptoException) {
    throw AltResultException("result body")
}
