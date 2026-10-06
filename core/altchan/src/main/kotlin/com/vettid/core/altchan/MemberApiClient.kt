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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    /** 2.1.0: whether the vault keeps a backup copy of its credential (false: it cannot be recovered); null: not reported. */
    val credentialBackup: Boolean? = null,
    /** 2.1.0: a start-over requested on the portal (VAULT-MESSAGING §11.11.9), until it executes or is cancelled. */
    val deletion: PendingDeletion? = null,
)

/**
 * `VaultStatus.deletion` (MEMBER-API 2.1.0, `deletion_id` since 2.1.1): `state` `pending` or `executing`, when the
 * vault is deleted, and the id the app's cancel names (null from an API before 2.1.1: the app links to the portal).
 */
data class PendingDeletion(val state: String, val deletesAt: String, val deletionId: String? = null)

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

/** `POST /api/vault/enroll/redeem`'s answer (§11.12.1): the member's vault, the member, and a masked email to show. */
data class Redeemed(val vaultId: String, val userGuid: String, val emailHint: String)

/** `POST /api/vault/recovery/claim`'s answer (§11.11.7): the member (for the sealed register and unlock) and a masked email. */
data class Claimed(val userGuid: String, val emailHint: String)

/** A sealed request ready to post (§11.3, §11.4, §11.11.3). */
class SealedRequest(val requestId: String, val etkKid: String, envelope: ByteArray, val manifestSha256: String?) {
    private val envelope = envelope.copyOf()

    fun envelope(): ByteArray = envelope.copyOf()
}

/**
 * The member API (MEMBER-API 2.0.0) as the app uses it: the setup-code redeem,
 * the vault routes of the alternate channel and the recovery claim and register,
 * every request signed by the app key ([MemberAuth.AppKey]; the app never signs
 * in, VAULT-MESSAGING §11.12). It only ever sees opaque envelopes. [apiBase] is the account origin
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
    private val http: OkHttpClient = http

    /** One signed request; [vault] names the vault in the signature header instead of the client's (a redeem: ""). */
    private suspend fun call(method: String, path: String, body: String? = null, vault: String? = null): JsonObject? {
        val url = (base + path).toHttpUrl()
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val rb = Request.Builder().url(url)
        auth.apply(rb, method, url, bytes, vault)
        rb.method(method, if (body != null) bytes.toRequestBody(JSON) else if (method == "POST") bytes.toRequestBody(null) else null)
        val (code, resp, retryAfter) = execute(rb.build())
        if (code / 100 != 2) throw errorOf(code, resp, retryAfter)
        val bytesIn = resp
        if (bytesIn.isEmpty()) return null
        return try {
            StrictJson.parseObject(bytesIn)
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

    // --- setup codes (MEMBER-API 2.0.0 "Setup codes", VAULT-MESSAGING §11.12.1) ---

    /**
     * Redeems a scanned setup QR's secret ([secret], 22 characters base64url) with this app's key ([appKey], SPKI
     * DER), signed by that key with an empty `vault`. Any failure of the code itself is `404 invalid_code`.
     */
    suspend fun redeemSecret(secret: String, appKey: ByteArray): Redeemed =
        redeemed(call("POST", REDEEM, JsonBuilder().string("secret", secret).base64("app_key", appKey).build(), vault = ""))

    /** Redeems a typed setup code with the member's email ([code] normalised: 8 symbols, no hyphen). */
    suspend fun redeemTyped(email: String, code: String, appKey: ByteArray): Redeemed = redeemed(
        call("POST", REDEEM, JsonBuilder().string("email", email).string("code", code).base64("app_key", appKey).build(), vault = ""),
    )

    private fun redeemed(o: JsonObject?): Redeemed {
        val r = o ?: throw IOException("member API: empty")
        return Redeemed(r.string("vault_id"), r.string("user_guid"), r.optString("email_hint") ?: "")
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
            credentialBackup = (v["credential_backup"] as? JsonBool)?.value,
            deletion = (v["deletion"] as? JsonObject)?.let { d ->
                val at = s(d, "deletes_at") ?: return@let null
                PendingDeletion(s(d, "state") ?: "pending", at, s(d, "deletion_id"))
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

    /** Posts a sealed vault.enroll for [vaultId] (the redeem's, MEMBER-API 2.0.0); returns the vault id. */
    suspend fun enroll(vaultId: String, instanceId: String, r: SealedRequest): String {
        val body = JsonBuilder().string("vault_id", vaultId).string("request_id", r.requestId).string("instance_id", instanceId)
            .string("etk_kid", r.etkKid)
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

    /**
     * Claims the recovery of the scanned QR with this app's key (§11.11.7, 0.15.0), signed by that key with the QR's
     * `vault`. `409 recovery_not_available` unless the recovery is `available`; `404` for an unknown pair.
     */
    suspend fun recoveryClaim(vaultId: String, recoveryId: String, appKey: ByteArray): Claimed {
        val body = JsonBuilder().string("vault_id", vaultId).string("recovery_id", recoveryId).base64("app_key", appKey).build()
        val o = call("POST", "/api/vault/recovery/claim", body, vault = vaultId) ?: throw IOException("member API: empty")
        return Claimed(o.string("user_guid"), o.optString("email_hint") ?: "")
    }

    /**
     * Cancels a pending start-over (`POST /api/vault/deletion/cancel {deletion_id}`, MEMBER-API 2.1.0), signed by the
     * vault's app key. True when it was cancelled; false once it is executing.
     */
    suspend fun deletionCancel(deletionId: String): Boolean {
        val o = call("POST", "/api/vault/deletion/cancel", JsonBuilder().string("deletion_id", deletionId).build())
        return (o?.get("cancelled") as? JsonBool)?.value ?: false
    }

    /** Posts a sealed vault.recovery.register (no manifest_sha256). */
    suspend fun recoveryRegister(vaultId: String, instanceId: String, r: SealedRequest) {
        call("POST", "/api/vault/recovery/register", posted(r, "vault_id" to vaultId, "instance_id" to instanceId), vault = vaultId)
    }

    companion object {
        const val DEFAULT_POLL_MS = 600L
        const val PRODUCTION_API = "https://account.vettid.org"
        const val PRODUCTION_MANIFEST = "https://vettid.org/.well-known/vettid/pcr-manifest.json"

        /** The staging account site (it proxies the `/api/` routes) and manifest: the `staging` build type only. */
        const val STAGING_API = "https://account.staging.vettid.org"
        const val STAGING_MANIFEST = "https://staging.vettid.org/.well-known/vettid/pcr-manifest.json"
        const val MANIFEST_PATH = "/.well-known/vettid/pcr-manifest.json"
        private const val REDEEM = "/api/vault/enroll/redeem"
        private const val HTTP_OK = 200
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
