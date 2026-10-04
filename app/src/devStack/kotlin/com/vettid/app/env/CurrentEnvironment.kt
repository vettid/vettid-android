package com.vettid.app.env

import com.vettid.app.BuildConfig
import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.MemberAuth
import com.vettid.core.altchan.SessionCookieJar
import com.vettid.core.data.Endpoints
import com.vettid.core.relay.OriginMapInterceptor
import com.vettid.core.testing.DevStack
import com.vettid.core.testing.TestAndroidAttester
import okhttp3.OkHttpClient

/**
 * DEVELOPMENT ONLY (`devStack` build type, never in a release): the local
 * dev stack through `adb reverse` (devstack/README.md). The relay URL that
 * tokens name, https://relay.vettid.test, is mapped to the relay's
 * plain-HTTP port; the trust anchors are the stack's TEST-ONLY Nitro root and
 * manifest key; device attestation uses the stack's TEST attestation CA,
 * because the dev enclave pins only that CA; the member API stand-in names
 * the member by a bearer user_guid.
 */
internal val currentEnvironment: AppEnvironment = object : AppEnvironment {
    private val stack = DevStack(BuildConfig.DEV_STACK_API, BuildConfig.DEV_STACK_RELAY, BuildConfig.DEV_STACK_CTL)

    override val name = "devStack"
    override val endpoints = Endpoints(stack.apiBase, stack.manifestUrl, stack.relayUrl)

    override fun http(base: OkHttpClient): OkHttpClient =
        base.newBuilder().addInterceptor(OriginMapInterceptor(mapOf(stack.relayUrl to BuildConfig.DEV_STACK_RELAY))).build()

    override suspend fun trust(http: OkHttpClient): AltTrust = stack.trust()

    override fun attester(): Attester = TestAndroidAttester()

    override fun memberAuth(cookies: SessionCookieJar): MemberAuth = MemberAuth.Bearer(BuildConfig.DEV_STACK_GUID)
}
