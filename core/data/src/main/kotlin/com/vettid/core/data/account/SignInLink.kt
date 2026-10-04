package com.vettid.core.data.account

import java.net.URI
import java.net.URLDecoder

/**
 * A sign-in link (MEMBER-API "Auth"):
 * `https://account.vettid.org/auth/#t=<token>&e=<email>`. The token travels in
 * the fragment. The app receives the link through its App Link on
 * account.vettid.org `/auth/`, or the member pastes the link (or the token
 * alone) when App Links cannot be verified.
 */
data class SignInLink(val token: String, val email: String?) {
    override fun toString(): String = "SignInLink(email=$email)"

    companion object {
        const val HOST = "account.vettid.org"
        const val PATH = "/auth/"
        private val TOKEN_RE = Regex("^[A-Za-z0-9_.~-]{16,512}$")
        private const val MAX_INPUT = 4096

        /**
         * Parses a pasted or opened link, or a bare token. [allowedHosts] are the
         * hosts whose `/auth/` links are accepted (the production account host,
         * plus the dev stack's in devStack builds). Returns null if the input is
         * neither.
         */
        @Suppress("ReturnCount", "ComplexCondition") // one rejection per rule of the link format
        fun parse(input: String, allowedHosts: Set<String> = setOf(HOST)): SignInLink? {
            val s = input.trim()
            if (s.isEmpty() || s.length > MAX_INPUT) return null
            if (TOKEN_RE.matches(s)) return SignInLink(s, null)
            val uri = try {
                URI(s)
            } catch (_: java.net.URISyntaxException) {
                return null
            }
            if (uri.scheme != "https" && !(uri.scheme == "http" && uri.host in allowedHosts && uri.host != HOST)) return null
            if (uri.host !in allowedHosts || uri.path != PATH) return null
            val params = fragmentParams(uri.rawFragment ?: return null)
            val token = params["t"]?.takeIf { TOKEN_RE.matches(it) } ?: return null
            val email = params["e"]?.takeIf { it.contains('@') && it.length <= MAX_EMAIL }
            return SignInLink(token, email)
        }

        private const val MAX_EMAIL = 254

        private fun fragmentParams(fragment: String): Map<String, String> =
            fragment.split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) {
                    null
                } else {
                    val v = try {
                        URLDecoder.decode(part.substring(i + 1), "UTF-8")
                    } catch (_: IllegalArgumentException) {
                        return@mapNotNull null
                    }
                    part.substring(0, i) to v
                }
            }.toMap()
    }
}

/** A plausible email address for the sign-in form (the API decides; this only guards typos). */
object EmailFormat {
    private const val MAX = 254
    private val RE = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$")

    fun isPlausible(email: String): Boolean = email.length <= MAX && RE.matches(email.trim())
}
