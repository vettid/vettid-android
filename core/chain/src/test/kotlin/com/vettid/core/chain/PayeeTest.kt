package com.vettid.core.chain

import org.junit.Assert.assertEquals
import org.junit.Test

class PayeeTest {
    private val regtest = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
    private val mainnet = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    private val testnet = "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl"

    @Test
    fun anAddressOfTheWalletsNetworkIsOkWithItsScriptSize() {
        assertEquals(PayeeCheck.Ok(22), Payee.check(Network.REGTEST, regtest))
        assertEquals(PayeeCheck.Ok(22), Payee.check(Network.TESTNET4, testnet))
        assertEquals(PayeeCheck.Ok(22), Payee.check(Network.SIGNET, testnet))
        assertEquals(PayeeCheck.Ok(22), Payee.check(Network.MAINNET, mainnet))
    }

    @Test
    fun anAddressOfAnotherNetworkNamesWhichOne() {
        assertEquals(PayeeCheck.WrongNetwork(AddressFamily.MAINNET), Payee.check(Network.TESTNET4, mainnet))
        assertEquals(PayeeCheck.WrongNetwork(AddressFamily.TEST), Payee.check(Network.MAINNET, testnet))
        assertEquals(PayeeCheck.WrongNetwork(AddressFamily.REGTEST), Payee.check(Network.SIGNET, regtest))
    }

    @Test
    fun textThatIsNotAnAddressIsInvalid() {
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.TESTNET4, "not an address"))
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.TESTNET4, ""))
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.TESTNET4, testnet.dropLast(1) + "q"))
    }
}
