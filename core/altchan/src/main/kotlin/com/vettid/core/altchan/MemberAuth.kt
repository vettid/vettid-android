package com.vettid.core.altchan

import okhttp3.HttpUrl
import okhttp3.Request

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

    class AppKey(private val signer: AppKeySigner, private val vaultId: () -> String?) : MemberAuth() {
        override fun apply(b: Request.Builder, method: String, url: HttpUrl, body: ByteArray, vaultOverride: String?) {
            val vault = vaultOverride ?: vaultId()
            b.header(AppRequestSigning.HEADER, AppRequestSigning.header(signer, method, url.encodedPath, url.encodedQuery, vault, body))
        }
    }

    class Bearer(private val userGuid: String) : MemberAuth() {
        override fun apply(b: Request.Builder, method: String, url: HttpUrl, body: ByteArray, vaultOverride: String?) {
            b.header("Authorization", "Bearer $userGuid")
        }
    }
}
