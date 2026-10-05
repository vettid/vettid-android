package com.vettid.app.env

import android.content.Context
import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.KeystoreAttester
import com.vettid.core.data.Endpoints
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.data.account.AccountGateway
import com.vettid.core.data.account.SessionAccountGateway
import com.vettid.core.data.env.AppEnvironment
import okhttp3.OkHttpClient
import java.io.File
import java.net.URI

/**
 * STAGING ONLY (`staging` build type, never in a release): the staging vault
 * service. The member API through account.staging.vettid.org (the account
 * site proxies the `/api/` routes), the staging manifest at staging.vettid.org pinned
 * to the staging channel's key A, the real AWS Nitro root and the production
 * relay (the staging images pin it). Device attestation is the phone's real
 * Keystore key; staging images accept it only from `com.vettid.app` signed
 * with the staging key (vettid-vault enclave/releasecfg/staging.json).
 * Sign-in links are accepted from the staging account site only.
 */
internal val currentEnvironment: AppEnvironment = object : AppEnvironment {
    override val name = "staging"
    override val endpoints = Endpoints.STAGING
    override val signInHosts: Set<String> = setOf(URI(endpoints.apiBase).host)

    override fun http(base: OkHttpClient): OkHttpClient = base

    override suspend fun trust(http: OkHttpClient): AltTrust = AltTrust.staging()

    override fun attester(): Attester = KeystoreAttester()

    override fun accountGateway(context: Context, http: OkHttpClient): AccountGateway {
        val cookies = KeystoreFileStore(File(context.noBackupFilesDir, "member-session.bin"), "member-session").asCookiePersistence()
        return SessionAccountGateway(endpoints.apiBase, endpoints.manifestUrl, http, cookies)
    }
}
