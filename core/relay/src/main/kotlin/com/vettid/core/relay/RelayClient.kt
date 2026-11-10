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
import okhttp3.EventListener
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * A relay error response (RELAY-PROTOCOL §7.1). [code] is `http_<status>` when the body carried none.
 *
 * The exception's message is the status and the code only: the relay's own text (the body's `message`, or a body
 * that is not JSON) is a relay answer, so it is kept in [detail] and never in the message, which logs and crash
 * reports may carry (the owner's rule, 2026-10-23).
 */
class RelayException(
    val status: Int,
    val code: String,
    message: String,
    val retryAfterSeconds: Int = 0,
    /**
     * (RELAY-PROTOCOL 0.9.2 §7.1) What a `quota_exceeded` used up: `token`, `mailbox`, `mailbox_blobs` or `sender_blobs`;
     * null without one (a relay before 0.9.2, or any other refusal).
     */
    val quota: String? = null,
) : IOException("relay: $status $code") {
    /** The relay's text ("" without one): never in the exception's message. */
    val detail: String = message

    /**
     * 429 and 5xx are retried with backoff (§7.2), and so is a signature the relay found stale or already seen
     * ([staleSignature]); other client errors never are, and neither is `quota_exceeded` (a 429 that is not transient:
     * §7.1, "a client MUST NOT retry the request unchanged"; GC #6847, #336's Opus L3).
     */
    val retryable: Boolean
        get() = code != QUOTA_EXCEEDED && (status == HTTP_TOO_MANY || status >= HTTP_SERVER_ERROR || staleSignature)

    /**
     * `timestamp_stale` or `replay_detected` (§4.1): the request's signature was made too long before it reached the
     * relay (a phone frozen in the background with the request in hand) or was sent twice. Nothing of the request
     * was applied; the same request signed afresh is accepted. Only a clock that is really wrong keeps failing, and
     * the backoff bounds that.
     */
    val staleSignature: Boolean get() = status == HTTP_UNAUTHORIZED && (code == TIMESTAMP_STALE || code == REPLAY_DETECTED)

    companion object {
        const val HTTP_UNAUTHORIZED = 401
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

        /** [quota] values (§7.1). */
        const val QUOTA_TOKEN = "token"
        const val QUOTA_MAILBOX = "mailbox"
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
 * `relayclient`. Every request is signed afresh per attempt (§4.1), and again
 * by [TransportRetry] right before each attempt it makes after waiting for the
 * network ([RequestSigner]): a signature never waits in a frozen process and
 * then reaches the relay stale. 429, 5xx, `timestamp_stale`, `replay_detected`
 * and transport errors are retried with exponential backoff and jitter,
 * honouring `retry_after` (§7.2, CLIENT-NOTES §6) up to [CallLimits.maxRetryAfterS];
 * every attempt but a long-poll's has a call timeout ([CallLimits]). Other
 * client errors are not retried, a claim GET is never retried (it may already
 * have consumed the claim), and a blob upload is not sent again once it went
 * out (it may already be stored).
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
    private val limits: CallLimits = CallLimits(),
) {
    /**
     * Bounds on how long one request may take, so that a relay (a claim's comes from a scanned code) cannot keep a
     * caller waiting for hours: each attempt's whole call ([callMs], [blobMs] for a blob's bytes) and the longest
     * `retry_after` honoured ([maxRetryAfterS]); a longer one ends the request at once with its error, as the last
     * attempt's would. A long-poll has no call timeout: a full batch (up to 100 messages of 256 KiB) may take long
     * on a slow link and still be arriving, and only the read timeout ends one that stalls (a collect that must
     * end sooner passes its own call timeout, as the transfer's settlement window does: a coroutine timeout cannot cut a
     * body read that is blocked).
     */
    data class CallLimits(
        val callMs: Long = CALL_TIMEOUT_MS,
        val blobMs: Long = BLOB_CALL_TIMEOUT_MS,
        val maxRetryAfterS: Int = MAX_RETRY_AFTER_S,
    )

    val baseUrl: String = baseUrl.trimEnd('/')

    /** The raw public key. */
    val publicKey: ByteArray get() = key.publicKey

    /** Canonical base64 of the public key (a token's `sub`). */
    val publicKeyB64: String get() = RelayAuth.encodeKey(key.publicKey)

    /** This principal's mailbox id. */
    val mailboxId: String get() = RelayAuth.mailboxId(key.publicKey)

    /** Long-polls run longer than the default read timeout. */
    private val pollHttp: OkHttpClient by lazy {
        // OkHttp's own silent resend of a GET whose response was lost would hide the lost attempt from [execute] (and from
        // [Call.onLostAttempt]): off here, so every attempt is seen. A connection that never carried the request is still
        // resent by [TransportRetry] and by [execute].
        http.newBuilder().readTimeout(POLL_READ_TIMEOUT_S, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    }

    internal class Call(
        val method: String,
        val path: String,
        val query: String? = null,
        val body: ByteArray? = null,
        val token: String? = null,
        val contentType: String? = null,
        val unsigned: Boolean = false,
        val longPoll: Boolean = false,
        /** Carries a blob's bytes (up to 8 MiB): the longer call timeout. */
        val blob: Boolean = false,
        /** Each attempt's call timeout instead of the default one; also bounds a long-poll (a caller's own bound). */
        val callMs: Long? = null,
        /**
         * False: an attempt that went out and then failed without the relay's own refusal (an I/O error, a timeout,
         * a 5xx) is not sent again, since it may have been done (a blob upload: each one fills the mailbox).
         */
        val resendAfterSent: Boolean = true,
        /** A signed request's signature is accepted once (§4.1): [TransportRetry] resends it only if it never went out. */
        val transportRetry: TransportRetry.Policy = TransportRetry.Policy.UNSENT_ONLY,
        /**
         * Called for each attempt of [execute] that ended without the relay's own answer: an I/O error, or a 5xx (which a
         * proxy can send after the relay handed messages out). The attempt may have been answered and its messages leased
         * to it; the retry then finds the mailbox empty and the loss is invisible to the caller (a collect).
         */
        val onLostAttempt: (() -> Unit)? = null,
        /** Called when a RETRY attempt of [execute] (not the first) goes out: its send time is a send of its own. */
        val onRetrySent: (() -> Unit)? = null,
        /** How many attempts [execute] makes instead of the client's own (1: one attempt, bounded by its call timeout alone). */
        val attempts: Int? = null,
        /**
         * Called each time an attempt actually starts writing the request (OkHttp's `requestHeadersStart`): after any wait
         * for the network, connect and retry inside the transport, every attempt of [execute] and of [TransportRetry]. The
         * time the relay can first see the request, so the one its lease can start from.
         */
        val onSent: (() -> Unit)? = null,
    )

    private fun backoffMillis(attempt: Int): Long {
        val d = BACKOFF_BASE_MS shl minOf(attempt, BACKOFF_MAX_SHIFT)
        return d / 2 + random.nextLong(d / 2)
    }

    internal suspend fun <T> execute(c: Call, read: (Response) -> T): T {
        var last: IOException? = null
        val sent = AtomicBoolean()
        for (i in 0 until maxOf(c.attempts ?: maxAttempts, 1)) {
            if (i > 0) {
                val e = last
                val wait = if (e is RelayException && e.retryAfterSeconds > 0) {
                    e.retryAfterSeconds * MS_PER_S + random.nextLong(JITTER_MS)
                } else {
                    backoffMillis(i)
                }
                sleep(wait)
                c.onRetrySent?.invoke()
            }
            sent.set(false)
            try {
                return once(c, sent, read)
            } catch (e: RelayException) {
                if (e.status >= HTTP_SERVER_ERROR) c.onLostAttempt?.invoke()
                if (!mayRetry(c, e, sent.get())) throw e
                last = e
            } catch (e: IOException) {
                c.onLostAttempt?.invoke()
                if (!c.resendAfterSent && sent.get()) throw e
                last = e
            }
        }
        throw last ?: IOException("relay: no attempt made")
    }

    /**
     * Whether a refused attempt is tried again: a retryable refusal, not `quota_exceeded` (RELAY-PROTOCOL §7.1: never
     * retried unchanged), a `retry_after` within the cap, and not a resend that [Call.resendAfterSent] forbids: after a
     * send, neither a 5xx nor `replay_detected` (OkHttp may have resent the request on a failed connection, and the
     * relay may have done the first).
     */
    private fun mayRetry(c: Call, e: RelayException, sent: Boolean): Boolean =
        e.retryable && e.retryAfterSeconds <= limits.maxRetryAfterS &&
            (c.resendAfterSent || !sent || e.status < HTTP_SERVER_ERROR && e.code != RelayException.REPLAY_DETECTED)

    @Suppress("CyclomaticComplexMethod") // one branch per request shape (signed, token, body, content type, long-poll)
    internal suspend fun <T> once(c: Call, sent: AtomicBoolean? = null, read: (Response) -> T): T {
        val url = baseUrl + c.path + (c.query?.let { "?$it" } ?: "")
        val rb = Request.Builder().url(url)
        if (!c.unsigned) {
            val signer = signer(c.method, c.path, RelayAuth.bodyHash(c.body))
            signer.headers().forEach { (k, v) -> rb.header(k, v) }
            rb.tag(RequestSigner::class.java, signer)
        }
        c.token?.let { rb.header("Authorization", "VettID-Deposit $it") }
        val ct = c.contentType ?: if (c.body != null) JSON else null
        val body = when {
            c.body != null -> c.body.toRequestBody(ct?.toMediaType())
            c.method == "POST" || c.method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        rb.method(c.method, body)
        rb.tag(TransportRetry.Policy::class.java, c.transportRetry)
        val client = if (c.longPoll) pollHttp else http
        val call = client.newCall(rb.build())
        val timeoutMs = c.callMs ?: if (c.longPoll) null else if (c.blob) limits.blobMs else limits.callMs
        if (timeoutMs != null) {
            // The whole attempt, the body read by [read] included (it runs before the response is closed).
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        }
        if (c.onSent != null || sent != null) {
            call.addEventListener(
                object : EventListener() {
                    override fun requestHeadersStart(call: okhttp3.Call) {
                        sent?.set(true)
                        c.onSent?.invoke()
                    }
                },
            )
        }
        call.await().use { resp ->
            if (resp.code >= HTTP_ERROR) throw errorOf(resp)
            return read(resp)
        }
    }

    /** Signs [method] [path] with this principal's key at the moment it is asked to (§4.1). */
    private fun signer(method: String, path: String, bodyHash: ByteArray) =
        RequestSigner { RelayAuth.headers(key, method, path, Instant.now(clock), bodyHash) }

    private fun errorOf(resp: Response): RelayException {
        val text = resp.body.bytesAtMost(ERROR_BODY_LIMIT.toLong())
        val headerRetry = resp.header("Retry-After")?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val o = try {
            StrictJson.parseObject(text)
        } catch (_: CryptoException) {
            null
        }
        val code = (o?.get("code") as? JsonString)?.value
        if (o == null || code.isNullOrEmpty()) {
            return RelayException(resp.code, "http_${resp.code}", String(text, Charsets.UTF_8).trim().take(MAX_MESSAGE), headerRetry)
        }
        val retry = (o["retry_after"] as? JsonNumber)?.raw?.toIntOrNull()?.coerceAtLeast(0) ?: headerRetry
        val quota = (o["quota"] as? JsonString)?.value?.takeIf { code == RelayException.QUOTA_EXCEEDED }
        return RelayException(resp.code, code, (o["message"] as? JsonString)?.value ?: "", retry, quota)
    }

    private suspend fun json(c: Call): JsonObject? = execute(c) { resp ->
        if (resp.code == HTTP_NO_CONTENT) {
            null
        } else {
            // A collect's batch can be large; any other answer is a few fields. Neither is read without a bound.
            val limit = if (c.longPoll) MAX_COLLECT_READ else MAX_JSON_READ
            val b = boundedBody(resp, limit)
            if (b.isEmpty()) null else StrictJson.parseObject(b)
        }
    }

    /**
     * A 2xx answer's body, at most [limit] bytes. A longer one is [AnswerTooLarge], an [IOException]: the relay
     * answered, so a collect's batch may be leased to it, and its caller counts it as a lost attempt (the lease held).
     */
    private fun boundedBody(resp: Response, limit: Long): ByteArray {
        val b = resp.body.bytesAtMost(limit + 1)
        if (b.size > limit) throw AnswerTooLarge(limit)
        return b
    }

    /** A 2xx answer over the client's bound (see [boundedBody]); not a [RelayException]: no refusal. */
    class AnswerTooLarge(limit: Long) : IOException("relay: answer over $limit bytes")

    /** Registers this principal's mailbox (idempotent, §6.1). */
    suspend fun register(): Registration {
        val body = JsonBuilder().string("pubkey", publicKeyB64).bytes()
        return execute(Call("POST", "/v1/register", body = body)) { resp ->
            val o = StrictJson.parseObject(boundedBody(resp, MAX_JSON_READ))
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

    /**
     * Long-polls this mailbox (§6.3): [wait] ≤ 25 s, [max] ≤ 100. [callTimeout] bounds each attempt, the body read
     * included; none by default ([CallLimits]): a caller that must end sooner passes one (the transfer's settlement window).
     * [attempts]: how many (default the client's); a caller whose bound covers the whole request passes 1.
     */
    suspend fun collect(
        wait: Duration = Duration.ofSeconds(DEFAULT_WAIT_S),
        max: Int = DEFAULT_MAX,
        onLostAttempt: (() -> Unit)? = null,
        onRetrySent: (() -> Unit)? = null,
        onSent: (() -> Unit)? = null,
        callTimeout: Duration? = null,
        attempts: Int? = null,
    ): List<RelayMessage> {
        val q = "wait=${wait.seconds.coerceIn(0, MAX_WAIT_S)}&max=${max.coerceIn(1, MAX_COLLECT)}"
        val call = Call(
            "GET", "/v1/mailbox", query = q, longPoll = true, onLostAttempt = onLostAttempt, onRetrySent = onRetrySent,
            onSent = onSent, callMs = callTimeout?.toMillis(), attempts = attempts,
        )
        val o = json(call) ?: return emptyList()
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
        val call = Call(
            "PUT", "/v1/blob/$mailboxId", body = ciphertext, token = token, contentType = OCTET, blob = true,
            resendAfterSent = false,
        )
        val o = json(call)!!
        return StoredRef(o.string("blob_id"), time(o.optString("expires_at")))
    }

    /** Fetches a blob deposited to this mailbox (§6.8). */
    suspend fun getBlob(blobId: String, maxBytes: Long = MAX_BLOB_READ): ByteArray =
        execute(Call("GET", "/v1/blob/$blobId", blob = true)) { resp ->
            // At most [maxBytes] are ever held: a declared length over it is refused before reading, and so is a body
            // that grows beyond it.
            val declared = resp.header("Content-Length")?.toLongOrNull()
            if (declared != null && declared > maxBytes) throw RelayException(resp.code, RelayException.PAYLOAD_TOO_LARGE, "blob")
            val b = resp.body.bytesAtMost(maxBytes + 1)
            if (b.size > maxBytes) {
                b.fill(0)
                throw RelayException(resp.code, RelayException.PAYLOAD_TOO_LARGE, "blob")
            }
            b
        }

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
        once(Call("GET", "/v1/claim/$claimId", unsigned = true, transportRetry = TransportRetry.Policy.NEVER)) { resp ->
            resp.body.bytesAtMost(MAX_CLAIM_READ.toLong())
        }

    /** Deletes a claim this mailbox created (idempotent). */
    suspend fun deleteClaim(claimId: String) {
        json(Call("DELETE", "/v1/claim/$claimId"))
    }

    /** Opens a WebSocket collect session (§6.4), owner-signed at the upgrade. */
    fun openStream(): RelayStream {
        val path = "/v1/mailbox/ws"
        val signer = signer("GET", path, RelayAuth.bodyHash(null))
        val rb = Request.Builder().url(baseUrl + path).tag(TransportRetry.Policy::class.java, TransportRetry.Policy.UNSENT_ONLY)
            .tag(RequestSigner::class.java, signer)
        signer.headers().forEach { (k, v) -> rb.header(k, v) }
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

        /** Any call but a long-poll or a blob's: a claim fetch, a deposit. */
        const val CALL_TIMEOUT_MS = 60_000L

        /** A blob's upload or download (8 MiB at about 30 KiB/s). */
        const val BLOB_CALL_TIMEOUT_MS = 300_000L

        /** The longest `retry_after` waited for; a longer one ends the request. */
        const val MAX_RETRY_AFTER_S = 30

        /** Any JSON answer but a collect's. */
        private const val MAX_JSON_READ = 1L shl 20

        /** A collect's: [MAX_COLLECT] messages of 256 KiB, base64, with their fields. */
        private const val MAX_COLLECT_READ = 40L shl 20
        private const val BACKOFF_BASE_MS = 250L
        private const val BACKOFF_MAX_SHIFT = 6
        private const val JITTER_MS = 250L
        private const val MS_PER_S = 1000L
        private const val HTTP_ERROR = 400
        private const val HTTP_SERVER_ERROR = 500
        private const val HTTP_CREATED = 201
        private const val HTTP_NO_CONTENT = 204
        private const val ERROR_BODY_LIMIT = 4096
        private const val MAX_MESSAGE = 256
        private const val MAX_CLAIM_READ = 1 shl 20

        /** The reference relay's `max_blob_bytes` (RELAY-PROTOCOL §6.8): no blob a relay keeps is larger. */
        const val MAX_BLOB_READ = 8_388_608L
        private const val JSON = "application/json"
        private const val OCTET = "application/octet-stream"
    }
}
