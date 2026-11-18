package com.vettid.core.wallet

import com.vettid.core.chain.Network
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * With no latch, the open check is what keeps a closed manager quiet: every entry point takes its generation with it
 * ([WalletsManager] `openGen`). Closed (after a lock, a hold or a wipe, and before the next open), nothing is sent to the vault,
 * nothing is published, nothing is written to this phone's flags, and the call ends as `cleared` or its result type's own answer.
 */
class WalletClosedTest {
    private val ops = FakeWalletOps()
    private val chain = FakeChain()
    private var requests = 0
    private val flagsFile = object : WalletFlagsFile {
        var bytes: ByteArray? = null

        override fun load(): ByteArray? = bytes

        override fun save(bytes: ByteArray) {
            this.bytes = bytes
        }
    }

    private fun manager() = WalletsManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        {
            requests++
            ops
        },
        { n -> chain.open(n) },
        WalletFlags(flagsFile),
        { it == Network.TESTNET4 || it == Network.SIGNET },
        { Instant.parse("2026-10-31T20:00:00Z") },
    )

    private val id = "01WALLET0000000000000000AA"
    private val pw get() = "pw".toCharArray()
    private val txid = "ab".repeat(32)

    /** Each entry point that may reach the vault, called on a closed manager; what it answers instead. */
    private val entryPoints: List<Pair<String, suspend (WalletsManager) -> Any?>> = listOf(
        "refresh" to { m -> m.refresh() },
        "create" to { m -> m.create("New", Network.TESTNET4, pw, WalletCaps(1_000, null)) },
        "import" to { m -> m.import("Old", Network.TESTNET4, pw, "a b c".toCharArray(), null, WalletCaps(1_000, null)) },
        "setAddressType" to { m -> m.setAddressType(id, "p2wpkh") },
        "settleCaps" to { m -> m.settleCaps(id) },
        "lowerCaps" to { m -> m.lowerCaps(id, 1_000, null) },
        "raiseCaps" to { m -> m.raiseCaps(id, CapsSeen(4, null, null), 5_000, null, "123456".toCharArray(), pw) },
        "revealPhrase" to { m -> m.revealPhrase(id, pw) },
        "delete" to { m -> m.delete(id, pw) },
        "receiveAddress" to { m -> m.receiveAddress(id) },
        "newReceiveAddress" to { m -> m.newReceiveAddress(id) },
        "newAddress" to { m -> m.newAddress(id) },
        "answerLimits" to { m -> m.answerLimits(id, WalletCaps(1_000, null)) },
        "resetSettings" to { m -> m.resetSettings() },
        "rereadSettings" to { m -> m.rereadSettings() },
        "lookup" to { m -> m.lookup(id) },
        "transactions" to { m -> m.transactions(id) },
        "history" to { m -> m.history(id, 20) },
        "walletOfTransaction" to { m -> m.walletOfTransaction(txid) },
        "prepareSend" to { m -> m.prepareSend(id, SendRequest("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", 40_000, 2.0, false)) },
        "retryBroadcast" to { m -> m.retryBroadcast(id) },
        "checkSent" to { m -> m.checkSent(id) },
        "retrySign" to { m -> m.retrySign(id) },
    )

    /** A manager that was open with a wallet and a pending limit on this phone's flags, then closed. */
    private suspend fun closed(): WalletsManager {
        ops.wallets[id] = WalletFixtures.wallet(id = id)
        // The settings file does not read, so a reset or a re-read would write or load something if it ran.
        flagsFile.bytes = """{"version":2,"backed_up":[]""".encodeToByteArray()
        val m = manager()
        m.open()
        assertEquals(1, m.wallets.value.size)
        m.clear()
        return m
    }

    @Test
    fun aClosedManagerSendsPublishesAndWritesNothing() = runBlocking {
        for ((name, call) in entryPoints) {
            val m = closed()
            val before = requests
            val flags = flagsFile.bytes?.copyOf()
            val calls = ops.calls.size
            val answer = runCatching { call(m) }
            assertEquals("$name sent a request while closed", before, requests)
            assertEquals("$name reached the vault while closed", calls, ops.calls.size)
            assertArrayEquals("$name wrote this phone's flags while closed", flags, flagsFile.bytes)
            assertTrue("$name published wallets", m.wallets.value.isEmpty())
            assertFalse("$name marked the wallets listed", m.listed.value)
            assertFalse("$name opened the manager", m.isOpen.value)
            assertTrue("$name published a pending limit", m.capsPending.value.isEmpty())
            // A failure is a failure (never an exception of another kind), and every result type says it was cleared or had nothing.
            answer.exceptionOrNull()?.let { assertTrue("$name threw $it", it is VaultFailure) }
            when (val v = answer.getOrNull()) {
                is LookupResult -> assertEquals(name, LookupResult.Cleared, v)
                is TransactionsResult -> assertEquals(name, TransactionsResult.Cleared, v)
                is PrepareResult -> assertEquals(name, PrepareResult.Cleared, v)
                is SendResult -> assertEquals(name, SendResult.NothingToSend, v)
                is Boolean -> assertFalse(name, v)
                else -> Unit
            }
        }
    }

    /** The entry points that send at once (no wallet in memory needed) answer `cleared` while closed. */
    @Test
    fun theDirectSendersAnswerCleared() = runBlocking {
        for (name in listOf("create", "import", "revealPhrase", "delete", "newAddress", "history", "answerLimits", "setAddressType")) {
            val m = closed()
            val e = runCatching { entryPoints.single { it.first == name }.second(m) }.exceptionOrNull()
            assertEquals(name, WalletsManager.CLEARED, (e as? VaultFailure)?.code)
        }
    }
}
