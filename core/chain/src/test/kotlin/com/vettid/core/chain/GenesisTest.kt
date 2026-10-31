package com.vettid.core.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The built-in genesis hashes (ANDROID-PLAN §4 "The chain source"), against bitcoin-kmp's own blocks. */
class GenesisTest {
    @Test
    fun eachBuiltInHashIsBitcoinKmpsGenesisInDisplayOrder() {
        for (n in listOf(Network.MAINNET, Network.TESTNET, Network.TESTNET4, Network.SIGNET)) {
            assertEquals(n.name, n.chainHash.value.toByteArray().toDisplayHex(), Genesis.of(n))
            assertEquals(n, Genesis.networkOf(Genesis.of(n)!!))
        }
    }

    @Test
    fun theWellKnownMainnetHashFixesTheByteOrder() {
        assertEquals("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", Genesis.of(Network.MAINNET))
    }

    @Test
    fun regtestHasNone() {
        assertNull(Genesis.of(Network.REGTEST))
        assertNull(Genesis.networkOf(Network.REGTEST.chainHash.value.toByteArray().toDisplayHex()))
    }
}
