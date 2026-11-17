package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.json.Jcs
import com.vettid.core.crypto.json.JsonArray
import com.vettid.core.crypto.json.JsonNumber
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.JsonString
import com.vettid.core.crypto.json.JsonValue
import com.vettid.core.crypto.json.StrictJson

/**
 * An approval's document (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents"): the bytes of
 * `JCS({"action", "payload", "summary"})` a service attached to an `approval` challenge, as
 * `connection.authenticate.document` returned them, once they passed the app's checks ([check]). Only a checked document
 * is shown, and the hash an approval sends is [sha256] of exactly these bytes, never of a re-serialised form.
 *
 * Equal when the bytes are equal. Prints its action and size only.
 */
class ApprovalDocument private constructor(
    private val raw: ByteArray,
    private val hash: ByteArray,
    val action: String,
    val summary: String,
    /** `payload`, as parsed (members in the document's order, which is canonical). */
    val payload: JsonObject,
) {
    /** The document's bytes, exactly as fetched (a copy). */
    val bytes: ByteArray get() = raw.copyOf()

    /** `d`, SHA-256 of [bytes] (a copy). */
    val d: ByteArray get() = hash.copyOf()

    /** The `document_sha256` an approval sends: standard base64 of [d]. */
    val sha256: String get() = Base64s.encodeStd(hash)

    override fun equals(other: Any?): Boolean = other is ApprovalDocument && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = "ApprovalDocument(action=$action, size=${raw.size})"

    /** Why a document is not shown: any of these makes the screen say "This request can't be shown" and offer only Deny. */
    enum class Failure {
        /** `document` is not standard base64 with padding (or the answer had no `document` string). */
        ENCODING,

        /** Over [MAX_BYTES], or not `document_size` long (or the ask gave no size). */
        SIZE,

        /** Its SHA-256 is not `document_sha256` (or that is not 32 bytes of standard base64, or the ask named no document). */
        HASH,

        /**
         * Not strict JSON (§5.3), nested over [MAX_DEPTH], a number that is not a plain integer within 2^53 - 1 (0.90.16), or
         * not exactly `action`, `payload` and `summary` within their bounds. (The vault tells nesting apart as `depth`; to
         * the app both mean the same: not shown.)
         */
        SHAPE,

        /** Not its own RFC 8785 form. */
        CANONICAL,

        /** The challenge's `context` does not end with the 16 hex digits of `d[0:8]`. */
        REF,
    }

    /** A document checked against what its ask announced: shown, or refused with its reason. */
    sealed interface Check {
        data class Shown(val document: ApprovalDocument) : Check

        data class Refused(val reason: Failure) : Check
    }

    companion object {
        /** The bounds of §10.4 "The document", as vettid-vault `vms/peerwire` has them. */
        const val MAX_BYTES = 65_536
        const val MAX_DEPTH = 16
        const val MAX_SUMMARY = 1_024
        const val MAX_ACTION = 64

        /** The longest standard base64 of [MAX_BYTES] bytes. */
        private const val MAX_BASE64 = (MAX_BYTES + 2) / 3 * 4
        private const val REF_BYTES = 8
        private const val SHA256_BYTES = 32

        /** `action`, `payload` and `summary`, and nothing else. */
        private const val MEMBERS = 3

        /**
         * Checks a fetched document as §10.4 asks of the app: [base64] decodes (standard base64 with padding) to bytes that
         * are [size] long and hash to [sha256], and that parse as the receiving vault parsed them (its checks 2 to 4:
         * strict, at most 16 levels with the document object as level 1, integers only, exactly the three members,
         * canonical, and [context] ending with the reference).
         */
        @Suppress("ReturnCount") // one refusal per check, in the vault's order
        fun check(base64: String, sha256: String?, size: Int?, context: String?): Check {
            // Judged by its length first, as the vault does: an over-long string is `size` whatever it holds.
            if (base64.length > MAX_BASE64) return Check.Refused(Failure.SIZE)
            val bytes = try {
                Base64s.decodeStd(base64)
            } catch (_: CryptoException) {
                return Check.Refused(Failure.ENCODING)
            }
            if (bytes.size > MAX_BYTES || size == null || bytes.size != size) return Check.Refused(Failure.SIZE)
            val expected = sha256?.let { runCatching { Base64s.decodeStd(it, SHA256_BYTES) }.getOrNull() }
            if (expected == null || !expected.contentEquals(Bytes.sha256(bytes))) return Check.Refused(Failure.HASH)
            return checkBytes(bytes, context)
        }

        /** The checks on the decoded bytes (the vault's 2 to 4). */
        @Suppress("ReturnCount") // one refusal per check, in the vault's order
        internal fun checkBytes(bytes: ByteArray, context: String?): Check {
            val doc = shaped(bytes) ?: return Check.Refused(Failure.SHAPE)
            val canonical = runCatching { Jcs.canonical(doc) }.getOrNull()
            if (canonical == null || !canonical.contentEquals(bytes)) return Check.Refused(Failure.CANONICAL)
            val d = Bytes.sha256(bytes)
            // As bytes, as the vault's strings.HasSuffix compares; the reference is ASCII, so a String suffix is the same.
            if (context == null || !context.endsWith(ref(d))) return Check.Refused(Failure.REF)
            return Check.Shown(
                ApprovalDocument(
                    bytes.copyOf(), d, (doc["action"] as JsonString).value, (doc["summary"] as JsonString).value,
                    doc["payload"] as JsonObject,
                ),
            )
        }

        /** The document object when check 2 passes: strict, at most [MAX_DEPTH], integers only, the three members. */
        private fun shaped(bytes: ByteArray): JsonObject? {
            val doc = try {
                StrictJson.parseObject(bytes, MAX_DEPTH)
            } catch (_: CryptoException) {
                return null
            }
            val action = (doc["action"] as? JsonString)?.value
            val summary = (doc["summary"] as? JsonString)?.value
            val whole = doc.members.size == MEMBERS && doc["payload"] is JsonObject && action != null && summary != null &&
                validAction(action) && summary.toByteArray(Charsets.UTF_8).size <= MAX_SUMMARY && onlyIntegers(doc)
            return doc.takeIf { whole }
        }

        /** The 16 lower-case hex digits of `d[0:8]` a document's challenge's `context` ends with. */
        fun ref(d: ByteArray): String = Bytes.hex(d.copyOf(REF_BYTES))

        /** 1 to [MAX_ACTION] bytes of `[a-z0-9_.]`. */
        internal fun validAction(a: String): Boolean =
            a.length in 1..MAX_ACTION && a.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' }

        /** Every number, at any depth, a plain integer within 2^53 - 1 (VAULT-MESSAGING 0.90.16 §10.4 check 2). */
        internal fun onlyIntegers(v: JsonValue): Boolean = when (v) {
            is JsonNumber -> Jcs.isInteger(v.raw)
            is JsonArray -> v.items.all(::onlyIntegers)
            is JsonObject -> v.members.values.all(::onlyIntegers)
            else -> true
        }
    }
}

/** How an approval over a document ended ([ApprovalsRepository.approveAuthentication] with a document). */
sealed interface DocumentApproval {
    /** Signed in the document form; the request left the list. */
    data object Approved : DocumentApproval

    /**
     * The vault had no record of this device fetching the document it showed, and fetching it again did not give the same
     * bytes: nothing was approved, the request is still open, and the screen starts again from [check]. Since a
     * challenge's document is fixed and checked against the hash the ask announced, other bytes always fail the checks,
     * so in practice this is a failure: "This request can't be shown", Deny only.
     */
    data class Changed(val check: ApprovalDocument.Check) : DocumentApproval
}
