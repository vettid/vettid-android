// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import com.vettid.core.chain.Network
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing of a wallet reaches a log from its screens (ANDROID-PLAN §4 "Local state"): the UI states print their type names and
 * steps only, no logging call in the module, and nothing of the flow is put in saved state (only the route's wallet id is read).
 */
class WalletUiPrivacyTest {
    @Test
    fun theStatesPrintNoNameWordOrPassword() {
        val row = WalletRow("01WALLET0000000000000000AA", "Savings", Network.SIGNET, "signet", backedUp = false)
        val values = listOf(
            row,
            WalletsUiState(loading = false, rows = listOf(row)),
            BackupUiState(BackupStep.CHECK, name = "Savings", password = "hunter2", words = PhraseWords(listOf("orbit".toCharArray(), "maple".toCharArray())), typed = "orb", candidates = listOf(1)),
        )
        for (v in values) {
            for (s in listOf("Savings", "hunter2", "orbit", "maple", "orb", "01WALLET")) assertFalse("'$s' in $v", "$v".contains(s))
        }
    }

    @Test
    fun theModuleHasNoLoggingCallAndKeepsNothingInSavedState() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size >= 3)
        val banned = listOf(
            "android.util.Log", "Log.", "println(", "printStackTrace", "Timber", "System.out", "System.err",
            "java.util.logging", "Logger", "stackTraceToString", "rememberSaveable", "saved[", "saved.set", "getStateFlow",
        )
        // Debug builds expose test tags as resource ids: a tag may carry a position, an id or a network, never a word, name or address.
        val tagged = Regex("""testTag\(\"[^\"]*\$\{?([A-Za-z_][A-Za-z0-9_.]*)""")
        val allowed = Regex("""i|at|id|[A-Za-z_]+\.id|[A-Za-z_]+\.vaultName""")
        for (f in sources) {
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            for (b in banned) assertFalse("${f.name} calls $b", code.contains(b))
            for (m in tagged.findAll(code)) assertTrue("${f.name}: test tag built from ${m.groupValues[1]}", allowed.matches(m.groupValues[1]))
        }
    }
}
