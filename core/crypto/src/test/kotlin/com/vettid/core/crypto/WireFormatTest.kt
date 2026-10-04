package com.vettid.core.crypto

import com.vettid.core.crypto.envelope.Envelope
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.InnerError
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.envelope.Padding
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WireFormatTest {
    private val t0 = Instant.parse("2026-10-01T12:00:00Z")
    private val id = "01JB2Z6V9K3M4N5P6Q7R8S9T0V"

    private fun rejects(json: String) =
        assertThrows(json, CryptoException.Format::class.java) { StrictJson.parseObject(json.toByteArray()) }

    @Test
    fun strictJson() {
        val o = StrictJson.parseObject(""" { "a" : 1, "b" : [true, null, "x\u00e9\n"], "c": {"d": -1.5e3} } """.toByteArray())
        assertEquals(1L, o.uint("a", 0, 10))
        assertEquals("{\"d\": -1.5e3}", o.obj("c").raw)
        rejects("""{"a":1,"a":2}""")
        rejects("""{"a":{"b":1,"b":1}}""")
        rejects("""{"a":1} x""")
        rejects("""{"a":1}{}""")
        rejects("""[1]""")
        rejects("""{"a":01}""")
        rejects("""{"a":"x""" + "\u0001" + """"}""")
        rejects("""{"a":"\x"}""")
        rejects("""{"a":tru}""")
        rejects("""{"a":1,}""")
        rejects("{" + """"a":""".repeat(0) + "\"a\":" + "[".repeat(40) + "]".repeat(40) + "}")
        val invalidUtf8 = byteArrayOf('{'.code.toByte(), 0xff.toByte(), '}'.code.toByte())
        assertThrows(CryptoException.Format::class.java) { StrictJson.parseObject(invalidUtf8) }
        // Member names are case-sensitive and distinct.
        val ab = StrictJson.parseObject("""{"a":1,"A":2}""")
        assertEquals(2L, ab.uint("A", 0, 9))
        // Integers: plain, no sign, fraction or exponent, at most 2^53 - 1.
        val n = StrictJson.parseObject("""{"a":-1,"b":1.0,"c":1e2,"d":9007199254740991,"e":9007199254740992}""")
        for (k in listOf("a", "b", "c", "e")) assertThrows(k, CryptoException.Format::class.java) { n.uint(k, 0, Long.MAX_VALUE) }
        assertEquals(9007199254740991L, n.uint("d", 0, Long.MAX_VALUE))
        assertThrows(CryptoException.Format::class.java) { n.string("a") }
        assertThrows(CryptoException.Format::class.java) { n.string("missing") }
        // Surrogates: pairs combine, lone ones decode to U+FFFD (as Go).
        val s = StrictJson.parseObject("""{"a":"\ud83d\ude00","b":"\ud800x"}""")
        assertEquals("\uD83D\uDE00", s.string("a"))
        assertEquals("\uFFFDx", s.string("b"))
    }

    @Test
    fun goCompatibleStringEncoding() {
        val raw = "a\"b\\c\n\r\t\b\u000c\u0001\u001f\u007f<>&\u2028\u2029é😀"
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001\\u001f\u007f<>&\\u2028\\u2029é😀\"", JsonBuilder.quote(raw))
        assertEquals("\"\\ufffd\"", JsonBuilder.quote("\uD800"))
        val built = JsonBuilder().string("a", "x").uint("b", 3).bool("c", true).raw("d", "{}").build()
        assertEquals("{\"a\":\"x\",\"b\":3,\"c\":true,\"d\":{}}", built)
        assertEquals("{\"a\":[1,2]}", String(StrictJson.compactObject(" { \"a\" : [ 1 , 2 ] } ".toByteArray())))
        assertEquals("{\"a\":\" x \"}", String(StrictJson.compact("{\"a\": \" x \"}".toByteArray())))
    }

    @Test
    fun padding() {
        assertEquals(512, Padding.paddedLen(0))
        assertEquals(512, Padding.paddedLen(511))
        assertEquals(1024, Padding.paddedLen(512))
        assertEquals(16_384, Padding.paddedLen(16_383))
        assertEquals(32_768, Padding.paddedLen(16_384))
        assertEquals(245_760, Padding.paddedLen(245_759))
        assertThrows(CryptoException.Format::class.java) { Padding.paddedLen(245_760) }
        val p = Padding.pad("{}".toByteArray())
        assertEquals("{}", String(Padding.unpad(p)))
        // Over-padding, a wrong marker, no marker, bad sizes.
        assertThrows(CryptoException.Format::class.java) { Padding.unpad(Padding.padFixed("{}".toByteArray(), 1024)) }
        assertThrows(CryptoException.Format::class.java) { Padding.unpad(p.copyOf().also { it[2] = 0x81.toByte() }) }
        assertThrows(CryptoException.Format::class.java) { Padding.unpad(ByteArray(512)) }
        assertThrows(CryptoException.Format::class.java) { Padding.unpad(p.copyOf(511)) }
        assertThrows(CryptoException.Format::class.java) { Padding.unpad(p.copyOf().also { it[511] = 1 }) }
        assertEquals("{}", String(Padding.unpadFixed(Padding.padFixed("{}".toByteArray(), 4096), 4096)))
        assertThrows(CryptoException.Format::class.java) { Padding.unpadFixed(Padding.padFixed("{}".toByteArray(), 4096), 12_288) }
    }

    @Test
    fun innerRules() {
        val ok = Inner(id = id, type = "message.send", ts = t0, seq = 3)
        assertEquals(
            """{"v":1,"id":"$id","type":"message.send","ts":"2026-10-01T12:00:00.000Z","seq":3,"body":{}}""",
            String(ok.marshal(Mode.SESSION)),
        )
        val resp = Inner(
            id = id,
            type = "x.y",
            ts = t0,
            re = id,
            status = "error",
            error = InnerError("not_found", "m"),
            body = "{\"a\" : 1}".toByteArray(),
        )
        assertEquals(
            """{"v":1,"id":"$id","type":"x.y","ts":"2026-10-01T12:00:00.000Z","re":"$id","status":"error",""" +
                """"error":{"code":"not_found","message":"m"},"body":{"a":1}}""",
            String(resp.marshal(Mode.SEALED)),
        )
        val bad = listOf(
            Inner(id = "01jb2z6v9k3m4n5p6q7r8s9t0v", type = "x", ts = t0),
            Inner(id = "81JB2Z6V9K3M4N5P6Q7R8S9T0V", type = "x", ts = t0),
            Inner(id = id, type = "X", ts = t0),
            Inner(id = id, type = "a..b", ts = t0),
            Inner(id = id, type = "a.", ts = t0),
            Inner(id = id, type = "1a", ts = t0),
            Inner(id = id, type = "x", ts = t0, seq = 1), // seq in sealed mode
            Inner(id = id, type = "x", ts = t0, re = id), // re without status
            Inner(id = id, type = "x", ts = t0, re = id, status = "ok", error = InnerError("e")),
            Inner(id = id, type = "x", ts = t0, re = id, status = "error"),
            Inner(id = id, type = "x", ts = t0, re = id, status = "error", error = InnerError("Bad")),
        )
        for (b in bad) assertThrows(CryptoException.Format::class.java) { b.marshal(Mode.SEALED) }
        assertThrows(CryptoException.Format::class.java) { Inner(id = id, type = "x", ts = t0).marshal(Mode.SESSION) }
        fun parse(s: String, mode: Mode = Mode.SEALED) = Inner.parse(s.toByteArray(), mode)
        val base = """"v":1,"id":"$id","type":"x","ts":"2026-10-01T12:00:00.000Z""""
        assertEquals("x", parse("{$base,\"unknown\":[1],\"body\":{}}").type)
        for (s in listOf(
            "{$base}", // no body
            "{$base,\"body\":[]}",
            "{$base,\"body\":{\"a\":1,\"a\":2}}",
            "{$base,\"seq\":1,\"body\":{}}",
            "{\"v\":2,\"id\":\"$id\",\"type\":\"x\",\"ts\":\"2026-10-01T12:00:00.000Z\",\"body\":{}}",
            "{\"v\":1,\"id\":\"$id\",\"type\":\"x\",\"ts\":\"2026-10-01T12:00:00Z\",\"body\":{}}",
            "{\"v\":1,\"id\":\"$id\",\"type\":\"x\",\"ts\":\"2026-10-01T12:00:00.000+00:00\",\"body\":{}}",
            "{$base,\"re\":\"\",\"status\":\"ok\",\"body\":{}}",
        )) {
            assertThrows(s, CryptoException.Format::class.java) { parse(s) }
        }
        assertThrows(CryptoException.Format::class.java) { parse("{$base,\"body\":{}}", Mode.SESSION) }
        // Freshness.
        val fresh = Inner(id = id, type = "x", ts = t0, exp = t0.plusSeconds(60))
        fresh.checkTime(t0, true)
        assertThrows(CryptoException.Time::class.java) { fresh.checkTime(t0.minusSeconds(301), true) }
        assertThrows(CryptoException.Time::class.java) { fresh.checkTime(t0.plusSeconds(61), false) }
        assertThrows(CryptoException.Time::class.java) { Inner(id = id, type = "x", ts = t0).checkTime(t0.plusSeconds(17 * 86_400L), true) }
        Inner(id = id, type = "x", ts = t0).checkTime(t0.plusSeconds(17 * 86_400L), false)
    }

    @Test
    fun timestampsAndUlids() {
        assertEquals("2026-10-01T12:00:00.123Z", Timestamps.formatMillis(Instant.parse("2026-10-01T12:00:00.123456Z")))
        assertEquals(Instant.parse("2026-02-28T00:00:00Z"), Timestamps.parseSeconds("2026-02-28T00:00:00Z"))
        for (s in listOf("2026-02-30T00:00:00Z", "2026-1-01T00:00:00Z", "2026-10-01T12:00:00.000Z", "2026-10-01 12:00:00Z")) {
            assertThrows(s, CryptoException.Format::class.java) { Timestamps.parseSeconds(s) }
        }
        val u = Ulid.new(t0)
        assertTrue(Ulid.isValid(u))
        assertEquals("0000000001" + "0".repeat(16), deterministic(ByteArray(10)) { Ulid.new(Instant.ofEpochMilli(1)) })
        val entropyOne = deterministic(ByteArray(9) + byteArrayOf(1)) { Ulid.new(Instant.ofEpochMilli(0)) }
        assertEquals("0000000000" + "0".repeat(15) + "1", entropyOne)
        assertFalse(Ulid.isValid("01JB2Z6V9K3M4N5P6Q7R8S9T0I")) // I is not Crockford
        assertFalse(Ulid.isValid("01JB2Z6V9K3M4N5P6Q7R8S9T0"))
    }

    @Test
    fun envelopeStructure() {
        val sk = KemPrivateKey.generate()
        val padded = Inner.encode(Inner(id = id, type = "x", ts = t0), Mode.SEALED)
        val (env, _) = Envelope.sealSealed(sk.publicKey, Kid.ANONYMOUS, padded)
        assertEquals(Envelope.OVERHEAD_SEALED + 512, env.size)
        Envelope.parse(env)
        fun mutate(i: Int, v: Int) = env.copyOf().also { it[i] = v.toByte() }
        assertThrows(CryptoException.Format::class.java) { Envelope.parse(mutate(0, 1)) }
        assertThrows(CryptoException.Suite::class.java) { Envelope.parse(mutate(1, 1)) }
        assertThrows(CryptoException.Suite::class.java) { Envelope.parse(mutate(1, 3)) }
        assertThrows(CryptoException.Format::class.java) { Envelope.parse(mutate(2, 3)) }
        assertThrows(CryptoException.Format::class.java) { Envelope.parse(mutate(3, 1)) }
        assertThrows(CryptoException.Format::class.java) { Envelope.parse(env.copyOf(env.size - 1)) }
        assertThrows(CryptoException.Format::class.java) { Envelope.parse(env.copyOf(19)) }
        // A tampered header (the AAD) or ciphertext does not open.
        assertThrows(CryptoException.Decrypt::class.java) { Envelope.openSealed(Envelope.parse(mutate(5, 9)), sk) }
        val lastFlipped = mutate(env.size - 1, env[env.size - 1] + 1)
        assertThrows(CryptoException.Decrypt::class.java) { Envelope.openSealed(Envelope.parse(lastFlipped), sk) }
        // The kid selects the key; no other key is tried.
        assertThrows(CryptoException.Protocol::class.java) { Envelope.openSealed(Envelope.parse(env), KemPrivateKey.generate()) }
        assertThrows(CryptoException.Protocol::class.java) { Envelope.openSession(Envelope.parse(env), ByteArray(32)) }
    }

    @Test
    fun blob() {
        val s = com.vettid.core.crypto.envelope.Blob.seal("content".toByteArray())
        assertEquals("content", String(com.vettid.core.crypto.envelope.Blob.open(s.blob, s.key, s.sha256)))
        assertThrows(CryptoException.Decrypt::class.java) {
            com.vettid.core.crypto.envelope.Blob.open(s.blob, s.key, ByteArray(32))
        }
    }
}
