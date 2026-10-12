package com.vettid.core.altchan

import com.vettid.core.relay.RequestSigner
import okhttp3.HttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * How requests to the member API are authenticated.
 *
 * - [AppKey]: the production model since MEMBER-API 2.0.0 (VAULT-MESSAGING §11.12.2): the app never signs in;
 *   every request carries `X-VettID-App`, signed by the app key, and names the vault ([vaultId], empty before
 *   one is known: a redeem). No cookies, no CSRF header.
 * - [Bearer]: the local dev stack's member API stand-in (vettid-vault `internal/memberapitest`), which names the
 *   member by `Authorization: Bearer <user_guid>`. Development and tests only.
 */
sealed class MemberAuth {
    /** Adds the authentication to [b] for a request to [url] with [body] (empty for a GET); [vaultOverride] names the vault. */
    internal abstract fun apply(b: Request.Builder, method: String, url: HttpUrl, body: ByteArray, vaultOverride: String?)

    /**
     * Each request carries a signature with a timestamp and a single-use nonce (MEMBER-API "App request signing": a
     * nonce seen twice within 600 s is `401`), so a request is never sent twice as it is: every attempt is signed anew.
     */
    internal open val signedPerRequest: Boolean get() = false

    class AppKey(private val signer: AppKeySigner, private val vaultId: () -> String?) : MemberAuth() {
        override val signedPerRequest: Boolean get() = true

        /**
         * The header, and a [RequestSigner] as the request's tag: the app's transport retry
         * (`com.vettid.core.relay.TransportRetry`) asks it for the header of every attempt, so that a request re-sent
         * after a dropped connection carries a new timestamp and nonce instead of the ones the API may already have
         * seen (A6 audit N4: the replay was answered `401`, which the unlock screen read as "this phone was
         * replaced"). The first attempt uses the header made here; no request is signed twice for nothing.
         */
        override fun apply(b: Request.Builder, method: String, url: HttpUrl, body: ByteArray, vaultOverride: String?) {
            val vault = vaultOverride ?: vaultId()
            fun sign() = AppRequestSigning.header(signer, method, url.encodedPath, url.encodedQuery, vault, body)
            val first = AtomicReference<String?>(sign())
            b.header(AppRequestSigning.HEADER, first.get().orEmpty())
            b.tag(RequestSigner::class.java, RequestSigner { mapOf(AppRequestSigning.HEADER to (first.getAndSet(null) ?: sign())) })
        }
    }

    class Bearer(private val userGuid: String) : MemberAuth() {
        override fun apply(b: Request.Builder, method: String, url: HttpUrl, body: ByteArray, vaultOverride: String?) {
            b.header("Authorization", "Bearer $userGuid")
        }
    }
}
