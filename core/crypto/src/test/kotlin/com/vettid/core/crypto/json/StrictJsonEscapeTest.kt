package com.vettid.core.crypto.json

import com.vettid.core.crypto.CryptoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * VAULT-MESSAGING 0.83.1 (vettid.org #508): what the vault and the iPhone app refuse, the app never accepts.
 * `\uXXXX` takes ASCII hex digits only, a surrogate escape must be a high one followed at once by a `\u` low one, and a
 * leading byte-order mark is refused.
 */
class StrictJsonEscapeTest {
    private fun refused(json: String) {
        assertThrows(json, CryptoException.Format::class.java) { StrictJson.parseObject(json) }
    }

    private fun refused(bytes: ByteArray) {
        assertThrows(CryptoException.Format::class.java) { StrictJson.parseObject(bytes) }
    }

    private fun value(json: String): String = (StrictJson.parseObject(json)["a"] as JsonString).value

    @Test
    fun asciiHexDigitsInBothCasesAreRead() {
        assertEquals("A", value("""{"a":"\u0041"}"""))
        assertEquals("\u00e9", value("""{"a":"\u00E9"}"""))
        assertEquals("\u00e9", value("""{"a":"\u00e9"}"""))
    }

    @Test
    fun aNonAsciiDigitInAnEscapeIsRefused() {
        // Fullwidth zero (U+FF10), Arabic-Indic zero (U+0660), Devanagari zero (U+0966), fullwidth A (U+FF21): Character.digit
        // reads all of them as hex digits; the vault does not.
        for (d in listOf('\uff10', '\u0660', '\u0966', '\uff21')) {
            refused("{\"a\":\"\\u${d}${d}41\"}")
            refused("{\"a\":\"\\u00${d}1\"}")
        }
    }

    @Test
    fun aSurrogatePairIsOneCharacter() {
        assertEquals("\ud83d\ude00", value("""{"a":"\ud83d\ude00"}"""))
        assertEquals("\ud83d\ude00", value("""{"a":"\uD83D\uDE00"}"""))
    }

    @Test
    fun anUnpairedSurrogateEscapeIsRefusedNotReplaced() {
        // A high surrogate at the end of the string, before other text, before a non-low escape, or before a high one.
        refused("""{"a":"\ud83d"}""")
        refused("""{"a":"\ud83dx"}""")
        refused("""{"a":"\ud83d\u0041"}""")
        refused("""{"a":"\ud83d\ud83d"}""")
        refused("""{"a":"\ud83d\n"}""")
        // A lone low surrogate.
        refused("""{"a":"\ude00"}""")
        refused("""{"a":"x\udfff"}""")
        // In a member name too.
        refused("""{"\ud83d":1}""")
    }

    @Test
    fun aLeadingByteOrderMarkIsRefused() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        refused(bom + """{"a":"b"}""".toByteArray())
        refused(bom)
        // An escaped U+FEFF inside a string is just a character.
        assertEquals("\ufeff", value("""{"a":"\ufeff"}"""))
    }
}
