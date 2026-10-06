@file:Suppress("MaxLineLength") // fixtures stay on one line

package com.vettid.core.altchan

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `X-VettID-App` (VAULT-MESSAGING §11.12.2, MEMBER-API 2.0.0 "App request signing"). */
class AppRequestSigningTest {
    @Test
    fun theSigningStringIsTheSpecsLinesWithoutATrailingNewline() {
        val body = """{"secret":"x"}""".toByteArray()
        val s = AppRequestSigning.signingString("POST", "/api/vault/enroll/redeem", "", "", "00112233445566778899aabbccddeeff", 1_791_000_000, "bm9uY2U", body)
        val expected = "vettid/member-api/app/1\nPOST\n/api/vault/enroll/redeem\n\n\n00112233445566778899aabbccddeeff\n1791000000\nbm9uY2U\n" +
            Bytes.hex(Bytes.sha256(body))
        assertEquals(expected, s)
        // A GET hashes the empty body; the query is the raw string without "?".
        val g = AppRequestSigning.signingString("GET", "/api/vault/enclave", "release=ab%2Fc", "v1", "k", 5, "n", ByteArray(0))
        assertEquals("vettid/member-api/app/1\nGET\n/api/vault/enclave\nrelease=ab%2Fc\nv1\nk\n5\nn\n" +
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", g)
    }

    @Test
    fun akidIsTheFirst16BytesOfTheSpkiHashInLowerHex() {
        val spki = ByteArray(91) { it.toByte() }
        val akid = AppRequestSigning.akid(spki)
        assertEquals(32, akid.length)
        assertEquals(Bytes.hex(Bytes.sha256(spki)).substring(0, 32), akid)
        assertEquals(akid.lowercase(), akid)
    }

    @Test
    fun theHeaderHasTheSpecsFieldsInOrderAndASignatureOverTheString() {
        val key = MemberApiTest.SoftAppKey()
        val nonce = ByteArray(16) { (it * 7).toByte() }
        val h = AppRequestSigning.header(key, "POST", "/api/vault/lock", null, "vid", "{}".toByteArray(), ts = 1_791_000_000, nonceBytes = nonce)
        val parts = h.split("; ")
        assertEquals(listOf("v", "vault", "kid", "ts", "nonce", "sig"), parts.map { it.substringBefore('=') })
        val f = parts.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("1", f["v"])
        assertEquals("vid", f["vault"])
        assertEquals(AppRequestSigning.akid(key.spki()), f["kid"])
        assertEquals("1791000000", f["ts"])
        assertEquals(Base64s.encodeRawUrl(nonce), f["nonce"])
        for (k in listOf("nonce", "sig")) assertTrue("$k: base64url without padding", f[k]!!.none { it == '=' || it == '+' || it == '/' })
        val msg = AppRequestSigning.signingString("POST", "/api/vault/lock", "", "vid", f["kid"]!!, 1_791_000_000, f["nonce"]!!, "{}".toByteArray())
        assertTrue(key.verify(msg.toByteArray(), Base64s.decodeRawUrl(f["sig"]!!)))
        // An empty vault (a redeem) is written as nothing after `vault=`.
        assertTrue(AppRequestSigning.header(key, "POST", "/x", null, null, ByteArray(0)).contains("; vault=; kid="))
    }
}
