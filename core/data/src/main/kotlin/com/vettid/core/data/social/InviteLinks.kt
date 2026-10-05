package com.vettid.core.data.social

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import java.time.Instant

/**
 * Invitation links and QR codes (VAULT-MESSAGING §6.4, 0.10.2). The QR code
 * holds the compact JSON payload; the bare link is its unpadded base64url; a
 * connection invitation is shared as the URL `<r>/connect#<link>` on the
 * invitation's own relay (`r` in the payload), and the relay's page offers
 * `vettid://connect#<link>`. The app accepts every form, also inside a longer
 * text (a URL whose fragment, query value or last path segment is the link, or
 * a message with the link on a line of its own). The host of a URL is never
 * trusted: the payload's `r` decides the relay, and only the bare payload is
 * passed to the vault.
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

    /** The custom scheme of the relay page's "Open in VettID" link (§6.4). */
    const val SCHEME = "vettid"

    /** The path every relay serves its `/connect` page on (§6.4). */
    const val CONNECT_PATH = "/connect"

    /**
     * The invitation URL `<r>/connect#<link>` (§6.4) for a connection link, built from
     * the payload's own relay `r`; null for a link that does not parse or is not a connection.
     */
    fun url(link: String): String? = try {
        val q = InviteQr.parseLink(link)
        if (q.kind != InviteKind.CONNECTION) null else q.relay.trimEnd('/') + CONNECT_PATH + "#" + q.link()
    } catch (_: CryptoException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Whether an opened URI is meant for the connect flow: `vettid://connect#…`, or an
     * `https` URL whose path is `/connect` (any host: the payload inside decides, §6.4).
     */
    fun isConnectUri(uri: String): Boolean {
        val u = try {
            java.net.URI(uri.trim())
        } catch (_: java.net.URISyntaxException) {
            null
        }
        return when (u?.scheme?.lowercase()) {
            SCHEME -> (u.host ?: u.rawSchemeSpecificPart?.trimStart('/')?.substringBefore('/'))?.lowercase() == "connect"
            "https", "http" -> u.rawPath?.trimEnd('/') == CONNECT_PATH
            else -> false
        }
    }

    /** The QR content for a link: the compact JSON payload (§6.4). */
    fun qrPayload(link: String): String = String(InviteQr.parseLink(link).marshal(), Charsets.UTF_8)

    /** The invitation's expiry, from a link. */
    fun expiry(link: String): Instant = Instant.ofEpochSecond(InviteQr.parseLink(link).exp)

    /** What a scanned or pasted text holds, for a direct transfer to this phone (§6.7.1). */
    sealed interface TransferParsed {
        /** The old phone's transfer code: [link] is the pairing link (kind `p`). */
        data class Ok(val link: String, val exp: Instant) : TransferParsed

        /** A valid VettID code of another kind (a connection invitation, a desktop or agent pairing). */
        data object NotATransfer : TransferParsed

        data object Expired : TransferParsed

        data object Invalid : TransferParsed
    }

    /** Parses the old phone's transfer QR (the compact JSON) or its pasted link (§6.7.1: pairing kind `p`). */
    fun parseTransfer(text: String, now: Instant = Instant.now()): TransferParsed {
        val t = text.trim()
        val q = if (t.isEmpty()) null else candidates(t).firstNotNullOfOrNull { decode(it) }
        return when {
            q == null -> TransferParsed.Invalid
            q.kind != InviteKind.APP -> TransferParsed.NotATransfer
            !Instant.ofEpochSecond(q.exp).isAfter(now) -> TransferParsed.Expired
            else -> TransferParsed.Ok(q.link(), Instant.ofEpochSecond(q.exp))
        }
    }

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
