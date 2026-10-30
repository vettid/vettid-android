package com.vettid.core.relay

import okhttp3.Authenticator
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

/**
 * Adds [FixedUserAgent] as an application interceptor. OkHttp builds the proxy tunnel's `CONNECT` itself with
 * `User-Agent: okhttp/x.y.z`, and no interceptor sees it; a proxy authenticator is asked about that request first
 * (preemptively, challenge `OkHttp-Preemptive`), so it replaces the value there. A [proxyAuthenticator] already
 * wanted is kept, and asked for every other challenge.
 */
fun OkHttpClient.Builder.fixedUserAgent(proxyAuthenticator: Authenticator = Authenticator.NONE): OkHttpClient.Builder =
    addInterceptor(FixedUserAgent).proxyAuthenticator(fixedUserAgentTunnel(proxyAuthenticator))

/** The preemptive-CONNECT rewrite of [fixedUserAgent], with [other] answering real proxy challenges. */
internal fun fixedUserAgentTunnel(other: Authenticator): Authenticator = Authenticator { route, response ->
    if (response.header("Proxy-Authenticate") == "OkHttp-Preemptive") {
        response.request.newBuilder().header("User-Agent", FixedUserAgent.VALUE).build()
    } else {
        other.authenticate(route, response)
    }
}
