package com.vettid.core.relay

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends requests for one origin to another, keeping path and query: the
 * local dev stack's mapping of `https://relay.vettid.test` (the relay URL
 * that tokens name, `aud`) to the relay's plain-HTTP port reached through
 * `adb reverse`. Development builds and tests only; a release build never
 * installs it. Request signatures cover method, path and body, not the
 * host (RELAY-PROTOCOL §4.1), so they are unaffected.
 */
class OriginMapInterceptor(map: Map<String, String>) : Interceptor {
    private val map: Map<String, HttpUrl> = map.entries.associate { (from, to) -> from.trimEnd('/') to to.toHttpUrl() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val u = req.url
        val origin = "${u.scheme}://${u.host}" + if (u.port != HttpUrl.defaultPort(u.scheme)) ":${u.port}" else ""
        val to = map[origin] ?: return chain.proceed(req)
        val url = u.newBuilder().scheme(to.scheme).host(to.host).port(to.port).build()
        return chain.proceed(req.newBuilder().url(url).build())
    }
}
