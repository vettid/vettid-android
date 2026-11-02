// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import com.vettid.core.chain.Network
import com.vettid.core.wallet.TransactionsResult
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
        val payee = com.vettid.core.wallet.WalletPayee("orbit1address", 4242)
        val tx = com.vettid.core.wallet.WalletTransaction(
            "abab".repeat(16), com.vettid.core.wallet.TxDirection.SENT, 4242, -4243, 1, com.vettid.core.wallet.TxStatus.Confirmed(9999),
            9999, java.time.Instant.EPOCH, java.time.Instant.EPOCH, listOf(payee), "maple",
        )
        val walletTxs = com.vettid.core.wallet.WalletTransactions(java.time.Instant.EPOCH, "orbit.example", "https://orbit.example/maple", listOf(tx))
        val values = listOf(
            row,
            WalletsUiState(loading = false, rows = listOf(row)),
            WalletDetailUiState(loaded = true, name = "Savings", password = "hunter2", last = com.vettid.core.wallet.WalletLookup(java.time.Instant.EPOCH, "orbit.example", 4242, 0, setOf("maple"))),
            ReceiveUiState(loading = false, address = com.vettid.core.wallet.WalletAddress("orbit1address", "p2tr", 0, false, false), amountText = "4242"),
            ChainServerUiState(Network.SIGNET, com.vettid.core.chain.ChainServer.NotSet, text = "https://orbit.example/maple"),
            WalletDetailsUiState(loaded = true, fingerprint = "orbit", accounts = listOf(AccountDetails("p2tr", "orbit", "maple", "orb"))),
            ImportUiState(
                typed = "orbit maple", passphrase = "hunter2", password = "hunter2", name = "Savings",
                status = PhraseStatus(2, null, true), limits = LimitsForm(true, "4242", "9999"),
            ),
            LimitsForm(true, "4242", "9999"),
            BackupUiState(BackupStep.LIMITS, name = "Savings", pin = "424242", limits = LimitsForm(true, "4242", "9999")),
            // W2-3: the transactions, their list, the screens' states and every result that carries a value.
            TransactionsUiState(loading = false, name = "Savings", list = walletTxs, problem = TransactionsResult.ServerFailed(com.vettid.core.chain.ChainError.Malformed())),
            TransactionUiState(
                loading = false, name = "Savings", tx = tx, host = "orbit.example", explorerUrl = "https://orbit.example/maple/tx/abab", openUrl = "https://orbit.example/maple",
            ),
            tx, walletTxs,
            BackupUiState(BackupStep.CHECK, name = "Savings", password = "hunter2", words = PhraseWords(listOf("orbit".toCharArray(), "maple".toCharArray())), typed = "orb", candidates = listOf(1)),
        )
        for (v in values) {
            for (s in listOf("Savings", "hunter2", "orbit", "maple", "orb", "01WALLET", "4242", "4243", "9999", "abab", "orbit1address")) assertFalse("'$s' in $v", "$v".contains(s))
        }
    }

    @Test
    fun theModuleHasNoLoggingCallAndKeepsNothingInSavedState() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size >= 13)
        val banned = listOf(
            "android.util.Log", "Log.", "println(", "printStackTrace", "Timber", "System.out", "System.err",
            "java.util.logging", "Logger", "stackTraceToString", "rememberSaveable", "saved[", "saved.set", "getStateFlow",
        )
        // Debug builds expose test tags as resource ids: a tag may carry a position, an id, a network or an address type, never a word, name or address.
        val tagged = Regex("""testTag\(\"[^\"]*\$\{?([A-Za-z_][A-Za-z0-9_.]*)""")
        val allowed = Regex("""i|at|id|type|[A-Za-z_]+\.id|[A-Za-z_]+\.vaultName""")
        for (f in sources) {
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            for (b in banned) assertFalse("${f.name} calls $b", code.contains(b))
            for (m in tagged.findAll(code)) assertTrue("${f.name}: test tag built from ${m.groupValues[1]}", allowed.matches(m.groupValues[1]))
        }
    }
}
