package com.vettid.app.env

import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.KeystoreAttester
import com.vettid.core.altchan.MemberAuth
import com.vettid.core.altchan.SessionCookieJar
import com.vettid.core.data.Endpoints
import okhttp3.OkHttpClient

/**
 * Where the app talks to and what it trusts. Release and debug builds use
 * [ProductionEnvironment]; the debug-only `devStack` build type points the app
 * at the local dev stack (devstack/README.md) and is never part of a release.
 */
interface AppEnvironment {
    val name: String
    val endpoints: Endpoints

    /** Adds the environment's transport settings (the dev stack's address mapping) to [base]. */
    fun http(base: OkHttpClient): OkHttpClient

    /** The pinned Nitro root and manifest keys. */
    suspend fun trust(http: OkHttpClient): AltTrust

    /** The device attestation key (§11.7). */
    fun attester(): Attester

    /** How the member API knows the member. */
    fun memberAuth(cookies: SessionCookieJar): MemberAuth
}

/** account.vettid.org, relay.vettid.org, the pinned production anchors, the Keystore attestation key. */
object ProductionEnvironment : AppEnvironment {
    override val name = "production"
    override val endpoints = Endpoints.PRODUCTION

    override fun http(base: OkHttpClient): OkHttpClient = base

    override suspend fun trust(http: OkHttpClient): AltTrust = AltTrust.production()

    override fun attester(): Attester = KeystoreAttester()

    override fun memberAuth(cookies: SessionCookieJar): MemberAuth = MemberAuth.Session(cookies)
}
