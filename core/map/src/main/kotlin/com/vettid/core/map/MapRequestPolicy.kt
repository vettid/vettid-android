package com.vettid.core.map

import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/**
 * Where the map may reach. The note told the member one server, so the map reaches that server and nothing else: **https only**,
 * and only the noted server's host and port ([allowed], the authority of the style address). A style or a TileJSON that names
 * another host (a sprite, glyphs, tiles), `mapbox:` or `maptiler:` (which the SDK turns into api.mapbox.com or api.maptiler.com),
 * `file:` or `asset:` gets nothing; a redirect is not followed. Pure, so it is unit-tested without the SDK.
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
    fun authorityOf(url: String): String? {
        val uri = try {
            URI(url.trim())
        } catch (_: URISyntaxException) {
            return null
        }
        val host = uri.host?.lowercase()
        return when {
            !"https".equals(uri.scheme, ignoreCase = true) || host.isNullOrEmpty() -> null
            uri.port == -1 || uri.port == HTTPS_PORT -> host
            else -> "$host:${uri.port}"
        }
    }

    fun allows(url: String, only: String? = allowed): Boolean = only != null && authorityOf(url) == only

    /** The resource transform of the SDK: an allowed address passes, everything else becomes [REFUSED]. */
    fun transform(url: String, only: String? = allowed): String = if (allows(url, only)) url else REFUSED
}

/** The client the SDK loads with: the rule above on every request, no redirects, no cache, a generic User-Agent. */
object MapNetwork {
    private const val PER_HOST = 20

    fun client(only: () -> String? = { MapRequestPolicy.allowed }): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = PER_HOST })
        .addInterceptor(Interceptor { chain -> proceed(chain, only()) })
        .build()

    private fun proceed(chain: Interceptor.Chain, only: String?): Response {
        val request = chain.request()
        if (!MapRequestPolicy.allows(request.url.toString(), only)) throw IOException("refused")
        return chain.proceed(request.newBuilder().header("User-Agent", MapRequestPolicy.USER_AGENT).build())
    }
}
