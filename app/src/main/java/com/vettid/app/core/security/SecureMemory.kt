package com.vettid.app.core.security

import java.io.Closeable
import java.security.SecureRandom
import java.util.Arrays

/**
 * Secure memory handling utilities
 *
 * Features:
 * - Auto-clearing byte arrays
 * - Secure string handling
 * - Memory overwrite on clear
 */

/**
 * A byte array wrapper that automatically clears its contents
 * when closed or garbage collected.
 *
 * Usage:
 * ```kotlin
 * SecureByteArray.wrap(sensitiveData).use { secure ->
 *     // Use secure.data
 * } // Automatically cleared
 * ```
 */
class SecureByteArray private constructor(
    private var _data: ByteArray?
) : Closeable {

    /**
     * Access the underlying data
     * @throws IllegalStateException if already cleared
     */
    val data: ByteArray
        get() = _data ?: throw IllegalStateException("SecureByteArray has been cleared")

    /**
     * Check if data is still available
     */
    val isCleared: Boolean
        get() = _data == null

    /**
     * Get the size of the data
     */
    val size: Int
        get() = _data?.size ?: 0

    companion object {
        private val secureRandom = SecureRandom()

        /**
         * Wrap existing byte array (does NOT copy - takes ownership)
         */
        fun wrap(data: ByteArray): SecureByteArray {
            return SecureByteArray(data)
        }

        /**
         * Create a copy of the data in secure memory
         */
        fun copy(data: ByteArray): SecureByteArray {
            return SecureByteArray(data.copyOf())
        }

        /**
         * Create a new secure byte array of specified size
         */
        fun allocate(size: Int): SecureByteArray {
            return SecureByteArray(ByteArray(size))
        }

        /**
         * Create a secure byte array from a string (UTF-8)
         * The string should also be cleared after this call if possible
         */
        fun fromString(str: String): SecureByteArray {
            return SecureByteArray(str.toByteArray(Charsets.UTF_8))
        }

        /**
         * Create a secure byte array with random data
         */
        fun random(size: Int): SecureByteArray {
            val data = ByteArray(size)
            secureRandom.nextBytes(data)
            return SecureByteArray(data)
        }
    }

    /**
     * Clear the data by overwriting with random bytes, then zeros
     */
    override fun close() {
        clear()
    }

    /**
     * Explicitly clear the data
     */
    fun clear() {
        _data?.let { data ->
            // First pass: overwrite with random data
            secureRandom.nextBytes(data)
            // Second pass: overwrite with zeros
            Arrays.fill(data, 0.toByte())
            // Third pass: overwrite with ones
            Arrays.fill(data, 0xFF.toByte())
            // Final pass: zeros again
            Arrays.fill(data, 0.toByte())
        }
        _data = null
    }

    /**
     * Copy data to a new secure array (useful for transformations)
     */
    fun copyTo(): SecureByteArray {
        return copy(data)
    }

    /**
     * Apply a transformation and return new secure array
     * Original is NOT cleared - caller should clear if needed
     */
    inline fun transform(block: (ByteArray) -> ByteArray): SecureByteArray {
        return wrap(block(data))
    }

    /**
     * Use the data and automatically clear after
     */
    inline fun <T> use(block: (SecureByteArray) -> T): T {
        return try {
            block(this)
        } finally {
            clear()
        }
    }

    protected fun finalize() {
        // Safety net - clear on garbage collection
        clear()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecureByteArray) return false
        val thisData = _data
        val otherData = other._data
        if (thisData == null || otherData == null) return thisData == otherData
        return thisData.contentEquals(otherData)
    }

    override fun hashCode(): Int {
        return _data?.contentHashCode() ?: 0
    }
}

/**
 * A char array wrapper that automatically clears its contents
 * Useful for password handling
 */
class SecureCharArray private constructor(
    private var _data: CharArray?
) : Closeable {

    val data: CharArray
        get() = _data ?: throw IllegalStateException("SecureCharArray has been cleared")

    val isCleared: Boolean
        get() = _data == null

    val size: Int
        get() = _data?.size ?: 0

    companion object {
        /**
         * Wrap existing char array (takes ownership)
         */
        fun wrap(data: CharArray): SecureCharArray {
            return SecureCharArray(data)
        }

        /**
         * Create from string (string should be cleared after if possible)
         */
        fun fromString(str: String): SecureCharArray {
            return SecureCharArray(str.toCharArray())
        }

        /**
         * Create a copy
         */
        fun copy(data: CharArray): SecureCharArray {
            return SecureCharArray(data.copyOf())
        }
    }

    override fun close() {
        clear()
    }

    fun clear() {
        _data?.let { data ->
            // Overwrite with zeros
            Arrays.fill(data, '\u0000')
            // Overwrite with random chars
            for (i in data.indices) {
                data[i] = (SecureRandom().nextInt(65536)).toChar()
            }
            // Final zeros
            Arrays.fill(data, '\u0000')
        }
        _data = null
    }

    /**
     * Convert to SecureByteArray (UTF-8)
     */
    fun toSecureByteArray(): SecureByteArray {
        val bytes = String(data).toByteArray(Charsets.UTF_8)
        return SecureByteArray.wrap(bytes)
    }

    inline fun <T> use(block: (SecureCharArray) -> T): T {
        return try {
            block(this)
        } finally {
            clear()
        }
    }

    protected fun finalize() {
        clear()
    }
}

/**
 * Extension functions for secure memory handling
 */

/**
 * Clear a byte array securely
 */
fun ByteArray.secureClear() {
    val random = SecureRandom()
    // Multiple overwrite passes
    random.nextBytes(this)
    Arrays.fill(this, 0.toByte())
    Arrays.fill(this, 0xFF.toByte())
    Arrays.fill(this, 0.toByte())
}

/**
 * Clear a char array securely
 */
fun CharArray.secureClear() {
    Arrays.fill(this, '\u0000')
}

/**
 * Clear a string by creating a char array and clearing it
 * Note: String is immutable, so this only works if JVM reuses the internal array
 * For truly secure string handling, use SecureCharArray instead
 */
fun String.toSecureCharArray(): SecureCharArray {
    return SecureCharArray.fromString(this)
}

/**
 * Create a SecureByteArray from ByteArray (copies data)
 */
fun ByteArray.toSecure(): SecureByteArray {
    return SecureByteArray.copy(this)
}

/**
 * Wrap ByteArray in SecureByteArray (takes ownership, no copy)
 */
fun ByteArray.wrapSecure(): SecureByteArray {
    return SecureByteArray.wrap(this)
}
