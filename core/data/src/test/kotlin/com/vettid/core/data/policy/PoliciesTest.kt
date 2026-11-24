package com.vettid.core.data.policy

import com.vettid.core.data.policy.PasswordPolicy.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {
    /** A PIN the member already has takes the vault PIN's form (§11.3): 6 to 32 ASCII digits, nothing about its strength. */
    @Test
    fun wellFormedIsTheVaultPinsForm() {
        for (ok in listOf("111111", "402817", "1".repeat(32))) assertTrue(ok, PinPolicy.wellFormed(ok))
        for (bad in listOf("", "40281", "1".repeat(33), "40281a", "40281 7", "\u0664\u0660\u0662\u0668\u0661\u0667")) {
            assertFalse(bad, PinPolicy.wellFormed(bad))
        }
    }

    /** A PIN field keeps ASCII digits only, at most 32: never the other Unicode digits `isDigit` takes. */
    @Test
    fun digitsKeepsAsciiDigitsOnly() {
        val arabicIndic = (0x0661..0x0666).map { Char(it) }.joinToString("")
        val fullwidth = (0xFF11..0xFF16).map { Char(it) }.joinToString("")
        val devanagari = (0x0967..0x096C).map { Char(it) }.joinToString("")
        for (other in listOf(arabicIndic, fullwidth, devanagari)) {
            assertTrue(other.all { it.isDigit() }) // what the fields used to let in
            assertEquals("", PinPolicy.digits(other))
            assertFalse(PinPolicy.wellFormed(other))
        }
        assertEquals("402817", PinPolicy.digits("4a0 2-8$fullwidth" + "17"))
        assertEquals("1".repeat(32), PinPolicy.digits("1".repeat(40)))
    }

    @Test
    fun pinRules() {
        assertEquals(PinPolicy.Problem.NOT_DIGITS, PinPolicy.check(""))
        assertEquals(PinPolicy.Problem.NOT_DIGITS, PinPolicy.check("12a456"))
        assertEquals(PinPolicy.Problem.TOO_SHORT, PinPolicy.check("4821"))
        assertEquals(PinPolicy.Problem.TOO_LONG, PinPolicy.check("4".repeat(16) + "9".repeat(17)))
        assertEquals(PinPolicy.Problem.REPEATED, PinPolicy.check("000000"))
        assertEquals(PinPolicy.Problem.SEQUENCE, PinPolicy.check("123456"))
        assertEquals(PinPolicy.Problem.SEQUENCE, PinPolicy.check("987654"))
        assertEquals(PinPolicy.Problem.SEQUENCE, PinPolicy.check("890123"))
        assertEquals(PinPolicy.Problem.PATTERN, PinPolicy.check("454545"))
        assertEquals(PinPolicy.Problem.PATTERN, PinPolicy.check("846846"))
        assertEquals(PinPolicy.Problem.COMMON, PinPolicy.check("696969"))
        assertNull(PinPolicy.check("97531086"))
        assertNull(PinPolicy.check("402817"))
        // The spec allows 32 digits.
        assertNull(PinPolicy.check("40281795".repeat(4).replaceRange(0, 1, "5")))
    }

    @Test
    fun passwordRules() {
        assertEquals(PasswordPolicy.Problem.TOO_SHORT, PasswordPolicy.check("short1!"))
        assertEquals(PasswordPolicy.Problem.TOO_LONG, PasswordPolicy.check("x".repeat(1025)))
        // 8 bytes is the spec's minimum, counted in UTF-8 bytes.
        assertEquals(PasswordPolicy.Problem.TOO_LONG, PasswordPolicy.check("é".repeat(513)))
        assertEquals(PasswordPolicy.Problem.CONTROL_CHARACTERS, PasswordPolicy.check("Tab\tInside-Password-1"))
        assertEquals(PasswordPolicy.Problem.TOO_WEAK, PasswordPolicy.check("password"))
        assertEquals(PasswordPolicy.Problem.TOO_WEAK, PasswordPolicy.check("12345678901"))
        assertEquals(PasswordPolicy.Problem.SAME_AS_PIN, PasswordPolicy.check("9753108642975310", pin = "9753108642975310"))
        assertNull(PasswordPolicy.check("correct horse battery staple (test)"))
        assertNull(PasswordPolicy.check("Tr0ub4dor&3x!"))
    }

    @Test
    fun strengthGrows() {
        assertEquals(Strength.TOO_SHORT, PasswordPolicy.strength("abc"))
        assertEquals(Strength.WEAK, PasswordPolicy.strength("aaaaaaaaaa"))
        assertTrue(PasswordPolicy.strength("Tr0ub4dor&3x!") >= Strength.GOOD)
        assertEquals(Strength.STRONG, PasswordPolicy.strength("correct horse battery staple (test)"))
    }

    @Test
    fun deletePhrase() {
        assertTrue(DeletePhrase.matches(" delete my vault "))
        assertTrue(!DeletePhrase.matches("Delete my vault"))
    }

    /** VAULT-MESSAGING §11.14.7 on the phone, in the vault's order, with the app's own limits first. */
    @Test
    fun releaseRPasswordRules() {
        assertEquals(PasswordRule.SHORT, PasswordRule.check("", null))
        assertEquals(PasswordRule.SHORT, PasswordRule.check("elevenchars", null))
        assertEquals(PasswordRule.REPEATED, PasswordRule.check("zzzzzzzzzzzzzzzz", null))
        assertEquals(PasswordRule.COMMON, PasswordRule.check("password1234", null))
        assertEquals(PasswordRule.COMMON, PasswordRule.check("PASSWORD1234", null))
        assertEquals(PasswordRule.SAME_AS_PIN, PasswordRule.check("975310864208", "975310864208"))
        assertEquals(PasswordRule.GUESSABLE, PasswordRule.check("123456789123", null))
        assertEquals(PasswordRule.OK, PasswordRule.check("correct horse battery staple (test)", null))
        assertEquals(PasswordRule.OK, PasswordRule.check("correct horse battery staple (test)", ""))
        assertEquals(PasswordRule.CONTROL_CHARACTERS, PasswordRule.check("Tab\tInside-Password-1", null))
        assertEquals(PasswordRule.TOO_LONG, PasswordRule.check("é".repeat(513), null))
        // Characters, not bytes: twelve two-byte letters are long enough.
        assertNotEquals(PasswordRule.SHORT, PasswordRule.check("éàüöñçøåæßðþ", null))
        assertTrue(PasswordRule.OK.passes)
        assertFalse(PasswordRule.GUESSABLE.passes)
    }

    @Test
    fun theVaultsReasons() {
        assertEquals(PasswordRule.SHORT, PasswordRule.fromVault("short"))
        assertEquals(PasswordRule.REPEATED, PasswordRule.fromVault("repeated"))
        assertEquals(PasswordRule.COMMON, PasswordRule.fromVault("common"))
        assertEquals(PasswordRule.SAME_AS_PIN, PasswordRule.fromVault("same_as_pin"))
        assertEquals(PasswordRule.GUESSABLE, PasswordRule.fromVault("guessable"))
        assertEquals(PasswordRule.VAULT_REFUSED, PasswordRule.fromVault("something_new"))
        assertEquals(PasswordRule.VAULT_REFUSED, PasswordRule.fromVault(null))
        assertFalse(PasswordRule.VAULT_REFUSED.passes)
    }
}
