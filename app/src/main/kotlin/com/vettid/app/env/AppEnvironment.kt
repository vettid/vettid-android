package com.vettid.app.env

import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.KeystoreAttester
import com.vettid.core.data.Endpoints
import com.vettid.core.data.env.AppEnvironment
import okhttp3.OkHttpClient

/**
 * account.vettid.org, relay.vettid.org, the pinned production anchors, the
 * Keystore attestation key, and the Keystore app key that signs every member
 * API request (no sign-in, MEMBER-API 2.0.0). Release and debug builds use it;
 * the debug-only `devStack` build type points the app at the local dev stack instead.
 */
object ProductionEnvironment : AppEnvironment {
    override val name = "production"
    override val endpoints = Endpoints.PRODUCTION

    override fun http(base: OkHttpClient): OkHttpClient = base

    override suspend fun trust(http: OkHttpClient): AltTrust = AltTrust.production()

    override fun attester(): Attester = KeystoreAttester()
}
