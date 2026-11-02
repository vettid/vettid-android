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

    // --- W2-1 review: the encodings the vault would read differently ---

    @Test
    fun upperCaseBech32IsAcceptedAndMixedCaseRefused() {
        assertEquals(PayeeCheck.Ok(22), Payee.check(Network.TESTNET4, testnet.uppercase()))
        val mixed = testnet.substring(0, 10).uppercase() + testnet.substring(10)
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.TESTNET4, mixed))
    }

    @Test
    fun theBech32AndBech32mMismatchIsRefused() {
        // BIP350 invalid examples: a v1+ program in bech32, a v0 program in bech32m.
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.MAINNET, "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd"))
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.TESTNET4, "tb1z0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqglt7rf"))
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.MAINNET, "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh"))
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.TESTNET4, "tb1q0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq24jc47"))
        // A valid taproot address (bech32m) is Ok.
        assertEquals(PayeeCheck.Ok(34), Payee.check(Network.TESTNET4, "tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesf3hn0c"))
    }

    @Test
    fun aBadBase58ChecksumIsRefused() {
        assertEquals(PayeeCheck.Ok(25), Payee.check(Network.MAINNET, "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"))
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.MAINNET, "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN3"))
    }

    /** Refused, not trimmed: the vault's summary is compared with the string as entered, so the screen trims what it takes in. */
    @Test
    fun aLeadingOrTrailingSpaceIsRefused() {
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.TESTNET4, " $testnet"))
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.TESTNET4, "$testnet "))
    }

    /** The vault pays only P2PKH, P2SH, P2WPKH, P2WSH and P2TR (`output_script`); a witness v2 address is a script it refuses. */
    @Test
    fun aWitnessVersionTwoAddressIsRefusedAsTheVaultRefusesItsScript() {
        assertEquals(PayeeCheck.Invalid, Payee.check(Network.MAINNET, "bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs"))
    }
}
