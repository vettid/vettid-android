package com.vettid.core.relay

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Every HTTP request the app makes itself carries `User-Agent: VettID-Android`, with no version, model or OS
 * details, in place of OkHttp's `okhttp/x.y.z` (VAULT-MESSAGING §13.6). An application interceptor, not a network one:
 * OkHttp skips network interceptors for WebSocket handshakes, and an application interceptor's header survives
 * the bridge step that would add `okhttp/x.y.z` and the redirect follow-ups. It replaces any value set earlier.
 * Clients derived with `newBuilder()` keep it.
 */
object FixedUserAgent : Interceptor {
    const val VALUE = "VettID-Android"

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", VALUE).build())
}

/** Adds [FixedUserAgent] as an application interceptor. */
fun OkHttpClient.Builder.fixedUserAgent(): OkHttpClient.Builder = addInterceptor(FixedUserAgent)
