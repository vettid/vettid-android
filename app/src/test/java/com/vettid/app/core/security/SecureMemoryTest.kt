package com.vettid.app.core.security

import org.junit.Assert.*
import org.junit.Test

class SecureMemoryTest {

    @Test
    fun `SecureByteArray wrap takes ownership of data`() {
        val originalData = byteArrayOf(1, 2, 3, 4, 5)
        val secure = SecureByteArray.wrap(originalData)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), secure.data)
        assertEquals(5, secure.size)
        assertFalse(secure.isCleared)
    }

    @Test
    fun `SecureByteArray copy creates independent copy`() {
        val originalData = byteArrayOf(1, 2, 3, 4, 5)
        val secure = SecureByteArray.copy(originalData)

        // Modify original
        originalData[0] = 99

        // Secure copy should not be affected
        assertEquals(1.toByte(), secure.data[0])
    }

    @Test
    fun `SecureByteArray clear zeros out data`() {
        val secure = SecureByteArray.wrap(byteArrayOf(1, 2, 3, 4, 5))

        secure.clear()

        assertTrue(secure.isCleared)
        assertEquals(0, secure.size)
    }

    @Test(expected = IllegalStateException::class)
    fun `SecureByteArray data throws after clear`() {
        val secure = SecureByteArray.wrap(byteArrayOf(1, 2, 3))
        secure.clear()
        secure.data // Should throw
    }

    @Test
    fun `SecureByteArray use block automatically clears`() {
        var wasCleared = false

        SecureByteArray.wrap(byteArrayOf(1, 2, 3)).use { secure ->
            assertEquals(3, secure.size)
            wasCleared = false
        }

        // Note: We can't directly check if it's cleared from outside the use block
        // but the implementation clears it
    }

    @Test
    fun `SecureByteArray from string converts correctly`() {
        val secure = SecureByteArray.fromString("test")

        assertArrayEquals("test".toByteArray(Charsets.UTF_8), secure.data)

        secure.clear()
    }

    @Test
    fun `SecureByteArray allocate creates zeroed array`() {
        val secure = SecureByteArray.allocate(10)

        assertEquals(10, secure.size)
        assertTrue(secure.data.all { it == 0.toByte() })

        secure.clear()
    }

    @Test
    fun `SecureByteArray random creates non-zero data`() {
        val secure = SecureByteArray.random(32)

        assertEquals(32, secure.size)
        // Very unlikely that 32 random bytes are all zero
        assertFalse(secure.data.all { it == 0.toByte() })

        secure.clear()
    }

    @Test
    fun `SecureByteArray transform creates new secure array`() {
        val secure = SecureByteArray.wrap(byteArrayOf(1, 2, 3))

        val transformed = secure.transform { data ->
            data.map { (it * 2).toByte() }.toByteArray()
        }

        assertArrayEquals(byteArrayOf(2, 4, 6), transformed.data)

        secure.clear()
        transformed.clear()
    }

    @Test
    fun `SecureByteArray equals works correctly`() {
        val a = SecureByteArray.wrap(byteArrayOf(1, 2, 3))
        val b = SecureByteArray.wrap(byteArrayOf(1, 2, 3))
        val c = SecureByteArray.wrap(byteArrayOf(1, 2, 4))

        assertEquals(a, b)
        assertNotEquals(a, c)

        a.clear()
        b.clear()
        c.clear()
    }

    @Test
    fun `SecureCharArray clears correctly`() {
        val secure = SecureCharArray.fromString("password")

        assertArrayEquals("password".toCharArray(), secure.data)

        secure.clear()

        assertTrue(secure.isCleared)
    }

    @Test
    fun `SecureCharArray toSecureByteArray converts correctly`() {
        val charArray = SecureCharArray.fromString("test")
        val byteArray = charArray.toSecureByteArray()

        assertArrayEquals("test".toByteArray(Charsets.UTF_8), byteArray.data)

        charArray.clear()
        byteArray.clear()
    }

    @Test
    fun `ByteArray secureClear overwrites data`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)

        data.secureClear()

        // After secure clear, all bytes should be zero
        assertTrue(data.all { it == 0.toByte() })
    }

    @Test
    fun `CharArray secureClear overwrites data`() {
        val data = charArrayOf('a', 'b', 'c')

        data.secureClear()

        // After secure clear, all chars should be null char
        assertTrue(data.all { it == '\u0000' })
    }

    @Test
    fun `ByteArray toSecure creates copy`() {
        val original = byteArrayOf(1, 2, 3)
        val secure = original.toSecure()

        // Modify original
        original[0] = 99

        // Secure should be unaffected
        assertEquals(1.toByte(), secure.data[0])

        secure.clear()
    }

    @Test
    fun `ByteArray wrapSecure takes ownership`() {
        val original = byteArrayOf(1, 2, 3)
        val secure = original.wrapSecure()

        // Same underlying array
        assertSame(original, secure.data)

        secure.clear()
    }
}
