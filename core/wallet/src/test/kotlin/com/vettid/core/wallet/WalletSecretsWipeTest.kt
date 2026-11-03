package com.vettid.core.wallet

import com.vettid.core.chain.Network
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * N1: the password and the PIN are `CharArray`s all the way down, and [WalletsManager] wipes each array it is given when the call
 * ends: on success, on a refusal and when it stops before sending anything. The fake vault keeps the very arrays it was handed.
 */
class WalletSecretsWipeTest {
    private val ops = FakeWalletOps()
    private val chain = FakeChain()
    private val flagsFile = object : WalletFlagsFile {
        var bytes: ByteArray? = null

        override fun load(): ByteArray? = bytes

        override fun save(bytes: ByteArray) {
            this.bytes = bytes
        }
    }

    private fun manager() = WalletsManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { ops },
        { n -> chain.open(n) },
        WalletFlags(flagsFile),
        { it == Network.SIGNET || it == Network.TESTNET4 },
        { Instant.parse("2026-10-31T20:00:00Z") },
    )

    private fun assertWiped(vararg arrays: CharArray) {
        for (a in arrays) assertTrue("an array was not wiped", a.all { it == '\u0000' })
        assertTrue("the vault was handed nothing", ops.handedSecrets.isNotEmpty())
        for (a in ops.handedSecrets) assertTrue("an array handed to the vault was not wiped", a.all { it == '\u0000' })
    }

    private fun capped(): VaultWallet = WalletFixtures.wallet(id = "01WALLET000000000000000001", caps = WalletFixtures.DEFAULT_CAPS)
        .copy(capped = true, perTxSats = 500_000, perPeriodSats = 2_000_000).also { ops.wallets[it.id] = it }

    @Test
    fun createWipesThePasswordAfterTheCreateAndTheRevealOnSuccess() = runBlocking {
        val pw = "pw".toCharArray()
        assertTrue(manager().create("Savings", Network.TESTNET4, pw).phrase != null)
        assertEquals(listOf("create:testnet4", "reveal"), ops.calls.filter { it.startsWith("create") || it == "reveal" })
        assertWiped(pw)
    }

    @Test
    fun createWipesThePasswordWhenTheVaultRefuses() = runBlocking {
        val pw = "wrong".toCharArray()
        assertTrue(runCatching { manager().create("Savings", Network.TESTNET4, pw) }.exceptionOrNull() is VaultFailure)
        assertWiped(pw)
    }

    @Test
    fun createWipesThePasswordWhenItStopsBeforeSending() = runBlocking {
        val pw = "pw".toCharArray()
        assertTrue(runCatching { manager().create("Savings", Network.MAINNET, pw) }.isFailure)
        assertTrue(pw.all { it == '\u0000' })
    }

    @Test
    fun importWipesThePasswordButNotTheWordsTheCallerOwns() = runBlocking {
        val pw = "pw".toCharArray()
        val words = "a b c".toCharArray()
        manager().import("Old", Network.TESTNET4, pw, words, null)
        assertWiped(pw)
        assertEquals("a b c", String(words))
        val bad = "wrong".toCharArray()
        assertTrue(runCatching { manager().import("Old", Network.TESTNET4, bad, "a b c".toCharArray(), null) }.isFailure)
        assertTrue(bad.all { it == '\u0000' })
    }

    @Test
    fun revealWipesThePasswordAndThePinOnSuccessAndOnFailure() = runBlocking {
        val w = capped()
        val m = manager().also { it.open() }
        val pw = "pw".toCharArray()
        val pin = "123456".toCharArray()
        assertEquals(24, m.revealPhrase(w.id, pw, pin).wordCount)
        assertWiped(pw, pin)
        ops.handedSecrets.clear()
        val pw2 = "wrong".toCharArray()
        val pin2 = "123456".toCharArray()
        assertTrue(runCatching { m.revealPhrase(w.id, pw2, pin2) }.exceptionOrNull() is VaultFailure)
        assertWiped(pw2, pin2)
        // No PIN for a capped wallet: nothing is sent, and the password is wiped all the same.
        val pw3 = "pw".toCharArray()
        assertTrue(runCatching { m.revealPhrase(w.id, pw3) }.exceptionOrNull() is VaultFailure)
        assertTrue(pw3.all { it == '\u0000' })
    }

    @Test
    fun deleteWipesThePasswordOnSuccessAndOnFailure() = runBlocking {
        val w = capped()
        val m = manager().also { it.open() }
        val bad = "wrong".toCharArray()
        assertTrue(runCatching { m.delete(w.id, bad) }.exceptionOrNull() is VaultFailure)
        assertWiped(bad)
        ops.handedSecrets.clear()
        val pw = "pw".toCharArray()
        m.delete(w.id, pw)
        assertWiped(pw)
    }

    @Test
    fun raiseCapsWipesThePinAndThePasswordOnSuccessAndOnFailure() = runBlocking {
        val w = capped()
        val m = manager().also { it.open() }
        val badPin = "000000".toCharArray()
        val pw1 = "pw".toCharArray()
        assertTrue(runCatching { m.raiseCaps(w.id, m.capsSeen(w.id)!!, null, 3_000_000, badPin, pw1) }.exceptionOrNull() is VaultFailure)
        assertWiped(badPin, pw1)
        ops.handedSecrets.clear()
        val pin = "123456".toCharArray()
        val pw2 = "pw".toCharArray()
        m.raiseCaps(w.id, m.capsSeen(w.id)!!, null, 3_000_000, pin, pw2)
        assertWiped(pin, pw2)
        // Limits that moved since the form: nothing is sent, the arrays are wiped.
        val seen = m.capsSeen(w.id)!!
        val pin3 = "123456".toCharArray()
        val pw3 = "pw".toCharArray()
        assertTrue(runCatching { m.raiseCaps(w.id, seen.copy(version = seen.version - 1), null, 1_000_000, pin3, pw3) }.isFailure)
        assertTrue(pin3.all { it == '\u0000' } && pw3.all { it == '\u0000' })
    }
}
