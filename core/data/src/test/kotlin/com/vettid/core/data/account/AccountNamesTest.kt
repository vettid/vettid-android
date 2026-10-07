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
        for (bad in listOf("\u0000", "\u001f", "\u0080", "\u009f", " ", " ", "\uD800")) {
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
        assertEquals("⁨Ada King⁩", AccountNames.isolate("Ada King"))
    }
}
