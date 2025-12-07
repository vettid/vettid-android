package com.vettid.app.features.enrollment

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PasswordStrength calculation
 */
class PasswordStrengthTest {

    // MARK: - Weak Passwords

    @Test
    fun `empty password is weak`() {
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate(""))
    }

    @Test
    fun `very short password is weak`() {
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("abc"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("1234567"))
    }

    @Test
    fun `common word password is weak`() {
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("password"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("qwerty12"))
    }

    @Test
    fun `all lowercase password is weak`() {
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("abcdefghij"))
    }

    // MARK: - Fair Passwords

    @Test
    fun `mixed case short password is fair`() {
        assertEquals(PasswordStrength.FAIR, PasswordStrength.calculate("Password"))
    }

    @Test
    fun `lowercase with numbers is fair`() {
        assertEquals(PasswordStrength.FAIR, PasswordStrength.calculate("password123"))
    }

    @Test
    fun `mixed case with numbers is fair`() {
        assertEquals(PasswordStrength.FAIR, PasswordStrength.calculate("Password1"))
    }

    // MARK: - Good Passwords

    @Test
    fun `mixed case with numbers and symbol is good`() {
        assertEquals(PasswordStrength.GOOD, PasswordStrength.calculate("Password1!"))
    }

    @Test
    fun `12 char mixed case with number is good`() {
        assertEquals(PasswordStrength.GOOD, PasswordStrength.calculate("MyPassword12"))
    }

    // MARK: - Strong Passwords

    @Test
    fun `long password with all character types is strong`() {
        assertEquals(PasswordStrength.STRONG, PasswordStrength.calculate("MyStr0ng!P@ssword"))
    }

    @Test
    fun `16 plus char password with symbols is strong`() {
        assertEquals(PasswordStrength.STRONG, PasswordStrength.calculate("ThisIs@V3ryStr0ng!"))
    }

    @Test
    fun `password with special chars only mixed case and numbers is strong`() {
        assertEquals(PasswordStrength.STRONG, PasswordStrength.calculate("Abc123!@#DefGhi"))
    }

    // MARK: - Edge Cases

    @Test
    fun `password with consecutive characters is penalized`() {
        val withConsecutive = PasswordStrength.calculate("Passssword1!")
        val withoutConsecutive = PasswordStrength.calculate("Password1!")
        // The one with consecutive chars should be equal or lower strength
        assertTrue(withConsecutive.ordinal <= withoutConsecutive.ordinal)
    }

    @Test
    fun `unicode characters are handled`() {
        // Should not throw and should calculate something
        val strength = PasswordStrength.calculate("Pässwörd123!")
        assertNotNull(strength)
    }

    @Test
    fun `password at minimum length boundary`() {
        // 8 chars is the minimum to not be immediately weak
        val sevenChar = PasswordStrength.calculate("Abcde1!")
        val eightChar = PasswordStrength.calculate("Abcdef1!")

        assertEquals(PasswordStrength.WEAK, sevenChar)
        // 8 chars with good variety should be better than weak
        assertTrue(eightChar.ordinal >= PasswordStrength.FAIR.ordinal)
    }

    @Test
    fun `only numbers is weak`() {
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("12345678"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("123456789012"))
    }

    @Test
    fun `only special characters is weak`() {
        assertEquals(PasswordStrength.WEAK, PasswordStrength.calculate("!@#$%^&*"))
    }

    // MARK: - Real-world Examples

    @Test
    fun `passphrase style password is strong`() {
        assertEquals(PasswordStrength.STRONG, PasswordStrength.calculate("Correct-Horse-Battery-Staple!"))
    }

    @Test
    fun `short but complex password is good`() {
        // Short but has all character types
        assertEquals(PasswordStrength.GOOD, PasswordStrength.calculate("Aa1!Bb2@"))
    }
}
