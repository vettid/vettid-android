package com.vettid.app.env

import android.content.Context
import com.vettid.app.BuildConfig
import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.KeystoreAttester
import com.vettid.core.data.Endpoints
import com.vettid.core.data.account.AccountGateway
import com.vettid.core.data.env.AppEnvironment
import com.vettid.core.relay.OriginMapInterceptor
import com.vettid.core.testing.DevStack
import okhttp3.OkHttpClient

/**
 * DEVELOPMENT ONLY (`devStack` build type, never in a release): the local
 * dev stack through `adb reverse` (devstack/README.md). The relay URL that
 * tokens name, https://relay.vettid.test, is mapped to the relay's
 * plain-HTTP port; the trust anchors are the stack's TEST-ONLY Nitro root and
 * manifest key. Device attestation is the phone's REAL Keystore key
 * (StrongBox): the stack runs with devstack/device-policy.json, which adds
 * Google's attestation roots, this build's package and the debug signing
 * digest to the dev enclave's policy. The member API stand-in has no sign-in
 * or account routes, so [DevAccountGateway] simulates them in the app.
 */
internal val currentEnvironment: AppEnvironment = object : AppEnvironment {
    private val stack = DevStack(BuildConfig.DEV_STACK_API, BuildConfig.DEV_STACK_RELAY, BuildConfig.DEV_STACK_CTL)

    override val name = "devStack"
    override val endpoints = Endpoints(stack.apiBase, stack.manifestUrl, stack.relayUrl)
    override val isDevStack = true

    override fun http(base: OkHttpClient): OkHttpClient =
        base.newBuilder().addInterceptor(OriginMapInterceptor(mapOf(stack.relayUrl to BuildConfig.DEV_STACK_RELAY))).build()

    override suspend fun trust(http: OkHttpClient): AltTrust = stack.trust()

    override fun attester(): Attester = KeystoreAttester()

    override fun accountGateway(context: Context, http: OkHttpClient): AccountGateway =
        DevAccountGateway(context, endpoints.apiBase, endpoints.manifestUrl, http)
}
