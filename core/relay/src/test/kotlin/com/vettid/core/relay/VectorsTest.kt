// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.relay

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.Ed25519PrivateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * RELAY-PROTOCOL 0.5.0 §9 test vectors, which vettid-relay's
 * relayauth/vectors_test.go also pins. Every value is copied verbatim from
 * the spec and must be reproduced byte for byte.
 */
class VectorsTest {
    companion object {
        const val RECIPIENT_SEED = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        const val RECIPIENT_PUB = "iojj3XQJ8ZX9UtstPLpdcspnCb8dlBIb83SIAbQPb1w="
        const val RECIPIENT_MAILBOX = "gr2q7gf5lh6pzfdnurnkvputhp"
        const val SENDER_SEED = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
        const val SENDER_PUB = "gTl3Dqh9F19Wo1Rmw0x+zMuNipG07jeiXfYPW4/Js5Q="
        const val SENDER_MAILBOX = "ni4ahvpqlgicuhdnv66jxjdssi"

        const val CLAIMS = """{"iss":"gr2q7gf5lh6pzfdnurnkvputhp","sub":"gTl3Dqh9F19Wo1Rmw0x+zMuNipG07jeiXfYPW4/Js5Q=","aud":"https://relay.example.vettid.org","iat":"2026-06-10T00:00:00Z","exp":"2026-07-10T00:00:00Z","jti":"01JXAMPLE0000000000000000","scope":"deposit"}"""
        const val TOKEN = "v4.public.eyJpc3MiOiJncjJxN2dmNWxoNnB6ZmRudXJua3ZwdXRocCIsInN1YiI6ImdUbDNEcWg5RjE5V28xUm13MHgrek11TmlwRzA3amVpWGZZUFc0L0pzNVE9IiwiYXVkIjoiaHR0cHM6Ly9yZWxheS5leGFtcGxlLnZldHRpZC5vcmciLCJpYXQiOiIyMDI2LTA2LTEwVDAwOjAwOjAwWiIsImV4cCI6IjIwMjYtMDctMTBUMDA6MDA6MDBaIiwianRpIjoiMDFKWEFNUExFMDAwMDAwMDAwMDAwMDAwMCIsInNjb3BlIjoiZGVwb3NpdCJ9rWQjEmgif2o_c9SNTUXzHPPLWKZCPMAPUk3VPpbWkTIY0pfihNHpVZeX-YDM35FN_u1FVYPHj4BrO4sGJQj6BA"
        const val AUD = "https://relay.example.vettid.org"

        const val METHOD = "POST"
        const val PATH = "/v1/mailbox/gr2q7gf5lh6pzfdnurnkvputhp"
        const val BODY = """{"payload":"b3BhcXVlLWNpcGhlcnRleHQtYnl0ZXM="}"""
        const val BODY_HEX = "0237514b3df17b219036f1b8fa6ca70d4ca597eb630933841707380055d7e12a"
        const val TIMESTAMP = "2026-06-10T12:00:00Z"
        const val CANONICAL = "POST\n/v1/mailbox/gr2q7gf5lh6pzfdnurnkvputhp\n2026-06-10T12:00:00Z\n0237514b3df17b219036f1b8fa6ca70d4ca597eb630933841707380055d7e12a"
        const val DIGEST_B64 = "l+mc5XMNElCBLC49xJH4Lc8LHEAlAK1HunqaS+LZ33k="
        const val SIG_B64 = "pOZT7Pb981+3K4wWv6Zryb43miSLBfKdCZ4Wc/qNzQyxFtOPrsSSkK1Gj5mF+Zb3z3yC4Bi7AyjIyVkeN+nsDA=="

        fun key(seedB64: String): Ed25519PrivateKey = Ed25519PrivateKey.fromSeed(Base64s.decodeStd(seedB64))
    }

    @Test
    fun keysAndMailboxIds() { // §9.1
        for ((seed, pub, mbx) in listOf(
            Triple(RECIPIENT_SEED, RECIPIENT_PUB, RECIPIENT_MAILBOX),
            Triple(SENDER_SEED, SENDER_PUB, SENDER_MAILBOX),
        )) {
            val k = key(seed)
            assertEquals(pub, RelayAuth.encodeKey(k.publicKey))
            assertEquals(mbx, RelayAuth.mailboxId(k.publicKey))
            assertTrue(RelayAuth.isValidMailboxId(mbx))
        }
    }

    @Test
    fun depositToken() { // §9.2
        val claims = TokenClaims(
            iss = RECIPIENT_MAILBOX, sub = SENDER_PUB, aud = AUD,
            iat = Instant.parse("2026-06-10T00:00:00Z"), exp = Instant.parse("2026-07-10T00:00:00Z"),
            jti = "01JXAMPLE0000000000000000", scope = TokenClaims.SCOPE_DEPOSIT,
        )
        assertEquals(CLAIMS, claims.toJson())
        assertEquals(TOKEN, DepositTokens.mint(key(RECIPIENT_SEED), claims))
        assertEquals(TOKEN, Paseto.sign(key(RECIPIENT_SEED), CLAIMS.toByteArray()))
        val parsed = DepositTokens.verify(TOKEN, Base64s.decodeStd(RECIPIENT_PUB))
        assertEquals(claims, parsed)
        assertEquals(SENDER_PUB, RelayAuth.encodeKey(parsed.subKey!!))
    }

    @Test
    fun signedRequest() { // §9.3
        val bh = RelayAuth.bodyHash(BODY.toByteArray())
        assertEquals(BODY_HEX, Bytes.hex(bh))
        assertEquals(CANONICAL, String(RelayAuth.canonical(METHOD, PATH, TIMESTAMP, bh)))
        assertEquals(DIGEST_B64, Base64s.encodeStd(RelayAuth.digest(METHOD, PATH, TIMESTAMP, bh)))
        val sender = key(SENDER_SEED)
        assertEquals(SIG_B64, Base64s.encodeStd(RelayAuth.signRequest(sender, METHOD, PATH, TIMESTAMP, bh)))
        val h = RelayAuth.headers(sender, METHOD, PATH, Instant.parse(TIMESTAMP), bh)
        assertEquals(SENDER_PUB, h[RelayAuth.HEADER_KEY])
        assertEquals(TIMESTAMP, h[RelayAuth.HEADER_TIMESTAMP])
        assertEquals(SIG_B64, h[RelayAuth.HEADER_SIG])
    }

    @Test
    fun emptyBodyHash() {
        assertEquals(RelayAuth.EMPTY_BODY_HASH, Bytes.hex(RelayAuth.bodyHash(null)))
        assertEquals(RelayAuth.EMPTY_BODY_HASH, Bytes.hex(RelayAuth.bodyHash(ByteArray(0))))
    }

    @Test
    fun canonicalUppercasesMethod() {
        val bh = RelayAuth.bodyHash(null)
        assertEquals(String(RelayAuth.canonical("GET", "/v1/mailbox", "t", bh)), String(RelayAuth.canonical("get", "/v1/mailbox", "t", bh)))
    }

    @Test
    fun timestampsAreMillisecondRfc3339() {
        assertEquals("2026-06-10T12:00:00Z", RelayAuth.timestamp(Instant.parse("2026-06-10T12:00:00Z")))
        assertEquals("2026-06-10T12:00:00.123Z", RelayAuth.timestamp(Instant.parse("2026-06-10T12:00:00.123456Z")))
        assertEquals("2026-06-10T12:00:00.1Z", RelayAuth.timestamp(Instant.parse("2026-06-10T12:00:00.100Z")))
        assertEquals("2026-06-10T12:00:00.05Z", RelayAuth.timestamp(Instant.parse("2026-06-10T12:00:00.050Z")))
    }
}
