package com.vettid.core.altchan

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.json.JsonBool
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonNumber
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.JsonString
import com.vettid.core.crypto.json.StrictJson
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.time.Duration

/** `GET /api/vault/enclave`: the instance to seal to (§11.2). */
class EnclaveInfo(val instanceId: String, val release: String, descriptor: ByteArray, attestation: ByteArray) {
    private val descriptor = descriptor.copyOf()
    private val attestation = attestation.copyOf()

    fun descriptor(): ByteArray = descriptor.copyOf()

    fun attestation(): ByteArray = attestation.copyOf()
}

/**
 * A response slot (§11.5): `queued`, `done` or `expired`; [envelope] only when done; [code] a host code. Only
 * `etk_unknown` means something to the app (re-seal, §11.9); any other code beside an envelope, such as
 * `recovery_registered` (0.10.6, §11.11.3: for the member API), is ignored and the sealed result decides.
 */
class Slot(val status: String, envelope: ByteArray?, val code: String?) {
    private val envelope = envelope?.copyOf()

    fun envelope(): ByteArray? = envelope?.copyOf()

    companion object {
        const val QUEUED = "queued"
        const val DONE = "done"
        const val EXPIRED = "expired"
        const val ETK_UNKNOWN = "etk_unknown"

        /** The host's clear marker on a successful `recovery_register` (0.10.6); the app ignores it. */
        const val RECOVERY_REGISTERED = "recovery_registered"
    }
}

/** `GET /api/vault/status` (advisory, never a security signal). */
data class VaultStatus(
    val vaultId: String,
    val state: String,
    val sealedRelease: String?,
    val leased: Boolean,
    val alarmKind: String?,
    val recoveryState: String?,
    val recoveryAvailableAt: String? = null,
    /** The sealed release from the routing table (W8); null while not sealed yet. */
    val release: ReleaseInfo? = null,
)

/**
 * `VaultStatus.release` (MEMBER-API "Vault", W8): advisory release status and
 * the in-app notice (`update_available`, `final_warning`, `ended`, `rescue`,
 * `unavailable`, or null). The app's own decisions come from the signed
 * manifest (§11.10.6); this is for display.
 */
data class ReleaseInfo(
    val number: Long?,
    val status: String,
    val endsAt: String?,
    val newestActive: Long?,
    val notice: String?,
) {
    companion object {
        const val NOTICE_UPDATE_AVAILABLE = "update_available"
        const val NOTICE_FINAL_WARNING = "final_warning"
        const val NOTICE_ENDED = "ended"
        const val NOTICE_RESCUE = "rescue"
        const val NOTICE_UNAVAILABLE = "unavailable"
    }
}

/**
 * `GET /api/vault/status`: the member's vault ([vault], null without one) and whether the operator paused the
 * vault service ([servicePaused], MEMBER-API 1.2.0 top-level `service`; absent means available).
 */
data class VaultStatusAnswer(val vault: VaultStatus?, val servicePaused: Boolean)

/** `POST /api/auth/verify` / `pin` outcome. */
enum class SignInStatus { SIGNED_IN, PIN_REQUIRED }

/** The parts of `Me` the app uses (MEMBER-API "Account"). */
data class Me(
    val userGuid: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val state: String,
    val termsNeedAcceptance: Boolean,
    val pinEnabled: Boolean,
)

/** `GET /api/vault/recovery` (MEMBER-API "Vault recovery"). */
data class RecoveryInfo(val recoveryId: String, val state: String, val availableAt: String, val expiresAt: String)

/** A sealed request ready to post (§11.3, §11.4, §11.11.3). */
class SealedRequest(val requestId: String, val etkKid: String, envelope: ByteArray, val manifestSha256: String?) {
    private val envelope = envelope.copyOf()

    fun envelope(): ByteArray = envelope.copyOf()
}

/**
 * The member API (MEMBER-API v1) as the app uses it: sign-in (magic link +
 * PIN), `Me`, the vault routes of the alternate channel and recovery. It
 * only ever sees opaque envelopes. [apiBase] is the account origin
 * (`https://account.vettid.org`); [manifestUrl] is where the release
 * manifest is served (`https://vettid.org/.well-known/vettid/pcr-manifest.json`).
 */
class MemberApiClient(
    apiBase: String,
    val manifestUrl: String,
    http: OkHttpClient,
    private val auth: MemberAuth,
    private val pollInterval: Duration = Duration.ofMillis(DEFAULT_POLL_MS),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val base = apiBase.trimEnd('/')
    private val http: OkHttpClient = if (auth is MemberAuth.Session) http.newBuilder().cookieJar(auth.cookies).build() else http

    private suspend fun call(method: String, path: String, body: String? = null, retryAuth: Boolean = true): JsonObject? {
        val rb = Request.Builder().url(base + path)
        auth.apply(rb, method)
        rb.method(method, body?.toRequestBody(JSON) ?: if (method == "POST") ByteArray(0).toRequestBody(null) else null)
        val (code, bytes, retryAfter) = execute(rb.build())
        val mayRefresh = auth is MemberAuth.Session && !path.startsWith("/api/auth/")
        if (code == HTTP_UNAUTHORIZED && retryAuth && mayRefresh) {
            // MEMBER-API: a 401 that survived the server-side renewal may try /api/auth/refresh once.
            val refreshed = try {
                call("POST", REFRESH, retryAuth = false)
                true
            } catch (_: MemberApiException) {
                false
            }
            if (refreshed) return call(method, path, body, retryAuth = false)
        }
        if (code / 100 != 2) throw errorOf(code, bytes, retryAfter)
        if (bytes.isEmpty()) return null
        return try {
            StrictJson.parseObject(bytes)
        } catch (_: CryptoException) {
            throw IOException("member API: malformed response")
        }
    }

    /** One HTTP exchange: status, body (at most [MAX_BODY] bytes) and the `Retry-After` header in seconds (0 if absent). */
    private class Answer(val code: Int, val body: ByteArray, val retryAfter: Int) {
        operator fun component1() = code

        operator fun component2() = body

        operator fun component3() = retryAfter
    }

    private suspend fun execute(r: Request): Answer {
        val call = http.newCall(r)
        return com.vettid.core.altchan.internal.awaitCall(call) { resp ->
            val body = resp.body.source().use { s ->
                s.request(MAX_BODY.toLong())
                s.buffer.readByteArray(minOf(s.buffer.size, MAX_BODY.toLong()))
            }
            Answer(resp.code, body, retryAfterHeader(resp.header("Retry-After")))
        }
    }

    private fun errorOf(code: Int, b: ByteArray, headerRetry: Int = 0): MemberApiException {
        val o = try {
            StrictJson.parseObject(b)
        } catch (_: CryptoException) {
            return MemberApiException(code, "http_$code", retryAfterSeconds = headerRetry)
        }
        fun s(k: String) = (o[k] as? JsonString)?.value
        val c = s("code") ?: s("error") ?: "http_$code"
        val retry = (o["retry_after"] as? JsonNumber)?.raw?.toIntOrNull()?.takeIf { it >= 0 } ?: headerRetry
        // `message` is the API's text for developers; `reason` (the operator's, MEMBER-API 1.2.0) is never read.
        return MemberApiException(code, c, s("message") ?: "", s("release"), minOf(retry, MAX_RETRY_AFTER_S), s("service"))
    }

    // --- sign-in (MEMBER-API "Auth") ---

    /** Asks for a sign-in link by email (always `{ok: true}`). */
    suspend fun authStart(email: String) {
        call("POST", "/api/auth/start", JsonBuilder().string("email", email).build())
    }

    /** Exchanges the link's token (from the URL fragment) for a session, or a PIN step. */
    suspend fun authVerify(email: String, token: String): SignInStatus =
        signIn(call("POST", "/api/auth/verify", JsonBuilder().string("email", email).string("token", token).build()))

    /** The account PIN step (not the vault PIN). */
    suspend fun authPin(pin: String): SignInStatus = signIn(call("POST", "/api/auth/pin", JsonBuilder().string("pin", pin).build()))

    private fun signIn(o: JsonObject?): SignInStatus = when (o?.optString("status")) {
        "signed_in" -> SignInStatus.SIGNED_IN
        "pin_required" -> SignInStatus.PIN_REQUIRED
        else -> throw IOException("member API: unexpected sign-in status")
    }

    suspend fun authRefresh() {
        call("POST", REFRESH, retryAuth = false)
    }

    /** Revokes the refresh token and clears the cookies. */
    suspend fun signOut() {
        try {
            call("POST", "/api/auth/signout", retryAuth = false)
        } finally {
            (auth as? MemberAuth.Session)?.cookies?.clear()
        }
    }

    suspend fun me(): Me {
        val o = call("GET", "/api/account/me") ?: throw IOException("member API: empty")
        val terms = o.optObj("terms")
        return Me(
            userGuid = o.string("user_guid"), email = o.optString("email") ?: "", firstName = o.optString("first_name") ?: "",
            lastName = o.optString("last_name") ?: "", state = o.optString("state") ?: "",
            termsNeedAcceptance = (terms?.get("needs_acceptance") as? JsonBool)?.value ?: false,
            pinEnabled = (o["pin_enabled"] as? JsonBool)?.value ?: false,
        )
    }

    // --- vault (alternate channel) ---

    suspend fun vaultStatus(): VaultStatus? = vaultStatusAnswer().vault

    /** `GET /api/vault/status` with the top-level `service` (MEMBER-API 1.2.0); still served while paused. */
    suspend fun vaultStatusAnswer(): VaultStatusAnswer {
        val o = call("GET", "/api/vault/status")
        val paused = (o?.get("service") as? JsonString)?.value == MemberApiException.SERVICE_PAUSED
        return VaultStatusAnswer(parseVault(o?.get("vault") as? JsonObject), paused)
    }

    private fun parseVault(v: JsonObject?): VaultStatus? {
        if (v == null) return null
        fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonString)?.value
        fun n(o: JsonObject?, k: String) = (o?.get(k) as? JsonNumber)?.raw?.toLongOrNull()
        val rel = v["release"] as? JsonObject
        val rec = v["recovery"] as? JsonObject
        return VaultStatus(
            vaultId = v.string("vault_id"), state = v.optString("state") ?: "", sealedRelease = s(v, "sealed_release"),
            leased = (v["leased"] as? JsonBool)?.value ?: false, alarmKind = s(v["alarm"] as? JsonObject, "kind"),
            recoveryState = s(rec, "state"), recoveryAvailableAt = s(rec, "available_at"),
            release = rel?.let {
                ReleaseInfo(n(it, "number"), s(it, "status") ?: "unknown", s(it, "ends_at"), n(it, "newest_active"), s(it, "notice"))
            },
        )
    }

    /** The instance to seal to; [release] (a PCR0) only to abandon an unconfirmed move (§11.10.4). */
    suspend fun enclave(release: String? = null): EnclaveInfo {
        val q = release?.let { "?release=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val o = call("GET", "/api/vault/enclave$q") ?: throw IOException("member API: empty")
        val d = o.string("descriptor")
        val a = o.string("attestation")
        return try {
            EnclaveInfo(o.string("instance_id"), o.optString("release") ?: "", Base64s.decodeStd(d), Base64s.decodeStd(a))
        } catch (_: CryptoException) {
            throw IOException("member API: malformed enclave answer")
        }
    }

    /**
     * [enclave], waiting while the release starts (503 `release_starting`, §11.10.5), for at most
     * [MAX_START_TOTAL_MS] in all; then the last `release_starting` is thrown. Any other error, including
     * `503 vault_unavailable` (no release yet, or the vault service paused, MEMBER-API 1.2.0), is thrown at
     * once: nothing retries it automatically.
     */
    suspend fun enclaveWait(release: String? = null): EnclaveInfo {
        var waited = 0L
        while (true) {
            try {
                return enclave(release)
            } catch (e: MemberApiException) {
                if (e.code != MemberApiException.RELEASE_STARTING || waited >= MAX_START_TOTAL_MS) throw e
                val ms = minOf(maxOf(e.retryAfterSeconds, 1) * MS_PER_S, MAX_START_WAIT_MS)
                sleep(ms)
                waited += ms
            }
        }
    }

    private fun posted(r: SealedRequest, vararg extra: Pair<String, String>): String {
        val b = JsonBuilder()
        extra.forEach { b.string(it.first, it.second) }
        b.string("request_id", r.requestId)
        return b
            .string("etk_kid", r.etkKid)
            .string("envelope", Base64s.encodeStd(r.envelope()))
            .also { jb -> r.manifestSha256?.let { jb.string("manifest_sha256", it) } }
            .build()
    }

    /** Posts a sealed vault.enroll; returns the vault id the API assigned. */
    suspend fun enroll(instanceId: String, r: SealedRequest): String {
        val body = JsonBuilder().string("request_id", r.requestId).string("instance_id", instanceId).string("etk_kid", r.etkKid)
            .string("envelope", Base64s.encodeStd(r.envelope())).string("manifest_sha256", r.manifestSha256 ?: "").build()
        return (call("POST", "/api/vault/enroll", body) ?: throw IOException("member API: empty")).string("vault_id")
    }

    /** Posts a sealed vault.unlock. */
    suspend fun unlock(vaultId: String, instanceId: String, r: SealedRequest) {
        val body = JsonBuilder().string("vault_id", vaultId).string("request_id", r.requestId).string("instance_id", instanceId)
            .string("etk_kid", r.etkKid).string("envelope", Base64s.encodeStd(r.envelope()))
            .string("manifest_sha256", r.manifestSha256 ?: "")
            .build()
        call("POST", "/api/vault/unlock", body)
    }

    /** Asks for a lock (no envelope). */
    suspend fun lock(vaultId: String, requestId: String) {
        call("POST", "/api/vault/lock", JsonBuilder().string("vault_id", vaultId).string("request_id", requestId).build())
    }

    /** One look at a response slot. */
    suspend fun slot(requestId: String): Slot {
        val o = call("GET", "/api/vault/requests/" + URLEncoder.encode(requestId, "UTF-8")) ?: throw IOException("member API: empty")
        val env = o.optString("envelope")?.takeIf { it.isNotEmpty() }?.let {
            try {
                Base64s.decodeStd(it)
            } catch (_: CryptoException) {
                throw IOException("member API: malformed slot envelope")
            }
        }
        return Slot(o.string("status"), env, o.optString("code"))
    }

    /** Polls a slot until it is no longer queued (the API allows 2 polls per second). */
    suspend fun poll(requestId: String): Slot {
        while (true) {
            val s = slot(requestId)
            if (s.status != Slot.QUEUED) return s
            sleep(pollInterval.toMillis())
        }
    }

    /** The served release manifest (§11.10.1). */
    suspend fun manifest(): ByteArray {
        val (code, b) = execute(Request.Builder().url(manifestUrl).get().build())
        if (code != HTTP_OK) throw MemberApiException(code, MemberApiException.MANIFEST_UNAVAILABLE)
        return b
    }

    // --- recovery (MEMBER-API "Vault recovery", VAULT-MESSAGING §11.11) ---

    suspend fun recoveryStatus(): RecoveryInfo? {
        val r = call("GET", "/api/vault/recovery")?.get("recovery") as? JsonObject ?: return null
        return RecoveryInfo(r.string("recovery_id"), r.string("state"), r.optString("available_at") ?: "", r.optString("expires_at") ?: "")
    }

    suspend fun recoveryCancel(recoveryId: String) {
        call("POST", "/api/vault/recovery/cancel", JsonBuilder().string("recovery_id", recoveryId).build())
    }

    /** Posts a sealed vault.recovery.register (no manifest_sha256). */
    suspend fun recoveryRegister(vaultId: String, instanceId: String, r: SealedRequest) {
        call("POST", "/api/vault/recovery/register", posted(r, "vault_id" to vaultId, "instance_id" to instanceId))
    }

    companion object {
        const val DEFAULT_POLL_MS = 600L
        const val PRODUCTION_API = "https://account.vettid.org"
        const val PRODUCTION_MANIFEST = "https://vettid.org/.well-known/vettid/pcr-manifest.json"

        /** The staging account site (it proxies the `/api/` routes) and manifest: the `staging` build type only. */
        const val STAGING_API = "https://account.staging.vettid.org"
        const val STAGING_MANIFEST = "https://staging.vettid.org/.well-known/vettid/pcr-manifest.json"
        const val MANIFEST_PATH = "/.well-known/vettid/pcr-manifest.json"
        private const val REFRESH = "/api/auth/refresh"
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val MAX_BODY = 1 shl 20
        private const val MS_PER_S = 1000L
        private const val MAX_START_WAIT_MS = 2000L

        /** How long [enclaveWait] waits for a release to start before it gives up (an instance starts in minutes). */
        const val MAX_START_TOTAL_MS = 10 * 60 * 1000L

        /** A larger `retry_after` / `Retry-After` is clamped to this (a day). */
        const val MAX_RETRY_AFTER_S = 86_400

        /** `Retry-After` in delta-seconds; an HTTP date or anything else counts as absent (0). */
        internal fun retryAfterHeader(v: String?): Int =
            v?.trim()?.toIntOrNull()?.takeIf { it >= 0 }?.let { minOf(it, MAX_RETRY_AFTER_S) } ?: 0
        private val JSON = "application/json".toMediaType()
    }
}
