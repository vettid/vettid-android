// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.relay

import com.vettid.core.crypto.Base64s
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Token minting and checking, mirroring vettid-relay relayauth/token_test.go. */
class TokenTest {
    private val recipient = VectorsTest.key(VectorsTest.RECIPIENT_SEED)
    private val sender = VectorsTest.key(VectorsTest.SENDER_SEED)
    private val now = Instant.parse("2026-10-04T12:34:56.789Z")

    @Test
    fun standingTokenIsBackdatedSixtySeconds() {
        val t = DepositTokens.mintStanding(recipient, VectorsTest.SENDER_PUB, VectorsTest.AUD, now, Duration.ofDays(30), jti = "01JXAMPLE0000000000000001")
        val c = DepositTokens.verify(t, recipient.publicKey)
        assertEquals(Instant.parse("2026-10-04T12:33:56Z"), c.iat)
        assertEquals(c.iat.plus(Duration.ofDays(30)), c.exp)
        assertEquals(VectorsTest.RECIPIENT_MAILBOX, c.iss)
        assertEquals(TokenClaims.SCOPE_DEPOSIT, c.scope)
        assertEquals(VectorsTest.SENDER_PUB, c.sub)
    }

    @Test
    fun openTokenBackdatesAtMostHalfItsLifetime() {
        val short = DepositTokens.verify(DepositTokens.mintOpen(recipient, VectorsTest.AUD, now, Duration.ofSeconds(60)), recipient.publicKey)
        assertEquals(Instant.parse("2026-10-04T12:34:26Z"), short.iat)
        assertEquals(Duration.ofSeconds(60), Duration.between(short.iat, short.exp))
        assertTrue(short.isOpen)
        assertEquals(TokenClaims.OPEN_SUB, short.sub)
        assertNull(short.subKey)
        val long = DepositTokens.verify(DepositTokens.mintOpen(recipient, VectorsTest.AUD, now, Duration.ofMinutes(10)), recipient.publicKey)
        assertEquals(Instant.parse("2026-10-04T12:33:56Z"), long.iat)
    }

    @Test
    fun quotaFollowsScopeInClaims() {
        val t = DepositTokens.mintStanding(recipient, VectorsTest.SENDER_PUB, VectorsTest.AUD, now, Duration.ofDays(365), "j1", Quota(4, 65536))
        val m = String(Paseto.verify(t, recipient.publicKey))
        assertTrue(m, m.endsWith(""""scope":"deposit","quota":{"msgs":4,"bytes":65536}}"""))
        assertEquals(Quota(4, 65536), DepositTokens.verify(t, recipient.publicKey).quota)
    }

    @Test
    fun rejectsWrongKeyTamperingFootersAndGarbage() {
        val t = VectorsTest.TOKEN
        assertThrows(TokenException.Invalid::class.java) { Paseto.verify(t, sender.publicKey) }
        val flipped = t.substring(0, 40) + (if (t[40] == 'A') 'B' else 'A') + t.substring(41)
        assertThrows(TokenException.Invalid::class.java) { Paseto.verify(flipped, recipient.publicKey) }
        assertThrows(TokenException.Invalid::class.java) { Paseto.verify("$t.Zm9v", recipient.publicKey) }
        assertThrows(TokenException.Invalid::class.java) { Paseto.verify("v3.public." + t.substring(10), recipient.publicKey) }
        assertThrows(TokenException.Invalid::class.java) { Paseto.verify(t.substring(0, 50) + "\n" + t.substring(50), recipient.publicKey) }
        assertThrows(TokenException.Invalid::class.java) { Paseto.verify("v4.public.AAAA", recipient.publicKey) }
    }

    @Test
    fun claimsAreValidatedStructurally() {
        fun signed(json: String) = Paseto.sign(recipient, json.toByteArray())
        val base = """"iss":"gr2q7gf5lh6pzfdnurnkvputhp","aud":"a","iat":"2026-06-10T00:00:00Z","exp":"2026-07-10T00:00:00Z","jti":"j""""
        // open token must have sub "*"; sender-bound must have a key; unknown scope refused; missing jti refused
        assertThrows(TokenException.Invalid::class.java) { DepositTokens.verify(signed("""{$base,"sub":"x","scope":"deposit_open"}"""), recipient.publicKey) }
        assertThrows(TokenException.Invalid::class.java) { DepositTokens.verify(signed("""{$base,"sub":"*","scope":"deposit"}"""), recipient.publicKey) }
        assertThrows(TokenException.Invalid::class.java) { DepositTokens.verify(signed("""{$base,"sub":"*","scope":"admin"}"""), recipient.publicKey) }
        assertThrows(TokenException.Invalid::class.java) {
            DepositTokens.verify(signed("""{"iss":"gr2q7gf5lh6pzfdnurnkvputhp","sub":"*","aud":"a","iat":"2026-06-10T00:00:00Z","exp":"2026-07-10T00:00:00Z","scope":"deposit_open"}"""), recipient.publicKey)
        }
        val ok = DepositTokens.verify(signed("""{$base,"sub":"*","scope":"deposit_open","extra":1}"""), recipient.publicKey)
        assertTrue(ok.isOpen)
    }

    @Test
    fun decodeKeyIsStrict() {
        assertEquals(32, RelayAuth.decodeKey(VectorsTest.SENDER_PUB)!!.size)
        assertNull(RelayAuth.decodeKey(VectorsTest.SENDER_PUB.dropLast(1)))
        assertNull(RelayAuth.decodeKey(Base64s.encodeStd(ByteArray(31)) + "A"))
    }
}
