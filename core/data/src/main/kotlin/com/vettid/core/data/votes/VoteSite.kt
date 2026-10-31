package com.vettid.core.data.votes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What one read of the public votes site (votes.vettid.org) came to (VAULT-MESSAGING §10.19.8). */
sealed interface SiteAnswer {
    /** HTTP 200 with a whole body. */
    class Found(val bytes: ByteArray) : SiteAnswer

    /** HTTP 404: the vault stamps an `absent` entry with its own clock; the app sends no time. */
    data object Absent : SiteAnswer

    /** No answer, a 5xx or any other status, a timeout, a TLS failure, or a truncated or oversized body. */
    data object Unreachable : SiteAnswer
}

/**
 * Reads from the public votes site (VOTES-SITE §3: `/v1/…`) with no session: no cookies, no identifier, no
 * authorization header (ANDROID-PLAN 0.1.102 "The app's work with the public site"). The site sees the phone's address and
 * the hour, and which documents are read.
 */
interface VoteSite {
    /** False when this build has no site (the dev stack): nothing is fetched and nothing is sent. */
    val configured: Boolean

    suspend fun get(path: String, maxBytes: Long = DEFAULT_MAX): SiteAnswer

    companion object {
        /** A proposal, its signature and bundle, a results document: far under this. */
        const val DEFAULT_MAX = 1L shl 20

        /** The service list's own limit: the encoded request body (VAULT-MESSAGING 0.81.2, `attachment_max_bytes`). */
        const val LIST_MAX = 8_257_532L
    }
}

/** [VoteSite] over OkHttp, for [base] (`https://votes.vettid.org`); [path] is under `/v1/`. */
class OkHttpVoteSite(base: String?, http: OkHttpClient) : VoteSite {
    private val root = base?.trimEnd('/')
    override val configured: Boolean get() = root != null

    // A client of its own: no cookie jar (OkHttp has none by default), no redirects off the site, short timeouts.
    private val client = http.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    @Suppress("ReturnCount", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
    override suspend fun get(path: String, maxBytes: Long): SiteAnswer {
        val url = root?.let { "$it/v1/${path.trimStart('/')}" } ?: return SiteAnswer.Unreachable
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().header("User-Agent", "VettID").header("Accept", "*/*").build()
                client.newCall(request).execute().use { r ->
                    when {
                        r.code == HTTP_NOT_FOUND -> SiteAnswer.Absent
                        r.code != HTTP_OK -> SiteAnswer.Unreachable
                        else -> {
                            val body = r.body
                            val declared = body.contentLength()
                            if (declared > maxBytes) return@use SiteAnswer.Unreachable
                            val bytes = body.source().use { s ->
                                val buf = okio.Buffer()
                                while (buf.size <= maxBytes) {
                                    if (s.read(buf, READ_CHUNK) < 0) break
                                }
                                buf
                            }
                            // Over the limit, or shorter than the body announced: a truncated body is no answer.
                            if (bytes.size > maxBytes || (declared >= 0 && bytes.size != declared)) {
                                SiteAnswer.Unreachable
                            } else {
                                SiteAnswer.Found(bytes.readByteArray())
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                SiteAnswer.Unreachable
            } catch (_: RuntimeException) {
                SiteAnswer.Unreachable
            }
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val CALL_TIMEOUT_S = 30L
        const val READ_CHUNK = 64L * 1024
    }
}
