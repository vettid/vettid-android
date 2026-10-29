package com.vettid.core.data.account

import com.vettid.core.data.account.AccountNames.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The registration rule for a requested name and the receiver's rule for core names (VAULT-MESSAGING 0.18.0/0.19.0 §10.8). */
class AccountNamesTest {
    @Test
    fun registrationRule() {
        for (ok in listOf("Ada", "King", "O'Brien", "O’Brien", "Jean-Luc", "St. John", "Zoë", "José", "Łukasz", "李", "Anne Marie")) {
            assertEquals(ok, Check.OK, AccountNames.check(ok))
            assertEquals(ok, ok, AccountNames.normalize(ok))
        }
        // Trimmed of U+0020 only (0.19.0).
        assertEquals("Ada", AccountNames.normalize("  Ada "))
        assertEquals(Check.INVALID, AccountNames.check("\tAda"))
        assertEquals(Check.INVALID, AccountNames.check("Ada "))
        assertEquals(Check.INVALID, AccountNames.check("Ada\n"))
        // The first character is a letter (or mark); then letters, marks, spaces and '’.-
        assertEquals(Check.INVALID, AccountNames.check("-Ada"))
        assertEquals(Check.INVALID, AccountNames.check("'Ada"))
        assertEquals(Check.INVALID, AccountNames.check("Ada1"))
        assertEquals(Check.INVALID, AccountNames.check("Ada_King"))
        assertEquals(Check.INVALID, AccountNames.check("Ada@"))
        assertEquals(Check.EMPTY, AccountNames.check(""))
        assertEquals(Check.EMPTY, AccountNames.check("   "))
        assertNull(AccountNames.normalize("   "))
        // At most 40 UTF-16 code units, counted after trimming.
        assertEquals(Check.OK, AccountNames.check("a".repeat(40)))
        assertEquals(Check.OK, AccountNames.check(" " + "a".repeat(40) + " "))
        assertEquals(Check.TOO_LONG, AccountNames.check("a".repeat(41)))
        // A supplementary letter counts as two units.
        val gothic = "𐌰" // U+10330, a letter
        assertEquals(Check.OK, AccountNames.check(gothic.repeat(20)))
        assertEquals(Check.TOO_LONG, AccountNames.check(gothic.repeat(20) + "a"))
    }

    @Test
    fun coreRule() {
        assertTrue(AccountNames.isValidCore("Ada"))
        assertTrue(AccountNames.isValidCore("a".repeat(160)))
        assertFalse(AccountNames.isValidCore("a".repeat(161)))
        assertTrue(AccountNames.isValidCore("é".repeat(80))) // 160 bytes
        assertFalse(AccountNames.isValidCore("é".repeat(80) + "a"))
        assertFalse(AccountNames.isValidCore(""))
        assertFalse(AccountNames.isValidCore(null))
        for (bad in listOf("\u0000", "\u001f", "\u0080", "\u009f", "\u2028", "\u2029", "\uD800")) {
            assertFalse(bad, AccountNames.isValidCore("Ada$bad"))
        }
        // Not the registration rule: the receiver takes what the snapshot carries.
        assertTrue(AccountNames.isValidCore("Ada 2"))
        assertTrue(AccountNames.isValidCore("Ada\u007f"))
    }

    @Test
    fun fullNameAndIsolation() {
        assertEquals("Ada King", AccountNames.full("Ada", "King"))
        assertNull(AccountNames.full("Ada", null))
        assertNull(AccountNames.full("", "King"))
        assertEquals("\u2068Ada King\u2069", AccountNames.isolate("Ada King"))
    }

    private val fsi = "\u2068"
    private val pdi = "\u2069"

    @Test
    fun everyBidiControlIsRemovedFromAReceivedName() {
        val controls = listOf(
            "LRE" to "\u202A", "RLE" to "\u202B", "PDF" to "\u202C", "LRO" to "\u202D", "RLO" to "\u202E",
            "LRI" to "\u2066", "RLI" to "\u2067", "FSI" to "\u2068", "PDI" to "\u2069",
            "LRM" to "\u200E", "RLM" to "\u200F", "ALM" to "\u061C",
        )
        for ((label, c) in controls) {
            assertEquals(label, "${fsi}AdaKing$pdi", AccountNames.isolate("Ada${c}King"))
            assertEquals(label, "AdaKing", AccountNames.cleaned("${c}Ada${c}King$c"))
        }
    }

    @Test
    fun zeroWidthByteOrderMarkAndTagCharactersAreRemoved() {
        assertEquals("AdaKing", AccountNames.cleaned("Ada\u200BKing"))
        assertEquals("AdaKing", AccountNames.cleaned("\uFEFFAdaKing"))
        // Tag characters (U+E0020..U+E007F) are outside the BMP: surrogate pairs.
        assertEquals("Ada", AccountNames.cleaned("A\uDB40\uDC20d\uDB40\uDC41a\uDB40\uDC7F"))
    }

    @Test
    fun lineBreaksBecomeSpacesAndTheNameIsTrimmed() {
        for (br in listOf("\n", "\r\n", "\r", "\u2028", "\u2029", "\u0085", "\t")) {
            assertEquals(br, "${fsi}Ada King$pdi", AccountNames.isolate("Ada${br}King"))
        }
        assertEquals("${fsi}Ada$pdi", AccountNames.isolate("\n  Ada \u2029"))
    }

    @Test
    fun zwjAndZwnjAreKept() {
        assertEquals("$fsi\uD83D\uDC69\u200D\uD83D\uDCBB$pdi", AccountNames.isolate("\uD83D\uDC69\u200D\uD83D\uDCBB"))
        assertEquals("${fsi}می\u200Cخواهم$pdi", AccountNames.isolate("می\u200Cخواهم"))
    }

    @Test
    fun nothingLeftIsNullAndIsolationIsIdempotent() {
        assertNull(AccountNames.isolateOrNull(null))
        assertNull(AccountNames.isolateOrNull(""))
        assertNull(AccountNames.isolateOrNull(" \u202E\u202C\u200B\n "))
        assertEquals("$fsi$pdi", AccountNames.isolate("\u202E"))
        val once = AccountNames.isolate("Bank")
        assertEquals(once, AccountNames.isolate(once))
        assertEquals("Bank", AccountNames.cleaned(once))
    }

    @Test
    fun aClosingIsolateCannotEscapeAndReverseTheFixedWords() {
        val out = AccountNames.isolate("Bank${pdi}\u202E")
        assertEquals("${fsi}Bank$pdi", out)
        assertEquals(1, out.count { it == '\u2069' })
    }
}
