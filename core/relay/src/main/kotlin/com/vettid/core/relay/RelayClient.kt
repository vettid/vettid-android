package com.vettid.core.relay

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonNumber
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.JsonString
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** A relay error response (RELAY-PROTOCOL §7.1). [code] is `http_<status>` when the body carried none. */
class RelayException(val status: Int, val code: String, message: String, val retryAfterSeconds: Int = 0) :
    IOException("relay: $status $code${if (message.isEmpty()) "" else ": $message"}") {
    /** 429 and 5xx are retried with backoff (§7.2); other client errors never are. */
    val retryable: Boolean get() = status == HTTP_TOO_MANY || status >= HTTP_SERVER_ERROR

    companion object {
        const val HTTP_TOO_MANY = 429
        const val HTTP_SERVER_ERROR = 500

        const val BAD_REQUEST = "bad_request"
        const val SIGNATURE_INVALID = "signature_invalid"
        const val TIMESTAMP_STALE = "timestamp_stale"
        const val REPLAY_DETECTED = "replay_detected"
        const val TOKEN_INVALID = "token_invalid"
        const val TOKEN_EXPIRED = "token_expired"
        const val TOKEN_REVOKED = "token_revoked"
        const val TOKEN_USED = "token_used"
        const val MAILBOX_UNKNOWN = "mailbox_unknown"
        const val BLOB_UNKNOWN = "blob_unknown"
        const val CLAIM_UNKNOWN = "claim_unknown"
        const val PAYLOAD_TOO_LARGE = "payload_too_large"
        const val QUOTA_EXCEEDED = "quota_exceeded"
        const val RATE_LIMITED = "rate_limited"
    }
}

/** The relay limits returned at registration (§6.1). */
data class RelayLimits(
    val maxPayloadBytes: Long,
    val messageTtlSeconds: Long,
    val visibilityTimeoutSeconds: Long,
    val maxTokenLifetimeSeconds: Long,
    val openTokenMaxLifetimeSeconds: Long,
    val maxClaimBytes: Long,
    val claimTtlSeconds: Long,
    val maxBlobBytes: Long?,
    val blobTtlSeconds: Long?,
)

/** The register response. [created] is false when the key was already registered (200). */
data class Registration(val mailboxId: String, val limits: RelayLimits, val created: Boolean)

/** A collected message (§6.3, §6.4). [sender] is the relay key that signed the deposit; [jti] may be absent (pre-0.4 relays). */
class RelayMessage(val msgId: String, val depositedAt: String, val sender: String, val jti: String?, payload: ByteArray) {
    private val payload = payload.copyOf()

    fun payload(): ByteArray = payload.copyOf()

    /** The sender's raw relay key, or null if malformed. */
    fun senderKey(): ByteArray? = RelayAuth.decodeKey(sender)

    override fun toString(): String = "RelayMessage($msgId)"

    companion object {
        internal fun parse(o: JsonObject): RelayMessage = RelayMessage(
            msgId = o.string("msg_id"),
            depositedAt = o.optString("deposited_at") ?: "",
            sender = o.optString("sender") ?: "",
            jti = o.optString("jti"),
            payload = o.base64("payload"),
        )
    }
}

/** A denylist entry (§5.5): `jti` revokes one token, `sub` everything a sender holds. */
data class Revocation(val kind: String, val value: String) {
    companion object {
        fun jti(jti: String) = Revocation("jti", jti)

        fun sub(senderB64: String) = Revocation("sub", senderB64)
    }
}

/** A stored blob or claim and when it expires. */
data class StoredRef(val id: String, val expiresAt: Instant?)

/**
 * The relay client for one principal (one relay keypair), as vettid-relay's
 * `relayclient`. Every request is signed afresh per attempt (§4.1);
 * 429, 5xx and transport errors are retried with exponential backoff and
 * jitter, honouring `retry_after` (§7.2, CLIENT-NOTES §6); other client
 * errors are not retried, and a claim GET is never retried (it may already
 * have consumed the claim).
 *
 * [baseUrl] is the relay's base URL as tokens name it (`aud`). Transport
 * concerns (TLS trust, a development address mapping) belong to [http]'s
 * interceptors, never to this class.
 */
class RelayClient(
    baseUrl: String,
    private val key: Ed25519PrivateKey,
    private val http: OkHttpClient,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAttempts: Int = DEFAULT_ATTEMPTS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    /** The raw public key. */
    val publicKey: ByteArray get() = key.publicKey

    /** Canonical base64 of the public key (a token's `sub`). */
    val publicKeyB64: String get() = RelayAuth.encodeKey(key.publicKey)

    /** This principal's mailbox id. */
    val mailboxId: String get() = RelayAuth.mailboxId(key.publicKey)

    /** Long-polls run longer than the default read timeout. */
    private val pollHttp: OkHttpClient by lazy { http.newBuilder().readTimeout(POLL_READ_TIMEOUT_S, TimeUnit.SECONDS).build() }

    internal class Call(
        val method: String,
        val path: String,
        val query: String? = null,
        val body: ByteArray? = null,
        val token: String? = null,
        val contentType: String? = null,
        val unsigned: Boolean = false,
        val longPoll: Boolean = false,
    )

    private fun backoffMillis(attempt: Int): Long {
        val d = BACKOFF_BASE_MS shl minOf(attempt, BACKOFF_MAX_SHIFT)
        return d / 2 + random.nextLong(d / 2)
    }

    internal suspend fun <T> execute(c: Call, read: (Response) -> T): T {
        var last: IOException? = null
        for (i in 0 until maxOf(maxAttempts, 1)) {
            if (i > 0) {
                val e = last
                val wait = if (e is RelayException && e.retryAfterSeconds > 0) {
                    e.retryAfterSeconds * MS_PER_S + random.nextLong(JITTER_MS)
                } else {
                    backoffMillis(i)
                }
                sleep(wait)
            }
            try {
                return once(c, read)
            } catch (e: RelayException) {
                if (!e.retryable) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("relay: no attempt made")
    }

    @Suppress("CyclomaticComplexMethod") // one branch per request shape (signed, token, body, content type, long-poll)
    internal suspend fun <T> once(c: Call, read: (Response) -> T): T {
        val url = baseUrl + c.path + (c.query?.let { "?$it" } ?: "")
        val rb = Request.Builder().url(url)
        if (!c.unsigned) {
            RelayAuth.headers(key, c.method, c.path, Instant.now(clock), RelayAuth.bodyHash(c.body)).forEach { (k, v) -> rb.header(k, v) }
        }
        c.token?.let { rb.header("Authorization", "VettID-Deposit $it") }
        val ct = c.contentType ?: if (c.body != null) JSON else null
        val body = when {
            c.body != null -> c.body.toRequestBody(ct?.toMediaType())
            c.method == "POST" || c.method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        rb.method(c.method, body)
        val client = if (c.longPoll) pollHttp else http
        client.newCall(rb.build()).await().use { resp ->
            if (resp.code >= HTTP_ERROR) throw errorOf(resp)
            return read(resp)
        }
    }

    private fun errorOf(resp: Response): RelayException {
        val text = resp.body.bytesAtMost(ERROR_BODY_LIMIT.toLong())
        val headerRetry = resp.header("Retry-After")?.trim()?.toIntOrNull() ?: 0
        val o = try {
            StrictJson.parseObject(text)
        } catch (_: CryptoException) {
            null
        }
        val code = (o?.get("code") as? JsonString)?.value
        if (o == null || code.isNullOrEmpty()) {
            return RelayException(resp.code, "http_${resp.code}", String(text, Charsets.UTF_8).trim().take(MAX_MESSAGE), headerRetry)
        }
        val retry = (o["retry_after"] as? JsonNumber)?.raw?.toIntOrNull() ?: headerRetry
        return RelayException(resp.code, code, (o["message"] as? JsonString)?.value ?: "", retry)
    }

    private suspend fun json(c: Call): JsonObject? = execute(c) { resp ->
        if (resp.code == HTTP_NO_CONTENT) {
            null
        } else {
            val b = resp.body.bytes()
            if (b.isEmpty()) null else StrictJson.parseObject(b)
        }
    }

    /** Registers this principal's mailbox (idempotent, §6.1). */
    suspend fun register(): Registration {
        val body = JsonBuilder().string("pubkey", publicKeyB64).bytes()
        return execute(Call("POST", "/v1/register", body = body)) { resp ->
            val o = StrictJson.parseObject(resp.body.bytes())
            val l = o.obj("limits")
            fun n(k: String): Long = l.uint(k, 0, StrictJson.MAX_SAFE_INTEGER)
            fun opt(k: String): Long? = l.optUint(k, 0, StrictJson.MAX_SAFE_INTEGER)
            Registration(
                mailboxId = o.string("mailbox_id"),
                limits = RelayLimits(
                    n("max_payload_bytes"), n("message_ttl_seconds"), n("visibility_timeout_seconds"), n("max_token_lifetime_seconds"),
                    n("open_token_max_lifetime_seconds"), n("max_claim_bytes"), n("claim_ttl_seconds"),
                    opt("max_blob_bytes"), opt("blob_ttl_seconds"),
                ),
                created = resp.code == HTTP_CREATED,
            )
        }
    }

    /** A standing, sender-bound token for [senderB64] to deposit into this mailbox on [audience] (default this relay). */
    fun mintToken(
        senderB64: String,
        audience: String = baseUrl,
        ttl: Duration = DepositTokens.STANDING_TTL,
        jti: String? = null,
        quota: Quota? = null,
    ): String =
        DepositTokens.mintStanding(key, senderB64, audience, Instant.now(clock), ttl, jti, quota)

    /** A one-shot open token (§5.6) for this mailbox. Keep [ttl] as short as the use allows. */
    fun mintOpenToken(audience: String = baseUrl, ttl: Duration = Duration.ofMinutes(5), jti: String? = null): String =
        DepositTokens.mintOpen(key, audience, Instant.now(clock), ttl, jti)

    /**
     * Deposits [payload] into [mailboxId] with [token] (§6.2) and returns the
     * relay's msg_id. A retried deposit whose first attempt succeeded leaves
     * two messages: receivers dedupe at the E2E layer too.
     */
    suspend fun deposit(mailboxId: String, token: String, payload: ByteArray): String {
        val body = JsonBuilder().base64("payload", payload).bytes()
        return json(Call("POST", "/v1/mailbox/$mailboxId", body = body, token = token))!!.string("msg_id")
    }

    /** Long-polls this mailbox (§6.3): [wait] ≤ 25 s, [max] ≤ 100. */
    suspend fun collect(wait: Duration = Duration.ofSeconds(DEFAULT_WAIT_S), max: Int = DEFAULT_MAX): List<RelayMessage> {
        val q = "wait=${wait.seconds.coerceIn(0, MAX_WAIT_S)}&max=${max.coerceIn(1, MAX_COLLECT)}"
        val o = json(Call("GET", "/v1/mailbox", query = q, longPoll = true)) ?: return emptyList()
        return o.array("messages").map { RelayMessage.parse(it.asObject()) }
    }

    /** Acks (deletes) a collected message (§6.5; always 204). */
    suspend fun ack(msgId: String) {
        json(Call("DELETE", "/v1/mailbox/$msgId"))
    }

    /** Deletes this mailbox and everything in it (§6.10, relay ≥ 0.5.0; idempotent). */
    suspend fun deleteMailbox() {
        json(Call("DELETE", "/v1/mailbox"))
    }

    /** Adds entries to this mailbox's denylist (§5.5). */
    suspend fun revoke(vararg entries: Revocation) {
        val arr = JsonBuilder.array(entries.map { JsonBuilder().string("kind", it.kind).string("value", it.value).build() })
        json(Call("POST", "/v1/mailbox/denylist", body = JsonBuilder().raw("revoke", arr).bytes()))
    }

    /**
     * Moves this mailbox to [newKey] (§6.7) and returns the new mailbox id.
     * This client keeps signing with the old key; make a new client for the new one.
     */
    suspend fun rotate(newKey: Ed25519PrivateKey): String {
        val proof = newKey.signRaw(mailboxId.toByteArray())
        val body = JsonBuilder().string("new_pubkey", RelayAuth.encodeKey(newKey.publicKey)).base64("new_key_proof", proof).bytes()
        return json(Call("POST", "/v1/mailbox/rotate", body = body))!!.string("mailbox_id")
    }

    /** Uploads ciphertext to [mailboxId] (claim-check, §6.8). */
    suspend fun putBlob(mailboxId: String, token: String, ciphertext: ByteArray): StoredRef {
        val o = json(Call("PUT", "/v1/blob/$mailboxId", body = ciphertext, token = token, contentType = OCTET))!!
        return StoredRef(o.string("blob_id"), time(o.optString("expires_at")))
    }

    /** Fetches a blob deposited to this mailbox (§6.8). */
    suspend fun getBlob(blobId: String): ByteArray = execute(Call("GET", "/v1/blob/$blobId")) { it.body.bytes() }

    /** Deletes a blob (idempotent). */
    suspend fun deleteBlob(blobId: String) {
        json(Call("DELETE", "/v1/blob/$blobId"))
    }

    /** Leaves a single-fetch claim owned by this mailbox (§6.9); [ttl] null uses the relay default. */
    suspend fun putClaim(data: ByteArray, ttl: Duration? = null): StoredRef {
        val path = if (ttl != null) "/v1/claim/ttl/${ttl.seconds}" else "/v1/claim"
        val o = json(Call("PUT", path, body = data, contentType = OCTET))!!
        return StoredRef(o.string("claim_id"), time(o.optString("expires_at")))
    }

    /**
     * Fetches (and thereby deletes) a claim on this relay (§6.9). Unauthenticated
     * and NEVER retried: after a lost response the claim is gone (`claim_unknown`).
     */
    suspend fun getClaim(claimId: String): ByteArray =
        once(Call("GET", "/v1/claim/$claimId", unsigned = true)) { resp ->
            resp.body.bytesAtMost(MAX_CLAIM_READ.toLong())
        }

    /** Deletes a claim this mailbox created (idempotent). */
    suspend fun deleteClaim(claimId: String) {
        json(Call("DELETE", "/v1/claim/$claimId"))
    }

    /** Opens a WebSocket collect session (§6.4), owner-signed at the upgrade. */
    fun openStream(): RelayStream {
        val path = "/v1/mailbox/ws"
        val rb = Request.Builder().url(baseUrl + path)
        RelayAuth.headers(key, "GET", path, Instant.now(clock), RelayAuth.bodyHash(null)).forEach { (k, v) -> rb.header(k, v) }
        return RelayStream.open(http, rb.build())
    }

    private fun time(s: String?): Instant? = s?.let {
        try {
            OffsetDateTime.parse(it).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    companion object {
        const val DEFAULT_ATTEMPTS = 3
        const val DEFAULT_WAIT_S = 25L
        const val DEFAULT_MAX = 32
        private const val MAX_WAIT_S = 25L
        private const val MAX_COLLECT = 100
        private const val POLL_READ_TIMEOUT_S = 60L
        private const val BACKOFF_BASE_MS = 250L
        private const val BACKOFF_MAX_SHIFT = 6
        private const val JITTER_MS = 250L
        private const val MS_PER_S = 1000L
        private const val HTTP_ERROR = 400
        private const val HTTP_CREATED = 201
        private const val HTTP_NO_CONTENT = 204
        private const val ERROR_BODY_LIMIT = 4096
        private const val MAX_MESSAGE = 256
        private const val MAX_CLAIM_READ = 1 shl 20
        private const val JSON = "application/json"
        private const val OCTET = "application/octet-stream"
    }
}
