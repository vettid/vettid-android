// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import com.vettid.core.chain.ChainServer
import com.vettid.core.vault.VaultJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Nothing of a wallet reaches a log (ANDROID-PLAN §4 "Local state"): no phrase, address, account key or amount in a string template
 * of any type here, in an error message, or through a logging call in this module; and the phrase is read only from the revealed
 * item's own field.
 */
class WalletPrivacyTest {
    @Test
    fun theTypesPrintTheirNamesOnly() {
        val w = WalletFixtures.wallet()
        val a = WalletFixtures.address(AddressChain("p2tr", false), 0)
        val values = listOf(
            w, w.accounts[0], a, AddressPage(listOf(a), null), RevealedPhrase("abandon ability".toCharArray()),
            WalletLookup(Instant.EPOCH, "node.example", 123_456, 789, setOf(a.address)),
            ChainOpen.Ready("node.example") { error("unused") }, LookupResult.NoServer(ChainServer.NotSet),
        )
        val secrets = listOf(a.address, WalletFixtures.TPUB86, WalletFixtures.TPUB84, "abandon", "123456", "Savings", "node.example")
        for (v in values) {
            for (s in secrets) assertFalse("'$s' in $v", "$v".contains(s))
        }
    }

    @Test
    fun thePhraseIsTheRecoveryPhraseFieldNormalised() {
        val o = VaultJson.parseObject(
            """{"fields":[{"field_id":"f2","value":"not this"},{"field_id":"f1","value":"  abandon  ABILITY able about above absent absorb abstract absurd abuse access accident "}]}"""
                .encodeToByteArray(),
        )
        val p = VaultWalletOps.phraseOf(o, "f1")
        assertEquals(12, p.wordCount)
        assertEquals("abandon", String(p.words()[0]))
        assertEquals("ABILITY", String(p.words()[1]))
        // Not a BIP39 length, or no such field: refused, with no value in the message.
        val short = VaultJson.parseObject("""{"fields":[{"field_id":"f1","value":"abandon ability"}]}""".encodeToByteArray())
        val e = runCatching { VaultWalletOps.phraseOf(short, "f1") }.exceptionOrNull()!!
        assertTrue(e is WalletWireException)
        assertFalse(e.message!!.contains("abandon"))
        assertTrue(runCatching { VaultWalletOps.phraseOf(o, "f9") }.exceptionOrNull() is WalletWireException)
    }

    @Test
    fun theModuleHasNoLoggingCall() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size >= 5)
        val banned = listOf(
            "android.util.Log", "Log.", "println(", "printStackTrace", "Timber", "System.out", "System.err",
            "java.util.logging", "Logger", "stackTraceToString",
        )
        for (f in sources) {
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            for (b in banned) assertFalse("${f.name} calls $b", code.contains(b))
        }
    }
}
