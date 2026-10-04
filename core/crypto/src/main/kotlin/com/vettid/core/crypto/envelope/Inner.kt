package com.vettid.core.crypto.envelope

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import java.time.Duration
import java.time.Instant

/** Envelope modes (§5.2). */
enum class Mode(val byte: Int) {
    SESSION(0x01),
    SEALED(0x02),
    ;

    companion object {
        fun of(b: Int): Mode? = entries.firstOrNull { it.byte == b }
    }
}

/** The `error` member of a response. */
data class InnerError(val code: String, val message: String = "")

/**
 * The inner plaintext (§5.3). Marshalling writes members in the order
 * v, id, type, ts, seq, re, exp, status, error, body, exactly as the Go
 * reference does; parsing is strict (unknown members ignored, known members
 * exactly typed, duplicate names rejected at any depth).
 */
class Inner(
    val id: String,
    val type: String,
    val ts: Instant,
    /** Session mode only (≥ 1); 0 means absent. */
    val seq: Long = 0,
    /** Responses: the id of the request answered. */
    val re: String? = null,
    val exp: Instant? = null,
    /** Responses: `ok` or `error`. */
    val status: String? = null,
    val error: InnerError? = null,
    /** The body object's JSON (`{}` when empty). */
    val body: ByteArray = EMPTY_BODY,
) {
    /** A copy with [seq] set (sealing in an epoch assigns it). */
    fun withSeq(seq: Long): Inner = Inner(id, type, ts, seq, re, exp, status, error, body)

    @Suppress("CyclomaticComplexMethod")
    internal fun validate(mode: Mode) {
        if (!Ulid.isValid(id) || !validType(type)) throw CryptoException.Format("inner")
        when (mode) {
            Mode.SESSION -> if (seq < 1 || seq > StrictJson.MAX_SAFE_INTEGER) throw CryptoException.Format("inner seq")
            Mode.SEALED -> if (seq != 0L) throw CryptoException.Format("inner seq")
        }
        if (re != null && !Ulid.isValid(re)) throw CryptoException.Format("inner re")
        if ((re == null) != (status == null)) throw CryptoException.Format("inner re/status")
        when (status) {
            null -> Unit
            STATUS_OK -> if (error != null) throw CryptoException.Format("inner error")
            STATUS_ERROR -> if (error == null) throw CryptoException.Format("inner error")
            else -> throw CryptoException.Format("inner status")
        }
        if (status != STATUS_ERROR && error != null) throw CryptoException.Format("inner error")
        error?.let {
            if (!validErrorCode(it.code) || it.message.toByteArray().size > MAX_ERROR_MSG_LEN) {
                throw CryptoException.Format("inner error")
            }
        }
    }

    /** Encodes the inner plaintext as compact JSON. */
    fun marshal(mode: Mode): ByteArray {
        validate(mode)
        val b = StrictJson.compactObject(body)
        val jb = JsonBuilder()
            .uint("v", VERSION)
            .string("id", id)
            .string("type", type)
            .string("ts", Timestamps.formatMillis(ts))
        if (mode == Mode.SESSION) jb.uint("seq", seq)
        re?.let { jb.string("re", it) }
        exp?.let { jb.string("exp", Timestamps.formatMillis(it)) }
        status?.let { jb.string("status", it) }
        error?.let {
            val eb = JsonBuilder().string("code", it.code)
            if (it.message.isNotEmpty()) eb.string("message", it.message)
            jb.raw("error", eb.build())
        }
        jb.raw("body", b)
        return jb.bytes()
    }

    /**
     * Freshness (§8.4, §5.3): `ts` at most 5 minutes in the future; for durable
     * types at most 16 days old; `exp`, if present, not passed.
     */
    fun checkTime(now: Instant, durable: Boolean) {
        if (Duration.between(now, ts) > MAX_CLOCK_SKEW_FUTURE) throw CryptoException.Time("timestamp in the future")
        if (durable && Duration.between(ts, now) > MAX_DURABLE_AGE) throw CryptoException.Time("timestamp too old")
        if (exp != null && now.isAfter(exp)) throw CryptoException.Time("expired")
    }

    override fun toString(): String = "Inner(id=$id, type=$type)"

    companion object {
        const val VERSION = 1L
        const val STATUS_OK = "ok"
        const val STATUS_ERROR = "error"
        const val MAX_TYPE_LEN = 64
        const val MAX_ERROR_CODE_LEN = 64
        const val MAX_ERROR_MSG_LEN = 1024
        val MAX_CLOCK_SKEW_FUTURE: Duration = Duration.ofMinutes(5)
        val MAX_DURABLE_AGE: Duration = Duration.ofDays(16)
        internal val EMPTY_BODY = "{}".toByteArray()

        /** `[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*`, at most 64 bytes. */
        fun validType(s: String): Boolean {
            if (s.isEmpty() || s.length > MAX_TYPE_LEN) return false
            var start = true
            for ((i, c) in s.withIndex()) {
                when {
                    c in 'a'..'z' -> start = false
                    c in '0'..'9' || c == '-' -> if (start) return false
                    c == '.' -> {
                        if (start || i == s.length - 1) return false
                        start = true
                    }
                    else -> return false
                }
            }
            return true
        }

        private fun validErrorCode(s: String): Boolean =
            s.isNotEmpty() && s.length <= MAX_ERROR_CODE_LEN &&
                s.withIndex().all { (i, c) -> c in 'a'..'z' || c == '_' || (i > 0 && c in '0'..'9') }

        /** Parses an unpadded inner plaintext strictly. */
        @Suppress("CyclomaticComplexMethod")
        fun parse(b: ByteArray, mode: Mode): Inner {
            val o = StrictJson.parseObject(b)
            if (o.uint("v", VERSION, VERSION) != VERSION) throw CryptoException.Format("inner v")
            val id = o.string("id")
            val type = o.string("type")
            val ts = Timestamps.parseMillis(o.string("ts"))
            val seq = o.optUint("seq", 1, StrictJson.MAX_SAFE_INTEGER)
            if ((seq != null) != (mode == Mode.SESSION)) throw CryptoException.Format("inner seq")
            val re = o.optString("re")
            if (re != null && re.isEmpty()) throw CryptoException.Format("inner re")
            val exp = o.optString("exp")?.let { Timestamps.parseMillis(it) }
            val status = o.optString("status")
            if (status != null && status.isEmpty()) throw CryptoException.Format("inner status")
            val error = o.optObj("error")?.let { InnerError(it.string("code"), it.optString("message") ?: "") }
            val body = o.obj("body").rawBytes()
            val inner = Inner(id, type, ts, seq ?: 0, re, exp, status, error, body)
            inner.validate(mode)
            return inner
        }

        /** Marshals and bucket-pads. */
        fun encode(inner: Inner, mode: Mode): ByteArray = Padding.pad(inner.marshal(mode))

        /** Strictly unpads and parses. */
        fun decode(padded: ByteArray, mode: Mode): Inner = parse(Padding.unpad(padded), mode)
    }
}
