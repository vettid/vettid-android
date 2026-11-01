// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import com.vettid.core.chain.Network
import com.vettid.core.vault.VaultJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire form of `<wallet>` and `<address>` (VAULT-MESSAGING §10.18), and the check of the app's derivation against the vault's
 * addresses before any look-up (ANDROID-PLAN §4 "The chain source", "Which addresses").
 */
class AddressCheckTest {
    private val receive = AddressChain("p2tr", false)
    private val receive84 = AddressChain("p2wpkh", false)

    private fun issued(vararg chains: Pair<AddressChain, Int>) = chains.associate { (c, n) -> c to (0 until n).map { WalletFixtures.address(c, it) } }

    @Test
    fun theWalletReadsAsTheVaultSendsIt() {
        val w = WalletFixtures.wallet()
        assertEquals("Savings", w.name)
        assertEquals(Network.TESTNET4, w.network)
        assertEquals("p2tr", w.addressType)
        assertEquals(listOf("p2tr", "p2wpkh"), w.accounts.map { it.type })
        assertEquals(1, w.account("p2tr")!!.nextReceive)
        // A legacy `testnet` wallet does not say which test chain it is on.
        assertNull(WalletFixtures.wallet(network = "testnet").network)
        assertEquals("VaultWallet", "$w")
        assertEquals("VaultAccount", "${w.accounts[0]}")
    }

    @Test
    fun aMissingFieldNamesTheFieldNeverAValue() {
        val bad = WalletFixtures.walletJson().replace("\"fingerprint\":\"73c5da0a\",", "")
        val e = runCatching { WalletWire.wallet(VaultJson.parseObject(bad.encodeToByteArray())) }.exceptionOrNull()
        assertTrue(e is WalletWireException)
        assertEquals("wallet answer: fingerprint", e!!.message)
    }

    @Test
    fun theVaultsAddressesAreTheAppsOwn() {
        // The anchor: the vault's own first P2WPKH receive address of the vector wallet.
        assertEquals(WalletFixtures.FIRST_P2WPKH, WalletFixtures.address(receive84, 0).address)
        val r = AddressCheck.check(WalletFixtures.wallet(), Network.TESTNET4, issued(receive to 3, receive84 to 1, AddressChain("p2tr", true) to 1))
        assertTrue(r is AddressCheckResult.Match)
        assertEquals("73c5da0a", (r as AddressCheckResult.Match).wallet.fingerprintHex)
    }

    @Test
    fun anyDifferenceIsAMismatch() {
        val w = WalletFixtures.wallet()
        val good = issued(receive to 2)
        // One address that is not the app's.
        val swapped = mapOf(receive to listOf(good.getValue(receive)[0], WalletFixtures.address(receive84, 1).copy(type = "p2tr", index = 1)))
        assertEquals(AddressCheckResult.Mismatch, AddressCheck.check(w, Network.TESTNET4, swapped))
        // An address listed at another index.
        assertEquals(AddressCheckResult.Mismatch, AddressCheck.check(w, Network.TESTNET4, mapOf(receive to listOf(good.getValue(receive)[1].copy(index = 0)))))
        // Another fingerprint, another account path, a missing account.
        val other = { json: String -> WalletWire.wallet(VaultJson.parseObject(json.encodeToByteArray())) }
        assertEquals(AddressCheckResult.Mismatch, AddressCheck.check(other(WalletFixtures.walletJson(fingerprint = "00000000")), Network.TESTNET4, good))
        assertEquals(AddressCheckResult.Mismatch, AddressCheck.check(other(WalletFixtures.walletJson(path86 = "m/86'/1'/1'")), Network.TESTNET4, good))
        assertEquals(AddressCheckResult.Mismatch, AddressCheck.check(w.copy(accounts = w.accounts.take(1)), Network.TESTNET4, good))
        // A test wallet's keys read as mainnet's: the key version does not match.
        assertEquals(AddressCheckResult.Mismatch, AddressCheck.check(w, Network.MAINNET, good))
    }

    @Test
    fun aChainWithNothingIssuedHasNothingToCompare() {
        assertTrue(AddressCheck.check(WalletFixtures.wallet(), Network.TESTNET4, issued(receive to 1)) is AddressCheckResult.Match)
        assertFalse(AddressCheckResult.Mismatch.toString().contains("tb1"))
    }
}
