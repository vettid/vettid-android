package com.vettid.core.data.env

import android.content.Context
import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.KeystoreAppKeySigner
import com.vettid.core.data.Endpoints
import com.vettid.core.data.account.AppKeyMemberGateway
import com.vettid.core.data.account.MemberGateway
import com.vettid.core.keystore.AppApiKey
import okhttp3.OkHttpClient

/**
 * Where the app talks to and what it trusts. Release and debug builds use the
 * production environment (in `:app`); the `staging` build type uses staging
 * (account.staging.vettid.org, the staging manifest key); the debug-only
 * `devStack` build type points the app at the local dev stack
 * (devstack/README.md). Neither is ever part of a release.
 */
interface AppEnvironment {
    val name: String
    val endpoints: Endpoints

    /** True only in the debug-only `devStack` build (development hints in the UI). */
    val isDevStack: Boolean get() = false

    /** Adds the environment's transport settings (the dev stack's address mapping) to [base]. */
    fun http(base: OkHttpClient): OkHttpClient

    /** The pinned Nitro root and manifest keys. */
    suspend fun trust(http: OkHttpClient): AltTrust

    /** The device attestation key (§11.7). */
    fun attester(): Attester

    /**
     * The member API, every request signed by the app key (VAULT-MESSAGING §11.12.2) for the vault [vaultId]
     * names: the Keystore app key in production and staging.
     */
    fun memberGateway(context: Context, http: OkHttpClient, vaultId: () -> String?): MemberGateway {
        val key = AppApiKey()
        return AppKeyMemberGateway(endpoints.apiBase, endpoints.manifestUrl, http, KeystoreAppKeySigner(key), { key.regenerate() }, vaultId)
    }
}
