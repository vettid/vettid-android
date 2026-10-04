package com.vettid.core.data.social

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import java.time.Instant

/**
 * Invitation links and QR codes (VAULT-MESSAGING §6.4). The QR code holds the
 * compact JSON payload; a link is its unpadded base64url. The app accepts
 * either, also inside a longer text (a URL whose fragment, query value or last
 * path segment is the link, or a message with the link on a line of its own).
 */
object InviteLinks {
    /** What a pasted or scanned text holds. */
    sealed interface Parsed {
        /** A connection invitation: [link] is what `connection.invite.accept` takes. */
        data class Ok(val link: String, val exp: Instant) : Parsed

        /** A valid VettID code of another kind (a device pairing or transfer). */
        data object NotAConnection : Parsed

        data object Expired : Parsed

        data object Invalid : Parsed
    }

    /** The QR content for a link: the compact JSON payload (§6.4). */
    fun qrPayload(link: String): String = String(InviteQr.parseLink(link).marshal(), Charsets.UTF_8)

    /** The invitation's expiry, from a link. */
    fun expiry(link: String): Instant = Instant.ofEpochSecond(InviteQr.parseLink(link).exp)

    fun parse(text: String, now: Instant = Instant.now()): Parsed {
        val t = text.trim()
        val q = if (t.isEmpty()) null else candidates(t).firstNotNullOfOrNull { decode(it) }
        return when {
            q == null -> Parsed.Invalid
            q.kind != InviteKind.CONNECTION -> Parsed.NotAConnection
            !Instant.ofEpochSecond(q.exp).isAfter(now) -> Parsed.Expired
            else -> Parsed.Ok(q.link(), Instant.ofEpochSecond(q.exp))
        }
    }

    private fun decode(s: String): InviteQr? = try {
        if (s.startsWith("{")) InviteQr.parse(s.toByteArray(Charsets.UTF_8)) else InviteQr.parseLink(s)
    } catch (_: CryptoException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private val LINK_CHARS = Regex("[A-Za-z0-9_-]{40,}")

    private fun candidates(t: String): Sequence<String> = sequence {
        yield(t)
        if (t.startsWith("{")) return@sequence
        val noSpace = t.filterNot { it.isWhitespace() }
        if (noSpace != t) yield(noSpace)
        noSpace.substringAfterLast('#', "").takeIf { it.isNotEmpty() }?.let { f ->
            yield(f)
            yield(f.substringAfterLast('='))
        }
        noSpace.substringAfterLast('=', "").takeIf { it.isNotEmpty() }?.let { yield(it) }
        noSpace.trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }?.let { yield(it) }
        LINK_CHARS.findAll(t).forEach { yield(it.value) }
    }
}
