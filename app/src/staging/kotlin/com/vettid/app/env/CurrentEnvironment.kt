package com.vettid.app.env

import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.KeystoreAttester
import com.vettid.core.data.Endpoints
import com.vettid.core.data.env.AppEnvironment
import okhttp3.OkHttpClient

/**
 * STAGING ONLY (`staging` build type, never in a release): the staging vault
 * service. The member API through account.staging.vettid.org (the account
 * site proxies the `/api/` routes), the staging manifest at staging.vettid.org pinned
 * to the staging channel's key A, the real AWS Nitro root and the production
 * relay (the staging images pin it). Device attestation is the phone's real
 * Keystore key; staging images accept it only from `com.vettid.app` signed
 * with the staging key (vettid-vault enclave/releasecfg/staging.json).
 * Setup codes and their links are accepted from the staging account site only.
 */
internal val currentEnvironment: AppEnvironment = object : AppEnvironment {
    override val name = "staging"
    override val endpoints = Endpoints.STAGING

    override fun http(base: OkHttpClient): OkHttpClient = base

    override suspend fun trust(http: OkHttpClient): AltTrust = AltTrust.staging()

    override fun attester(): Attester = KeystoreAttester()
}
