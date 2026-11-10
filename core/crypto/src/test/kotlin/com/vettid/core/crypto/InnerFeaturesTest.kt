package com.vettid.core.crypto

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.session.DeviceFeatures
import com.vettid.core.crypto.session.HsTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 0.86.0 (§5.3, §10.8): the inner `features` member round-trips, sits between `error` and `body`, and a malformed list
 * is refused like any malformed known member; a message without it is unchanged. Mirrors vettid-vault `TestInnerFeatures`
 * and vettid-ios `InnerFeaturesTests`.
 */
class InnerFeaturesTest {
    private val ts = Instant.parse("2026-11-05T00:00:00Z")
    private val id = "01JB2Z6V9K3M4N5P6Q7R8S9T0V"

    @Test
    fun theListRoundTripsBeforeTheBody() {
        val j = Inner(id = id, type = HsTypes.CONFIRM, ts = ts, features = listOf("hs_confirm", "vote")).marshal(Mode.SEALED)
        assertTrue(String(j).contains(""""features":["hs_confirm","vote"],"body":{}"""))
        val out = Inner.parse(j, Mode.SEALED)
        assertEquals(listOf("hs_confirm", "vote"), out.features)
        // Sealing assigns seq and keeps the list.
        assertEquals(listOf("hs_confirm", "vote"), out.withSeq(7).features)
        // In session mode too, and after an error (the member order v … status, error, features, body).
        val e = Inner(
            id = id, type = "x.y", ts = ts, seq = 3, re = id, status = Inner.STATUS_ERROR,
            error = com.vettid.core.crypto.envelope.InnerError("not_found"), features = listOf("hs_confirm"),
        ).marshal(Mode.SESSION)
        assertTrue(String(e).endsWith(""""error":{"code":"not_found"},"features":["hs_confirm"],"body":{}}"""))
        assertEquals(listOf("hs_confirm"), Inner.parse(e, Mode.SESSION).features)
    }

    @Test
    fun anAbsentListIsNotWritten() {
        val pj = Inner(id = id, type = "x.y", ts = ts).marshal(Mode.SEALED)
        assertFalse(String(pj).contains("features"))
        assertNull(Inner.parse(pj, Mode.SEALED).features)
    }

    @Test
    fun aMalformedListIsRefused() {
        val pj = String(Inner(id = id, type = "x.y", ts = ts).marshal(Mode.SEALED))
        val bad = listOf(
            "[]", """["b","a"]""", """["a","a"]""", """["A"]""", """["a-b"]""", "[1]", "\"a\"", "null", "{}", """[""]""",
            """["${"a".repeat(33)}"]""",
            """[${(0..32).joinToString(",") { "\"f%02d\"".format(it) }}]""",
        )
        for (b in bad) {
            val raw = pj.replace(""""body":{}""", """"features":$b,"body":{}""")
            assertThrows("accepted $b", CryptoException.Format::class.java) { Inner.parse(raw.toByteArray(), Mode.SEALED) }
        }
        // A duplicate member is refused as any duplicate.
        val dup = pj.replace(""""body":{}""", """"features":["a"],"features":["a"],"body":{}""")
        assertThrows(CryptoException::class.java) { Inner.parse(dup.toByteArray(), Mode.SEALED) }
        val tooMany = (0 until 33).map { "f%02d".format(it) }
        for (b in listOf(emptyList(), listOf("b", "a"), listOf("x y"), listOf("é"), tooMany)) {
            assertThrows("marshalled $b", CryptoException.Format::class.java) {
                Inner(id = id, type = "x.y", ts = ts, features = b).marshal(Mode.SEALED)
            }
        }
        assertTrue(Inner.validFeatures(tooMany.take(32)))
        assertTrue(Inner.validFeatures(listOf("a".repeat(32))))
        assertTrue(Inner.validFeatures(listOf("_", "a", "b_1")))
        assertTrue(Inner.validFeatures(DeviceFeatures.ALL))
        assertEquals(listOf("hs_confirm"), DeviceFeatures.ALL) // relay_follow is not advertised
    }

    /** An unknown member next to it is still ignored (§5.3), and `features` is checked wherever it sits. */
    @Test
    fun theMemberIsCheckedWhereverItSits() {
        val pj = String(Inner(id = id, type = "x.y", ts = ts).marshal(Mode.SEALED))
        val first = pj.replaceFirst("{", """{"features":["hs_confirm"],"zz":1,""")
        assertEquals(listOf("hs_confirm"), Inner.parse(first.toByteArray(), Mode.SEALED).features)
        val badFirst = pj.replaceFirst("{", """{"features":["Z"],""")
        assertThrows(CryptoException.Format::class.java) { Inner.parse(badFirst.toByteArray(), Mode.SEALED) }
    }
}
