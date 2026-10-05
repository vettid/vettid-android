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
        // 0.10.2: the URL on the invitation's relay, any host, and the relay page's vettid: link.
        assertEquals(expected, InviteLinks.parse("https://relay.vettid.org/connect#$link", now))
        assertEquals(expected, InviteLinks.parse("https://my-home-relay.example/connect#$link", now))
        assertEquals(expected, InviteLinks.parse("vettid://connect#$link", now))
        assertEquals(expected, InviteLinks.parse("Join me: https://relay.vettid.test/connect#$link (VettID)", now))
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

    @Test
    fun buildsTheUrlFromThePayloadsRelay() {
        val link = qr().link()
        assertEquals("https://relay.vettid.test/connect#$link", InviteLinks.url(link))
        val slash =
            InviteQr(InviteKind.CONNECTION, "https://r.example/base/", "abcdefghijklmnopqrstuvwxyz", ByteArray(32), ByteArray(32), exp)
        assertEquals("https://r.example/base/connect#${slash.link()}", InviteLinks.url(slash.link()))
        assertEquals(null, InviteLinks.url(qr(InviteKind.DESKTOP).link()))
        assertEquals(null, InviteLinks.url("garbage"))
        // The URL host is not trusted: a link on another host still names the payload's relay.
        val p = InviteLinks.parse("https://evil.example/connect#$link", now) as InviteLinks.Parsed.Ok
        assertEquals("https://relay.vettid.test", InviteQr.parseLink(p.link).relay)
    }

    @Test
    fun recognisesConnectUris() {
        assertTrue(InviteLinks.isConnectUri("vettid://connect#abc"))
        assertTrue(InviteLinks.isConnectUri("https://relay.vettid.org/connect#abc"))
        assertTrue(InviteLinks.isConnectUri("https://self-hosted.example/connect/#abc"))
        assertEquals(false, InviteLinks.isConnectUri("https://account.vettid.org/auth/#t=x"))
        assertEquals(false, InviteLinks.isConnectUri("vettid://other#abc"))
        assertEquals(false, InviteLinks.isConnectUri("not a uri"))
    }
}
