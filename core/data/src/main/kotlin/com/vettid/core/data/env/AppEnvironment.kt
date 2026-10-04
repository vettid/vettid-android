package com.vettid.core.data.env

import android.content.Context
import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Attester
import com.vettid.core.data.Endpoints
import com.vettid.core.data.account.AccountGateway
import okhttp3.OkHttpClient

/**
 * Where the app talks to and what it trusts. Release and debug builds use the
 * production environment (in `:app`); the debug-only `devStack` build type
 * points the app at the local dev stack (devstack/README.md) and is never part
 * of a release.
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

    /** Sign-in, `Me` and the member API client for the vault routes. */
    fun accountGateway(context: Context, http: OkHttpClient): AccountGateway
}
