package com.vettid.core.altchan

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.json.StrictJson
import java.net.URI
import java.net.URISyntaxException

/**
 * The setup code the account portal issues (VAULT-MESSAGING §11.12.1, 0.15.0): a QR (or the same-device App Link)
 * carrying a 128-bit secret, or an 8-symbol code typed with the member's email. The app redeems it with its app
 * key; it never signs in.
 */
object SetupCodes {
    /** The typed code's alphabet: no 0, 1, I, L or O. */
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val LENGTH = 8
    const val SECRET_LENGTH = 22

    /** The App Link path on the account host (`https://<account host>/vault/enroll/#s=<secret>`). */
    const val LINK_PATH = "/vault/enroll/"

    private val SECRET_RE = Regex("^[A-Za-z0-9_-]{22}$")
    private const val MAX_INPUT = 1024
    private const val GROUP = 4

    /** What a scanned QR or an opened link turned out to be. */
    sealed interface Scanned {
        /** A setup QR for this app's member API: [secret] is redeemed. */
        data class Secret(val secret: String) : Scanned {
            override fun toString(): String = "Secret(…)"
        }

        /** A setup or recovery QR made by another environment's portal ([api], compared exactly; never contacted). */
        data class OtherEnvironment(val api: String) : Scanned

        /** Not a setup code. */
        data object NotACode : Scanned
    }

    fun isSecret(s: String): Boolean = SECRET_RE.matches(s)

    /**
     * A scanned QR's text: `{"v":1,"t":"e","api":"<origin>","s":"<secret>"}`. [api] is this build's member API
     * origin, compared exactly with the QR's `api` (an identifier, never an address the app connects to).
     */
    @Suppress("ReturnCount")
    fun parseScanned(text: String, api: String): Scanned {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_INPUT) return Scanned.NotACode
        val o = try {
            StrictJson.parseObject(t.toByteArray(Charsets.UTF_8))
        } catch (_: CryptoException) {
            return Scanned.NotACode
        }
        return try {
            if (o.uint("v", 1, 1) != 1L || o.string("t") != TYPE) return Scanned.NotACode
            val qrApi = o.string("api")
            val secret = o.string("s")
            when {
                !isSecret(secret) -> Scanned.NotACode
                qrApi != origin(api) -> Scanned.OtherEnvironment(qrApi)
                else -> Scanned.Secret(secret)
            }
        } catch (_: CryptoException) {
            Scanned.NotACode
        }
    }

    /**
     * The same-device App Link `https://<account host>/vault/enroll/#s=<secret>` (§11.12.1): accepted only from
     * [api]'s own host, the secret in the fragment.
     */
    @Suppress("ReturnCount")
    fun parseLink(link: String, api: String): String? {
        val s = link.trim()
        if (s.length > MAX_INPUT) return null
        val uri = try {
            URI(s)
        } catch (_: URISyntaxException) {
            return null
        }
        val own = try {
            URI(api)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.scheme != own.scheme || uri.host != own.host || uri.port != own.port || uri.path != LINK_PATH) return null
        val frag = uri.rawFragment ?: return null
        val secret = frag.split('&').firstOrNull { it.startsWith("s=") }?.substring(2) ?: return null
        return secret.takeIf { isSecret(it) }
    }

    /** Whether [link] is a setup App Link of any form (to route it; [parseLink] decides whether it is accepted). */
    fun isSetupLink(link: String): Boolean = try {
        URI(link.trim()).path == LINK_PATH
    } catch (_: URISyntaxException) {
        false
    }

    /**
     * A typed code in its canonical form (spaces and hyphens dropped, upper case, 8 symbols of [ALPHABET]), or
     * null when it is not one: the app refuses it before anything is sent.
     */
    @Suppress("ReturnCount")
    fun normalize(typed: String): String? {
        val sb = StringBuilder(LENGTH)
        for (ch in typed) {
            when (val c = ch.uppercaseChar()) {
                ' ', '-', '\t', ' ' -> Unit
                else -> if (c in ALPHABET) sb.append(c) else return null
            }
            if (sb.length > LENGTH) return null
        }
        return sb.toString().takeIf { it.length == LENGTH }
    }

    /** Whether [typed] holds a character a code never contains (0, 1, I, L, O or another symbol). */
    fun hasForeignCharacter(typed: String): Boolean =
        typed.any { ch -> val c = ch.uppercaseChar(); c !in ALPHABET && c != ' ' && c != '-' && c != '\t' && c != ' ' }

    /** For display: `XXXX-XXXX`. */
    fun grouped(code: String): String = code.chunked(GROUP).joinToString("-")

    /** The member's email as the API compares it: trimmed, lower case. */
    fun normalizeEmail(email: String): String = email.trim().lowercase()

    /** The origin of an API base URL (`https://host[:port]`), as the QR's `api` names it. */
    fun origin(api: String): String = api.trimEnd('/')

    private const val TYPE = "e"
}
