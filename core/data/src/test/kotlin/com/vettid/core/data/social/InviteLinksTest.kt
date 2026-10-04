package com.vettid.core.data.social

import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InviteLinksTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val exp = now.plusSeconds(600).epochSecond

    private fun qr(kind: InviteKind = InviteKind.CONNECTION, e: Long = exp) =
        InviteQr(kind, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 }, ByteArray(32) { 2 }, e)

    @Test
    fun acceptsTheLinkTheQrJsonAndLinksInsideText() {
        val q = qr()
        val link = q.link()
        val json = String(q.marshal())
        val expected = InviteLinks.Parsed.Ok(link, Instant.ofEpochSecond(exp))
        assertEquals(expected, InviteLinks.parse(link, now))
        assertEquals(expected, InviteLinks.parse("  $link\n", now))
        assertEquals(expected, InviteLinks.parse(json, now))
        assertEquals(expected, InviteLinks.parse("https://vettid.org/connect#$link", now))
        assertEquals(expected, InviteLinks.parse("https://example.org/i?c=$link", now))
        assertEquals(expected, InviteLinks.parse("Connect with me on VettID: paste this:\n\n$link", now))
        assertEquals(json, InviteLinks.qrPayload(link))
        assertEquals(Instant.ofEpochSecond(exp), InviteLinks.expiry(link))
    }

    @Test
    fun refusesOtherKindsExpiredAndGarbage() {
        assertEquals(InviteLinks.Parsed.NotAConnection, InviteLinks.parse(qr(InviteKind.DESKTOP).link(), now))
        assertEquals(InviteLinks.Parsed.Expired, InviteLinks.parse(qr(e = now.epochSecond).link(), now))
        assertEquals(InviteLinks.Parsed.Invalid, InviteLinks.parse("", now))
        assertEquals(InviteLinks.Parsed.Invalid, InviteLinks.parse("hello there", now))
        assertEquals(InviteLinks.Parsed.Invalid, InviteLinks.parse("""{"v":2,"t":"c"}""", now))
        // A link with one character changed no longer parses (or parses to another claim): never the original.
        val link = qr().link()
        val tampered = link.dropLast(4) + "AAAA"
        assertTrue(InviteLinks.parse(tampered, now) != InviteLinks.Parsed.Ok(link, Instant.ofEpochSecond(exp)))
    }
}
