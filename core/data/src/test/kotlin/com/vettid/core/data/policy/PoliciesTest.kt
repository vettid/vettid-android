package com.vettid.core.data.policy

import com.vettid.core.data.policy.PasswordPolicy.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {
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
        assertNull(PasswordRule.fromVault("something_new"))
        assertNull(PasswordRule.fromVault(null))
    }
}
