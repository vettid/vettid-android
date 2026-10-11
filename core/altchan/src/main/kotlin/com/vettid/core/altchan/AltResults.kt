package com.vettid.core.altchan

import com.vettid.core.crypto.Bytes
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
import java.time.Instant
import java.util.Locale

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
    fun open(raw: ByteArray, kem: KemPrivateKey, type: String, requestId: String): ByteArray = openInner(raw, kem, type, requestId).body

    /** As [open], returning the whole inner (its `ts` is the enclave's clock when it answered). */
    fun openInner(raw: ByteArray, kem: KemPrivateKey, type: String, requestId: String): Inner {
        if (raw.size != AltChannel.RESULT_ENVELOPE_SIZE) throw AltResultException("result size")
        try {
            val env = Envelope.parse(raw)
            if (env.mode != Mode.SEALED) throw AltResultException("result mode")
            val (padded, _) = Envelope.openSealed(env, kem)
            val inner = Inner.parse(Padding.unpadFixed(padded, Padding.ALT_CHANNEL), Mode.SEALED)
            if (inner.type != type || inner.re != requestId) throw AltResultException("result does not answer the request")
            return inner
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
    /**
     * `credential_backup` (0.10.6, §11.11.5 step 1): in a recovery-registered app's result, whether the vault
     * keeps a copy of the credential, so whether the password can recover it. Null when absent (an older vault).
     */
    val credentialBackup: Boolean? = null,
    /**
     * The result's inner `ts`: the enclave's clock when it answered, after any lock of the running vault the unlock
     * made (§11.4). A `vault.locking` not newer than this is from before the vault opened. Null when not known.
     */
    val at: Instant? = null,
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
                credentialBackup = if (o.has("credential_backup")) o.bool("credential_backup") else null,
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
data class RecoveryCode(
    val vaultId: String,
    val recoveryId: String,
    val code: String,
    /** The QR's member API origin (0.15.0): compared exactly with the app's own, never contacted; null when absent. */
    val api: String? = null,
) {
    override fun toString(): String = "RecoveryCode($vaultId, $recoveryId)"

    companion object {
        fun parseQr(b: ByteArray): RecoveryCode = wrap {
            val o = AltResults.obj(b)
            if (o.uint("v", 1, 1) != 1L || o.string("t") != "r") throw AltResultException("recovery QR")
            checked(RecoveryCode(o.string("vault_id"), o.string("recovery_id"), o.string("code"), o.optString("api")))
        }

        /** [c] when its code, recovery id and vault id have the forms a recovery QR's must have. */
        internal fun checked(c: RecoveryCode): RecoveryCode {
            if (!RecoveryCodes.isValid(c.code) || !Ulid.isValid(c.recoveryId) || !validVaultId(c.vaultId)) {
                throw AltResultException("recovery QR")
            }
            return c
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
 * characters (`0-9A-HJKMNP-TV-Z`, upper case, no padding); and its typed form
 * (0.25.0, §11.11.10), which the portal shows under the QR as 12 groups of four:
 *
 * ```
 * typed = locator (12) || code (32) || check (4)
 * check = the first 20 bits of
 *         SHA-256("vettid/vms/2/recovery-typed-check" || 0x00 || api || 0x00 || locator || code),
 *         as 4 characters of the alphabet (most significant 5 bits first)
 * ```
 */
object RecoveryCodes {
    const val LENGTH = 32
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** The member API's locator, which stands for (`vault_id`, `recovery_id`) at the claim (60 random bits). */
    const val LOCATOR_LENGTH = 12
    const val CHECK_LENGTH = 4
    const val TYPED_LENGTH = LOCATOR_LENGTH + LENGTH + CHECK_LENGTH
    const val GROUP = 4
    const val TYPED_GROUPS = TYPED_LENGTH / GROUP
    private const val TYPED_CHECK_LABEL = "vettid/vms/2/recovery-typed-check"
    private const val BITS_PER_CHAR = 5
    private const val CHAR_MASK = 31
    private const val BYTE_MASK = 0xff

    /** Why a typed character was refused in the field (§11.11.10 step 1): never guessed, never sent. */
    enum class Refused {
        /** `U` is not in the alphabet and is not mapped: "Check whether it is a V." */
        LETTER_U,

        /** Anything else that is neither a digit nor a letter of the alphabet, a look-alike or a separator. */
        OTHER,
    }

    /**
     * What typed or pasted text holds: [text] the canonical characters so far (at most [TYPED_LENGTH]; a
     * forty-ninth is ignored), and, when a character was refused, [refused] with [refusedGroup] (1 to 12), the
     * group it would have gone into. Refused characters are dropped, never replaced.
     */
    class TypedInput(val text: String, val refused: Refused? = null, val refusedGroup: Int = 0) {
        /** Groups of four that are complete. */
        val groups: Int get() = text.length / GROUP

        val complete: Boolean get() = text.length == TYPED_LENGTH

        override fun toString(): String = "TypedInput(${text.length} characters, refused=$refused)"
    }

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    fun isLocator(s: String): Boolean = s.length == LOCATOR_LENGTH && s.all { it in ALPHABET }

    /**
     * One character as the canonical form has it, null for a separator (dropped), or a [Refused]: spaces, hyphens
     * and line breaks are dropped, letters upper-cased, and the Crockford look-alikes read as digits (I and L as 1,
     * O as 0), which the canonical form never contains.
     */
    private fun canonical(ch: Char): Any? = when (val c = ch.uppercaseChar()) {
        ' ', '-', '\t', '\n', '\r', '\u00a0' -> null
        'I', 'L' -> '1'
        'O' -> '0'
        'U' -> Refused.LETTER_U
        else -> if (c in ALPHABET) c else Refused.OTHER
    }

    private fun normalize(typed: String, length: Int): String? {
        val sb = StringBuilder(length)
        for (ch in typed) {
            when (val c = canonical(ch)) {
                null -> Unit
                is Char -> sb.append(c)
                else -> return null
            }
            if (sb.length > length) return null
        }
        return sb.toString().takeIf { it.length == length }
    }

    /**
     * A typed code (the 32 characters alone) in its canonical form, or null when it is not one: spaces and
     * hyphens are dropped, letters upper-cased, and the Crockford look-alikes read
     * as digits (I and L as 1, O as 0), which the canonical form never contains.
     */
    fun normalize(typed: String): String? = normalize(typed, LENGTH)

    /** The typed form (§11.11.10) in its canonical 48 characters, or null: the same rules; `U` is never mapped. */
    fun normalizeTyped(typed: String): String? = normalize(typed, TYPED_LENGTH)

    /**
     * The typed form as a field takes it, character by character (§11.11.10 step 1): what is accepted so far and
     * the first character that was refused, if any.
     */
    fun typedInput(raw: String): TypedInput {
        val sb = StringBuilder(TYPED_LENGTH)
        var refused: Refused? = null
        var group = 0
        for (ch in raw) {
            if (sb.length == TYPED_LENGTH) break
            when (val c = canonical(ch)) {
                null -> Unit
                is Char -> sb.append(c)
                is Refused -> if (refused == null) {
                    refused = c
                    group = sb.length / GROUP + 1
                }
            }
        }
        return TypedInput(sb.toString(), refused, group)
    }

    /** The member API origin as the check hashes it: the portal's own origin, no trailing slash, lower case. */
    fun origin(api: String): String = api.trimEnd('/').lowercase(Locale.ROOT)

    /**
     * The 4 check characters for [api] (a member API origin), [locator] and [code] in canonical form. They hold no
     * secret (anyone can compute them for a guess): they catch typing mistakes and another environment's code
     * before anything is sent.
     */
    fun typedCheck(api: String, locator: String, code: String): String {
        require(isLocator(locator) && isValid(code)) { "typed check: not a locator and a code" }
        val h = Bytes.sha256("$TYPED_CHECK_LABEL\u0000${origin(api)}\u0000$locator$code".toByteArray(Charsets.UTF_8))
        val bits = ((h[0].toInt() and BYTE_MASK) shl 12) or ((h[1].toInt() and BYTE_MASK) shl 4) or ((h[2].toInt() and BYTE_MASK) shr 4)
        return buildString(CHECK_LENGTH) {
            for (i in CHECK_LENGTH - 1 downTo 0) append(ALPHABET[(bits shr (i * BITS_PER_CHAR)) and CHAR_MASK])
        }
    }

    /** The characters typed so far, in groups of four (`ABCD EFGH …`). */
    fun grouped(code: String): String = code.chunked(GROUP).joinToString(" ")
}

/**
 * A typed recovery code whose check characters matched this app's member API origin (§11.11.10 step 2): the
 * [locator] to claim with and the [code] for the enclave. Only [parse] makes one, so a string that fails the check
 * can never reach the API. The check characters are verified and dropped; nothing here is ever stored or logged.
 */
class TypedRecoveryCode private constructor(val locator: String, val code: String) {
    /**
     * The same [RecoveryCode] a scan of this recovery's QR produces, from the claim's answer (§11.11.10 steps 3
     * and 4): everything after it is the scanned path. [api] is this app's member API origin.
     */
    fun recoveryCode(claimed: Claimed, api: String): RecoveryCode {
        val vaultId = claimed.vaultId ?: throw AltResultException("recovery claim")
        val recoveryId = claimed.recoveryId ?: throw AltResultException("recovery claim")
        return RecoveryCode.checked(RecoveryCode(vaultId, recoveryId, code, api.trimEnd('/')))
    }

    override fun equals(other: Any?): Boolean = other is TypedRecoveryCode && other.locator == locator && other.code == code

    override fun hashCode(): Int = locator.hashCode() * HASH_PRIME + code.hashCode()

    override fun toString(): String = "TypedRecoveryCode(…)"

    /** What typed text turned out to be. */
    sealed interface Parsed {
        /** 48 characters whose check matches: [code] may be claimed. */
        class Ok(val code: TypedRecoveryCode) : Parsed

        /**
         * 48 valid characters whose check does not match this app's origin: a typing mistake, or a code of
         * another environment's account site. Input validation, not a wrong code: nothing is sent or counted.
         */
        data object BadCheck : Parsed

        /** Exactly the 32 characters of a bare code (an account page from before MEMBER-API 2.4.0). */
        data object BareCode : Parsed

        /** Not 48 characters of the alphabet (too few, too many, or a character the code never contains). */
        data object NotTyped : Parsed
    }

    companion object {
        private const val HASH_PRIME = 31

        /** [typed] as the member typed or pasted it; [api] is this app's own built-in member API origin. */
        fun parse(typed: String, api: String): Parsed {
            val t = RecoveryCodes.normalizeTyped(typed)
                ?: return if (RecoveryCodes.normalize(typed) != null) Parsed.BareCode else Parsed.NotTyped
            val locator = t.substring(0, RecoveryCodes.LOCATOR_LENGTH)
            val code = t.substring(RecoveryCodes.LOCATOR_LENGTH, RecoveryCodes.LOCATOR_LENGTH + RecoveryCodes.LENGTH)
            val check = t.substring(RecoveryCodes.LOCATOR_LENGTH + RecoveryCodes.LENGTH)
            // Nothing secret is compared: anyone can compute the check characters for a guess.
            val matches = RecoveryCodes.typedCheck(api, locator, code) == check
            return if (matches) Parsed.Ok(TypedRecoveryCode(locator, code)) else Parsed.BadCheck
        }
    }
}

private inline fun <T> wrap(f: () -> T): T = try {
    f()
} catch (_: CryptoException) {
    throw AltResultException("result body")
}
