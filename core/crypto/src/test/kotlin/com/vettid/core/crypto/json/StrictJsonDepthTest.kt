package com.vettid.core.crypto.json

import com.vettid.core.crypto.CryptoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The nesting limit, and a lower one for a value that will sit inside another document (VAULT-MESSAGING 0.81.6 §10.19.8). */
class StrictJsonDepthTest {
    /** An object nested [depth] deep in all (the outer one counts 1). */
    private fun nested(depth: Int): String = "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1)

    @Test
    fun theLimitIs32AndTheOuterObjectCountsOne() {
        assertEquals(32, StrictJson.MAX_DEPTH)
        StrictJson.parseObject(nested(32))
        assertThrows(CryptoException::class.java) { StrictJson.parseObject(nested(33)) }
    }

    @Test
    fun aLowerLimitForAValueThatGoesInsideAnother() {
        StrictJson.parseObject(nested(28).toByteArray(), 28)
        assertThrows(CryptoException::class.java) { StrictJson.parseObject(nested(29).toByteArray(), 28) }
        // An array counts as a level too.
        assertThrows(CryptoException::class.java) { StrictJson.parseObject("{\"a\":[[{}]]}".toByteArray(), 3) }
        StrictJson.parseObject("{\"a\":[[{}]]}".toByteArray(), 4)
    }
}
