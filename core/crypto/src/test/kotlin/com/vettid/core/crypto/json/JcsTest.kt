package com.vettid.core.crypto.json

import com.vettid.core.crypto.CryptoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 8785 as vettid-vault `vms/jcs` writes it (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents"). */
class JcsTest {
    private fun canonical(s: String): String = String(Jcs.canonical(StrictJson.parseObject(s)), Charsets.UTF_8)

    @Test
    fun sortsTheMembersByUtf16CodeUnitsAtEveryDepth() {
        // U+1F600 is D83D DE00 in UTF-16, before U+E000; by code point it would come after.
        val grin = String(Character.toChars(0x1F600))
        val pua = "\uE000"
        assertEquals(
            "{\"b\":{\"a\":null,\"z\":[]},\"${grin}\":2,\"${pua}\":1}",
            canonical("{\"${pua}\":1,\"${grin}\":2,\"b\":{\"z\":[],\"a\":null}}"),
        )
        assertEquals("{\"B\":3,\"a\":2,\"ab\":1}", canonical("{\"ab\":1,\"a\":2,\"B\":3}"))
    }

    @Test
    fun escapesAsEcmaScript() {
        // In: an escaped A, an escaped slash, the two-character escapes, two controls, U+007F, an escaped e-acute, U+2028.
        val input = "{\"s\":\"\\u0041\\/\\\"\\\\\\b\\f\\n\\r\\t\\u0001\\u001f\\u007f\\u00e9\\u2028\"}"
        val want = "{\"s\":\"A/\\\"\\\\\\b\\f\\n\\r\\t\\u0001\\u001f\u007F\u00E9\u2028\"}"
        assertEquals(want, canonical(input))
    }

    @Test
    fun writesNoWhiteSpaceAndKeepsArrayOrder() {
        assertEquals("{\"a\":[3,1,2],\"f\":false,\"t\":true}", canonical(" { \"a\" : [ 3 , 1 , 2 ] , \"t\" : true , \"f\" : false } "))
    }

    @Test
    fun refusesEveryNumberButAPlainSafeInteger() {
        for (n in listOf("-1", "1.5", "1e3", "1E3", "-0", "0.0", "9007199254740992", "12345678901234567890")) {
            assertThrows(n, CryptoException::class.java) { canonical("{\"n\":$n}") }
            assertThrows(n, CryptoException::class.java) { canonical("{\"n\":[{\"m\":$n}]}") }
        }
        assertEquals("{\"n\":9007199254740991,\"z\":0}", canonical("{\"n\":9007199254740991,\"z\":0}"))
        assertTrue(Jcs.isInteger("0"))
        assertFalse(Jcs.isInteger("007"))
        assertFalse(Jcs.isInteger(""))
        assertFalse(Jcs.isInteger("+1"))
    }
}
