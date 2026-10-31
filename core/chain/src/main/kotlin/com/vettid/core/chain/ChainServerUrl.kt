package com.vettid.core.chain

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * An Esplora server's base URL (ANDROID-PLAN §4 "The chain source"), such as `https://mempool.space/signet/api`. Made only by
 * [parse] (what the member types: `https` only, no credentials, no query or fragment), by the built-in defaults, or by the
 * development build's regtest default ([loopbackOrNull]). Its [toString] names the host only: the path is never logged.
 */
class ChainServerUrl private constructor(internal val url: HttpUrl) {
    /** The URL as stored and as the setting shows it (no trailing slash). */
    val text: String get() = url.toString().trimEnd('/')

    /** The host, for "Using mempool.space". */
    val host: String get() = url.host

    override fun equals(other: Any?): Boolean = other is ChainServerUrl && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = "ChainServerUrl($host)"

    companion object {
        /** Longer than any server's base URL. */
        const val MAX_LENGTH = 2048

        /** The rules of "Use my own server": an `https` Esplora URL, no user name or password, no query or fragment. */
        fun parse(text: String): UrlCheck {
            val t = text.trim()
            if (t.isEmpty() || t.length > MAX_LENGTH || t.any { it.isWhitespace() || it.isISOControl() }) {
                return UrlCheck.Refused(UrlProblem.NOT_A_URL)
            }
            val scheme = t.substringBefore("://", missingDelimiterValue = "").lowercase()
            if (scheme.isEmpty()) return UrlCheck.Refused(UrlProblem.NOT_A_URL)
            if (scheme != "https") return UrlCheck.Refused(UrlProblem.NOT_HTTPS)
            // `https://@host` parses with an empty user name: any '@' before the path is credentials.
            val authority = t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            if ('@' in authority) return UrlCheck.Refused(UrlProblem.CREDENTIALS)
            val url = t.toHttpUrlOrNull() ?: return UrlCheck.Refused(UrlProblem.NOT_A_URL)
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return UrlCheck.Refused(UrlProblem.CREDENTIALS)
            if (url.query != null || url.fragment != null) return UrlCheck.Refused(UrlProblem.QUERY_OR_FRAGMENT)
            return UrlCheck.Ok(ChainServerUrl(normalised(url)))
        }

        /**
         * A development build's regtest server (devStack's build config): `http` or `https` to a loopback host only, reached
         * through `adb reverse`. Null for anything else, and for the empty value every other build has.
         */
        fun loopbackOrNull(text: String?): ChainServerUrl? {
            val url = text?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return null
            if (url.host !in LOOPBACK || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.query != null || url.fragment != null) return null
            return ChainServerUrl(normalised(url))
        }

        /** A built-in default, checked like a typed URL. */
        internal fun builtIn(text: String): ChainServerUrl = (parse(text) as UrlCheck.Ok).url

        /** Tests only: a local mock server, whatever its scheme. */
        internal fun forTest(url: HttpUrl): ChainServerUrl = ChainServerUrl(normalised(url))

        private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")

        /** Without a trailing empty path segment, so that `…/api` and `…/api/` are one server. */
        private fun normalised(url: HttpUrl): HttpUrl {
            val b = url.newBuilder()
            var segments = url.pathSegments
            while (segments.size > 1 && segments.last().isEmpty()) {
                b.removePathSegment(segments.size - 1)
                segments = segments.dropLast(1)
            }
            return b.build()
        }
    }
}

/** Why a typed server URL is refused before anything is sent. */
enum class UrlProblem {
    /** Not a URL, or longer than [ChainServerUrl.MAX_LENGTH]. */
    NOT_A_URL,

    /** `http` or another scheme: only `https` is allowed. */
    NOT_HTTPS,

    /** A user name or password in the URL. */
    CREDENTIALS,

    /** A `?query` or `#fragment`: an Esplora base URL has neither. */
    QUERY_OR_FRAGMENT,
}

/** The result of [ChainServerUrl.parse]. */
sealed interface UrlCheck {
    data class Ok(val url: ChainServerUrl) : UrlCheck

    data class Refused(val problem: UrlProblem) : UrlCheck
}
