package com.vettid.core.map

import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Where the map may reach. The note told the member one server, so the map reaches that server and nothing else: **https only**,
 * and only the noted server's host and port ([allowed], the authority of the style address). Every network request the SDK makes
 * goes through [MapNetwork]'s client, which refuses any other host (a sprite, glyphs, tiles or a TileJSON the style names,
 * `mapbox:` or `maptiler:` after the SDK turns them into api.mapbox.com or api.maptiler.com) and follows no redirect. The SDK's
 * resource transform sees only the online source, so `file:` and `asset:` addresses in a style are NOT stopped by it: MapLibre
 * could read a local or packaged file to draw it, with nothing sent anywhere. Pure, so it is unit-tested without the SDK.
 */
object MapRequestPolicy {
    /** A generic header: nothing that tells the tile server a VettID member is looking at a location. */
    const val USER_AGENT = "Mozilla/5.0"

    /** What a refused address is rewritten to: an https address nothing answers, so the client's rule ends it. */
    const val REFUSED = "https://refused.invalid/"

    private const val HTTPS_PORT = 443

    /** The one authority the map may reach (`host` or `host:port` when the port is not 443), set before any map exists. */
    @Volatile
    var allowed: String? = null

    /** `host`, or `host:port` for a port other than 443; null when [url] is not an https address with a host. */
    fun authorityOf(url: String): String? = url.trim().toHttpUrlOrNull()?.let { authorityOf(it) }

    fun authorityOf(url: HttpUrl): String? {
        if (!url.isHttps) return null
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return if (url.port == HTTPS_PORT) host else "$host:${url.port}"
    }

    fun allows(url: HttpUrl, only: String? = allowed): Boolean = only != null && authorityOf(url) == only

    fun allows(url: String, only: String? = allowed): Boolean = only != null && authorityOf(url) == only

    /** The resource transform of the SDK: an allowed address passes, everything else becomes [REFUSED]. */
    fun transform(url: String, only: String? = allowed): String = if (allows(url, only)) url else REFUSED
}

/**
 * The client the SDK loads with. It derives from the app's shared client ([install], from AppModule: its pool, network gate and
 * retry stay), clears the cache, follows no redirect, and adds the rule above and a generic User-Agent **after** the shared
 * client's own interceptors, so "Mozilla/5.0" is what goes out and not the app's. Without an installed client there is none:
 * the set-up fails, and a fresh client is never made.
 */
object MapNetwork {
    private const val PER_HOST = 20

    @Volatile
    private var base: OkHttpClient? = null

    /** Gives the map the app's shared client. */
    fun install(shared: OkHttpClient) {
        base = shared
    }

    /** The map's client, or an [IllegalStateException] when [install] has not been called. */
    fun client(only: () -> String? = { MapRequestPolicy.allowed }): OkHttpClient =
        derive(checkNotNull(base) { "no shared client installed" }, only)

    fun derive(shared: OkHttpClient, only: () -> String? = { MapRequestPolicy.allowed }): OkHttpClient = shared.newBuilder()
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = PER_HOST })
        .addInterceptor(Interceptor { chain -> proceed(chain, only()) })
        .build()

    private fun proceed(chain: Interceptor.Chain, only: String?): Response {
        val request = chain.request()
        // UnknownServiceException: the shared client's TransportRetry never retries it, so a refused address is not tried again.
        if (!MapRequestPolicy.allows(request.url, only)) throw java.net.UnknownServiceException("refused")
        return chain.proceed(request.newBuilder().header("User-Agent", MapRequestPolicy.USER_AGENT).build())
    }
}
