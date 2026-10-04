package com.vettid.core.data.policy

import com.vettid.core.data.policy.PasswordPolicy.Strength
import org.junit.Assert.assertEquals
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
}
